package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BlockingLiveSync : LiveSyncControl {
    @Volatile var resumeGate: CompletableDeferred<Unit>? = null
    val resumeEntered = CompletableDeferred<Unit>()

    override suspend fun suspendDispatch(reason: DispatchChangeReason) {}

    override suspend fun resumeDispatch() {
        val g = resumeGate ?: return
        resumeEntered.complete(Unit)
        withContext(NonCancellable) { g.await() } // e.g. withContext(IO) { blocking IPC }
    }

    override suspend fun teardown() {}
}

class OutboxRegressionTest {
    private val chat = ChatContactRequest("a")
    private val policy = ReconnectionPolicy(maxAttempts = 3, totalBudget = 5.seconds, initialDelay = 10.milliseconds, maxDelay = 20.milliseconds, jitterFactor = 0.0)

    private fun rt(body: suspend CoroutineScope.(SessionFixture) -> Unit) =
        runBlocking {
            val fixture = SessionFixture()
            try {
                withTimeout(30.seconds) { body(fixture) }
            } finally {
                fixture.close()
            }
        }

    private fun sentIds(t: StubTransport) = synchronized(t.sent) { t.sent.map { it.clientMessageId } }

    private fun serverCopy(body: String, sid: String, seq: Long) =
        StoredMessage(UUID.randomUUID(), sid, body, Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = seq, deliveryState = DeliveryState.SENT)

    @Test
    fun `R3 a reconcile superseded by Reestablished must not release the flush ahead of its successor`() =
        rt { fx ->
            val t = StubTransport()
            val ls = BlockingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), policy, liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val a = session.send("A")
            assertTrue(eventually { t.sent.size == 1 }) // the ack is lost
            ls.resumeGate = CompletableDeferred()
            t.push(TransportEvent.Disconnected(null)) // reconnect; reconcile #1 (empty) blocks in resumeDispatch
            withTimeout(3.seconds) { ls.resumeEntered.await() }
            t.transcriptPage = TranscriptPage(listOf(serverCopy("A", "srv-a", 5)), emptyList(), null) // the server did get A
            t.fetchDelay = 300.milliseconds
            t.push(TransportEvent.Reestablished) // supersedes reconcile #1
            assertTrue(eventually { t.fetchCalls.size == 2 })
            ls.resumeGate!!.complete(Unit) // reconcile #1 returns true from a cancelled job
            assertTrue(eventually(3.seconds) { c.events.count { it == ContactEvent.TranscriptReconciled } == 2 })
            delay(100.milliseconds)
            assertEquals(listOf(a), sentIds(t), "A was resent ahead of the reconcile that adopts it")
            c.stop()
        }

    @Test
    fun `R4 a stale send failing after the reconnect must not clear the new send's in-flight entry`() =
        rt { fx ->
            val t = StubTransport().apply { sendDelay = 150.milliseconds }
            val session = fx.session(mapOf(Modality.CHAT to t), policy, ackTimeout = 200.milliseconds)
            session.startChat(chat)
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val a = session.send("A")
            delay(30.milliseconds)
            t.failSends = 1 // consumed by the stale send when it lands
            t.push(TransportEvent.Disconnected(null)) // reconnect + empty reconcile + resend start well inside 150 ms
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a) }) // the post-reconnect send; its ack is lost
            t.autoAck = true
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, a) }, "lost ack on a live socket never recovered: watchdog not armed")
        }

    @Test
    fun `R1 reconcile give-up releases the held outbox`() =
        rt { fx ->
            val t = StubTransport().apply { autoAck = true }
            val session = fx.session(mapOf(Modality.CHAT to t), policy)
            session.startChat(chat)
            assertTrue(eventually { session.state.value == ContactState.Connected })
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { session.state.value is ContactState.Reconnecting })
            val a = session.send("A")
            t.failFetches = 100
            t.autoConnectEvent = true
            t.push(TransportEvent.Connected)
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a) })
        }

    @Test
    fun `R2 a send during reconcile backoff waits and the adopted message is not resent`() =
        rt { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t), policy)
            val c = EventCollector(session, this)
            session.startChat(chat)
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val a = session.send("A")
            assertTrue(eventually { t.sent.size == 1 })
            t.autoAck = true
            t.transcriptPage = TranscriptPage(listOf(serverCopy("A", "srv-a", 5)), emptyList(), null)
            t.failFetches = 2
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { c.events.count { it is ContactEvent.Failed } >= 1 })
            val b = session.send("B")
            assertTrue(eventually(3.seconds) { ContactEvent.TranscriptReconciled in c.events })
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, b) })
            delay(100.milliseconds)
            assertEquals(listOf(a, b), sentIds(t))
            c.stop()
        }

    @Test
    fun `R5 a later send does not resend one whose ack is still expected`() =
        rt { fx ->
            val t = StubTransport() // ack lags the send, as ConnectChatTransport's does
            val session = fx.session(mapOf(Modality.CHAT to t), policy)
            session.startChat(chat)
            assertTrue(eventually { session.state.value == ContactState.Connected })
            val a = session.send("A")
            assertTrue(eventually { t.sent.size == 1 })
            val b = session.send("B")
            assertTrue(eventually { t.sent.size >= 2 })
            delay(100.milliseconds)
            assertEquals(listOf(a, b), sentIds(t))
        }

    class GatedSuspendLiveSync : LiveSyncControl {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun suspendDispatch(reason: DispatchChangeReason) {
            val g = gate ?: return
            entered.complete(Unit)
            g.await()
        }
        override suspend fun resumeDispatch() {}
        override suspend fun teardown() {}
    }

    @Test
    fun `R6 a send during Reestablished's suspendDispatch must not flush ahead of reconcile`() =
        rt { fx ->
            val t = StubTransport().apply { failSends = 100 }
            val ls = GatedSuspendLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), policy, liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            // Through the collector, not the state: Failed is not replayed, so the collector must be
            // subscribed before the burst starts or it misses the first failures.
            assertTrue(eventually { ContactEvent.StateChanged(ContactState.Connected) in c.events })
            val a = session.send("A") // every attempt "fails" -- but the server got it
            assertTrue(eventually(10.seconds) { c.events.count { it is ContactEvent.Failed } >= 4 }) // the burst gave up
            t.transcriptPage = TranscriptPage(listOf(serverCopy("A", "srv-a", 5)), emptyList(), null)
            t.failSends = 0
            t.autoAck = true
            ls.gate = CompletableDeferred()
            t.push(TransportEvent.Reestablished)
            withTimeout(3.seconds) { ls.entered.await() }
            val b = session.send("B")
            delay(50.milliseconds)
            ls.gate!!.complete(Unit)
            assertTrue(eventually(3.seconds) { ContactEvent.TranscriptReconciled in c.events })
            assertTrue(eventually(3.seconds) { b in sentIds(t) })
            delay(100.milliseconds)
            assertEquals(listOf(b), sentIds(t), "A was resent ahead of the reconcile that adopts it")
            c.stop()
        }
}
