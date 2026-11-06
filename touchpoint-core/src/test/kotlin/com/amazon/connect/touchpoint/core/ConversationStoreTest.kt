package com.amazon.connect.touchpoint.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {
    private val dirs = mutableListOf<File>()
    private val opened = mutableListOf<ConversationStore>()

    private fun tempDir(): File = createTempDirectory("tp-store-").toFile().also { dirs += it }

    @AfterTest
    fun cleanUp() = runBlocking {
        opened.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private suspend fun store(
        dir: File,
        secureStore: SecureStore = InMemorySecureStore(),
        retention: Duration = ConversationStore.DEFAULT_RETENTION,
        schema: StoreSchema = StoreSchema.CURRENT,
    ): ConversationStore =
        ConversationStore.open(dir, secureStore, BundledSQLiteDriver(), retention, { Instant.now() }, Dispatchers.IO, schema).also { opened += it }

    private fun inbound(body: String, sequence: Long? = null, serverId: String? = null, id: UUID = UUID.randomUUID()) =
        StoredMessage(id, serverId, body, Direction.INBOUND, ParticipantRole.AGENT, sequence = sequence, deliveryState = DeliveryState.DELIVERED)

    private fun outbound(body: String, id: UUID = UUID.randomUUID(), state: DeliveryState = DeliveryState.SENDING) =
        StoredMessage(id, body = body, direction = Direction.OUTBOUND, participantRole = ParticipantRole.CUSTOMER, deliveryState = state)

    @Test
    fun `round-trips a conversation and its messages across a simulated restart`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            append("c1", outbound("hello"))
            append("c1", inbound("hi there", sequence = 1))
            close()
        }
        val reopened = store(dir, keys)
        // Server sequence orders the transcript; the unreconciled local echo pins to the tail.
        assertEquals(listOf("hi there", "hello"), reopened.messages("c1").map { it.body })
        assertEquals(Modality.CHAT, reopened.conversation("c1")?.modality)
        assertEquals(ConversationStore.OpenOutcome.OPENED, reopened.lastOpenOutcome)
    }

    @Test
    fun `the participant set persists keyed on ParticipantId, with humanAgentPresent denormalised`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        var set = ParticipantSet().join(Participant("p-bot", ParticipantRole.ASSISTANT, "Bot")).join(Participant("p-agent", ParticipantRole.AGENT, "Sam"))
        store.setParticipants("c1", set)
        val conv = store.conversation("c1")
        assertEquals(true, conv?.humanAgentPresent)
        assertEquals(true, conv?.participants?.contains("p-agent"))
        set = set.leave("p-agent")
        store.setParticipants("c1", set)
        assertEquals(false, store.conversation("c1")?.humanAgentPresent)
        // Gone from the set, still named: what Sam wrote is still Sam's after a relaunch.
        assertEquals(mapOf("p-bot" to "Bot", "p-agent" to "Sam"), store.conversation("c1")?.participantNames)
    }

    @Test
    fun `setting participants on an unknown conversation throws ContactNotFound`() = runBlocking {
        val store = store(tempDir())
        assertIs<TouchpointException.ContactNotFound>(runCatching { store.setParticipants("nope", ParticipantSet()) }.exceptionOrNull())
    }

    @Test
    fun `five delivery states, and failed is only reachable from sending`() {
        assertEquals(listOf("sending", "sent", "delivered", "read", "failed"), DeliveryState.entries.map { it.rawValue })
        assertTrue(DeliveryState.SENDING.canTransitionTo(DeliveryState.FAILED))
        assertFalse(DeliveryState.SENT.canTransitionTo(DeliveryState.FAILED))
        assertFalse(DeliveryState.DELIVERED.canTransitionTo(DeliveryState.FAILED))
        assertTrue(DeliveryState.SENDING.canTransitionTo(DeliveryState.SENT))
        assertTrue(DeliveryState.SENT.canTransitionTo(DeliveryState.DELIVERED))
        assertTrue(DeliveryState.DELIVERED.canTransitionTo(DeliveryState.READ))
        assertFalse(DeliveryState.READ.canTransitionTo(DeliveryState.SENT))
    }

    @Test
    fun `message bodies never appear in plaintext in any store file, including -wal and -shm`() = runBlocking {
        val dir = tempDir()
        val store = store(dir)
        val secret = "THE-QUICK-BROWN-FOX-${UUID.randomUUID()}"
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", outbound(secret, state = DeliveryState.SENT))
        assertTrue(File(dir, "${ConversationStore.DATABASE_FILE}-wal").isFile, "the store runs in WAL mode, so the scan covers the log")
        // Scan before and after the checkpoint: a fresh write sits in the WAL until then.
        for (pass in 1..2) {
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            assertTrue(files.isNotEmpty())
            for (f in files) assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(secret), "plaintext body found in ${f.name} (pass $pass)")
            store.checkpoint()
        }
        assertEquals(secret, store.messages("c1").first().body) // and the read path still decrypts
    }

    @Test
    fun `an interactive message's JSON, its citations, and the names of who was there are sealed as bodies are`() = runBlocking {
        val dir = tempDir()
        val store = store(dir)
        val choice = "CHOICE-${UUID.randomUUID()}"
        val source = "SOURCE-${UUID.randomUUID()}"
        val agent = "AGENT-${UUID.randomUUID()}"
        store.upsertConversation("c1", Modality.CHAT)
        val interactive = InteractiveContent.encode(InteractiveContent("QuickReply", "{\"data\":{\"content\":{\"title\":\"pick\",\"elements\":[{\"title\":\"$choice\"}]}}}"))
        val citations = Citation.encode(listOf(Citation(source, snippet = source)))
        store.append("c1", inbound("pick one", sequence = 1).copy(interactiveJson = interactive, citationsJson = citations))
        store.setParticipants("c1", ParticipantSet().join(Participant("a1", ParticipantRole.AGENT, agent)))
        for (pass in 1..2) {
            for (f in dir.listFiles().orEmpty().filter { it.isFile }) {
                val bytes = String(f.readBytes(), Charsets.ISO_8859_1)
                for (secret in listOf(choice, source, agent)) assertFalse(secret in bytes, "plaintext found in ${f.name} (pass $pass)")
            }
            store.checkpoint()
        }
        val read = store.messages("c1").single()
        assertEquals(interactive, read.interactiveJson)
        assertEquals(citations, read.citationsJson)
        assertEquals(mapOf("a1" to agent), store.conversation("c1")?.participantNames)
        assertEquals(true, store.conversation("c1")?.humanAgentPresent) // who is there still reads without the names
    }

    @Test
    fun `a corrupted store is dropped and rebuilt rather than crashing, and the integrator is told`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            checkpoint()
            close()
        }
        // Corrupt the database files only; the key fingerprint stays valid, so this is store
        // corruption, not key loss.
        dir.listFiles().orEmpty().filter { it.name.startsWith(ConversationStore.DATABASE_FILE) }.forEach { it.writeBytes(ByteArray(4096) { 0xFF.toByte() }) }
        val rebuilt = store(dir, keys)
        assertEquals(ConversationStore.OpenOutcome.REBUILT_AFTER_FAILURE, rebuilt.lastOpenOutcome)
        assertNull(rebuilt.conversation("c1"))
        rebuilt.upsertConversation("c2", Modality.VOICE)
        assertNotNull(rebuilt.conversation("c2"))
    }

    @Test
    fun `losing the encryption key resets the store cleanly`() = runBlocking {
        val dir = tempDir()
        store(dir).run {
            upsertConversation("c1", Modality.CHAT)
            append("c1", outbound("x", state = DeliveryState.SENT))
            close()
        }
        val fresh = store(dir, InMemorySecureStore())
        assertEquals(ConversationStore.OpenOutcome.RESET_AFTER_KEY_LOSS, fresh.lastOpenOutcome)
        assertTrue(fresh.messages("c1").isEmpty())
        assertNull(fresh.conversation("c1")) // wiped, not merely unreadable
        assertEquals(0L, fresh.totalMessageRowCount())
    }

    @Test
    fun `a present-but-wrong key is detected by fingerprint and resets the store (M3)`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            append("c1", outbound("x", state = DeliveryState.SENT))
            close()
        }
        keys.set(ConversationStore.ENCRYPTION_KEY_SECURE_STORE_KEY, ByteArray(32) { 0x42 }, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        val reopened = store(dir, keys)
        assertEquals(ConversationStore.OpenOutcome.RESET_AFTER_KEY_LOSS, reopened.lastOpenOutcome)
        assertTrue(reopened.messages("c1").isEmpty())
        assertNull(reopened.conversation("c1"))
        assertEquals(0L, reopened.totalMessageRowCount())
    }

    @Test
    fun `a corrupt participants blob fails SAFE - humanAgentPresent stays true (C7)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.setParticipants("c1", ParticipantSet().join(Participant("a", ParticipantRole.AGENT, "Sam")))
        store.corruptParticipantsBlobForTesting("c1")
        val rec = store.conversation("c1")
        assertEquals(true, rec?.humanAgentPresent)
        assertEquals(true, rec?.participants?.humanAgentPresent) // reconstructed from the flag
    }

    @Test
    fun `writes after deleteAll survive a reopen (C2 - the key must be regenerated)`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            deleteAllConversations()
            upsertConversation("c2", Modality.CHAT)
            append("c2", outbound("after wipe", state = DeliveryState.SENT))
            close()
        }
        val reopened = store(dir, keys)
        assertEquals(ConversationStore.OpenOutcome.OPENED, reopened.lastOpenOutcome)
        assertEquals("after wipe", reopened.messages("c2").first().body)
    }

    @Test
    fun `a duplicate receipt (same state) is an idempotent no-op, not an error (m3)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val id = UUID.randomUUID()
        store.append("c1", outbound("x", id))
        assertTrue(store.updateDeliveryState(id, DeliveryState.SENT, serverMessageId = "s1"))
        assertFalse(store.updateDeliveryState(id, DeliveryState.SENT, serverMessageId = "s1"))
        assertEquals(DeliveryState.SENT, store.messages("c1").first().deliveryState)
    }

    @Test
    fun `an illegal delivery transition is a malformed payload`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val id = UUID.randomUUID()
        store.append("c1", outbound("x", id, state = DeliveryState.READ))
        assertIs<TouchpointException.MalformedPayload>(runCatching { store.updateDeliveryState(id, DeliveryState.SENT) }.exceptionOrNull())
        assertIs<TouchpointException.ContactNotFound>(runCatching { store.updateDeliveryState(UUID.randomUUID(), DeliveryState.SENT) }.exceptionOrNull())
    }

    @Test
    fun `one undecryptable row does not take the whole transcript down (r1p3)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val bad = UUID.randomUUID()
        store.append("c1", inbound("ok-1", sequence = 1))
        store.append("c1", inbound("doomed", sequence = 2, id = bad))
        store.append("c1", inbound("ok-3", sequence = 3))
        store.corruptCiphertextForTesting(bad)
        assertEquals(listOf("ok-1", "ok-3"), store.messages("c1").map { it.body })
        assertEquals(1, store.undecryptableRowsSkipped)
        store.messages("c1")
        assertEquals(1, store.undecryptableRowsSkipped) // per read, not cumulative
        store.messages("no-such-contact")
        assertEquals(0, store.undecryptableRowsSkipped) // the empty path resets too
    }

    @Test
    fun `a body moved onto another row does not decrypt there (associated data)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        store.append("c1", inbound("transfer 10 dollars", sequence = 1, id = a))
        store.append("c1", inbound("transfer 9000 dollars", sequence = 2, id = b))
        store.swapCiphertextForTesting(from = b, to = a)
        assertEquals(listOf("transfer 9000 dollars"), store.messages("c1").map { it.body })
        assertEquals(1, store.undecryptableRowsSkipped)
    }

    @Test
    fun `deleteAll removes message rows too, not just conversations (r1p7)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", outbound("x", state = DeliveryState.SENT))
        store.deleteAllConversations()
        assertEquals(0L, store.totalMessageRowCount())
    }

    @Test
    fun `retention pruning removes old conversations, returns their ids, and cascades`() = runBlocking {
        val store = store(tempDir(), retention = Duration.ofSeconds(60))
        val now = Instant.now()
        store.upsertConversation("old", Modality.CHAT, lastActivity = now.minusSeconds(120))
        store.append("old", outbound("a", state = DeliveryState.SENT).copy(createdAt = now.minusSeconds(110)))
        store.upsertConversation("new", Modality.CHAT)
        assertEquals(listOf("old"), store.pruneExpired())
        assertNull(store.conversation("old"))
        assertTrue(store.messages("old").isEmpty())
        assertNotNull(store.conversation("new"))
        store.deleteConversation("new")
        assertNull(store.conversation("new"))
        assertEquals(0L, store.totalMessageRowCount())
    }

    @Test
    fun `deleteAll wipes conversations and rotates the encryption key`() = runBlocking {
        val keys = InMemorySecureStore()
        val store = store(tempDir(), keys)
        val before = keys.get(ConversationStore.ENCRYPTION_KEY_SECURE_STORE_KEY)
        store.upsertConversation("c1", Modality.CHAT)
        store.deleteAllConversations()
        assertNull(store.conversation("c1"))
        val after = keys.get(ConversationStore.ENCRYPTION_KEY_SECURE_STORE_KEY)
        assertNotNull(after)
        assertFalse(after.contentEquals(before)) // the old key is destroyed with the data
        assertTrue(store.allContactIds().isEmpty())
    }

    @Test
    fun `the field key is held when-unlocked-this-device-only`() = runBlocking {
        val keys = InMemorySecureStore()
        store(tempDir(), keys)
        assertEquals(listOf(SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY), keys.allAccessibility())
    }

    @Test
    fun `the schema is versioned from v1, and a v1 store migrates to a later schema in place`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            append("c1", inbound("kept across the migration", sequence = 1))
            close()
        }
        val v2 =
            StoreSchema(
                version = 2,
                migrations = listOf(StoreSchema.V1, StoreSchema.Migration(2, listOf("ALTER TABLE message ADD COLUMN reaction TEXT"))),
            )
        val migrated = store(dir, keys, schema = v2)
        assertEquals(ConversationStore.OpenOutcome.OPENED, migrated.lastOpenOutcome)
        assertEquals(listOf("kept across the migration"), migrated.messages("c1").map { it.body })
    }

    @Test
    fun `a store written by a newer schema is rebuilt, not read`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        val v2 = StoreSchema(2, listOf(StoreSchema.V1, StoreSchema.Migration(2, listOf("ALTER TABLE message ADD COLUMN reaction TEXT"))))
        store(dir, keys, schema = v2).run {
            upsertConversation("c1", Modality.CHAT)
            close()
        }
        val older = store(dir, keys) // this build only knows v1
        assertEquals(ConversationStore.OpenOutcome.REBUILT_AFTER_FAILURE, older.lastOpenOutcome)
        assertNull(older.conversation("c1"))
    }

    @Test
    fun `resume with a human agent in the persisted set reports dispatch must stay suspended`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.VOICE)
        store.setParticipants("c1", ParticipantSet().join(Participant("a", ParticipantRole.AGENT, "Sam")))
        assertTrue(store.humanAgentPresent("c1"))
        assertFalse(store.humanAgentPresent("unknown"))
    }

    @Test
    fun `append deduplicates on server id within a conversation and reports it`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        assertEquals(ConversationStore.AppendOutcome.Appended, store.append("c1", inbound("a", 10, "s1")))
        assertEquals(ConversationStore.AppendOutcome.Duplicate, store.append("c1", inbound("a", 10, "s1")))
        assertEquals(1, store.messages("c1").size)
        // The same server id in a *different* conversation is a different message.
        store.upsertConversation("c2", Modality.CHAT)
        assertEquals(ConversationStore.AppendOutcome.Appended, store.append("c2", inbound("a", 10, "s1")))
    }

    @Test
    fun `appending the same client id twice is a duplicate, not a second row`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val m = outbound("once")
        store.append("c1", m)
        assertEquals(ConversationStore.AppendOutcome.Duplicate, store.append("c1", m))
        assertEquals(1, store.messages("c1").size)
    }

    @Test
    fun `appending to an unknown conversation throws ContactNotFound`() = runBlocking {
        assertIs<TouchpointException.ContactNotFound>(runCatching { store(tempDir()).append("nope", outbound("x")) }.exceptionOrNull())
    }

    @Test
    fun `receipts update delivery state by server id - backward or unknown receipts are ignored, not thrown`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val id = UUID.randomUUID()
        store.append("c1", outbound("hi", id))
        store.updateDeliveryState(id, DeliveryState.SENT, serverMessageId = "s9", sequence = 5)
        assertEquals(id, store.applyReceipt("c1", "s9", ReceiptKind.READ))
        assertEquals(DeliveryState.READ, store.messages("c1").first().deliveryState)
        assertNull(store.applyReceipt("c1", "s9", ReceiptKind.DELIVERED)) // backward: ignored
        assertEquals(DeliveryState.READ, store.messages("c1").first().deliveryState)
        assertNull(store.applyReceipt("c1", "nope", ReceiptKind.DELIVERED)) // unknown: ignored
        assertNull(store.applyReceipt("other", "s9", ReceiptKind.DELIVERED)) // scoped per conversation
    }

    @Test
    fun `reconcile adopts an unacknowledged outbound echo instead of duplicating it`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val local = UUID.randomUUID()
        store.append("c1", outbound("pay my bill", local))
        // The server has it under srv-7; our ack never arrived. A transcript page brings it back fresh.
        val fromServer = StoredMessage(UUID.randomUUID(), "srv-7", "pay my bill", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 70, deliveryState = DeliveryState.SENT)
        assertEquals(ConversationStore.AppendOutcome.Adopted(local), store.append("c1", fromServer))
        val rows = store.messages("c1")
        assertEquals(1, rows.size)
        assertEquals(local, rows[0].clientMessageId)
        assertEquals("srv-7", rows[0].serverMessageId)
        assertEquals(70L, rows[0].sequence)
        assertEquals(DeliveryState.SENT, rows[0].deliveryState)
        assertEquals(70L, store.conversation("c1")?.lastSequence)
        // A different body is a different message.
        val other = StoredMessage(UUID.randomUUID(), "srv-8", "other", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 80, deliveryState = DeliveryState.SENT)
        assertEquals(ConversationStore.AppendOutcome.Appended, store.append("c1", other))
        assertEquals(2, store.messages("c1").size)
    }

    @Test
    fun `participantId round-trips through the store`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", inbound("x", 1, "s").copy(participantId = "p-9"))
        assertEquals("p-9", store.messages("c1").first().participantId)
    }

    @Test
    fun `paging walks backwards through the transcript and reports when it is exhausted`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        for (i in 1..25) store.append("c1", inbound("m$i", i.toLong(), "s$i"))
        val newest = store.messages("c1", before = null, limit = 10)
        assertEquals((16..25).map { "m$it" }, newest.messages.map { it.body })
        assertTrue(newest.hasMore)
        val middle = store.messages("c1", before = newest.messages.first().clientMessageId, limit = 10)
        assertEquals((6..15).map { "m$it" }, middle.messages.map { it.body })
        assertTrue(middle.hasMore)
        val oldest = store.messages("c1", before = middle.messages.first().clientMessageId, limit = 10)
        assertEquals((1..5).map { "m$it" }, oldest.messages.map { it.body })
        assertFalse(oldest.hasMore)
    }

    @Test
    fun `paging never skips a message that shares its server millisecond with the cursor`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        for (i in 1..6) store.append("c1", inbound("m$i", sequence = 100L + (i - 1) / 2, serverId = "s$i")) // pairs share a sequence
        val pages = mutableListOf<List<String>>()
        var cursor: UUID? = null
        do {
            val slice = store.messages("c1", before = cursor, limit = 1)
            pages += slice.messages.map { it.body }
            cursor = slice.messages.firstOrNull()?.clientMessageId
        } while (slice.hasMore)
        assertEquals(store.messages("c1").map { it.body }, pages.reversed().flatten()) // pager and full read agree
        assertEquals(6, pages.flatten().toSet().size)
    }

    @Test
    fun `a cursor that no longer exists reports an exhausted transcript`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", inbound("m1", 1, "s1"))
        assertEquals(TranscriptSlice(emptyList(), false), store.messages("c1", before = UUID.randomUUID(), limit = 10))
    }

    @Test
    fun `a page whose rows are all undecryptable does not wedge the pager`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val ids = (1..9).map { i -> UUID.randomUUID().also { store.append("c1", inbound("m$i", i.toLong(), "s$i", it)) } }
        // Scribble the middle three: a naive pager would hand back an empty page and stall there.
        ids.subList(3, 6).forEach { store.corruptCiphertextForTesting(it) }
        val newest = store.messages("c1", before = null, limit = 3)
        assertEquals(listOf("m7", "m8", "m9"), newest.messages.map { it.body })
        assertTrue(newest.hasMore)
        val next = store.messages("c1", before = newest.messages.first().clientMessageId, limit = 3)
        assertEquals(listOf("m1", "m2", "m3"), next.messages.map { it.body })
        assertFalse(next.hasMore)
        assertEquals(3, store.undecryptableRowsSkipped) // observable on the paged path too
        store.pendingOutbox("c1")
        assertEquals(3, store.undecryptableRowsSkipped) // reading the outbox does not pollute it
    }

    @Test
    fun `the newest page always carries the unsent outbox, even when delivered messages would fill it`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        for (i in 1..60) store.append("c1", inbound("delivered $i", i.toLong(), "s$i"))
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        store.append("c1", outbound("unsent one", a))
        store.append("c1", outbound("unsent two", b))
        val newest = store.messages("c1", before = null, limit = 50)
        assertEquals(50, newest.messages.size)
        assertEquals(listOf(a, b), newest.messages.takeLast(2).map { it.clientMessageId })
        assertEquals("delivered 13", newest.messages.first().body) // 48 delivered + 2 unsent
        assertTrue(newest.hasMore)
    }

    @Test
    fun `paging back from an echo walks the earlier echoes, then the sequenced history`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", inbound("delivered", 1, "s1"))
        val echoes = (1..3).map { i -> UUID.randomUUID().also { store.append("c1", outbound("echo $i", it)) } }
        val before = store.messages("c1", before = echoes[2], limit = 5)
        assertEquals(listOf("echo 1", "echo 2"), before.messages.map { it.body })
        assertTrue(before.hasMore)
        val older = store.messages("c1", before = echoes[0], limit = 5)
        assertEquals(listOf("delivered"), older.messages.map { it.body })
        assertFalse(older.hasMore)
    }

    @Test
    fun `the outbox is the unacknowledged outbound rows, in composition order`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val ids = (1..3).map { i -> UUID.randomUUID().also { store.append("c1", outbound("o$i", it)) } }
        store.append("c1", inbound("in", 1, "s1"))
        store.updateDeliveryState(ids[1], DeliveryState.SENT, serverMessageId = "s2")
        assertEquals(listOf("o1", "o3"), store.pendingOutbox("c1").map { it.body })
        assertEquals(listOf(ids[0], ids[2]), store.pendingClientIds("c1"))
        assertTrue(store.isPending(ids[0]))
        assertFalse(store.isPending(ids[1]))
        store.corruptCiphertextForTesting(ids[2])
        assertEquals(listOf("o1"), store.pendingOutbox("c1").map { it.body }) // unreadable: skipped here...
        assertEquals(listOf(ids[0], ids[2]), store.pendingClientIds("c1")) // ...but still listed, to be failed
    }

    @Test
    fun `last activity advances to a message's own time and never backwards`() = runBlocking {
        val store = store(tempDir())
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        store.upsertConversation("c1", Modality.CHAT, lastActivity = t0)
        store.append("c1", inbound("old replay", 1, "s1").copy(createdAt = t0.minusSeconds(3600)))
        assertEquals(t0, store.conversation("c1")?.lastActivity)
        store.append("c1", inbound("new", 2, "s2").copy(createdAt = t0.plusSeconds(60)))
        assertEquals(t0.plusSeconds(60), store.conversation("c1")?.lastActivity)
        assertNotEquals(0L, store.conversation("c1")?.lastSequence)
    }

    @Test
    fun `a closed store throws PersistenceFailure rather than crashing`() = runBlocking {
        val store = store(tempDir())
        store.close()
        assertIs<TouchpointException.PersistenceFailure>(runCatching { store.conversation("c1") }.exceptionOrNull())
    }

    // Regressions

    /** A frozen copy of schema v1 as shipped: an in-place edit of StoreSchema.V1 must fail here. */
    private val frozenV1 =
        listOf(
            "CREATE TABLE conversation (contact_id TEXT NOT NULL PRIMARY KEY, modality TEXT NOT NULL, last_sequence INTEGER NOT NULL DEFAULT 0, " +
                "next_local_order INTEGER NOT NULL DEFAULT 0, participants_json TEXT NOT NULL, participant_names_ciphertext BLOB, human_agent_present INTEGER NOT NULL DEFAULT 0, " +
                "last_activity INTEGER NOT NULL)",
            "CREATE TABLE message (client_message_id TEXT NOT NULL PRIMARY KEY, contact_id TEXT NOT NULL REFERENCES conversation(contact_id) ON DELETE CASCADE, " +
                "server_message_id TEXT, sequence INTEGER, local_order INTEGER NOT NULL DEFAULT 0, body_ciphertext BLOB NOT NULL, direction TEXT NOT NULL, " +
                "participant_role TEXT NOT NULL, participant_id TEXT, created_at INTEGER NOT NULL, delivery_state TEXT NOT NULL, interactive_ciphertext BLOB, " +
                "citations_ciphertext BLOB, is_streaming INTEGER NOT NULL DEFAULT 0, attachment_id TEXT, attachment_type TEXT, attachment_size INTEGER)",
            "CREATE INDEX message_by_sequence ON message(contact_id, sequence, client_message_id)",
            "CREATE INDEX message_by_server_id ON message(contact_id, server_message_id)",
            "CREATE INDEX message_pending ON message(contact_id, direction, delivery_state)",
            "CREATE INDEX message_by_attachment ON message(contact_id, attachment_id)",
            "CREATE INDEX conversation_by_activity ON conversation(last_activity)",
            "PRAGMA user_version = 1",
        )

    private fun userVersion(dir: File): Long =
        BundledSQLiteDriver().open(File(dir, ConversationStore.DATABASE_FILE).path).let { c ->
            try {
                c.query("PRAGMA user_version") { it.getLong(0) }.first()
            } finally {
                c.close()
            }
        }

    @Test
    fun `a store created by the frozen v1 schema is read as v1 and migrates to v2`() = runBlocking {
        val dir = tempDir()
        BundledSQLiteDriver().open(File(dir, ConversationStore.DATABASE_FILE).path).let { c ->
            try {
                frozenV1.forEach { c.execute(it) }
            } finally {
                c.close()
            }
        }
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            assertEquals(ConversationStore.OpenOutcome.OPENED, lastOpenOutcome) // read as v1, not rebuilt
            upsertConversation("c1", Modality.CHAT)
            append("c1", inbound("written under v1", sequence = 1))
            close()
        }
        val v2 = StoreSchema(2, listOf(StoreSchema.V1, StoreSchema.Migration(2, listOf("ALTER TABLE message ADD COLUMN reaction TEXT"))))
        store(dir, keys, schema = v2).run {
            assertEquals(listOf("written under v1"), messages("c1").map { it.body })
            close()
        }
        assertEquals(2L, userVersion(dir))
    }

    @Test
    fun `a server copy carrying the echo's own client id adopts it rather than being a duplicate`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val echo = outbound("hello")
        store.append("c1", echo)
        val outcome = store.append("c1", echo.copy(serverMessageId = "s1", sequence = 5, deliveryState = DeliveryState.SENT))
        assertEquals(ConversationStore.AppendOutcome.Adopted(echo.clientMessageId), outcome)
        assertFalse(store.isPending(echo.clientMessageId))
    }

    @Test
    fun `of two identical pending bodies, the older echo is adopted first`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val first = outbound("same")
        val second = outbound("same")
        store.append("c1", first)
        store.append("c1", second)
        val outcome = store.append("c1", outbound("same", state = DeliveryState.SENT).copy(serverMessageId = "s1", sequence = 1))
        assertEquals(ConversationStore.AppendOutcome.Adopted(first.clientMessageId), outcome)
        assertEquals(listOf(second.clientMessageId), store.pendingClientIds("c1"))
    }

    @Test
    fun `an acknowledgement raises the conversation's last sequence`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val m = outbound("x")
        store.append("c1", m)
        store.updateDeliveryState(m.clientMessageId, DeliveryState.SENT, "s1", 4242)
        assertEquals(4242L, store.conversation("c1")?.lastSequence)
    }

    @Test
    fun `an outbox larger than a page fills the newest page and reports more`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", inbound("history", sequence = 1))
        val pending = (1..5).map { outbound("p$it").also { m -> store.append("c1", m) } }
        val page = store.messages("c1", before = null, limit = 3)
        assertEquals(listOf("p3", "p4", "p5"), page.messages.map { it.body })
        assertTrue(page.hasMore)
        assertEquals(pending.size, store.pendingClientIds("c1").size)
    }

    @Test
    fun `server time orders the transcript whatever the device clock said (skewed clocks)`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val skewed = Instant.parse("2030-01-01T00:00:00Z") // a device clock far ahead
        store.append("c1", inbound("second", sequence = 2000).copy(createdAt = Instant.parse("2020-01-01T00:00:00Z")))
        val mine = outbound("first").copy(createdAt = skewed)
        store.append("c1", mine)
        store.updateDeliveryState(mine.clientMessageId, DeliveryState.SENT, "s1", 1000)
        assertEquals(listOf("first", "second"), store.messages("c1").map { it.body })
        assertEquals(listOf("first", "second"), store.messages("c1", before = null, limit = 10).messages.map { it.body })
    }

    @Test
    fun `a wipe whose new key cannot be stored keeps writes readable after a restart`() = runBlocking {
        val dir = tempDir()
        val inner = InMemorySecureStore()
        var refuse = false
        val flaky =
            object : SecureStore by inner {
                override suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility) {
                    if (refuse) throw TouchpointException.PersistenceFailure("device locked")
                    inner.set(key, value, accessibility)
                }
            }
        val store = store(dir, flaky)
        store.upsertConversation("c1", Modality.CHAT)
        refuse = true
        assertFailsWith<TouchpointException.PersistenceFailure> { store.deleteAllConversations() }
        refuse = false
        assertNull(store.conversation("c1")) // the rows went
        store.upsertConversation("c2", Modality.CHAT)
        store.append("c2", inbound("after the failed wipe", sequence = 1))
        store.close()
        val reopened = store(dir, flaky)
        assertEquals(ConversationStore.OpenOutcome.OPENED, reopened.lastOpenOutcome)
        assertEquals(listOf("after the failed wipe"), reopened.messages("c2").map { it.body })
    }

    @Test
    fun `an open that cannot write its fingerprint throws PersistenceFailure, nothing rawer`() = runBlocking {
        val dir = tempDir()
        File(dir, "fieldkey.fingerprint.tmp").mkdirs() // the fingerprint write will fail
        assertFailsWith<TouchpointException.PersistenceFailure> { store(dir) }
        Unit
    }

    @Test
    fun `a fingerprint left empty by a crash is unknown, not key loss - the store is kept`() = runBlocking {
        val dir = tempDir()
        val keys = InMemorySecureStore()
        store(dir, keys).run {
            upsertConversation("c1", Modality.CHAT)
            close()
        }
        File(dir, "fieldkey.fingerprint").writeBytes(ByteArray(0))
        val reopened = store(dir, keys)
        assertEquals(ConversationStore.OpenOutcome.OPENED, reopened.lastOpenOutcome)
        assertNotNull(reopened.conversation("c1"))
    }

    @Test
    fun `a huge page limit is clamped, not overflowed`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        for (i in 1L..3L) store.append("c1", inbound("m$i", sequence = i))
        val page = store.messages("c1", before = null, limit = Int.MAX_VALUE)
        assertEquals(listOf("m1", "m2", "m3"), page.messages.map { it.body })
        assertFalse(page.hasMore)
    }

    @Test
    fun `explicit deletes leave no message rows even with foreign keys off`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", inbound("m", sequence = 1))
        store.withForeignKeysOffForTesting()
        store.deleteConversation("c1")
        assertEquals(0L, store.totalMessageRowCount())
        store.upsertConversation("c2", Modality.CHAT, lastActivity = Instant.now().minus(Duration.ofDays(400)))
        store.append("c2", inbound("old", sequence = 1).copy(createdAt = Instant.now().minus(Duration.ofDays(400))))
        store.pruneExpired()
        assertEquals(0L, store.totalMessageRowCount())
        store.upsertConversation("c3", Modality.CHAT)
        store.append("c3", inbound("m3", sequence = 1))
        store.deleteAllConversations()
        assertEquals(0L, store.totalMessageRowCount())
    }

    @Test
    fun `unsent echoes stay in composition order when the device clock went backwards between them`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val now = Instant.now()
        store.append("c1", outbound("echo one").copy(createdAt = now))
        store.append("c1", outbound("echo two").copy(createdAt = now.minus(Duration.ofHours(1))))
        assertEquals(listOf("echo one", "echo two"), store.messages("c1").map { it.body })
        assertEquals(listOf("echo one", "echo two"), store.messages("c1", before = null, limit = 10).messages.map { it.body })
    }

    @Test
    fun `a file is one row whichever copy comes first - the session's or the server's echo - and its name stays encrypted`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val sent = outbound("invoice.pdf").copy(deliveryState = DeliveryState.SENT, attachmentId = "att-1", attachmentContentType = "application/pdf", attachmentSize = 42)
        assertEquals(ConversationStore.AppendOutcome.Appended, store.append("c1", sent))
        val echo = sent.copy(clientMessageId = java.util.UUID.randomUUID(), serverMessageId = "s9", sequence = 7)
        assertEquals(ConversationStore.AppendOutcome.Adopted(sent.clientMessageId), store.append("c1", echo))
        val row = store.messages("c1").single()
        assertEquals("s9", row.serverMessageId)
        assertEquals("att-1", row.attachmentId)
        assertEquals(42L, row.attachmentSize)
        // The echo again, or the session's copy after the echo: still one row.
        assertEquals(ConversationStore.AppendOutcome.Duplicate, store.append("c1", echo.copy(clientMessageId = java.util.UUID.randomUUID())))
        assertEquals(ConversationStore.AppendOutcome.Duplicate, store.append("c1", sent.copy(clientMessageId = java.util.UUID.randomUUID(), serverMessageId = null)))
        assertEquals(1, store.messages("c1").size)
    }

    @Test
    fun `an echo that comes first keeps the file's size from the session's copy, and never claims a text with the same words`() = runBlocking {
        val store = store(tempDir())
        store.upsertConversation("c1", Modality.CHAT)
        val text = outbound("photo.png") // pending, its words the file's name
        store.append("c1", text)
        val echo = outbound("photo.png").copy(serverMessageId = "s3", sequence = 3, deliveryState = DeliveryState.SENT, attachmentId = "att-3", attachmentContentType = "image/png")
        assertEquals(ConversationStore.AppendOutcome.Appended, store.append("c1", echo))
        assertTrue(store.isPending(text.clientMessageId))
        val late = echo.copy(clientMessageId = java.util.UUID.randomUUID(), serverMessageId = null, sequence = null, attachmentSize = 1234)
        assertEquals(ConversationStore.AppendOutcome.Duplicate, store.append("c1", late))
        assertEquals(echo.clientMessageId, store.messageIdForAttachment("c1", "att-3"))
        assertEquals(1234L, store.messages("c1").single { it.attachmentId == "att-3" }.attachmentSize)
    }
}
