package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Scriptable stand-in for the upstream session. Records every call, so a test can assert what reached the wire. */
private class FakeChatDriver : ChatSessionDriving {
    @Volatile var installed: ChatDriverHandlers? = null
    val connects: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Volatile var disconnects = 0
    val sentBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val sentInteractive: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val receipts: MutableList<Pair<ReceiptKind, String>> = Collections.synchronizedList(mutableListOf())
    val transcriptPages: MutableList<RawTranscriptPage> = Collections.synchronizedList(mutableListOf())
    val transcriptRequests: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Volatile var failNextSend: Exception? = null

    @Volatile var failConnect: Exception? = null

    @Volatile var receiptDelay: Duration = Duration.ZERO
    private var nextServerId = 0

    override fun setHandlers(handlers: ChatDriverHandlers) {
        installed = handlers
    }

    override suspend fun connect(contactId: String, participantId: String, participantToken: String?) {
        failConnect?.let {
            failConnect = null
            throw it
        }
        connects += participantToken
    }

    override suspend fun disconnect() {
        disconnects++
    }

    @Volatile var releases = 0

    override suspend fun release() {
        releases++
    }

    @Volatile var sendDelay: Duration = Duration.ZERO

    @Volatile var typingSent = 0

    override suspend fun sendTyping() {
        typingSent++
    }

    override suspend fun sendMessage(
        body: String,
        interactiveResponse: Boolean,
    ): SentMessageAck {
        sentInteractive += interactiveResponse
        failNextSend?.let {
            failNextSend = null
            throw it
        }
        if (sendDelay > Duration.ZERO) delay(sendDelay)
        sentBodies += body
        nextServerId++
        return SentMessageAck("srv-$nextServerId", "2026-10-08T14:00:0$nextServerId.123Z")
    }

    override suspend fun sendReceipt(kind: ReceiptKind, messageId: String) {
        // On the wire, a receipt completes whether or not its sender still waits: a cancellable fake
        // delay would hide a lane that sends after its connection has gone.
        if (receiptDelay > Duration.ZERO) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(receiptDelay) }
        receipts += kind to messageId
    }

    override suspend fun fetchTranscript(nextToken: String?, maxResults: Int): RawTranscriptPage {
        transcriptRequests += nextToken
        return synchronized(transcriptPages) { if (transcriptPages.isEmpty()) RawTranscriptPage(emptyList(), null) else transcriptPages.removeAt(0) }
    }

    fun fire(f: (ChatDriverHandlers) -> Unit) = installed?.let(f)

    fun receiptIds(): List<String> = synchronized(receipts) { receipts.map { it.second } }
}

private fun agentMessage(text: String, id: String, time: String = "2026-10-08T14:00:00.500Z", role: String = "AGENT", pid: String? = "p-agent") =
    RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, id, time, "text/plain", role, pid, "Agent", text, isOutgoing = false)

private fun customerEcho(text: String, id: String) =
    RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, id, "2026-10-08T14:00:01.000Z", "text/plain", "CUSTOMER", "p-me", "Me", text, isOutgoing = true)

private fun participantEvent(contentType: String, role: String, pid: String, name: String = "X") =
    RawTranscriptItem(RawTranscriptItem.Kind.EVENT, "ev-$pid-${contentType.takeLast(6)}", "2026-10-08T14:00:00.000Z", contentType, role, pid, name, null, isOutgoing = false)

private class Sink(transport: ConnectChatTransport, scope: CoroutineScope) {
    private val received = Collections.synchronizedList(mutableListOf<TransportEvent>())
    val job: Job = scope.launch(Dispatchers.Default) { transport.events.collect { received += it } }
    val events: List<TransportEvent> get() = synchronized(received) { received.toList() }

    fun stop() = job.cancel()
}

private suspend fun eventually(timeout: Duration = 2.seconds, condition: suspend () -> Boolean): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (deadline.hasNotPassedNow()) {
        if (condition()) return true
        delay(10.milliseconds)
    }
    return condition()
}

private val creds = ChatContactCredentials("c1", "p-me", "tok", null)

private fun transportTest(body: suspend CoroutineScope.(FakeChatDriver, ConnectChatTransport) -> Unit) =
    runBlocking {
        val d = FakeChatDriver()
        withTimeout(20.seconds) { body(d, ConnectChatTransport(d)) }
    }

