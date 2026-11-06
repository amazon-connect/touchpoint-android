package com.amazon.connect.touchpoint.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.DispatchChangeReason
import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.MessageId
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.SecureStore
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The real session over the real transport, with only the upstream SDK faked: covers the wiring
 * the unit tests cannot — what the coordinator makes of what this transport emits.
 */
class ChatSessionIntegrationTest {
    private class StubBackend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest) = ChatContactCredentials("c-int", "p-me", "tok", null)

        override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = throw TouchpointException.ContactNotFound()

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    private class Fixture(val dir: File = createTempDirectory("tp-chat-int-").toFile(), val keys: SecureStore = InMemorySecureStore())

    private suspend fun session(fx: Fixture, driver: ChatSessionDriving): Pair<TouchpointSession, ConversationStore> {
        val store = ConversationStore.open(fx.dir, fx.keys, BundledSQLiteDriver())
        return TouchpointSession(CredentialBroker(StubBackend(), fx.keys), store, mapOf(Modality.CHAT to ConnectChatTransport(driver))) to store
    }

    private suspend fun eventually(timeout: Duration = 3.seconds, condition: suspend () -> Boolean): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (deadline.hasNotPassedNow()) {
            if (condition()) return true
            delay(10.milliseconds)
        }
        return condition()
    }

    private fun CoroutineScope.record(session: TouchpointSession): MutableList<ContactEvent> =
        Collections.synchronizedList(mutableListOf<ContactEvent>()).also { list -> launch(Dispatchers.Default) { session.events.collect { list += it } } }

    private fun test(body: suspend CoroutineScope.(Fixture) -> Unit) =
        runBlocking {
            val fx = Fixture()
            try {
                withTimeout(30.seconds) { body(fx) }
            } finally {
                fx.dir.deleteRecursively()
            }
        }

    private class Driver : ChatSessionDriving {
        @Volatile var installed: ChatDriverHandlers? = null
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val interactive: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        val pages: MutableList<RawTranscriptPage> = Collections.synchronizedList(mutableListOf())

        /** Sends the server takes, but whose acknowledgement never comes back: a dropped socket mid-send. */
        @Volatile var loseAcks = 0
        private var n = 0

        override fun setHandlers(handlers: ChatDriverHandlers) {
            installed = handlers
        }

        override suspend fun connect(contactId: String, participantId: String, participantToken: String?) {
            // The SDK raises it from its socket after connect has returned, not within it.
            CoroutineScope(Dispatchers.Default).launch {
                delay(20.milliseconds)
                installed?.onConnected?.invoke()
            }
        }

        override suspend fun disconnect() = Unit

        override suspend fun release() = Unit

        override suspend fun sendMessage(
            body: String,
            interactiveResponse: Boolean,
        ): SentMessageAck {
            sent += body
            interactive += interactiveResponse
            if (loseAcks > 0) {
                loseAcks--
                throw com.amazon.connect.touchpoint.core.TouchpointException.TransportFailure("ack lost")
            }
            n++
            return SentMessageAck("srv-$n", "2026-10-08T18:00:0$n.000Z")
        }

        override suspend fun sendReceipt(kind: com.amazon.connect.touchpoint.core.ReceiptKind, messageId: String) = Unit

        val uploaded: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun uploadAttachment(file: com.amazon.connect.touchpoint.core.AttachmentUpload): String {
            uploaded += file.open().use { it.readBytes().decodeToString() }
            return "att-${uploaded.size}"
        }

        override suspend fun fetchTranscript(nextToken: String?, maxResults: Int): RawTranscriptPage =
            synchronized(pages) { if (pages.isEmpty()) RawTranscriptPage(emptyList(), null) else pages.removeAt(0) }
    }

    private fun item(kind: RawTranscriptItem.Kind, id: String, contentType: String, role: String, pid: String?, text: String? = null, time: String = "2026-10-08T17:00:00.000Z") =
        RawTranscriptItem(kind, id, time, contentType, role, pid, "Ann", text, isOutgoing = false)

    @Test
    fun `a sent message is acknowledged with the server's id and time, and an agent reply is stored in server order`() =
        test { fx ->
            val driver = Driver()
            val (session, store) = session(fx, driver)
            val events = record(session)
            session.startChat(ChatContactRequest("a"))
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val id = session.send("I need help with a charge")
            assertTrue(eventually { session.transcript().single().deliveryState == DeliveryState.SENT })
            val mine = session.transcript().single()
            assertEquals(MessageId("srv-1"), mine.serverMessageId)
            assertEquals(java.time.Instant.parse("2026-10-08T18:00:01Z").toEpochMilli(), mine.sequence)
            driver.installed?.onItem?.invoke(item(RawTranscriptItem.Kind.MESSAGE, "a1", ConnectContentType.PLAIN_TEXT, "AGENT", "p-a", "Happy to help.", "2026-10-08T18:00:05.000Z"))
            assertTrue(eventually { session.transcript().size == 2 })
            assertEquals(listOf("I need help with a charge", "Happy to help."), session.transcript().map { it.body })
            assertTrue(events.any { it is ContactEvent.DeliveryStateChanged && it.update.clientMessageId == id && it.update.state == DeliveryState.SENT })
            session.close()
            store.close()
        }

    @Test
    fun `an answer goes from the session to the wire as web's response, and its echo after a lost ack is adopted, not shown twice`() =
        test { fx ->
            val first = Driver().apply { loseAcks = 1 }
            val (s1, store1) = session(fx, first)
            s1.startChat(ChatContactRequest("a"))
            assertTrue(eventually { s1.state.value == ContactState.Connected })
            // Words with a space at the cap: normalised once, so the echo's title matches the stored row.
            val words = "a".repeat(199) + " " + "b".repeat(20)
            s1.sendChoice(words)
            assertTrue(eventually { first.sent.isNotEmpty() })
            assertEquals(listOf(true), first.interactive.toList())
            val envelope = first.sent.first()
            s1.close() // the process dies before any retry; the answer is still pending
            store1.close()

            // The server did take it: reconciliation brings back the echo.
            val second = Driver()
            second.pages +=
                RawTranscriptPage(
                    listOf(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "r1", "2026-10-08T18:00:01.000Z", ConnectContentType.INTERACTIVE_RESPONSE, "CUSTOMER", "p-me", "Me", envelope, isOutgoing = true)),
                    "",
                )
            val (s2, store2) = session(fx, second)
            val events = record(s2)
            s2.resume("c-int")
            assertTrue(eventually { events.contains(ContactEvent.TranscriptReconciled) })
            delay(200.milliseconds) // any flush that would re-send has had its chance
            val answer = s2.transcript().single()
            assertEquals(InteractiveContent.choiceText(words), answer.body)
            assertEquals(DeliveryState.SENT, answer.deliveryState)
            assertTrue(second.sent.isEmpty(), "adopted, so not sent again")
            s2.close()
            store2.close()
        }

    @Test
    fun `a supervisor joining over the live stream suspends Live Sync dispatch`() =
        test { fx ->
            val driver = Driver()
            val (session, store) = session(fx, driver)
            val events = record(session)
            session.startChat(ChatContactRequest("a"))
            assertTrue(eventually { session.state.value == ContactState.Connected })
            driver.installed?.onItem?.invoke(item(RawTranscriptItem.Kind.EVENT, "j1", ConnectContentType.JOINED, "SUPERVISOR", "p-sup"))
            assertTrue(eventually { events.contains(ContactEvent.LiveSyncDispatchChanged(DispatchState.SUSPENDED, DispatchChangeReason.HUMAN_AGENT_JOINED)) })
            session.close()
            store.close()
        }

    @Test
    fun `resume reconciles through the transport's pages, keyed on participant id, and keeps dispatch off while an agent is on`() =
        test { fx ->
            val first = Driver()
            val (s1, store1) = session(fx, first)
            s1.startChat(ChatContactRequest("a"))
            assertTrue(eventually { s1.state.value == ContactState.Connected })
            s1.close() // the process dies; credentials stay for resume
            store1.close()

            val second = Driver()
            second.pages +=
                RawTranscriptPage(
                    listOf(
                        item(RawTranscriptItem.Kind.EVENT, "j1", ConnectContentType.JOINED, "AGENT", "p-a"),
                        item(RawTranscriptItem.Kind.MESSAGE, "a1", ConnectContentType.PLAIN_TEXT, "AGENT", "p-a", "Still with you."),
                    ),
                    "",
                )
            val (s2, store2) = session(fx, second)
            val events = record(s2)
            s2.resume("c-int")
            assertTrue(eventually { events.contains(ContactEvent.TranscriptReconciled) })
            assertTrue(s2.participants().humanAgentPresent)
            assertEquals(listOf("Still with you."), s2.transcript().map { it.body })
            assertTrue(events.none { it == ContactEvent.LiveSyncDispatchChanged(DispatchState.ENABLED, DispatchChangeReason.TRANSCRIPT_RECONCILED) })
            s2.close()
            store2.close()
        }

    @Test
    fun `a file goes up whole, shows once Connect has it, and its echo gives it the server's place rather than a second row`() =
        test { fx ->
            val driver = Driver()
            val (session, store) = session(fx, driver)
            session.startChat(ChatContactRequest("a"))
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val file = com.amazon.connect.touchpoint.core.AttachmentUpload("note.txt", "text/plain", 5) { "hello".byteInputStream() }
            session.sendAttachment(file)
            assertEquals(listOf("hello"), driver.uploaded.toList())
            val row = session.transcript().single()
            assertEquals("note.txt", row.body)
            assertEquals("att-1", row.attachment?.id)
            assertEquals(5L, row.attachment?.sizeBytes)
            driver.installed?.onItem?.invoke(
                RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "f1", "2026-10-08T18:00:09.000Z", "text/plain", "CUSTOMER", "p-me", "Me", "note.txt", isOutgoing = true, attachmentId = "att-1"),
            )
            assertTrue(eventually { session.transcript().single().serverMessageId?.rawValue == "f1" })
            assertEquals(1, session.transcript().size)
            // Over web's 8 MB: refused before anything is sent.
            val big = com.amazon.connect.touchpoint.core.AttachmentUpload("big.mp4", "video/mp4", com.amazon.connect.touchpoint.core.AttachmentUpload.MAX_BYTES + 1) { error("not read") }
            kotlin.test.assertFailsWith<TouchpointException.InvalidConfiguration> { session.sendAttachment(big) }
            assertEquals(1, driver.uploaded.size)
            session.close()
            store.close()
        }
}
