package com.amazon.connect.touchpoint.core.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.ParticipantSet
import com.amazon.connect.touchpoint.core.StoredMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The store through the framework SQLite driver, on a device. `make connected`. */
@RunWith(AndroidJUnit4::class)
class AndroidStoresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        AndroidStores.directory(context).deleteRecursively()
    }

    private fun message(body: String, seq: Long, id: String = "s$seq") =
        StoredMessage(UUID.randomUUID(), id, body, Direction.INBOUND, ParticipantRole.AGENT, sequence = seq, deliveryState = DeliveryState.DELIVERED)

    /** Each test owns the process-wide instance and closes it, so the next one opens afresh. */
    private fun withStore(block: suspend (ConversationStore) -> Unit) = runBlocking {
        val store = AndroidStores.conversationStore(context, InMemorySecureStore())
        try {
            block(store)
        } finally {
            store.close()
        }
    }

    @Test
    fun opensWritesAndReopensThroughTheFrameworkDriver() = runBlocking {
        val keys = InMemorySecureStore()
        val secret = "PLAINTEXT-MARKER-${UUID.randomUUID()}"
        val first = AndroidStores.conversationStore(context, keys)
        try {
            first.upsertConversation("c1", Modality.CHAT)
            first.append("c1", message(secret, 1))
            first.checkpoint()
            assertSame(first, AndroidStores.conversationStore(context, keys)) // one per process while open
        } finally {
            first.close()
        }
        val reopened = AndroidStores.conversationStore(context, keys)
        try {
            assertEquals(ConversationStore.OpenOutcome.OPENED, reopened.lastOpenOutcome)
            assertEquals(listOf(secret), reopened.messages("c1").map { it.body })
            AndroidStores.directory(context).walkTopDown().filter { it.isFile }.forEach {
                assertFalse("plaintext in ${it.name}", String(it.readBytes(), Charsets.ISO_8859_1).contains(secret))
            }
        } finally {
            reopened.close()
        }
    }

    // The framework driver pools connections under WAL; these are the paths that would notice.

    @Test
    fun participantsUpdateAnExistingConversation() = withStore { store ->
        store.upsertConversation("c1", Modality.CHAT)
        store.setParticipants("c1", ParticipantSet().join(Participant("a1", ParticipantRole.AGENT, "A")))
        assertTrue(store.humanAgentPresent("c1"))
    }

    @Test
    fun deletingAConversationLeavesNoMessageRows() = withStore { store ->
        store.upsertConversation("c1", Modality.CHAT)
        store.append("c1", message("one", 1))
        store.deleteConversation("c1")
        assertTrue(store.messages("c1").isEmpty())
        store.upsertConversation("c1", Modality.CHAT) // same id again: nothing of the old one comes back
        assertTrue(store.messages("c1").isEmpty())
    }

    @Test
    fun pruningAndTheFullWipeLeaveNoMessageRows() = withStore { store ->
        store.upsertConversation("old", Modality.CHAT, lastActivity = Instant.now().minus(Duration.ofDays(60)))
        store.append("old", message("stale", 1).copy(createdAt = Instant.now().minus(Duration.ofDays(60))))
        assertEquals(listOf("old"), store.pruneExpired())
        store.upsertConversation("old", Modality.CHAT)
        assertTrue(store.messages("old").isEmpty())
        store.append("old", message("again", 2))
        store.deleteAllConversations()
        store.upsertConversation("old", Modality.CHAT)
        assertTrue(store.messages("old").isEmpty())
    }

    @Test
    fun pagingWalksBackThroughTheFrameworkDriver() = withStore { store ->
        store.upsertConversation("c1", Modality.CHAT)
        for (i in 1L..5L) store.append("c1", message("m$i", i))
        val newest = store.messages("c1", before = null, limit = 2)
        assertEquals(listOf("m4", "m5"), newest.messages.map { it.body })
        assertTrue(newest.hasMore)
    }

    @Test
    fun aFileThatIsNotSqliteIsRebuiltAndReported() = runBlocking {
        val keys = InMemorySecureStore()
        AndroidStores.conversationStore(context, keys).close()
        File(AndroidStores.directory(context), "touchpoint.db").writeBytes(ByteArray(4096) { 0x5A })
        val rebuilt = AndroidStores.conversationStore(context, keys)
        try {
            assertEquals(ConversationStore.OpenOutcome.REBUILT_AFTER_FAILURE, rebuilt.lastOpenOutcome)
            rebuilt.upsertConversation("c2", Modality.CHAT)
        } finally {
            rebuilt.close()
        }
    }

    @Test
    fun theStoreLivesInTheNoBackupDirectory() {
        assertEquals(context.noBackupFilesDir, AndroidStores.directory(context).parentFile?.parentFile)
    }
}