class ConnectChatTransportTest {
    @Test
    fun `files - an agent's arrives as an attachment, our own echo is kept for the store to key, and history's too`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            val theirs = agentMessage("statement.pdf", "f1").copy(contentType = "application/pdf", attachmentId = "att-9")
            d.fire { it.onItem(theirs) }
            d.fire { it.onItem(customerEcho("photo.png", "f2").copy(contentType = "image/png", attachmentId = "att-10")) }
            d.fire { it.onItem(customerEcho("words", "m3")) } // a text echo is still ours to drop
            assertTrue(eventually { sink.events.size == 3 })
            val agent = assertIs<TransportEvent.MessageReceived>(sink.events[1]).message
            assertEquals("statement.pdf", agent.body)
            assertEquals("att-9", agent.attachmentId)
            assertEquals("application/pdf", agent.attachmentContentType)
            assertEquals(Direction.INBOUND, agent.direction)
            val mine = assertIs<TransportEvent.MessageReceived>(sink.events[2]).message
            assertEquals(Direction.OUTBOUND, mine.direction)
            assertEquals("att-10", mine.attachmentId)
            assertEquals("f2", mine.serverMessageId)
            // As web's file: no line for a file.
            d.transcriptPages += RawTranscriptPage(listOf(theirs, agentMessage("hello", "m4")), null)
            assertEquals(listOf("hello"), t.fetchTranscriptEntries(100).map { it.text })
            sink.stop()
        }

    @Test
    fun `ordering contract - events raised before a collector attaches are buffered, not dropped`() =
        transportTest { d, t ->
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(agentMessage("early", "m1")) }
            val sink = Sink(t, this) // collect late
            assertTrue(eventually { sink.events.size == 2 })
            assertEquals(TransportEvent.Connected, sink.events.first())
            sink.stop()
        }

    @Test
    fun `lifecycle - connected, broken, ended, and disconnect reaches the SDK once`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            assertEquals(listOf("tok"), d.connects.toList())
            d.fire { it.onConnected() }
            d.fire { it.onConnectionBroken() }
            d.fire { it.onChatEnded() }
            assertTrue(eventually { sink.events.size == 3 })
            assertEquals(listOf(TransportEvent.Connected, TransportEvent.Disconnected(null), TransportEvent.ContactEnded(EndReason.AGENT_ENDED)), sink.events)
            t.disconnect()
            assertEquals(1, d.disconnects)
            sink.stop()
        }

    @Test
    fun `an inbound agent message is normalised with a millisecond sequence and its sender, and receipted once`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(agentMessage("hello", "m1", time = "2026-10-08T14:00:00.500Z")) }
            assertTrue(eventually { sink.events.size == 2 })
            val m = assertIs<TransportEvent.MessageReceived>(sink.events[1]).message
            assertEquals("m1", m.serverMessageId)
            assertEquals("hello", m.body)
            assertEquals(Direction.INBOUND, m.direction)
            assertEquals(ParticipantRole.AGENT, m.participantRole)
            assertEquals(DeliveryState.DELIVERED, m.deliveryState)
            assertEquals("p-agent", m.participantId)
            assertEquals(1_791_468_000_500L, m.sequence)
            assertEquals(Instant.ofEpochMilli(1_791_468_000_500L), m.createdAt)
            assertTrue(eventually { d.receipts.toList() == listOf(ReceiptKind.DELIVERED to "m1") })
            sink.stop()
        }

    @Test
    fun `receipt policy by role - human roles get a delivered receipt, system and bot none, bot maps to assistant`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(agentMessage("sys", "s1", role = "SYSTEM", pid = null)) }
            d.fire { it.onItem(agentMessage("bot", "b1", role = "CUSTOM_BOT", pid = "p-bot")) }
            d.fire { it.onItem(agentMessage("sup", "u1", role = "SUPERVISOR", pid = "p-sup")) }
            d.fire { it.onItem(agentMessage("who", "x1", role = "SOMETHING_NEW", pid = "p-x")) }
            assertTrue(eventually { sink.events.size == 5 })
            val roles = sink.events.filterIsInstance<TransportEvent.MessageReceived>().map { it.message.participantRole }
            assertEquals(listOf(ParticipantRole.SYSTEM, ParticipantRole.ASSISTANT, ParticipantRole.SUPERVISOR, ParticipantRole.UNKNOWN), roles)
            assertTrue(eventually { d.receipts.size == 2 })
            delay(100.milliseconds) // a receipt for system or bot would have had time to go out
            assertEquals(listOf("u1", "x1"), d.receiptIds())
            assertTrue(d.receipts.all { it.first == ReceiptKind.DELIVERED })
            sink.stop()
        }

    @Test
    fun `a frame seen twice on one connection is receipted once`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            repeat(2) { d.fire { it.onItem(agentMessage("sup", "u1", role = "SUPERVISOR", pid = "p-sup")) } }
            assertTrue(eventually { sink.events.size == 3 })
            delay(80.milliseconds)
            assertEquals(listOf("u1"), d.receiptIds())
            sink.stop()
        }

    @Test
    fun `our own live echo is dropped - the server id comes from the send's acknowledgement`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            eventually { sink.events == listOf(TransportEvent.Connected) }
            val id = UUID.randomUUID()
            t.send(OutboundMessage(id, "hi"))
            d.fire { it.onItem(customerEcho("hi", "srv-1")) }
            assertTrue(eventually { sink.events.size == 2 })
            delay(50.milliseconds)
            assertEquals(2, sink.events.size)
            assertEquals(TransportEvent.MessageDelivered(id, "srv-1", 1_791_468_001_123L), sink.events[1])
            assertEquals(listOf("hi"), d.sentBodies.toList())
            sink.stop()
        }

    @Test
    fun `an answer goes out as web's interactive response - its words as action and title, the choice in the payload`() =
        transportTest { d, t ->
            t.connect(creds)
            t.send(OutboundMessage(body = "Check booking status", interactiveTemplate = InteractiveContent.CHOICE_TEMPLATE))
            assertEquals(listOf(true), d.sentInteractive.toList())
            val sent = Json.parseToJsonElement(d.sentBodies.single()).jsonObject
            assertEquals(
                Json.parseToJsonElement(
                    """{"templateType":"TouchpointModality","version":"1.0","action":"Check booking status",
                    "data":{"content":{"title":"Check booking status"},"modalityPayload":{"request":{"structured":{"choiceId":"Check booking status"}},"context":{}}}}""",
                ),
                sent,
            )
            // Text stays text; an unknown template, or an answer Connect would refuse, goes as text too.
            t.send(OutboundMessage(body = "plain"))
            t.send(OutboundMessage(body = "odd", interactiveTemplate = "SomethingElse"))
            t.send(OutboundMessage(body = "x".repeat(20_000), interactiveTemplate = InteractiveContent.CHOICE_TEMPLATE))
            // A choice with an id of its own carries it as the choiceId.
            t.send(OutboundMessage(body = "Dispute it", interactiveTemplate = InteractiveContent.CHOICE_TEMPLATE, choiceId = "opt_dispute"))
            assertEquals(listOf(true, false, false, false, true), d.sentInteractive.toList())
            assertEquals(listOf("plain", "odd", "x".repeat(20_000)), d.sentBodies.drop(1).take(3))
            val withId = Json.parseToJsonElement(d.sentBodies.last()).jsonObject
            assertEquals("opt_dispute", withId["data"]?.jsonObject?.get("modalityPayload")?.jsonObject?.get("request")?.jsonObject?.get("structured")?.jsonObject?.get("choiceId")?.jsonPrimitive?.content)
            assertEquals("Dispute it", withId["action"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a slot goes out as web's slot response - the slot and its value in structured slots, no customer words`() =
        transportTest { d, t ->
            t.connect(creds)
            t.send(OutboundMessage(body = "harbour", interactiveTemplate = InteractiveContent.SLOT_TEMPLATE, choiceId = "Charge"))
            assertEquals(listOf(true), d.sentInteractive.toList())
            assertEquals(
                Json.parseToJsonElement(
                    """{"templateType":"TouchpointModality","version":"1.0","action":"Charge: harbour",
                    "data":{"content":{"title":"Charge: harbour"},"modalityPayload":{"request":{"structured":{"slots":[{"slotId":"Charge","value":"harbour"}]}}}}}""",
                ),
                Json.parseToJsonElement(d.sentBodies.single()),
            )
            // No slot id, nothing to fill: sent as text, not as a response the flow would misread.
            t.send(OutboundMessage(body = "2026-10-01", interactiveTemplate = InteractiveContent.SLOT_TEMPLATE))
            assertEquals(listOf(true, false), d.sentInteractive.toList())
        }

    @Test
    fun `a view's answer goes out as web's submitView - the view's name, its action, and what was entered as data`() =
        transportTest { d, t ->
            t.connect(creds)
            t.send(OutboundMessage(body = """{"reason":"a charge"}""", interactiveTemplate = InteractiveContent.VIEW_RESPONSE_TEMPLATE, choiceId = """{"action":"Submit","viewName":"Form"}"""))
            assertEquals(listOf(true), d.sentInteractive.toList())
            assertEquals(
                Json.parseToJsonElement("""{"templateType":"ViewResource","version":"1.0","viewName":"Form","action":"Submit","data":{"reason":"a charge"}}"""),
                Json.parseToJsonElement(d.sentBodies.single()),
            )
        }

    @Test
    fun `web's title rule - an answer is its words trimmed and capped at 200`() {
        assertEquals("Yes", InteractiveContent.choiceText("  Yes \n"))
        val long = Json.parseToJsonElement(InteractiveResponse.envelope("a".repeat(300)) ?: "").jsonObject
        assertEquals(200, long["action"]?.jsonPrimitive?.content?.length)
    }

    @Test
    fun `web's own template - an assistant's message is its words, an interim line an interim event, and a JSON message its text`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            val message = """{"templateType":"TouchpointModality","data":{"content":{"title":"t","modalityPayload":{"type":"message","message":"Which card?","choices":[{"choiceId":"c1","choiceText":"Rewards"}]}}}}"""
            val interim = """{"templateType":"TouchpointModality","data":{"content":{"modalityPayload":{"type":"interim","message":"Looking that up…"}}}}"""
            val json = """{"messages":[{"text":"First."},{"text":"Second.","choices":[{"id":"a","label":"A"}]}]}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m", "2026-10-08T14:00:00.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", message, false)) }
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i", "2026-10-08T14:00:01.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interim, false)) }
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "j", "2026-10-08T14:00:02.000Z", ConnectContentType.JSON, "CUSTOM_BOT", "p-b", "Bot", json, false)) }
            assertTrue(eventually { sink.events.size == 4 })
            delay(50.milliseconds)
            val bodies = sink.events.filterIsInstance<TransportEvent.MessageReceived>().map { it.message.body }
            assertEquals(listOf("Which card?", "First.\n\nSecond."), bodies)
            assertEquals(listOf(TransportEvent.InterimMessage("Looking that up…")), sink.events.filterIsInstance<TransportEvent.InterimMessage>())
            sink.stop()
        }

    @Test
    fun `an answer read back from the transcript is its words, with only its template kept`() =
        transportTest { d, t ->
            val response = InteractiveResponse.envelope("Cancel booking") ?: ""
            d.transcriptPages +=
                RawTranscriptPage(
                    listOf(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "r-1", "2026-10-08T14:00:01.000Z", ConnectContentType.INTERACTIVE_RESPONSE, "CUSTOMER", "p-me", "Me", response, isOutgoing = true)),
                    "",
                )
            t.connect(creds)
            val answer = t.fetchTranscript(null, 50).messages.single()
            assertEquals("Cancel booking", answer.body)
            assertEquals(Direction.OUTBOUND, answer.direction)
            assertEquals(InteractiveContent(InteractiveContent.CHOICE_TEMPLATE, rawJson = ""), Message(answer).interactive)
        }

    @Test
    fun `typing and an interim line come through live - a replayed or fetched interim line does not`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onTyping("AGENT") }
            val interim = """{"templateType":"TouchpointModality","data":{"content":{"modalityPayload":{"type":"interim","message":"Looking that up…"}}}}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i1", "2026-10-08T14:00:01.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interim, false)) }
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i2", "2026-10-08T14:00:02.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interim, false, isFromPastSession = true)) }
            // The SDK re-delivers a stored frame when a receipt for it comes: shown once.
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i1", "2026-10-08T14:00:01.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interim, false)) }
            // An interim line with no message clears the line, as web's does.
            val empty = """{"templateType":"TouchpointModality","data":{"content":{"title":"t","modalityPayload":{"type":"interim"}}}}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i4", "2026-10-08T14:00:04.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", empty, false)) }
            assertTrue(eventually { sink.events.size == 4 })
            delay(50.milliseconds)
            assertEquals(
                listOf(TransportEvent.Connected, TransportEvent.Typing(ParticipantRole.AGENT), TransportEvent.InterimMessage("Looking that up…"), TransportEvent.InterimMessage("")),
                sink.events.toList(),
            )
            d.transcriptPages += RawTranscriptPage(listOf(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "i3", "2026-10-08T14:00:03.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interim, false)), "")
            assertTrue(t.fetchTranscript(null, 50).messages.isEmpty())
            t.sendTyping()
            assertEquals(1, d.typingSent)
            sink.stop()
        }

    @Test
    fun `the server's transcript as the customer's copy - Connect's times and names, words not JSON, no interim lines or events`() =
        transportTest { d, t ->
            val quick = """{"templateType":"QuickReply","data":{"content":{"title":"Which card?","elements":[{"title":"Rewards"}]}}}"""
            val answer = InteractiveResponse.envelope("Rewards") ?: ""
            val interim = """{"templateType":"TouchpointModality","data":{"content":{"modalityPayload":{"type":"interim","message":"Looking…"}}}}"""
            d.transcriptPages +=
                RawTranscriptPage(
                    listOf(
                        participantEvent(ConnectContentType.JOINED, "AGENT", "p-a", "Sam"),
                        RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m1", "2026-10-08T14:00:00.123Z", ConnectContentType.PLAIN_TEXT, "CUSTOMER", "p-me", "Alex", "Hi", isOutgoing = true),
                        RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m2", "2026-10-08T14:00:01.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "", quick, false),
                        RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m3", "2026-10-08T14:00:02.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "BOT", interim, false),
                        RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m4", "2026-10-08T14:00:03.000Z", ConnectContentType.INTERACTIVE_RESPONSE, "CUSTOMER", "p-me", "Alex", answer, isOutgoing = true),
                        RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "m5", "2026-10-08T14:00:04.000Z", ConnectContentType.PLAIN_TEXT, "AGENT", "p-a", "Sam", "I can help.", false),
                    ),
                    "older",
                )
            t.connect(creds)
            val entries = t.fetchTranscriptEntries(100)
            assertEquals(
                listOf(
                    Triple("2026-10-08T14:00:00.123Z", "Alex", "Hi"),
                    Triple("2026-10-08T14:00:01.000Z", "CUSTOM_BOT", "Which card?"), // no display name: the role, as web
                    Triple("2026-10-08T14:00:03.000Z", "Alex", "Rewards"),
                    Triple("2026-10-08T14:00:04.000Z", "Sam", "I can help."),
                ),
                entries.map { Triple(it.absoluteTime, it.name, it.text) },
            )
            assertEquals(listOf<String?>(null), d.transcriptRequests.toList()) // the newest page, one request
            assertFalse("Alex" in entries.first().toString())
        }

    @Test
    fun `a quick reply with no title is read by its subtitle, as web reads it`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            val quick = """{"templateType":"QuickReply","version":"1.0","data":{"content":{"subtitle":"Which one?","elements":[{"title":"A"}]}}}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "q", "2026-10-08T14:00:00.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", quick, false)) }
            assertTrue(eventually { sink.events.size == 2 })
            assertEquals("Which one?", (sink.events[1] as TransportEvent.MessageReceived).message.body)
            sink.stop()
        }

    @Test
    fun `a failed send maps to TransportFailure carrying only the type, and never retries through the SDK`() =
        transportTest { d, t ->
            t.connect(creds)
            d.failNextSend = IllegalStateException("token=SECRET")
            val e = assertFailsWith<TouchpointException.TransportFailure> { t.send(OutboundMessage(body = "x")) }
            assertEquals("IllegalStateException", e.detail)
            assertFalse("SECRET" in e.toString())
            assertTrue(d.sentBodies.isEmpty())
        }

    @Test
    fun `a connect failure maps to the taxonomy, and the transport can connect again`() =
        transportTest { d, t ->
            d.failConnect = RuntimeException("boom")
            assertFailsWith<TouchpointException.TransportFailure> { t.connect(creds) }
            t.connect(creds)
            assertEquals(listOf("tok"), d.connects.toList())
        }

    @Test
    fun `participant joined and left carry the id and mapped role, and receipts become Receipt events`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(participantEvent(ConnectContentType.JOINED, "AGENT", "p-a", "Ann")) }
            d.fire { it.onItem(participantEvent(ConnectContentType.LEFT, "AGENT", "p-a")) }
            d.fire { it.onReceipt(ReceiptKind.READ, "srv-9") }
            assertTrue(eventually { sink.events.size == 4 })
            assertEquals(TransportEvent.ParticipantJoined(Participant("p-a", ParticipantRole.AGENT, "Ann")), sink.events[1])
            assertEquals(TransportEvent.ParticipantLeft("p-a"), sink.events[2])
            assertEquals(TransportEvent.Receipt("srv-9", ReceiptKind.READ), sink.events[3])
            sink.stop()
        }

    @Test
    fun `a join or leave with no participant id is ignored - a set cannot be keyed on nothing`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(participantEvent(ConnectContentType.JOINED, "AGENT", "p-a").copy(participantId = null)) }
            d.fire { it.onItem(agentMessage("after", "m1")) }
            assertTrue(eventually { sink.events.size == 2 })
            assertIs<TransportEvent.MessageReceived>(sink.events[1])
            sink.stop()
        }

    @Test
    fun `the wrong credential type is rejected before any network call`() =
        transportTest { d, t ->
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.connect(VoiceContactCredentials("v", "p", "t", "{}", "{}", null)) }
            assertTrue(d.connects.isEmpty())
        }

    @Test
    fun `markRead sends one read receipt for the given server id`() =
        transportTest { d, t ->
            t.connect(creds)
            t.markRead("m7")
            assertEquals(listOf(ReceiptKind.READ to "m7"), d.receipts.toList())
        }

    @Test
    fun `fetchTranscript maps a page - ours outbound SENT, theirs inbound DELIVERED, joins as events, token passed through`() =
        transportTest { d, t ->
            d.transcriptPages +=
                RawTranscriptPage(
                    listOf(
                        participantEvent(ConnectContentType.JOINED, "AGENT", "p-a", "Ann"),
                        customerEcho("mine", "c-1"),
                        agentMessage("theirs", "a-1"),
                        RawTranscriptItem(RawTranscriptItem.Kind.EVENT, "typing", "2026-10-08T14:00:02.000Z", "application/vnd.amazonaws.connect.event.typing", "AGENT", "p-a", "Ann", null, false),
                    ),
                    "older",
                )
            t.connect(creds)
            val page = t.fetchTranscript(null, 50)
            assertEquals(listOf<String?>(null), d.transcriptRequests.toList())
            assertEquals("older", page.nextToken)
            assertEquals(listOf<TransportEvent>(TransportEvent.ParticipantJoined(Participant("p-a", ParticipantRole.AGENT, "Ann"))), page.participantEvents)
            assertEquals(listOf("c-1", "a-1"), page.messages.map { it.serverMessageId })
            assertEquals(Direction.OUTBOUND, page.messages[0].direction)
            assertEquals(DeliveryState.SENT, page.messages[0].deliveryState)
            assertEquals(ParticipantRole.CUSTOMER, page.messages[0].participantRole)
            assertEquals(Direction.INBOUND, page.messages[1].direction)
            assertEquals(DeliveryState.DELIVERED, page.messages[1].deliveryState)
            assertTrue(d.receipts.isEmpty()) // history is not receipted again
            d.transcriptPages += RawTranscriptPage(emptyList(), "")
            assertNull(t.fetchTranscript("older", 50).nextToken) // an empty token means exhausted
        }

    @Test
    fun `markdown and interactive content are carried - interactive goes into interactiveJson with its template, never as the body`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "md", "2026-10-08T14:00:00.000Z", "text/markdown", "AGENT", "p-a", "A", "**bold**", false)) }
            val interactive = """{"templateType":"ListPicker","version":"1.0","data":{"content":{"title":"Pick"}}}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "ix", "2026-10-08T14:00:00.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", interactive, false)) }
            val untitled = """{"templateType":"TimePicker","version":"1.0","data":{"content":{"timeslots":[]}}}"""
            d.fire { it.onItem(RawTranscriptItem(RawTranscriptItem.Kind.MESSAGE, "tp", "2026-10-08T14:00:00.000Z", ConnectContentType.INTERACTIVE, "CUSTOM_BOT", "p-b", "Bot", untitled, false)) }
            assertTrue(eventually { sink.events.size == 4 })
            val msgs = sink.events.filterIsInstance<TransportEvent.MessageReceived>().map { it.message }
            assertEquals("**bold**", msgs[0].body)
            assertNull(msgs[0].interactiveJson)
            assertEquals("Pick", msgs[1].body) // the title is the fallback body
            assertEquals("ListPicker", Message(msgs[1]).interactive?.templateType)
            assertEquals("", msgs[2].body) // no title: an empty body, never raw JSON
            assertNotNull(msgs[2].interactiveJson)
            assertEquals(InteractiveContent("TimePicker", untitled), Message(msgs[2]).interactive)
            sink.stop()
        }

    @Test
    fun `after disconnect, late SDK callbacks are dropped and nothing leaks into the next collector`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            eventually { sink.events.size == 1 }
            t.disconnect()
            d.fire { it.onItem(agentMessage("late", "late-1", role = "SUPERVISOR")) }
            d.fire { it.onConnectionBroken() }
            delay(80.milliseconds)
            sink.stop()
            val sink2 = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            assertTrue(eventually { sink2.events.size == 1 })
            delay(50.milliseconds)
            assertEquals(listOf<TransportEvent>(TransportEvent.Connected), sink2.events)
            assertTrue(d.receipts.isEmpty()) // the late frame was never acknowledged either
            sink2.stop()
        }

    @Test
    fun `a slow receipt round trip does not delay the frames behind it`() =
        transportTest { d, t ->
            d.receiptDelay = 400.milliseconds
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            for (i in 1..5) d.fire { it.onItem(agentMessage("m$i", "m$i", role = "SUPERVISOR")) }
            assertTrue(eventually { sink.events.size == 6 })
            assertTrue(d.receipts.isEmpty())
            assertEquals((1..5).map { "m$it" }, sink.events.filterIsInstance<TransportEvent.MessageReceived>().map { it.message.serverMessageId })
            assertTrue(eventually(5.seconds) { d.receipts.size == 5 })
            sink.stop()
        }

    @Test
    fun `receipts queued before a drop never go out on the next connection`() =
        transportTest { d, t ->
            d.receiptDelay = 200.milliseconds
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            for (i in 1..3) d.fire { it.onItem(agentMessage("m$i", "old-$i", role = "SUPERVISOR")) }
            eventually { sink.events.size == 4 }
            t.disconnect() // three receipts are still queued or in flight
            sink.stop()
            val sink2 = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(agentMessage("fresh", "new-1", role = "SUPERVISOR")) }
            eventually { sink2.events.size == 2 }
            assertTrue(eventually(3.seconds) { "new-1" in d.receiptIds() })
            delay(700.milliseconds)
            val sent = d.receiptIds()
            assertFalse("old-2" in sent || "old-3" in sent, "stale receipts leaked: $sent")
            sink2.stop()
        }

    @Test
    fun `a collector that goes away without a disconnect does not swallow events meant for the next one`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            eventually { sink.events.size == 1 }
            sink.stop()
            delay(50.milliseconds)
            d.fire { it.onItem(agentMessage("after", "a-1")) }
            delay(50.milliseconds)
            val sink2 = Sink(t, this)
            assertTrue(eventually { sink2.events.any { it is TransportEvent.MessageReceived && it.message.body == "after" } })
            sink2.stop()
        }

    @Test
    fun `an unparseable server time yields no sequence rather than a wrong one`() {
        assertNull(ConnectChatTransport.sequence("yesterday"))
        assertEquals(0L, ConnectChatTransport.sequence("1970-01-01T00:00:00Z"))
    }

    @Test
    fun `RawTranscriptItem toString omits the text`() {
        assertFalse("SECRET" in agentMessage("SECRET body", "m1").toString())
    }

    // Regressions

    @Test
    fun `a reconnect the SDK makes itself surfaces as Reestablished, but the first one up is just Connected`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onReEstablished() } // before the connection was ever up
            d.fire { it.onReEstablished() } // after a silent drop in the background
            assertTrue(eventually { sink.events.size == 2 })
            assertEquals(listOf(TransportEvent.Connected, TransportEvent.Reestablished), sink.events)
            sink.stop()
        }

    @Test
    fun `one drop is one Disconnected however many times the SDK reports it`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onConnectionBroken() }
            d.fire { it.onConnectionBroken() }
            d.fire { it.onConnected() }
            d.fire { it.onConnectionBroken() }
            assertTrue(eventually { sink.events.size == 4 })
            delay(100.milliseconds)
            assertEquals(
                listOf(TransportEvent.Connected, TransportEvent.Disconnected(null), TransportEvent.Connected, TransportEvent.Disconnected(null)),
                sink.events,
            )
            sink.stop()
        }

    @Test
    fun `a connect the SDK never reports up fails after the timeout`() =
        runBlocking {
            val d = FakeChatDriver()
            val t = ConnectChatTransport(d, connectTimeout = 150.milliseconds)
            val sink = Sink(t, this)
            t.connect(creds) // and the SDK stays silent
            assertTrue(eventually { sink.events == listOf<TransportEvent>(TransportEvent.Disconnected("connect timeout")) })
            sink.stop()
        }

    @Test
    fun `a connect reported up in time is not failed by the watchdog`() =
        runBlocking {
            val d = FakeChatDriver()
            val t = ConnectChatTransport(d, connectTimeout = 150.milliseconds)
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            delay(300.milliseconds)
            assertEquals(listOf<TransportEvent>(TransportEvent.Connected), sink.events)
            sink.stop()
        }

    @Test
    fun `a send acknowledged after its connection went is dropped, not handed to the next contact`() =
        transportTest { d, t ->
            d.sendDelay = 200.milliseconds
            t.connect(creds)
            d.fire { it.onConnected() }
            val first = Sink(t, this)
            val sending = launch(Dispatchers.Default) { runCatching { t.send(OutboundMessage(body = "late")) } }
            delay(50.milliseconds)
            t.disconnect()
            first.stop()
            sending.join()
            val next = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            assertTrue(eventually { next.events.isNotEmpty() })
            delay(100.milliseconds)
            assertEquals(listOf<TransportEvent>(TransportEvent.Connected), next.events)
            next.stop()
        }

    @Test
    fun `an agent's last message that trails the chat's end still lands before ContactEnded`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onChatEnded() }
            d.fire { it.onItem(agentMessage("goodbye", "m9")) } // the SDK's extra hop for items
            assertTrue(eventually { sink.events.size == 3 })
            assertIs<TransportEvent.MessageReceived>(sink.events[1])
            assertEquals(TransportEvent.ContactEnded(EndReason.AGENT_ENDED), sink.events[2])
            sink.stop()
        }

    @Test
    fun `history the SDK replays is delivered but never receipted again`() =
        transportTest { d, t ->
            val sink = Sink(t, this)
            t.connect(creds)
            d.fire { it.onConnected() }
            d.fire { it.onItem(agentMessage("old", "h1").copy(isFromPastSession = true)) }
            assertTrue(eventually { sink.events.size == 2 })
            delay(100.milliseconds)
            assertTrue(d.receipts.isEmpty())
            sink.stop()
        }

    @Test
    fun `release lets go of the connection without ending the participant`() =
        transportTest { d, t ->
            t.connect(creds)
            t.release()
            assertEquals(1, d.releases)
            assertEquals(0, d.disconnects)
        }

    @Test
    fun `a chat with no participant token connects only with a custom chat client, and the token stays off the driver`() =
        runBlocking {
            val noToken = ChatContactCredentials("c1", "p-me", null, null)
            val d = FakeChatDriver()
            assertFailsWith<TouchpointException.InvalidConfiguration> { ConnectChatTransport(d).connect(noToken) }
            assertTrue(d.connects.isEmpty())
            val custom = FakeChatDriver()
            val t = ConnectChatTransport(custom, usesCustomChatClient = true)
            launch { t.connect(noToken) }
            assertTrue(eventually { custom.connects.size == 1 })
            assertEquals(null, custom.connects.single())
            t.disconnect()
        }
}
