package com.amazon.connect.touchpoint.livesync

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.DispatchChangeReason
import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.ExpiringCredentials
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.TranscriptPage
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dispatch-gate ordering matrix, one case per test: each runs the real [TouchpointSession] and [LiveSyncClient] against a
 * scripted chat transport and a scripted Live Sync socket, and asserts that nothing moved the app
 * or exactly what is allowed to. The voice case is `VoiceDispatchGateTest` in `touchpoint-voice`,
 * where the real voice transport turns Chime attendees into participants.
 *
 * "Processed" is the session having handled the participant event: it hands the gate change to the
 * client before it reads the next one, so from then on the client drops every invocation.
 */
class DispatchGateOrderingTest {
    private val invocation = """{"classification":"custom","action":"open_account","payload":{"accountType":"credit_card"}}"""

    /** A chat contact the test scripts: who joins and leaves, a reconnect, and the transcript reconciliation reads. */
    private class ScriptedChat : ContactTransport {
        private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        override val events: Flow<TransportEvent> = channel.receiveAsFlow()

        /** Held open by a test to keep reconciliation running; complete by default. */
        @Volatile var transcriptGate: CompletableDeferred<Unit> = CompletableDeferred(Unit)

        @Volatile var transcript: TranscriptPage = TranscriptPage.EMPTY
        val transcriptReads: MutableList<Unit> = Collections.synchronizedList(mutableListOf())

        override suspend fun connect(credentials: ExpiringCredentials) {
            channel.trySend(TransportEvent.Connected)
        }

        override suspend fun send(message: OutboundMessage) = Unit

        override suspend fun disconnect() = Unit

        override suspend fun fetchTranscript(
            before: String?,
            limit: Int,
        ): TranscriptPage {
            transcriptReads += Unit
            transcriptGate.await()
            return transcript
        }

        fun join(
            id: String,
            role: ParticipantRole,
        ) = channel.trySend(TransportEvent.ParticipantJoined(Participant(id, role, id)))

        fun leave(id: String) = channel.trySend(TransportEvent.ParticipantLeft(id))

        fun reestablished() = channel.trySend(TransportEvent.Reestablished)
    }

