package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The outbox: the offline behaviour users expect from every messaging app they already use. */
class OutboxTest {
    private val chat = ChatContactRequest("a")
    private val policy = ReconnectionPolicy(maxAttempts = 3, totalBudget = 5.seconds, initialDelay = 10.milliseconds, maxDelay = 20.milliseconds, jitterFactor = 0.0)

    private fun outboxTest(body: suspend CoroutineScope.(SessionFixture) -> Unit) =
        runBlocking {
            val fixture = SessionFixture()
            try {
                withTimeout(30.seconds) { body(fixture) }
            } finally {
                fixture.close()
            }
        }

    private suspend fun SessionFixture.outboxSession(
        t: StubTransport,
        ackTimeout: Duration = 30.seconds,
    ): TouchpointSession = session(mapOf(Modality.CHAT to t), policy, ackTimeout = ackTimeout)

    private suspend fun TouchpointSession.awaitConnected() = assertTrue(eventually { state.value == ContactState.Connected })

    private suspend fun TouchpointSession.awaitReconnecting() = assertTrue(eventually { state.value is ContactState.Reconnecting })

    private fun sentIds(t: StubTransport) = synchronized(t.sent) { t.sent.map { it.clientMessageId } }

    @Test
    fun `send offline, reconnect - every queued message is delivered exactly once, in composition order`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            val ids = listOf(session.send("one"), session.send("two"), session.send("three"))
            assertTrue(t.sent.isEmpty()) // nothing went out while offline
            t.autoConnectEvent = true
            t.push(TransportEvent.Connected)
            assertTrue(eventually { t.sent.size == 3 })
            assertEquals(ids, sentIds(t))
            assertTrue(eventually { session.transcript().all { it.deliveryState == DeliveryState.SENT } })
            delay(80.milliseconds)
            assertEquals(3, t.sent.size) // exactly once
        }

    @Test
    fun `a send that fails on a healthy connection is retried with backoff and stays SENDING meanwhile`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true; failSends = 2 }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("flaky")
            assertTrue(eventually { c.events.any { it is ContactEvent.Failed && it.error is TouchpointException.TransportFailure } })
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a) })
            assertTrue(eventually { session.transcript().first().deliveryState == DeliveryState.SENT })
            c.stop()
        }

    @Test
    fun `a later send never overtakes an earlier failed one`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true; failSends = 1 }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("first (fails once)")
            val b = session.send("second")
            assertTrue(eventually(3.seconds) { t.sent.size == 2 })
            assertEquals(listOf(a, b), sentIds(t))
        }

    @Test
    fun `a send that fails stays queued while offline and goes out on the next connect`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true; failSends = 1 }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("first (fails)")
            delay(5.milliseconds)
            t.push(TransportEvent.Disconnected(null))
            session.awaitConnected()
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a) })
        }

    @Test
    fun `lost ack then resume - reconcile adopts the server's copy and the flush does not resend it`() =
        outboxTest { fx ->
            val contactId: String
            val sentId: UUID
            run {
                // process 1: the server accepts, the ack never arrives, the app dies
                val t = StubTransport()
                val session = fx.outboxSession(t)
                session.startChat(chat)
                session.awaitConnected()
                contactId = session.contactId.orEmpty()
                sentId = session.send("pay my bill")
                assertTrue(eventually { t.sent.size == 1 })
                session.close()
            }
            val t = StubTransport().apply { autoAck = true }
            t.transcriptPage =
                TranscriptPage(
                    listOf(StoredMessage(UUID.randomUUID(), "srv-bill", "pay my bill", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 5, deliveryState = DeliveryState.SENT)),
                    emptyList(),
                    null,
                )
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.resume(contactId)
            assertTrue(eventually(3.seconds) { ContactEvent.TranscriptReconciled in c.events })
            delay(150.milliseconds) // every chance for a wrongly ordered flush to fire
            assertTrue(t.sent.isEmpty(), "flush resent a message the server already had")
            val rows = session.transcript()
            assertEquals(1, rows.size)
            assertEquals(sentId, rows[0].id)
            assertEquals(MessageId("srv-bill"), rows[0].serverMessageId)
            assertEquals(DeliveryState.SENT, rows[0].deliveryState)
            c.stop()
        }

    @Test
    fun `send during an in-flight flush with a lagging ack does not resend the in-flight message`() =
        outboxTest { fx ->
            val t = StubTransport().apply { sendDelay = 120.milliseconds; autoAck = true }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("A")
            delay(30.milliseconds)
            val b = session.send("B") // arrives while A's send is in flight
            assertTrue(eventually(3.seconds) { t.sent.size == 2 })
            delay(200.milliseconds)
            assertEquals(listOf(a, b), sentIds(t))
        }

    @Test
    fun `a disconnect during an in-flight send - not resent when the server has it, and the stale result is ignored`() =
        outboxTest { fx ->
            val t = StubTransport().apply { sendDelay = 150.milliseconds }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("in flight")
            delay(30.milliseconds)
            t.transcriptPage =
                TranscriptPage(
                    listOf(StoredMessage(UUID.randomUUID(), "srv-if", "in flight", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 9, deliveryState = DeliveryState.SENT)),
                    emptyList(),
                    null,
                )
            t.push(TransportEvent.Disconnected(null))
            session.awaitConnected()
            delay(400.milliseconds)
            assertEquals(listOf(a), sentIds(t)) // the original, once
            val rows = session.transcript()
            assertEquals(1, rows.size)
            assertEquals(DeliveryState.SENT, rows[0].deliveryState)
            assertEquals(MessageId("srv-if"), rows[0].serverMessageId)
        }

    @Test
    fun `end during an in-flight send - the row is failed, nothing follows ContactEnded, and a new contact flushes`() =
        outboxTest { fx ->
            val t = StubTransport().apply { sendDelay = 150.milliseconds; failSends = 1 }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("doomed")
            delay(30.milliseconds)
            session.end()
            delay(300.milliseconds) // the slow send fails after teardown
            val events = c.events
            val end = events.indexOfFirst { it is ContactEvent.ContactEnded }
            assertTrue(end >= 0)
            assertEquals(emptyList(), events.drop(end + 1))
            val row = session.transcript(session.contactId.orEmpty()).first()
            assertEquals(a, row.id)
            assertEquals(DeliveryState.FAILED, row.deliveryState)
            val t2 = StubTransport().apply { autoAck = true }
            val s2 = fx.outboxSession(t2)
            s2.startChat(ChatContactRequest("b"))
            s2.awaitConnected()
            val fresh = s2.send("fresh")
            assertTrue(eventually { sentIds(t2) == listOf(fresh) })
            c.stop()
        }

    @Test
    fun `an inbound message arriving mid-flush is surfaced without waiting for the flush`() =
        outboxTest { fx ->
            val t = StubTransport().apply { sendDelay = 150.milliseconds; autoAck = true }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            session.send("slow one")
            delay(20.milliseconds)
            t.push(TransportEvent.MessageReceived(inbound("agent says hi", "in-1", 1)))
            assertTrue(eventually(100.milliseconds) { c.events.any { it is ContactEvent.MessageReceived && it.message.body == "agent says hi" } })
            assertTrue(t.sent.isEmpty()) // the inbound beat the still-in-flight send
            assertTrue(eventually(2.seconds) { t.sent.size == 1 })
            assertEquals(listOf("agent says hi", "slow one"), session.transcript().map { it.body })
            c.stop()
        }

    @Test
    fun `the server echo of our own message can beat the ack - one row, one SENT transition, no extra MessageReceived`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true; echoBeforeAck = true }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("race me")
            assertTrue(eventually { session.transcript().first().deliveryState == DeliveryState.SENT })
            delay(60.milliseconds)
            val rows = session.transcript()
            assertEquals(1, rows.size)
            assertEquals(a, rows[0].id)
            assertEquals(1, c.events.count { it is ContactEvent.DeliveryStateChanged && it.update.clientMessageId == a && it.update.state == DeliveryState.SENT })
            assertEquals(1, c.events.count { it is ContactEvent.MessageReceived })
            c.stop()
        }

    @Test
    fun `the outbox survives process death - a new session over the same store flushes what the old one never sent`() =
        outboxTest { fx ->
            val contactId: String
            val queued: List<UUID>
            run {
                val t = StubTransport()
                val session = fx.outboxSession(t)
                session.startChat(chat)
                session.awaitConnected()
                contactId = session.contactId.orEmpty()
                t.autoConnectEvent = false
                t.push(TransportEvent.Disconnected(null))
                session.awaitReconnecting()
                queued = listOf(session.send("drafted offline 1"), session.send("drafted offline 2"))
                session.close()
            }
            val t = StubTransport().apply { autoAck = true }
            val session = fx.outboxSession(t)
            session.resume(contactId)
            assertTrue(eventually(3.seconds) { sentIds(t) == queued })
            assertTrue(eventually { session.transcript().all { it.deliveryState == DeliveryState.SENT } })
        }

    @Test
    fun `a duplicate server echo of a flushed message is suppressed`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.send("hello")
            eventually { session.transcript().first().deliveryState == DeliveryState.SENT }
            val sid = session.transcript().first().serverMessageId?.rawValue.orEmpty()
            t.push(TransportEvent.MessageReceived(StoredMessage(UUID.randomUUID(), sid, "hello", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 1001, deliveryState = DeliveryState.SENT)))
            delay(60.milliseconds)
            assertEquals(1, session.transcript().size)
            assertEquals(id, session.transcript().first().id)
            assertEquals(1, c.events.count { it is ContactEvent.MessageReceived })
            c.stop()
        }

    @Test
    fun `contact end with a non-empty outbox surfaces each failure rather than losing it`() =
        outboxTest { fx ->
            val t = StubTransport()
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            val a = session.send("never sent")
            val b = session.send("never sent 2")
            session.end()
            val rows = session.transcript(session.contactId.orEmpty())
            assertEquals(listOf(a, b), rows.map { it.id })
            assertTrue(rows.all { it.deliveryState == DeliveryState.FAILED })
            assertTrue(eventually { c.events.filterIsInstance<ContactEvent.DeliveryStateChanged>().filter { it.update.state == DeliveryState.FAILED }.map { it.update.clientMessageId } == listOf(a, b) })
            c.stop()
        }

    @Test
    fun `a send lost on the wire to a disconnect is resent after reconnect (at-least-once)`() =
        outboxTest { fx ->
            val t = StubTransport() // the send "succeeds" but no ack ever comes
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("vanished on the wire")
            assertTrue(eventually { t.sent.size == 1 })
            t.autoAck = true // before the reconnect: the flush that follows Connected can outrun a later change
            t.push(TransportEvent.Disconnected(null)) // the (empty) server transcript shows it never arrived
            session.awaitConnected()
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, a) })
            assertTrue(eventually(3.seconds) { session.transcript().first().deliveryState == DeliveryState.SENT })
        }

    @Test
    fun `an ack lost on a socket that stays up is resent after the ack timeout, not waited on for ever`() =
        outboxTest { fx ->
            val t = StubTransport()
            val session = fx.outboxSession(t, ackTimeout = 150.milliseconds)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("ack me")
            assertTrue(eventually { t.sent.size == 1 })
            t.autoAck = true
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, a) })
            assertTrue(eventually(3.seconds) { session.transcript().first().deliveryState == DeliveryState.SENT })
            delay(250.milliseconds)
            assertEquals(2, t.sent.size) // acknowledged: the watchdog stops
        }

    @Test
    fun `a flush that keeps failing gives up its burst and the next send starts a fresh one`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true; failSends = 100 }
            val session = fx.outboxSession(t)
            val c = EventCollector(session, this)
            session.startChat(chat)
            assertTrue(eventually { ContactEvent.StateChanged(ContactState.Connected) in c.events }) // the collector is subscribed
            val a = session.send("stubborn")
            // maxAttempts = 3: the first try and three retries, then the burst gives up.
            assertTrue(eventually(3.seconds) { c.events.count { it is ContactEvent.Failed } == 4 })
            delay(150.milliseconds)
            assertEquals(4, c.events.count { it is ContactEvent.Failed })
            assertEquals(DeliveryState.SENDING, session.transcript().single().deliveryState)
            t.failSends = 0
            val b = session.send("next")
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, b) })
            c.stop()
        }

    @Test
    fun `resend queues a fresh row for a failed message and refuses one that did not fail`() =
        outboxTest { fx ->
            val t = StubTransport()
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            val a = session.send("please retry me")
            session.end()
            val failed = session.transcript(session.contactId.orEmpty()).first()
            assertEquals(DeliveryState.FAILED, failed.deliveryState)
            val t2 = StubTransport().apply { autoAck = true }
            val s2 = fx.outboxSession(t2)
            s2.startChat(ChatContactRequest("b"))
            s2.awaitConnected()
            val retried = s2.resend(failed)
            assertTrue(retried != a)
            assertTrue(eventually { s2.transcript().first { it.clientMessageId == retried }.deliveryState == DeliveryState.SENT })
            val live = s2.transcript().first { it.clientMessageId == retried }
            assertFailsWith<TouchpointException.InvalidConfiguration> { s2.resend(live) }
            assertTrue(eventually { synchronized(t2.sent) { t2.sent.map { it.body } } == listOf("please retry me") })
        }

    @Test
    fun `an answer to a choice goes out in its template, stores nothing but the template beside its text, and is retried as an answer`() =
        outboxTest { fx ->
            val t = StubTransport()
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            session.sendChoice("  Check booking status ")
            session.sendChoice("Dispute it", choiceId = "opt_dispute")
            session.end()
            val (failed, opaque) = session.transcript(session.contactId.orEmpty()).take(2)
            // The response would repeat the encrypted words in the clear; only the template is
            // kept — and an opaque id where the choice has one.
            assertEquals(InteractiveContent("TouchpointModality", rawJson = ""), failed.interactive)
            assertEquals("Check booking status", failed.body) // normalised once, as web sends it
            assertEquals(InteractiveContent("TouchpointModality", rawJson = """{"choiceId":"opt_dispute"}"""), opaque.interactive)

            val t2 = StubTransport().apply { autoAck = true }
            val s2 = fx.outboxSession(t2)
            s2.startChat(ChatContactRequest("b"))
            s2.awaitConnected()
            val retried = s2.resend(failed)
            val retriedOpaque = s2.resend(opaque)
            val plain = s2.send("and a question")
            assertTrue(eventually { sentIds(t2) == listOf(retried, retriedOpaque, plain) })
            val (answer, withId, text) = synchronized(t2.sent) { t2.sent.toList() }
            assertEquals(Triple("Check booking status", "TouchpointModality", null), Triple(answer.body, answer.interactiveTemplate, answer.choiceId))
            assertEquals("Dispute it" to "opt_dispute", withId.body to withId.choiceId)
            assertEquals(null, text.interactiveTemplate)
            assertEquals(InteractiveContent("TouchpointModality", rawJson = ""), s2.transcript().first { it.clientMessageId == retried }.interactive)
        }

    @Test
    fun `a slot filled from a modality goes out in its own template with the slot's id, its value stored as a body is`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.sendSlot("Charge", "harbour")
            assertTrue(eventually { sentIds(t) == listOf(id) })
            val sent = synchronized(t.sent) { t.sent.single() }
            assertEquals(Triple("harbour", InteractiveContent.SLOT_TEMPLATE, "Charge"), Triple(sent.body, sent.interactiveTemplate, sent.choiceId))
            val stored = session.transcript().single { it.clientMessageId == id }
            assertEquals(InteractiveContent(InteractiveContent.SLOT_TEMPLATE, rawJson = """{"slotId":"Charge"}"""), stored.interactive)
            assertEquals("harbour", stored.body)
        }

    @Test
    fun `a view's answer goes out in its own template with its action and name, what was entered stored as a body is`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.submitView("Submit", """{"reason":"a charge"}""", "Form")
            assertTrue(eventually { sentIds(t) == listOf(id) })
            val sent = synchronized(t.sent) { t.sent.single() }
            assertEquals(InteractiveContent.VIEW_RESPONSE_TEMPLATE, sent.interactiveTemplate)
            assertEquals("""{"action":"Submit","viewName":"Form"}""", sent.choiceId)
            assertEquals("""{"reason":"a charge"}""", sent.body)
        }

    @Test
    fun `a failed slot or view answer is resent as one, its template and ids kept`() =
        outboxTest { fx ->
            val t = StubTransport()
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            session.sendSlot("Charge", "harbour")
            session.submitView("Submit", "{}", "Form")
            session.end()
            val (slot, view) = session.transcript(session.contactId.orEmpty()).take(2)
            val t2 = StubTransport().apply { autoAck = true }
            val s2 = fx.outboxSession(t2)
            s2.startChat(ChatContactRequest("b"))
            s2.awaitConnected()
            s2.resend(slot)
            s2.resend(view)
            assertTrue(eventually { synchronized(t2.sent) { t2.sent.size } == 2 })
            val (a, b) = synchronized(t2.sent) { t2.sent.toList() }
            assertEquals(InteractiveContent.SLOT_TEMPLATE to "Charge", a.interactiveTemplate to a.choiceId)
            assertEquals(InteractiveContent.VIEW_RESPONSE_TEMPLATE to """{"action":"Submit","viewName":"Form"}""", b.interactiveTemplate to b.choiceId)
        }

    @Test
    fun `an answer's words are normalised so that normalising again changes nothing - the echo must match to be adopted`() {
        for (raw in listOf("a".repeat(199) + " " + "b".repeat(50), "  Yes  ", "x".repeat(300), "a ".repeat(150), "", "   ")) {
            val once = InteractiveContent.choiceText(raw)
            assertEquals(once, InteractiveContent.choiceText(once), raw)
            assertTrue(once.length <= InteractiveContent.CHOICE_MAX_LENGTH)
        }
    }

    @Test
    fun `a row whose body cannot be read is failed visibly instead of blocking the queue`() =
        outboxTest { fx ->
            val t = StubTransport().apply { autoAck = true }
            val store = fx.store()
            val session = fx.session(mapOf(Modality.CHAT to t), policy, store = store)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            session.awaitReconnecting()
            val broken = session.send("will be unreadable")
            val fine = session.send("fine")
            store.corruptCiphertextForTesting(broken)
            t.autoConnectEvent = true
            t.push(TransportEvent.Connected)
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(fine) })
            assertTrue(eventually { c.events.any { it is ContactEvent.DeliveryStateChanged && it.update.clientMessageId == broken && it.update.state == DeliveryState.FAILED } })
            c.stop()
        }

    @Test
    fun `a send lost when the transport silently re-established is resent after reconciliation`() =
        outboxTest { fx ->
            val t = StubTransport() // the first send gets no ack before the silent drop
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("lost in the background")
            assertTrue(eventually { t.sent.size == 1 })
            t.autoAck = true
            t.push(TransportEvent.Reestablished) // the (empty) history shows the server never got it
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, a) })
            assertTrue(eventually(3.seconds) { session.transcript().single().deliveryState == DeliveryState.SENT })
        }

    @Test
    fun `a flush stopped by a disconnect sends nothing more, and the reconnect sends the rest once, in order`() =
        outboxTest { fx ->
            val t = StubTransport().apply { sendDelay = 150.milliseconds }
            val session = fx.outboxSession(t)
            session.startChat(chat)
            session.awaitConnected()
            val a = session.send("first, in flight")
            val b = session.send("second, queued behind it")
            delay(30.milliseconds)
            // The server got the first; the second it never saw.
            t.transcriptPage =
                TranscriptPage(
                    listOf(StoredMessage(UUID.randomUUID(), "srv-a", "first, in flight", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 9, deliveryState = DeliveryState.SENT)),
                    emptyList(),
                    null,
                )
            t.autoAck = true
            t.push(TransportEvent.Disconnected(null))
            session.awaitConnected()
            assertTrue(eventually(3.seconds) { sentIds(t) == listOf(a, b) })
            delay(400.milliseconds)
            assertEquals(listOf(a, b), sentIds(t)) // the stale flush did not go on to send b as well
        }
}
