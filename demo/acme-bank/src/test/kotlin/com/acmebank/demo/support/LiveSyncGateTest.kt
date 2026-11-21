package com.acmebank.demo.support

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.ExpiringCredentials
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import com.amazon.connect.touchpoint.livesync.ConsentDecision
import com.amazon.connect.touchpoint.livesync.LiveSyncClient
import com.amazon.connect.touchpoint.livesync.LiveSyncConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Live Sync dispatch gate per modality, through a real session: a chat binding is registered with
 * the gate, so an agent joining suspends the assistant; a voice binding is not, so Connect's own leg of
 * the call (which the SDK counts as a human attendee) leaves the assistant driving the screens.
 */
class LiveSyncGateTest {
    /** A contact whose remote side can be made to join, as Connect's attendee does on a call. */
    private class RemoteTransport : ContactTransport {
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        override val events: Flow<TransportEvent> = channel.receiveAsFlow()

        override suspend fun connect(credentials: ExpiringCredentials) {
            channel.trySend(TransportEvent.Connected)
        }

        override suspend fun send(message: OutboundMessage) = Unit

        override suspend fun disconnect() = Unit

        fun remoteJoins() = channel.trySend(TransportEvent.ParticipantJoined(Participant("remote-1", ParticipantRole.AGENT, "")))
    }

    private class Backend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest) = ChatContactCredentials("contact-chat", "p-me", null, null)

        override suspend fun startVoiceContact(request: VoiceContactRequest) = VoiceContactCredentials("contact-voice", "p-me", "fake", "{}", "{}", null)

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    /** Never asked: the client is not connected in these tests. */
    private object NoCredentials : LiveSyncCredentialSource {
        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()

        override suspend fun invalidateLiveSyncCredentials() = Unit
    }

    private fun client(contactId: String) =
        LiveSyncClient(
            LiveSyncConfiguration("us-east-1", contactId, LiveSyncChannel.from(Locale.US)),
            NoCredentials,
            consent = ConsentDecision.HELD,
            autoConnect = false,
            callbackDispatcher = Dispatchers.Unconfined,
        )

    private fun eventually(check: () -> Boolean): Boolean {
        repeat(200) {
            if (check()) return true
            Thread.sleep(10)
        }
        return check()
    }

    private fun withSession(block: suspend (TouchpointSession, chat: RemoteTransport, voice: RemoteTransport) -> Unit) =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("acme-ls-gate-").toFile()
            val keys = InMemorySecureStore()
            val store = ConversationStore.open(dir, keys, BundledSQLiteDriver())
            val chat = RemoteTransport()
            val voice = RemoteTransport()
            val session = TouchpointSession(CredentialBroker(Backend(), keys), store, mapOf(Modality.CHAT to chat, Modality.VOICE to voice))
            try {
                block(session, chat, voice)
            } finally {
                session.close()
                store.close()
                dir.deleteRecursively()
            }
        }

    @Test
    fun `a chat binding attaches the gate - an agent joining suspends the assistant`() =
        withSession { session, chat, _ ->
            session.startChat(ChatContactRequest("a"))
            val c = client("contact-chat")
            LiveSyncCoordinator.attachGate(c, session, gate = true)
            chat.remoteJoins()
            assertTrue(eventually { c.dispatchState.value == DispatchState.SUSPENDED }, "chat must keep pausing the assistant for a human agent")
            c.close()
        }

    @Test
    fun `a voice binding does not attach the gate - Connect's leg joining leaves the assistant driving`() =
        withSession { session, _, voice ->
            session.startVoice(VoiceContactRequest("a"))
            val c = client("contact-voice")
            LiveSyncCoordinator.attachGate(c, session, gate = false)
            voice.remoteJoins()
            // The session's own gate closes, as the SDK decides; the unattached client never hears of it.
            assertTrue(eventually { session.dispatchState == DispatchState.SUSPENDED }, "the session should still classify the join as a human")
            assertEquals(DispatchState.ENABLED, c.dispatchState.value)
            c.close()
        }
}