    private class Backend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest) = ChatContactCredentials("c-matrix", "p-me", "tok", null)

        override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = throw TouchpointException.ContactNotFound()

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    private class Rig(
        val session: TouchpointSession,
        val chat: ScriptedChat,
        val client: LiveSyncClient,
        val socket: FakeTransport,
        val transports: FakeTransportFactory,
        val actions: MutableList<String>,
    ) {
        /** A frame from the assistant, and whether it moved the app. */
        suspend fun invoke(frame: String): Boolean {
            val before = actions.size
            val suppressed = client.diagnostics.value.suppressedInvocations
            socket.frame(frame)
            assertTrue(eventually { actions.size > before || client.diagnostics.value.suppressedInvocations > suppressed }, "the frame was neither dispatched nor counted")
            return actions.size > before
        }

        suspend fun gate(state: DispatchState) = assertTrue(eventually { session.dispatchState == state && client.dispatchState.value == state }, "expected $state")
    }

    private fun matrix(block: suspend Rig.() -> Unit) =
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("tp-ls-matrix-").toFile()
            val keys = InMemorySecureStore()
            val store = ConversationStore.open(dir, keys, BundledSQLiteDriver())
            val chat = ScriptedChat()
            val session = TouchpointSession(CredentialBroker(Backend(), keys), store, mapOf(Modality.CHAT to chat))
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            val actions = Collections.synchronizedList(mutableListOf<String>())
            try {
                session.startChat(ChatContactRequest("a"))
                assertTrue(eventually { session.state.value == ContactState.Connected && session.dispatchState == DispatchState.ENABLED })
                client.advertise("open_account")
                client.onAction = { actions += it.action }
                client.attach(session)
                client.connect()
                val socket = transports.waitFor(1) ?: error("no Live Sync socket")
                socket.opened()
                assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
                Rig(session, chat, client, socket, transports, actions).block()
            } finally {
                client.close()
                session.close()
                store.close()
                dir.deleteRecursively()
            }
        }

    @Test
    fun `frame before the join is processed - it may run - and right after - it does not`() =
        matrix {
            assertTrue(invoke(invocation), "no one but the assistant is on the contact yet")
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertEquals(DispatchChangeReason.HUMAN_AGENT_JOINED, session.dispatchChangeReason)
            assertTrue(!invoke(invocation) && !invoke(invocation), "nothing runs once the join is processed")
            assertEquals(listOf("open_account"), actions)
        }

    @Test
    fun `join during reconcile - a live join while the transcript is being read keeps dispatch off when it completes`() =
        matrix {
            val held = CompletableDeferred<Unit>()
            chat.transcriptGate = held
            val reads = chat.transcriptReads.size
            chat.reestablished()
            gate(DispatchState.SUSPENDED)
            assertEquals(DispatchChangeReason.RESUMED_UNRECONCILED, session.dispatchChangeReason)
            assertTrue(eventually { chat.transcriptReads.size > reads }, "reconciliation never read the transcript")
            assertTrue(!invoke(invocation), "nothing runs before the transcript says who is on")
            chat.join("agent-1", ParticipantRole.AGENT)
            // The transcript was read before the agent arrived: it shows no one.
            held.complete(Unit)
            assertTrue(eventually { session.participants().humanAgentPresent })
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            assertTrue(actions.isEmpty())
        }

    @Test
    fun `leave then rejoin - off while the agent is on, on in between, off again on the rejoin`() =
        matrix {
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            chat.leave("agent-1")
            gate(DispatchState.ENABLED)
            assertEquals(null, session.dispatchChangeReason)
            assertTrue(invoke(invocation), "today's rule: with no person on the contact the assistant acts again")
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            assertEquals(listOf("open_account"), actions)
        }

    @Test
    fun `transfer - agent A leaves, agent B joins - off with either on, and the gap between them is today's open window`() =
        matrix {
            chat.join("agent-a", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            chat.leave("agent-a")
            gate(DispatchState.ENABLED)
            // Pending the product decision (AppSec item 5: stay paused for the rest of the contact):
            // between A leaving and B joining, the gate reopens. This assertion flips if that lands.
            assertTrue(invoke(invocation))
            chat.join("agent-b", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            assertEquals(1, actions.size)
        }

    @Test
    fun `transfer - B joining before A's leave is processed - never reopens`() =
        matrix {
            chat.join("agent-a", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            chat.join("agent-b", ParticipantRole.AGENT)
            chat.leave("agent-a")
            assertTrue(eventually { "agent-a" !in session.participants() && "agent-b" in session.participants() })
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            assertTrue(actions.isEmpty())
        }

    @Test
    fun `supervisor barge - the supervisor holds the gate after the agent leaves, and only their leaving reopens it`() =
        matrix {
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            chat.join("supervisor-1", ParticipantRole.SUPERVISOR)
            chat.leave("agent-1")
            assertTrue(eventually { "agent-1" !in session.participants() })
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation), "a supervisor is a person")
            chat.leave("supervisor-1")
            gate(DispatchState.ENABLED)
            assertTrue(invoke(invocation))
        }

    @Test
    fun `an unknown role is a person - a party this version cannot place holds the gate`() =
        matrix {
            chat.join("who", ParticipantRole.UNKNOWN)
            gate(DispatchState.SUSPENDED)
            assertTrue(!invoke(invocation))
            assertTrue(actions.isEmpty())
        }

    @Test
    fun `foreground replay - an agent who joined while the app was in the background holds the gate on the new socket`() =
        matrix {
            client.enterBackground()
            assertTrue(eventually { client.connectionState.value != LiveSyncConnectionState.Connected })
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            client.enterForeground()
            val next = transports.waitFor(2) ?: error("no socket after the foreground")
            next.opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            // Whatever the engine replays on the new socket does not move the app.
            val suppressed = client.diagnostics.value.suppressedInvocations
            next.frame(invocation)
            next.frame(invocation)
            assertTrue(eventually { client.diagnostics.value.suppressedInvocations == suppressed + 2 })
            assertTrue(actions.isEmpty())
        }

    @Test
    fun `a held confirmation goes when a person joins - the customer cannot allow it on the assistant's behalf afterwards`() =
        matrix {
            client.confirmationRequirements = mapOf("open_account" to ActionConfirmation("Open an account"))
            socket.frame(invocation)
            assertTrue(eventually { client.pendingConfirmation.value != null })
            chat.join("agent-1", ParticipantRole.AGENT)
            gate(DispatchState.SUSPENDED)
            assertTrue(eventually { client.pendingConfirmation.value == null && client.diagnostics.value.declinedConfirmations == 1 })
            client.confirmPendingAction() // too late: nothing is held
            assertTrue(actions.isEmpty())
        }
}
