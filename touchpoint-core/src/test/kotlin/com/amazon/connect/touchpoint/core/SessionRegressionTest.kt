package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionRegressionTest {
    private val chat = ChatContactRequest(displayName = "a")

    private fun sessionTest(body: suspend CoroutineScope.(SessionFixture) -> Unit) =
        runBlocking {
            val fixture = SessionFixture()
            try { withTimeout(30.seconds) { body(fixture) } } finally { fixture.close() }
        }

    /** end() lands while start() is in connect; the aborted connect then throws. */
    @Test
    fun `R1 end during connect - a late connect failure does not end the contact again`() =
        sessionTest { fx ->
            val gate = CompletableDeferred<Unit>()
            val t = StubTransport().apply { connectGate = gate; failConnects = 1 }
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            val starter = launch(Dispatchers.Default) { runCatching { session.startChat(chat) } }
            eventually { t.connectCalls == 1 }
            session.end()
            assertEquals(ContactState.Ended(EndReason.CUSTOMER_ENDED), session.state.value)
            gate.complete(Unit) // connect now throws, as an aborted connect does
            starter.join()
            delay(100.milliseconds)
            assertEquals(ContactState.Ended(EndReason.CUSTOMER_ENDED), session.state.value)
            c.stop()
        }

    /** end() during connect, then a NEW contact starts; the first connect's late failure tears down the new one. */
    @Test
    fun `R2 a stale connect failure does not touch the next contact`() =
        sessionTest { fx ->
            val gate = CompletableDeferred<Unit>()
            val t = StubTransport().apply { connectGate = gate }
            val session = fx.session(mapOf(Modality.CHAT to t))
            val starter = launch(Dispatchers.Default) { runCatching { session.startChat(chat) } }
            eventually { t.connectCalls == 1 }
            session.end()
            t.connectGate = null
            session.startChat(ChatContactRequest("b"))
            eventually { session.state.value == ContactState.Connected }
            val second = session.contactId
            t.failConnects = 1
            gate.complete(Unit)
            starter.join()
            delay(100.milliseconds)
            assertEquals(ContactState.Connected, session.state.value)
        }

    /** A transport whose events flow throws kills the pump silently; the contact is stuck Connected. */
    @Test
    fun `R3 a throwing transport events flow is a lost connection, not a stall`() =
        sessionTest { fx ->
            val t = object : ContactTransport by StubTransport() {
                val ch = Channel<TransportEvent>(Channel.UNLIMITED)
                override val events: Flow<TransportEvent> = flow {
                    for (e in ch) { if (e is TransportEvent.Disconnected) throw java.io.IOException("socket"); emit(e) }
                }
                override suspend fun connect(credentials: ExpiringCredentials) { ch.trySend(TransportEvent.Connected) }
            }
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            eventually { session.state.value == ContactState.Connected }
            t.ch.trySend(TransportEvent.Disconnected("x"))
            delay(300.milliseconds)
            t.ch.trySend(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
            delay(300.milliseconds)
            assertEquals(ContactState.Ended(EndReason.AGENT_ENDED), session.state.value)
            c.stop()
        }

    /** The documented contract: events "Completes when the transport disconnects". Reconnect never reaches Connected. */
    @Test
    fun `R4 a per-connection events flow is collected afresh on reconnect`() =
        sessionTest { fx ->
            val t = object : ContactTransport by StubTransport() {
                @Volatile var ch = Channel<TransportEvent>(Channel.UNLIMITED)
                override val events: Flow<TransportEvent> get() = ch.consumeAsFlow()
                override suspend fun connect(credentials: ExpiringCredentials) {
                    if (ch.isClosedForSend) ch = Channel(Channel.UNLIMITED)
                    ch.trySend(TransportEvent.Connected)
                }
                fun drop() { ch.trySend(TransportEvent.Disconnected("drop")); ch.close() }
            }
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            eventually { session.state.value == ContactState.Connected }
            t.drop()
            delay(1.seconds)
            assertEquals(ContactState.Connected, session.state.value)
        }

    /** After close(), start() succeeds but the pump never runs: stuck in Connecting with a live wire. */
    @Test
    fun `R5 start after close is refused`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.close()
            val r = runCatching { session.startChat(chat) }
            delay(300.milliseconds)
            assertEquals(true, r.isFailure)
        }

    /** A LiveSyncControl registered while dispatch is suspended is never told. */
    @Test
    fun `R6 a control registered while dispatch is suspended is told so`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            eventually { session.state.value == ContactState.Connected }
            t.push(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "A")))
            val c = EventCollector(session, this)
            eventually { c.events.any { it is ContactEvent.LiveSyncDispatchChanged } }
            c.stop()
            val ls = RecordingLiveSync()
            session.register(ls)
            delay(100.milliseconds)
            assertEquals(listOf("suspend:HUMAN_AGENT_JOINED"), ls.snapshot())
        }

    private suspend fun seed(fx: SessionFixture, agent: Boolean): String {
        val broker = fx.broker()
        val store = fx.store()
        val creds = broker.startChatContact(ChatContactRequest("seed"))
        store.upsertConversation(creds.contactId, Modality.CHAT)
        if (agent) store.setParticipants(creds.contactId, ParticipantSet().join(Participant("ag", ParticipantRole.AGENT, "A")))
        return creds.contactId
    }

    /** A second Connected while reconcile is in flight cancels it; nothing retries, so dispatch never lifts. */
    @Test
    fun `R8 a duplicate Connected does not cancel reconciliation`() =
        sessionTest { fx ->
            val id = seed(fx, agent = false)
            val ls = RecordingLiveSync()
            val t = StubTransport().apply { fetchDelay = 300.milliseconds }
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            session.resume(id)
            eventually { t.fetchCalls.size == 1 }
            t.push(TransportEvent.Connected) // e.g. a transport-internal reconnect, or two racing connects
            delay(2.seconds)
            assertEquals("resume", ls.snapshot().last())
        }

    /** A live leave during reconcile lifts dispatch before reconciliation; the fold then re-adds the agent. */
    @Test
    fun `R7 a live leave during reconciliation does not lift dispatch early, and a stale page cannot resurrect the agent`() =
        sessionTest { fx ->
            val id = seed(fx, agent = true)
            val ls = RecordingLiveSync()
            val t = StubTransport().apply { fetchDelay = 300.milliseconds }
            // The server page was read before the leave: it still shows the join.
            t.transcriptPage = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "A"))), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.resume(id)
            eventually { t.fetchCalls.size == 1 }
            t.push(TransportEvent.ParticipantLeft("ag"))
            kotlin.test.assertTrue(eventually { ContactEvent.TranscriptReconciled in c.events })
            delay(100.milliseconds)
            // The agent really left: reconciliation, not the leave, lifts dispatch — once.
            kotlin.test.assertFalse(session.participants().humanAgentPresent)
            assertEquals(listOf("suspend:RESUMED_UNRECONCILED", "resume"), ls.snapshot())
            kotlin.test.assertTrue(c.events.none { it == ContactEvent.LiveSyncDispatchChanged(DispatchState.ENABLED, DispatchChangeReason.HUMAN_AGENT_LEFT) })
            c.stop()
        }
}
