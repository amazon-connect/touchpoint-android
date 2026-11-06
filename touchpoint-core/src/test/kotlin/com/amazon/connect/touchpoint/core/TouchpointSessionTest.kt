package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The coordinator against a stub transport, real SQLite and an in-memory secure store. */
class TouchpointSessionTest {
    private val chat = ChatContactRequest(displayName = "a")

    private fun sessionTest(body: suspend CoroutineScope.(SessionFixture) -> Unit) =
        runBlocking {
            val fixture = SessionFixture()
            try {
                withTimeout(30.seconds) { body(fixture) }
            } finally {
                fixture.close()
            }
        }

    private suspend fun TouchpointSession.awaitConnected() = assertTrue(eventually { state.value == ContactState.Connected }, "never connected: ${state.value}")

    private fun List<ContactEvent>.received(): List<Message> = filterIsInstance<ContactEvent.MessageReceived>().map { it.message }

    @Test
    fun `happy path - idle, connecting, connected, with a contact id`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            assertEquals(ContactState.Idle, session.state.value)
            session.startChat(chat)
            session.awaitConnected()
            assertNotNull(session.contactId)
            assertTrue(eventually { c.states().take(2) == listOf(ContactState.Connecting, ContactState.Connected) })
            c.stop()
        }

    @Test
    fun `the other side typing and an assistant's interim line reach the host, live only - never the customer's own, never replayed`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.Typing(ParticipantRole.CUSTOMER))
            t.push(TransportEvent.Typing(ParticipantRole.AGENT))
            t.push(TransportEvent.InterimMessage("Looking that up…"))
            assertTrue(eventually { c.events.any { it is ContactEvent.InterimMessage } })
            assertEquals(listOf<ContactEvent>(ContactEvent.Typing(ParticipantRole.AGENT), ContactEvent.InterimMessage("Looking that up…")), c.events.filter { it is ContactEvent.Typing || it is ContactEvent.InterimMessage })
            assertEquals("InterimMessage(text=<redacted>)", ContactEvent.InterimMessage("secret").toString())
            // A collector that comes later is told the state, not what someone was doing a moment ago.
            val late = EventCollector(session, this)
            assertTrue(eventually { late.events.any { it is ContactEvent.StateChanged } })
            delay(100.milliseconds) // any (wrongly) replayed event has had its chance to land
            assertTrue(late.events.none { it is ContactEvent.Typing || it is ContactEvent.InterimMessage })
            c.stop()
            late.stop()
        }

    @Test
    fun `a refusal made for the customer's safety reaches the host's events as Failed, and the contact goes on (I6)`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.SecurityRejection(TouchpointException.InvalidConfiguration("sign-in redirect did not match")))
            @OptIn(InternalTouchpointApi::class)
            session.reportSecurityRejection(TouchpointException.InvalidConfiguration("guide renderer blocked a navigation"))
            assertTrue(eventually { c.events.count { it is ContactEvent.Failed } == 2 })
            assertEquals(ContactState.Connected, session.state.value)
            c.stop()
        }

    @Test
    fun `the customer's typing goes at most once every 2_5 seconds, only while connected, and a failure is not the host's`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.sendTyping() // no contact: nothing
            assertEquals(0, t.typingSent)
            session.startChat(chat)
            session.awaitConnected()
            repeat(5) { session.sendTyping() }
            assertEquals(1, t.typingSent)
            t.failTyping = true
            delay(2_600.milliseconds)
            session.sendTyping() // refused by the transport: swallowed
            assertEquals(2, t.typingSent)
            assertEquals(ContactState.Connected, session.state.value)
            // Not while reconnecting, nor once it has ended.
            t.failTyping = false
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { session.state.value is ContactState.Reconnecting })
            delay(2_600.milliseconds)
            session.sendTyping()
            session.end()
            session.sendTyping()
            assertEquals(2, t.typingSent)
        }

    @Test
    fun `the server's transcript is asked of the live connection, capped at web's 100, and a failure is the taxonomy's`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.serverTranscript() }
            session.startChat(chat)
            session.awaitConnected()
            t.entries = listOf(TranscriptEntry("2026-10-08T14:00:00.000Z", "Sam", ParticipantRole.AGENT, "Hello"))
            assertEquals(t.entries, session.serverTranscript())
            session.serverTranscript(limit = 5_000)
            assertEquals(listOf(100, 100), t.entryLimits.toList())
            t.failEntries = true
            val e = assertFailsWith<TouchpointException.TransportFailure> { session.serverTranscript() }
            assertFalse("SECRET" in e.toString())
        }

    @Test
    fun `start while a contact is active throws`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.startChat(ChatContactRequest("b")) }
            assertEquals(1, t.connectCalls)
        }

    @Test
    fun `two concurrent starts cannot both pass the already-active check`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val results = List(2) { async(Dispatchers.Default) { runCatching { session.startChat(chat) } } }.map { it.await() }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is TouchpointException.InvalidConfiguration)
            assertEquals(1, t.connectCalls)
        }

    @Test
    fun `inbound messages are persisted and surfaced`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.MessageReceived(inbound("hello", "s1", 1)))
            assertTrue(eventually { c.events.received().any { it.body == "hello" } })
            assertEquals(listOf("hello"), session.transcript().map { it.body })
            c.stop()
        }

    @Test
    fun `each new message from the other side is reported to the host, once, and never the customer's own`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val heard = java.util.Collections.synchronizedList(mutableListOf<IncomingMessages>())
            session.onIncomingMessages = { heard += it }
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.MessageReceived(inbound("hello", "s1", 1)))
            t.push(TransportEvent.MessageReceived(inbound("hello", "s1", 1))) // the same frame again: not news
            t.push(TransportEvent.MessageReceived(StoredMessage(java.util.UUID.randomUUID(), "s2", "mine", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 2, deliveryState = DeliveryState.SENT)))
            assertTrue(eventually { heard.isNotEmpty() })
            delay(100.milliseconds)
            assertEquals(1, heard.size)
            assertEquals(IncomingMessages.Origin.LIVE, heard.single().origin)
            assertEquals(listOf("hello"), heard.single().messages.map { it.body })
            assertFalse("hello" in heard.single().toString()) // no conversation content in toString
        }

    @Test
    fun `a replayed frame with a known server id is deduplicated - no second row, no second event`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val frame = inbound("Welcome", "sys-1", 1, ParticipantRole.SYSTEM)
            t.push(TransportEvent.MessageReceived(frame))
            t.push(TransportEvent.MessageReceived(frame))
            assertTrue(eventually { session.transcript().size == 1 })
            delay(50.milliseconds)
            assertEquals(1, session.transcript().size)
            assertEquals(1, c.events.received().size)
            c.stop()
        }

    @Test
    fun `send persists an optimistic echo as SENDING, sends it, then marks it SENT on acknowledgement`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            val id = session.send("hi")
            assertEquals(DeliveryState.SENDING, session.transcript().first().deliveryState)
            assertTrue(eventually { t.sent.map { it.clientMessageId } == listOf(id) })
            t.push(TransportEvent.MessageDelivered(id, "srv-1", 7))
            assertTrue(eventually { session.transcript().first().deliveryState == DeliveryState.SENT })
            assertEquals(7L, session.transcript().first().sequence)
        }

    @Test
    fun `send surfaces the optimistic echo as an event, so a UI bound only to events shows the bubble at once`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.send("hello there")
            assertTrue(eventually { c.events.received().any { it.clientMessageId == id && it.body == "hello there" && it.direction == Direction.OUTBOUND } })
            c.stop()
        }

    @Test
    fun `a failed send stays SENDING and surfaces Failed`() =
        sessionTest { fx ->
            val t = StubTransport().apply { failSends = 1 }
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            session.send("lost")
            assertEquals(DeliveryState.SENDING, session.transcript().single().deliveryState)
            assertTrue(eventually { c.events.any { it is ContactEvent.Failed && it.error is TouchpointException.TransportFailure } })
            c.stop()
        }

    @Test
    fun `bounded reconnection - reconnecting 1 and 2, then ended CONNECTION_LOST with a failed event`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.failConnects = 10
            t.push(TransportEvent.Disconnected("socket closed"))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            val states = c.states()
            assertTrue(ContactState.Reconnecting(1) in states)
            assertTrue(ContactState.Reconnecting(2) in states)
            assertFalse(ContactState.Reconnecting(3) in states)
            assertTrue(eventually { c.events.any { it is ContactEvent.Failed && it.error is TouchpointException.TransportFailure } })
            assertTrue(eventually { ContactEvent.ContactEnded(EndReason.CONNECTION_LOST) in c.events })
            assertEquals(3, t.connectCalls) // initial + 2 attempts
            c.stop()
        }

    @Test
    fun `a new contact after CONNECTION_LOST starts with a fresh reconnection budget`() =
        sessionTest { fx ->
            val t = StubTransport()
            // Tiny budget, so a stale budget window from the first contact would trip immediately.
            val policy = ReconnectionPolicy(maxAttempts = 2, totalBudget = 150.milliseconds, initialDelay = 10.milliseconds, maxDelay = 10.milliseconds, jitterFactor = 0.0)
            val session = fx.session(mapOf(Modality.CHAT to t), policy)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.failConnects = 10
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            delay(200.milliseconds) // let the old budget window pass
            t.failConnects = 0
            session.startChat(ChatContactRequest("b"))
            session.awaitConnected()
            val before = c.states().size
            t.failConnects = 1 // the first reconnect attempt fails, the second succeeds
            t.push(TransportEvent.Disconnected(null))
            assertTrue(
                eventually(3.seconds) {
                    session.state.value == ContactState.Connected && ContactState.Reconnecting(1) in c.states().drop(before)
                },
            )
            c.stop()
        }

    @Test
    fun `a reconnection that succeeds returns to connected`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.failConnects = 1
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Connected && ContactState.Reconnecting(2) in c.states() })
            c.stop()
        }

    @Test
    fun `a successful reconnect resets the attempt counter - the next drop starts again at 1`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.failConnects = 1
            t.push(TransportEvent.Disconnected(null))
            // The collector runs on its own thread: wait until it, not only the session, has the reconnect's Connected.
            assertTrue(eventually(3.seconds) { ContactState.Reconnecting(2) in c.states() && c.states().last() == ContactState.Connected })
            val before = c.states().size
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { c.states().drop(before).let { it.any { s -> s is ContactState.Reconnecting } && it.last() == ContactState.Connected } })
            assertEquals(ContactState.Reconnecting(1), c.states().drop(before).first { it is ContactState.Reconnecting })
            c.stop()
        }

    @Test
    fun `end disconnects the transport, tears down Live Sync, ends CUSTOMER_ENDED, and a new contact can start`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            session.end()
            assertEquals(ContactState.Ended(EndReason.CUSTOMER_ENDED), session.state.value)
            assertEquals(1, t.disconnectCalls)
            assertTrue("teardown" in ls.snapshot())
            assertTrue(eventually { ContactEvent.ContactEnded(EndReason.CUSTOMER_ENDED) in c.events })
            session.startChat(chat)
            session.awaitConnected()
            c.stop()
        }

    @Test
    fun `the participant set survives an out-of-order leave and join, and gates Live Sync dispatch`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.ParticipantLeft("agent-1")) // stale leave first
            t.push(TransportEvent.ParticipantJoined(Participant("agent-1", ParticipantRole.AGENT, "Sam")))
            assertTrue(eventually { "suspend:HUMAN_AGENT_JOINED" in ls.snapshot() })
            assertTrue(eventually { ContactEvent.LiveSyncDispatchChanged(DispatchState.SUSPENDED, DispatchChangeReason.HUMAN_AGENT_JOINED) in c.events })
            assertTrue(session.participants().humanAgentPresent)
            t.push(TransportEvent.ParticipantLeft("agent-1"))
            assertTrue(eventually { "resume" in ls.snapshot() })
            assertTrue(eventually { ContactEvent.LiveSyncDispatchChanged(DispatchState.ENABLED, DispatchChangeReason.HUMAN_AGENT_LEFT) in c.events })
            c.stop()
        }

    @Test
    fun `web's notices - an agent joining and leaving, a live connection dropping - after the newest message, replayed, kept past the end`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            fun notices(events: List<ContactEvent>) = events.filterIsInstance<ContactEvent.Notice>().map { it.notice }
            session.startChat(chat)
            session.awaitConnected()
            val hello = inbound("hello", "s1", 1, ParticipantRole.ASSISTANT)
            t.push(TransportEvent.MessageReceived(hello))
            assertTrue(eventually { c.events.received().isNotEmpty() })
            val sam = Participant("a1", ParticipantRole.AGENT, "Sam")
            t.push(TransportEvent.ParticipantJoined(sam))
            t.push(TransportEvent.ParticipantJoined(sam)) // a repeated frame is not a second agent
            t.push(TransportEvent.ParticipantJoined(Participant("s1", ParticipantRole.SUPERVISOR, "Lee"))) // nor is a supervisor one
            t.push(TransportEvent.ParticipantLeft("s1"))
            t.push(TransportEvent.ParticipantLeft("a1"))
            assertTrue(eventually { notices(c.events).size == 2 })
            assertEquals(listOf(TranscriptNotice.Kind.AGENT_JOINED, TranscriptNotice.Kind.AGENT_LEFT), notices(c.events).map { it.kind })
            assertTrue(notices(c.events).all { it.afterMessageId == hello.clientMessageId })
            // A drop from a live connection, once: a drop while already reconnecting is the same outage.
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { notices(c.events).size == 3 })
            assertEquals(TranscriptNotice.Kind.CONNECTION_LOST, notices(c.events).last().kind)
            assertTrue(eventually { session.state.value is ContactState.Reconnecting })
            t.push(TransportEvent.Disconnected(null))
            delay(100.milliseconds)
            assertEquals(3, notices(c.events).size)
            // A surface that opens after the end still sees them, as an ended chat on web does.
            session.end()
            val late = EventCollector(session, this)
            assertTrue(eventually { late.events.any { it is ContactEvent.ContactEnded } })
            assertEquals(notices(c.events), notices(late.events))
            c.stop()
            late.stop()
        }

    @Test
    fun `the sign-in card - after the newest message, kept in place, replayed to a late surface, and gone with the contact`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            fun cards(events: List<ContactEvent>) = events.filterIsInstance<ContactEvent.AuthenticationChanged>().map { it.card }
            // Nothing to sign in to before a contact.
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.beginAuthentication() }
            session.startChat(chat)
            session.awaitConnected()
            val ask = inbound("Let's get you signed in.", "s1", 1, ParticipantRole.ASSISTANT)
            t.push(TransportEvent.MessageReceived(ask))
            assertTrue(eventually { c.events.received().isNotEmpty() })
            val id = UUID.randomUUID()
            t.push(TransportEvent.AuthenticationChanged(id, AuthenticationStatus.PROMPT))
            assertTrue(eventually { cards(c.events).size == 1 })
            assertEquals(AuthenticationCard(id, AuthenticationStatus.PROMPT, ask.clientMessageId), session.authenticationCard)
            // A later message does not move it.
            t.push(TransportEvent.MessageReceived(inbound("Anything else?", "s2", 2, ParticipantRole.ASSISTANT)))
            t.push(TransportEvent.AuthenticationChanged(id, AuthenticationStatus.IN_PROGRESS))
            assertTrue(eventually { cards(c.events).size == 2 })
            assertEquals(ask.clientMessageId, cards(c.events).last().afterMessageId)
            // The calls reach the transport.
            assertEquals("https://idp.example/authorize?state=s", session.beginAuthentication().url)
            session.completeAuthentication("app://auth?code=c&state=s")
            session.cancelAuthentication(unlessRedirected = true)
            assertEquals(listOf("begin", "complete:app://auth?code=c&state=s", "cancel:true"), t.signInCalls.toList())
            // A surface that opens now sees where it stands.
            val late = EventCollector(session, this)
            assertTrue(eventually { cards(late.events).lastOrNull()?.status == AuthenticationStatus.IN_PROGRESS })
            late.stop()
            // No lock outlives the contact.
            session.end()
            assertNull(session.authenticationCard)
            val after = EventCollector(session, this)
            assertTrue(eventually { after.events.any { it is ContactEvent.ContactEnded } })
            assertTrue(cards(after.events).isEmpty())
            session.cancelAuthentication() // nothing to cancel: no throw
            c.stop()
            after.stop()
        }

    @Test
    fun `a registered LiveSyncControl stays registered across a contact`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.register(RecordingLiveSync()) // the caller keeps no reference
            assertTrue(session.isLiveSyncRegistered)
            session.startChat(chat)
            session.awaitConnected()
            assertTrue(session.isLiveSyncRegistered)
        }

    @Test
    fun `without a registered LiveSyncControl the gating calls are no-ops but events still fire`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.ParticipantJoined(Participant("a1", ParticipantRole.AGENT, "A")))
            assertTrue(eventually { c.events.any { it is ContactEvent.LiveSyncDispatchChanged && it.state == DispatchState.SUSPENDED } })
            c.stop()
        }

    @Test
    fun `a LiveSyncControl that throws neither crashes nor stops the session`() =
        sessionTest { fx ->
            val t = StubTransport()
            val throwing =
                object : LiveSyncControl {
                    override suspend fun suspendDispatch(reason: DispatchChangeReason): Unit = throw IllegalStateException("host bug")

                    override suspend fun resumeDispatch(): Unit = throw IllegalStateException("host bug")

                    override suspend fun teardown(): Unit = throw CancellationException("host cancelled its own work")
                }
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = throwing)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.ParticipantJoined(Participant("a1", ParticipantRole.AGENT, "A")))
            assertTrue(eventually { c.events.any { it is ContactEvent.LiveSyncDispatchChanged } })
            t.push(TransportEvent.MessageReceived(inbound("still flowing", "s1", 1)))
            assertTrue(eventually { c.events.received().any { it.body == "still flowing" } })
            session.end()
            assertEquals(ContactState.Ended(EndReason.CUSTOMER_ENDED), session.state.value)
            c.stop()
        }

    @Test
    fun `a transport-reported end fails every SENDING row and surfaces each`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            session.send("never acked")
            t.push(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
            assertTrue(eventually { session.state.value == ContactState.Ended(EndReason.AGENT_ENDED) })
            assertEquals(DeliveryState.FAILED, session.transcript().first().deliveryState)
            assertTrue(eventually { c.events.any { it is ContactEvent.DeliveryStateChanged && it.update.state == DeliveryState.FAILED } })
            c.stop()
        }

    @Test
    fun `resume with a human agent in the persisted set suspends dispatch before anything connects`() =
        sessionTest { fx ->
            val t = StubTransport().apply { autoConnectEvent = false } // hold the connection so ordering is observable
            val ls = RecordingLiveSync()
            val store = fx.store()
            val broker = fx.broker()
            val creds = broker.startChatContact(ChatContactRequest("seed")) // persists the token
            store.upsertConversation(creds.contactId, Modality.CHAT)
            store.setParticipants(creds.contactId, ParticipantSet().join(Participant("a1", ParticipantRole.AGENT, "A")))
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls, store = store, broker = broker)
            val c = EventCollector(session, this)
            session.resume(creds.contactId)
            assertEquals("suspend:RESUMED_UNRECONCILED", ls.snapshot().first())
            assertTrue(eventually { ContactEvent.LiveSyncDispatchChanged(DispatchState.SUSPENDED, DispatchChangeReason.RESUMED_UNRECONCILED) in c.events })
            assertEquals(creds.contactId, session.contactId)
            assertEquals(ContactState.Connecting, session.state.value)
            assertEquals(1, t.connectCalls)
            assertFalse("resume" in ls.snapshot())
            c.stop()
        }

    @Test
    fun `resume of an unknown contact throws ContactNotFound`() =
        sessionTest { fx ->
            val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
            assertFailsWith<TouchpointException.ContactNotFound> { session.resume("nope") }
            assertEquals(ContactState.Idle, session.state.value)
        }

    @Test
    fun `starting a modality with no registered transport throws InvalidConfiguration`() =
        sessionTest { fx ->
            val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.startVoice(VoiceContactRequest("a")) }
            assertEquals(ContactState.Idle, session.state.value)
            session.startChat(chat) // the refusal released the start claim
            session.awaitConnected()
        }

    @Test
    fun `a connect failure is thrown as a TouchpointException and the stream sees the end`() =
        sessionTest { fx ->
            val t = StubTransport().apply { failConnects = 1; connectFailure = { java.io.IOException("network down, token=secret") } }
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            val error = assertFailsWith<TouchpointException.TransportFailure> { session.startChat(chat) }
            assertEquals("IOException", error.detail) // the foreign message is not carried
            assertTrue(eventually { ContactEvent.ContactEnded(EndReason.CONNECTION_LOST) in c.events })
            c.stop()
        }

    @Test
    fun `a transport's own CancellationException while the caller is active is a failure, not a cancellation`() =
        sessionTest { fx ->
            val t = StubTransport().apply { failConnects = 1; connectFailure = { CancellationException("transport-internal") } }
            val session = fx.session(mapOf(Modality.CHAT to t))
            assertFailsWith<TouchpointException.TransportFailure> { session.startChat(chat) }
            assertEquals(ContactState.Ended(EndReason.CONNECTION_LOST), session.state.value)
        }

    @Test
    fun `a caller cancelled mid-connect is cancelled, and the contact is ended with its credentials kept for resume`() =
        sessionTest { fx ->
            val gate = CompletableDeferred<Unit>()
            val t = StubTransport().apply { connectGate = gate }
            val broker = fx.broker()
            val session = fx.session(mapOf(Modality.CHAT to t), broker = broker)
            val caller = launch(Dispatchers.Default) { session.startChat(chat) }
            assertTrue(eventually { t.connectCalls == 1 })
            caller.cancel()
            caller.join()
            assertTrue(caller.isCancelled)
            assertTrue(eventually { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            assertNotNull(broker.storedChatCredentials(session.contactId ?: ""))
            gate.complete(Unit)
        }

    @Test
    fun `reconnection delay is exponential, capped, and jitter is positive-only`() {
        val p = ReconnectionPolicy(initialDelay = 100.milliseconds, maxDelay = 1.seconds, jitterFactor = 0.5)
        assertEquals(100.milliseconds, p.delay(1, random = 0.0))
        assertEquals(200.milliseconds, p.delay(2, random = 0.0))
        assertEquals(1.seconds, p.delay(10, random = 0.0))
        assertEquals(150.milliseconds, p.delay(1, random = 1.0))
        assertTrue(p.delay(1, random = 0.0) >= p.initialDelay)
        assertEquals(1.seconds, p.delay(Int.MAX_VALUE, random = 0.0)) // no shift overflow
    }

    // Resume, receipts, read marks, retain policy

    @Test
    fun `resume after a restart reconnects with the persisted token, reconciles, then re-enables dispatch when no agent is present`() =
        sessionTest { fx ->
            val ls = RecordingLiveSync()
            val contactId: String
            run {
                // process 1
                val t = StubTransport()
                val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
                session.startChat(chat)
                session.awaitConnected()
                t.push(TransportEvent.MessageReceived(inbound("welcome", "s1", 100, ParticipantRole.SYSTEM)))
                assertTrue(eventually { session.transcript().size == 1 })
                contactId = session.contactId ?: ""
                session.close() // no end(): the process dies here
            }
            // process 2
            val t = StubTransport()
            t.transcriptPage = TranscriptPage(listOf(inbound("welcome", "s1", 100, ParticipantRole.SYSTEM), inbound("hello again", "s2", 101)), emptyList(), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.resume(contactId)
            session.awaitConnected()
            assertEquals(1, t.connectCalls)
            assertTrue(eventually { ContactEvent.TranscriptReconciled in c.events })
            assertEquals(listOf("welcome", "hello again"), session.transcript().map { it.body }) // s1 deduplicated, s2 added
            val calls = ls.snapshot()
            assertEquals("suspend:RESUMED_UNRECONCILED", calls.first())
            assertEquals("resume", calls.last())
            assertEquals(1, t.fetchCalls.size)
            c.stop()
        }

    private suspend fun seedContact(fx: SessionFixture): String {
        val t = StubTransport()
        val session = fx.session(mapOf(Modality.CHAT to t))
        session.startChat(chat)
        session.awaitConnected()
        return (session.contactId ?: "").also { session.close() }
    }

    @Test
    fun `resume keeps dispatch suspended when the reconciled transcript shows a human on the contact`() =
        sessionTest { fx ->
            val contactId = seedContact(fx)
            val ls = RecordingLiveSync()
            val t = StubTransport()
            t.transcriptPage = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.SUPERVISOR, "Sup"))), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            session.resume(contactId)
            session.awaitConnected()
            assertTrue(eventually { t.fetchCalls.size == 1 })
            delay(100.milliseconds)
            assertFalse("resume" in ls.snapshot())
            assertTrue(session.participants().humanAgentPresent)
        }

    @Test
    fun `resume of a conversation whose credentials are gone throws ContactNotFound and the transcript stays readable`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            val id = session.contactId ?: ""
            t.push(TransportEvent.MessageReceived(inbound("bye", "s1", 1)))
            assertTrue(eventually { session.transcript().size == 1 })
            session.end()
            assertFailsWith<TouchpointException.ContactNotFound> { session.resume(id) }
            assertEquals(listOf("bye"), session.transcript(id).map { it.body })
        }

    @Test
    fun `receipts move our messages through SENT, DELIVERED and READ with one event each`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.send("hi")
            t.push(TransportEvent.MessageDelivered(id, "s1", 7))
            t.push(TransportEvent.Receipt("s1", ReceiptKind.DELIVERED))
            t.push(TransportEvent.Receipt("s1", ReceiptKind.READ))
            assertTrue(eventually { session.transcript().first().deliveryState == DeliveryState.READ })
            val changes = c.events.filterIsInstance<ContactEvent.DeliveryStateChanged>().filter { it.update.clientMessageId == id }.map { it.update.state }
            assertEquals(listOf(DeliveryState.SENT, DeliveryState.DELIVERED, DeliveryState.READ), changes)
            c.stop()
        }

    @Test
    fun `markMessagesRead sends one read receipt for the newest inbound message and marks the rows read`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.MessageReceived(inbound("one", "s1", 1)))
            t.push(TransportEvent.MessageReceived(inbound("two", "s2", 2)))
            assertTrue(eventually { session.transcript().size == 2 })
            val two = session.transcript().last()
            session.markMessagesRead(two.clientMessageId)
            assertEquals(listOf("s2"), t.readMarks.toList())
            assertEquals(listOf(DeliveryState.READ, DeliveryState.READ), session.transcript().map { it.deliveryState })
            session.markMessagesRead(two.clientMessageId) // idempotent: nothing new to mark, nothing sent
            assertEquals(listOf("s2"), t.readMarks.toList())
        }

    @Test
    fun `chat retains the conversation and its unsent outbox across a lost connection`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            t.autoConnectEvent = false
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { session.state.value is ContactState.Reconnecting })
            val queued = session.send("while offline")
            assertTrue(t.sent.isEmpty()) // not sent while reconnecting
            t.autoConnectEvent = true
            t.push(TransportEvent.Connected)
            session.awaitConnected()
            val rows = session.transcript()
            assertEquals(listOf(queued), rows.map { it.clientMessageId })
            assertEquals(DeliveryState.SENDING, rows.first().deliveryState) // retained; the outbox flushes it
        }

    @Test
    fun `a lost connection keeps the credentials so the contact stays resumable`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            val id = session.contactId ?: ""
            t.failConnects = 5
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            assertNotNull(fx.broker().storedChatCredentials(id))
        }

    @Test
    fun `end forgets the credentials`() =
        sessionTest { fx ->
            val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
            session.startChat(chat)
            session.awaitConnected()
            val id = session.contactId ?: ""
            session.end()
            assertNull(fx.broker().storedChatCredentials(id))
        }

    @Test
    fun `voice ends on the first disconnect rather than reconnecting`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.VOICE to t))
            session.startVoice(VoiceContactRequest("v"))
            session.awaitConnected()
            t.push(TransportEvent.Disconnected("rtc gone"))
            assertTrue(eventually { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            assertEquals(1, t.connectCalls)
        }

    @Test
    fun `a mid-session reconnect suspends dispatch until the transcript is reconciled, then lifts it`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            t.transcriptPage = TranscriptPage(listOf(inbound("missed while offline", "s5", 5)), emptyList(), null)
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually { ContactEvent.TranscriptReconciled in c.events })
            assertEquals(listOf("missed while offline"), session.transcript().map { it.body })
            assertTrue(eventually { ls.snapshot() == listOf("suspend:RESUMED_UNRECONCILED", "resume") })
            c.stop()
        }

    @Test
    fun `deleteConversation and deleteAllConversations also forget the persisted credentials`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            val id = session.contactId ?: ""
            t.failConnects = 5
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            assertNotNull(fx.broker().storedChatCredentials(id))
            session.deleteConversation(id)
            assertNull(fx.broker().storedChatCredentials(id))
            assertTrue(session.transcript(id).isEmpty())

            t.failConnects = 0
            session.startChat(chat)
            session.awaitConnected()
            val second = session.contactId ?: ""
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.deleteAllConversations() }
            t.failConnects = 5
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            session.deleteAllConversations()
            assertNull(fx.broker().storedChatCredentials(second))
        }

    @Test
    fun `reconciliation pages back until the participant history is complete - an older agent join still gates dispatch`() =
        sessionTest { fx ->
            val contactId = seedContact(fx)
            val ls = RecordingLiveSync()
            val t = StubTransport()
            // Newest page: 100 chatty messages, no participant events, more history behind it.
            t.transcriptPage = TranscriptPage((1..100).map { inbound("m$it", "s$it", 1000L + it) }, emptyList(), "older-1")
            // The page before it holds the agent join.
            t.pagesByToken["older-1"] = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "Ann"))), "older-2")
            t.pagesByToken["older-2"] = TranscriptPage(listOf(inbound("welcome", "s0", 1)), emptyList(), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.resume(contactId)
            session.awaitConnected()
            assertTrue(eventually(5.seconds) { ContactEvent.TranscriptReconciled in c.events })
            assertEquals(listOf(null, "older-1", "older-2"), t.fetchCalls.toList())
            assertFalse("resume" in ls.snapshot())
            assertTrue(session.participants().humanAgentPresent)
            assertEquals(101, session.transcript().size)
            // The stream delivered the history oldest-first across pages, not page by page newest-first.
            val streamed = c.events.received().map { it.body }
            assertEquals(101, streamed.size)
            assertEquals("welcome", streamed.first())
            assertEquals("m100", streamed.last())
            c.stop()
        }

    @Test
    fun `participant events are folded in server order across pages - a leave after a join lifts dispatch`() =
        sessionTest { fx ->
            val contactId = seedContact(fx)
            val ls = RecordingLiveSync()
            val t = StubTransport()
            t.transcriptPage = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantLeft("ag")), "older")
            t.pagesByToken["older"] = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "Ann"))), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            session.resume(contactId)
            assertTrue(eventually(3.seconds) { ls.snapshot().lastOrNull() == "resume" })
            assertFalse(session.participants().humanAgentPresent)
        }

    @Test
    fun `a truncated history keeps dispatch suspended and says so`() =
        sessionTest { fx ->
            val contactId = seedContact(fx)
            val ls = RecordingLiveSync()
            val t = StubTransport()
            t.transcriptPage = TranscriptPage(emptyList(), emptyList(), "p1")
            for (i in 1..30) t.pagesByToken["p$i"] = TranscriptPage(emptyList(), emptyList(), "p${i + 1}")
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.resume(contactId)
            assertTrue(eventually(3.seconds) { ContactEvent.TranscriptReconciled in c.events })
            assertEquals(20, t.fetchCalls.size)
            assertTrue(eventually { c.events.any { it is ContactEvent.Failed && (it.error as? TouchpointException.TransportFailure)?.detail?.contains("stays suspended") == true } })
            assertFalse("resume" in ls.snapshot())
            c.stop()
        }

    @Test
    fun `an adopted echo surfaces as DeliveryStateChanged SENT for the bubble the host already shows`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val id = session.send("pay my bill") // the stub never acks
            // The server's copy of that message arrives on the live stream.
            t.push(
                TransportEvent.MessageReceived(
                    StoredMessage(java.util.UUID.randomUUID(), "srv-7", "pay my bill", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 70, deliveryState = DeliveryState.SENT),
                ),
            )
            assertTrue(eventually { c.events.any { it is ContactEvent.DeliveryStateChanged && it.update.clientMessageId == id && it.update.state == DeliveryState.SENT } })
            val rows = session.transcript()
            assertEquals(1, rows.size)
            assertEquals(id, rows[0].id)
            assertEquals(MessageId("srv-7"), rows[0].serverMessageId)
            assertEquals(DeliveryState.SENT, rows[0].deliveryState)
            assertEquals(1, c.events.received().size) // our own echo only
            c.stop()
        }

    @Test
    fun `a transient reconciliation failure is retried on a healthy connection, and dispatch lifts once it succeeds`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            assertTrue(eventually { ContactEvent.StateChanged(ContactState.Connected) in c.events }) // the collector is subscribed
            t.failFetches = 2
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(5.seconds) { t.fetchCalls.size >= 3 })
            assertTrue(eventually(5.seconds) { ls.snapshot().lastOrNull() == "resume" })
            assertTrue(eventually { c.events.count { it is ContactEvent.Failed } == 2 })
            assertEquals(ContactState.Connected, session.state.value)
            c.stop()
        }

    @Test
    fun `end during reconciliation never re-enables dispatch or emits after ContactEnded`() =
        sessionTest { fx ->
            val contactId = seedContact(fx)
            val ls = RecordingLiveSync()
            val t = StubTransport()
            t.fetchDelay = 300.milliseconds // reconcile is mid-flight when end() lands
            t.transcriptPage = TranscriptPage(listOf(inbound("late page", "s1", 1)), emptyList(), null)
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.resume(contactId)
            assertTrue(eventually { t.fetchCalls.size == 1 })
            session.end()
            assertEquals(ContactState.Ended(EndReason.CUSTOMER_ENDED), session.state.value)
            delay(500.milliseconds) // let the delayed fetch complete
            assertFalse("resume" in ls.snapshot())
            val events = c.events
            val endIndex = events.indexOfFirst { it is ContactEvent.ContactEnded }
            assertTrue(endIndex >= 0)
            assertEquals(emptyList(), events.drop(endIndex + 1))
            c.stop()
        }

    // Subscribers and replay

    @Test
    fun `every collector gets events, including one that starts after an earlier one stopped`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            val first = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            val second = EventCollector(session, this)
            t.push(TransportEvent.MessageReceived(inbound("one", "s1", 1)))
            assertTrue(eventually { second.events.received().isNotEmpty() })
            assertTrue(eventually { first.events.received().isNotEmpty() })
            first.stop()
            second.stop()
            delay(50.milliseconds)
            val third = EventCollector(session, this)
            delay(50.milliseconds)
            t.push(TransportEvent.MessageReceived(inbound("two", "s2", 2)))
            assertTrue(eventually { third.events.received().any { it.body == "two" } })
            third.stop()
        }

    @Test
    fun `a collector that starts after the contact started is caught up`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.MessageReceived(inbound("said before you looked", "s1", 1)))
            assertTrue(eventually { session.transcript().size == 1 })
            val late = EventCollector(session, this)
            assertTrue(eventually { ContactState.Connected in late.states() })
            assertTrue(eventually { late.events.received().any { it.body == "said before you looked" } })
            late.stop()
        }

    @Test
    fun `the replay is per contact - a new contact does not re-deliver the previous one's events`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.MessageReceived(inbound("first contact", "s1", 1)))
            assertTrue(eventually { session.transcript().size == 1 })
            session.end()
            session.startChat(ChatContactRequest("b"))
            session.awaitConnected()
            val late = EventCollector(session, this)
            delay(100.milliseconds)
            assertFalse(late.events.received().any { it.body == "first contact" })
            assertTrue(ContactState.Connected in late.states())
            late.stop()
        }

    @Test
    fun `catching a late collector up does not hand it the same state transition twice`() =
        sessionTest { fx ->
            val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
            session.startChat(chat)
            session.awaitConnected()
            val late = EventCollector(session, this)
            assertTrue(eventually { ContactState.Connected in late.states() })
            delay(80.milliseconds)
            assertEquals(1, late.states().count { it == ContactState.Connected })
            late.stop()
        }

    @Test
    fun `a late collector is told dispatch is suspended after the event that said so was evicted`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "Ann")))
            assertTrue(eventually { "suspend:HUMAN_AGENT_JOINED" in ls.snapshot() })
            // Just past the 200-event replay bound.
            for (i in 1..205) t.push(TransportEvent.MessageReceived(inbound("m$i", "s$i", i.toLong())))
            assertTrue(eventually(15.seconds) { session.transcript().size == 205 })
            val late = EventCollector(session, this)
            assertTrue(eventually { late.events.any { it is ContactEvent.LiveSyncDispatchChanged && it.state == DispatchState.SUSPENDED } })
            assertTrue(eventually { late.events.any { it is ContactEvent.ParticipantsChanged && it.participants.humanAgentPresent } })
            late.stop()
        }

    @Test
    fun `a new contact does not inherit the previous contact's suspended dispatch`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            session.startChat(chat)
            session.awaitConnected()
            t.push(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "Ann")))
            assertTrue(eventually { "suspend:HUMAN_AGENT_JOINED" in ls.snapshot() })
            session.end()
            session.startChat(ChatContactRequest("b"))
            session.awaitConnected()
            val late = EventCollector(session, this)
            delay(100.milliseconds)
            assertFalse(late.events.any { it is ContactEvent.LiveSyncDispatchChanged && it.state == DispatchState.SUSPENDED })
            late.stop()
        }

    @Test
    fun `close completes every collection`() =
        sessionTest { fx ->
            val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
            val c = EventCollector(session, this)
            delay(50.milliseconds)
            session.close()
            assertTrue(eventually { c.job.isCompleted })
            assertTrue(EventCollector(session, this).job.let { j -> eventually { j.isCompleted } }) // and any started after
        }

    @Test
    fun `resend queues a new message for a FAILED one and refuses any other`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            session.send("first")
            t.push(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
            assertTrue(eventually { session.state.value is ContactState.Ended })
            val failed = session.transcript().single()
            assertEquals(DeliveryState.FAILED, failed.deliveryState)
            session.startChat(ChatContactRequest("b"))
            session.awaitConnected()
            val retry = session.resend(failed)
            assertTrue(retry != failed.clientMessageId)
            val sending = session.transcript().single()
            assertFailsWith<TouchpointException.InvalidConfiguration> { session.resend(sending) }
        }

    @Test
    fun `earlierTranscript walks back from a message, oldest-first, and says when it is exhausted`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            for (i in 1..5) t.push(TransportEvent.MessageReceived(inbound("m$i", "s$i", i.toLong())))
            assertTrue(eventually { session.transcript().size == 5 })
            val newest = session.transcript().last()
            val page = session.earlierTranscript(newest, limit = 2)
            assertEquals(listOf("m3", "m4"), page.messages.map { it.body })
            assertTrue(page.hasMore)
            val rest = session.earlierTranscript(page.messages.first(), limit = 10)
            assertEquals(listOf("m1", "m2"), rest.messages.map { it.body })
            assertFalse(rest.hasMore)
        }

    @Test
    fun `Message toString omits the body`() {
        val m = Message(inbound("my account number is 1234", "s1", 1))
        assertFalse("1234" in m.toString())
        assertFalse("account" in m.toString())
    }

    @Test
    fun `Message projects interactive and citation blobs, and tolerates bad ones`() {
        val stored =
            inbound("pick one", "s1", 1).copy(
                interactiveJson = InteractiveContent.encode(InteractiveContent("QuickReply", "{\"x\":1}")),
                citationsJson = Citation.encode(listOf(Citation("FAQ", "https://example.test/faq"))),
            )
        val m = Message(stored)
        assertEquals("QuickReply", m.interactive?.templateType)
        assertEquals(listOf("FAQ"), m.citations.map { it.title })
        val bad = Message(stored.copy(interactiveJson = "not json", citationsJson = "{"))
        assertNull(bad.interactive)
        assertTrue(bad.citations.isEmpty())
        assertEquals("pick one", bad.body)
    }

    @Test
    fun `a connection the transport re-established by itself is reconciled, with dispatch off until then`() =
        sessionTest { fx ->
            val t = StubTransport()
            val ls = RecordingLiveSync()
            val session = fx.session(mapOf(Modality.CHAT to t), liveSync = ls)
            val c = EventCollector(session, this)
            session.startChat(chat)
            session.awaitConnected()
            // While the app was in the background, an agent joined.
            t.transcriptPage = TranscriptPage(emptyList(), listOf(TransportEvent.ParticipantJoined(Participant("ag", ParticipantRole.AGENT, "Ann"))), null)
            t.push(TransportEvent.Reestablished)
            assertTrue(eventually { ContactEvent.TranscriptReconciled in c.events })
            assertTrue(session.participants().humanAgentPresent)
            assertEquals(listOf("suspend:RESUMED_UNRECONCILED"), ls.snapshot()) // and never lifted
            assertEquals(1, t.connectCalls) // the transport reconnected; the session made no reconnect of its own
            c.stop()
        }

    @Test
    fun `a lost connection releases the transport, keeping the participant - every other end disconnects it`() =
        sessionTest { fx ->
            val t = StubTransport()
            val session = fx.session(mapOf(Modality.CHAT to t))
            session.startChat(chat)
            session.awaitConnected()
            t.failConnects = 10
            t.push(TransportEvent.Disconnected(null))
            assertTrue(eventually(3.seconds) { session.state.value == ContactState.Ended(EndReason.CONNECTION_LOST) })
            assertEquals(1, t.releaseCalls)
            assertEquals(0, t.disconnectCalls)
            t.failConnects = 0
            session.startChat(ChatContactRequest("b"))
            session.awaitConnected()
            session.end()
            assertEquals(1, t.disconnectCalls)
        }
}
