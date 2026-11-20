package com.acmebank.demo.support

import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.modalities
import com.amazon.connect.touchpoint.core.viewSummary
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** The stored form back to content, as the SDK reads a row: `InteractiveContent.decodeOrNull` is Core's own. */
private fun InteractiveContent.Companion.decodeForTest(stored: String): InteractiveContent =
    kotlinx.serialization.json.Json.decodeFromString(InteractiveContent.serializer(), stored)

class ScriptedChatTransportTest {
    private fun credentials(contact: String = "offline-${UUID.randomUUID()}") = ChatContactCredentials(contact, "customer", "offline", null)

    @Test
    fun `replies follow the words, and an unmatched message is told it is scripted`() {
        assertContains(ScriptedChatTransport.scriptedReply("Where is my replacement card?").text, "posted two business days ago")
        assertContains(ScriptedChatTransport.scriptedReply("I don't recognise a charge").text, "Tap **Dispute**")
        assertContains(ScriptedChatTransport.scriptedReply("Reset my password").text, "Settings")
        assertContains(ScriptedChatTransport.scriptedReply("THANK YOU").text, "welcome")
        assertContains(ScriptedChatTransport.scriptedReply("hello").text, "scripted")
    }

    @Test
    fun `the script offers choices, hands over, drops and ends on the words that ask for them`() {
        assertEquals(ScriptedChatTransport.TOPICS + ScriptedChatTransport.WRAP_UP, ScriptedChatTransport.scriptedReply("thanks!").choices)
        kotlin.test.assertNotNull(ScriptedChatTransport.scriptedReply("Where is my new card").interactiveJson)
        kotlin.test.assertTrue(ScriptedChatTransport.scriptedReply("Can I talk to an agent?").handsOver)
        kotlin.test.assertTrue(ScriptedChatTransport.scriptedReply("please drop the connection").dropsConnection)
        kotlin.test.assertTrue(ScriptedChatTransport.scriptedReply("goodbye").endsContact)
        assertContains(ScriptedChatTransport.scriptedReply("dispute a charge").text, "**Dispute**")
    }

    @Test
    fun `the showcase's cards journey - a carousel, a card with a date, then the bank's summary with a video, each as the SDK reads them`() {
        fun names(reply: ScriptedChatTransport.Reply): List<String> {
            val stored = InteractiveContent.decodeForTest(reply.interactiveJson ?: "")
            return stored.modalities.map { it.name }
        }
        assertEquals(listOf("DefaultCarousel"), names(ScriptedChatTransport.scriptedReply("Show me cards")))
        assertEquals(listOf("DefaultCard", "DefaultDateInput"), names(ScriptedChatTransport.slotReply("Charge")))
        assertEquals(listOf("AcmeDisputeSummary", "DefaultVideo"), names(ScriptedChatTransport.slotReply("Noticed")))
        kotlin.test.assertTrue(ScriptedChatTransport.scriptedReply("Sign in").promptsSignIn)
    }

    @Test
    fun `a filled slot is answered by its slot, not its words`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
            t.connect(credentials())
            t.send(OutboundMessage(body = "harbour", interactiveTemplate = InteractiveContent.SLOT_TEMPLATE, choiceId = "Charge"))
            val reply = withTimeout(5_000) { t.events.first { it is TransportEvent.MessageReceived && "first notice" in it.message.body } }
            assertIs<TransportEvent.MessageReceived>(reply)
            Unit
        }

    @Test
    fun `connect joins the agent and greets once per contact, across reconnects`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
            val c = credentials()
            t.connect(c)
            val first = withTimeout(2_000) { t.events.take(3).toList() }
            assertIs<TransportEvent.Connected>(first[0])
            assertIs<TransportEvent.ParticipantJoined>(first[1])
            val greeting = assertIs<TransportEvent.MessageReceived>(first[2])
            assertEquals("demo-greeting-${c.contactId}", greeting.message.serverMessageId)

            t.disconnect()
            t.connect(c) // a resume: same contact, no second hello
            val again = withTimeout(2_000) { t.events.take(2).toList() }
            assertIs<TransportEvent.Connected>(again[0])
            t.send(OutboundMessage(UUID.randomUUID(), "thanks"))
            val delivered = withTimeout(2_000) { t.events.first() }
            assertIs<TransportEvent.MessageDelivered>(delivered)
            val reply = assertIs<TransportEvent.MessageReceived>(withTimeout(2_000) { t.events.first() })
            assertContains(reply.message.body, "welcome")
            t.disconnect()
        }

    @Test
    fun `send before connect is a transport failure, not a silent drop`() =
        runBlocking {
            assertFailsWith<TouchpointException.TransportFailure> { ScriptedChatTransport().send(OutboundMessage(UUID.randomUUID(), "hi")) }
            Unit
        }

    @Test
    fun `server sequence only grows`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
            t.connect(credentials())
            withTimeout(2_000) { t.events.take(3).toList() }
            val seqs = mutableListOf<Long>()
            repeat(5) {
                t.send(OutboundMessage(UUID.randomUUID(), "x$it"))
                seqs += assertIs<TransportEvent.MessageDelivered>(withTimeout(2_000) { t.events.first() }).sequence!!
                seqs += assertIs<TransportEvent.MessageReceived>(withTimeout(2_000) { t.events.first() }).message.sequence!!
            }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(seqs.size, seqs.toSet().size)
            t.disconnect()
        }

    @Test
    fun `a subscriber that attaches between disconnect and connect hears the next connection`() =
        runBlocking {
            // The session's order: subscribe, then connect. A subscriber handed the closed channel
            // would complete at once — a drop the session would reconnect for.
            val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
            t.connect(credentials())
            withTimeout(2_000) { t.events.take(3).toList() }
            t.disconnect()
            val events = t.events
            t.connect(credentials())
            assertIs<TransportEvent.Connected>(withTimeout(2_000) { events.first() })
            t.disconnect()
        }

    @Test
    fun `once a person joins they answer, with receipts, and their goodbye ends the chat as Connect's does`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 5.milliseconds)
            t.connect(credentials())
            withTimeout(2_000) { t.events.take(3).toList() } // connected, the assistant, the greeting
            t.send(OutboundMessage(UUID.randomUUID(), "talk to an agent"))
            val handover = withTimeout(2_000) { t.events.take(7).toList() }
            assertIs<TransportEvent.ParticipantJoined>(handover[2])
            assertEquals(ScriptedChatTransport.FORM_ID, assertIs<TransportEvent.MessageReceived>(handover[6]).message.attachmentId)
            // The assistant sent no receipt; the person does.
            assertEquals(0, handover.count { it is TransportEvent.Receipt })

            t.send(OutboundMessage(UUID.randomUUID(), "my card is late"))
            val answer = withTimeout(2_000) { t.events.take(5).toList() }
            val delivered = assertIs<TransportEvent.MessageDelivered>(answer[0])
            assertEquals(listOf(ReceiptKind.DELIVERED, ReceiptKind.READ), answer.filterIsInstance<TransportEvent.Receipt>().map { it.kind })
            assertEquals(delivered.serverMessageId, assertIs<TransportEvent.Receipt>(answer[1]).serverMessageId)
            assertEquals(ParticipantRole.AGENT, assertIs<TransportEvent.MessageReceived>(answer[4]).message.participantRole)

            t.send(OutboundMessage(UUID.randomUUID(), "goodbye"))
            val end = withTimeout(2_000) { t.events.take(7).toList() }
            assertEquals(ScriptedChatTransport.HUMAN_AGENT_ID, assertIs<TransportEvent.ParticipantLeft>(end[5]).id)
            assertEquals(TransportEvent.ContactEnded(EndReason.AGENT_ENDED), end[6])
            t.disconnect()
        }

    @Test
    fun `a rich reply comes after the flow's interim line, and the long suggestion leads to the dispute help`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 5.milliseconds)
            t.connect(credentials())
            withTimeout(2_000) { t.events.take(3).toList() }
            t.send(OutboundMessage(UUID.randomUUID(), "show me cards"))
            val events = withTimeout(2_000) { t.events.take(3).toList() }
            assertEquals(ScriptedChatTransport.INTERIM, assertIs<TransportEvent.InterimMessage>(events[1]).text)
            assertIs<TransportEvent.MessageReceived>(events[2])
            assertContains(ScriptedChatTransport.scriptedReply(ScriptedChatTransport.LONG_TOPIC).text, "dispute")
            assertTrue(ScriptedChatTransport.LONG_TOPIC.length > 60)
            t.disconnect()
        }

    // ---- The tour

    private fun names(reply: ScriptedChatTransport.Reply): List<String> =
        reply.interactiveJson?.let { InteractiveContent.decodeForTest(it).modalities.map { m -> m.name } }.orEmpty()

    @Test
    fun `the greeting says it is the offline demo and offers the whole menu`() {
        val greeting = ScriptedChatTransport.greeting()
        assertContains(greeting.text, "offline demo")
        assertEquals(ScriptedChatTransport.TOPICS, greeting.choices)
        assertContains(ScriptedChatTransport.TOPICS, ScriptedChatTransport.SHOW_CARDS)
        assertContains(ScriptedChatTransport.TOPICS, ScriptedChatTransport.SIGN_IN)
    }

    @Test
    fun `each stop on the menu shows what it names`() {
        val reply = ScriptedChatTransport::scriptedReply
        assertEquals(listOf("DefaultCarousel"), names(reply(ScriptedChatTransport.SHOW_CARDS)))
        assertEquals(listOf("DefaultDateInput"), names(reply(ScriptedChatTransport.PICK_DATE)))
        assertEquals(listOf("DefaultVideo"), names(reply(ScriptedChatTransport.PLAY_VIDEO)))
        assertContains(reply(ScriptedChatTransport.FORMATTED_TEXT).text, "**bold**")
        val guide = reply(ScriptedChatTransport.OPEN_GUIDE)
        assertEquals("Before we connect you", InteractiveContent.decodeForTest(guide.interactiveJson!!).viewSummary?.heading)
        assertContains(assertNotNull(guide.followUp).text, "live mode")
        assertTrue(reply(ScriptedChatTransport.SIGN_IN).promptsSignIn)
        assertTrue(reply(ScriptedChatTransport.TALK_TO_AGENT).handsOver)
        assertEquals(ScriptedChatTransport.MORE_TOPICS + ScriptedChatTransport.BACK_TO_MENU, reply(ScriptedChatTransport.MORE).choices)
        assertEquals(ScriptedChatTransport.TOPICS, reply(ScriptedChatTransport.BACK_TO_MENU).choices)
        assertContains(reply("Send a file").choices, ScriptedChatTransport.TALK_TO_AGENT)
        assertTrue(reply("Drop the connection").dropsConnection)
        assertTrue(reply(ScriptedChatTransport.WRAP_UP).endsContact)
    }

    @Test
    fun `every stop leads somewhere next - a menu, a slot to fill, a person, the sign-in card or the end`() {
        for (topic in ScriptedChatTransport.TOPICS + ScriptedChatTransport.MORE_TOPICS) {
            val r = ScriptedChatTransport.scriptedReply(topic)
            val next = r.choices.isNotEmpty() || r.followUp?.choices?.isNotEmpty() == true || r.handsOver || r.endsContact || r.promptsSignIn ||
                r.modalitiesJson?.contains("\$saveAs") == true
            assertTrue(next, "\"$topic\" is a dead end")
        }
        assertEquals(ScriptedChatTransport.TOPICS, ScriptedChatTransport.slotReply("Noticed").choices)
    }

    @Test
    fun `free text is matched on its obvious words, and anything else is told it is scripted and offered the menu`() {
        val reply = ScriptedChatTransport::scriptedReply
        assertEquals(listOf("DefaultCarousel"), names(reply("a carousel please")))
        assertEquals(listOf("DefaultCard"), names(reply("show me a card")))
        assertEquals(listOf("DefaultDateInput"), names(reply("can I see the calendar")))
        assertEquals(listOf("DefaultVideo"), names(reply("a video?")))
        assertContains(reply("I want to upload a file").choices, ScriptedChatTransport.TALK_TO_AGENT)
        assertTrue(reply("Bye!").endsContact)
        assertTrue(reply("I need a human").handsOver)
        val unknown = reply("asdf qwerty")
        assertContains(unknown.text, "scripted")
        assertEquals(ScriptedChatTransport.TOPICS, unknown.choices)
    }

    @Test
    fun `a picked date is answered with the date`() {
        assertContains(ScriptedChatTransport.slotReply("TourDate", "11/09/2026").text, "11/09/2026")
    }

    // ---- Pausing the tour

    @Test
    fun `paused, the tour neither greets nor answers - the customer's message is only delivered`() =
        runBlocking {
            val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
            t.autoReplies = false
            t.connect(credentials())
            withTimeout(2_000) { t.events.take(2).toList() } // connected, the assistant
            assertNull(withTimeoutOrNull(150) { t.events.first() }, "no greeting while paused")
            t.send(OutboundMessage(UUID.randomUUID(), "show me cards"))
            assertIs<TransportEvent.MessageDelivered>(withTimeout(2_000) { t.events.first() })
            assertNull(withTimeoutOrNull(150) { t.events.first() }, "no reply while paused")
            // The console's switch is the same one.
            t.console.autoReplies = true
            assertTrue(t.autoReplies)
            t.send(OutboundMessage(UUID.randomUUID(), "show me cards"))
            assertIs<TransportEvent.MessageDelivered>(withTimeout(2_000) { t.events.first() })
            assertIs<TransportEvent.InterimMessage>(withTimeout(2_000) { t.events.first() })
            t.disconnect()
        }

    // ---- The agent console

    /** A connected transport with the tour paused, past its connected and joined events. */
    private suspend fun consoleTransport(): ScriptedChatTransport {
        val t = ScriptedChatTransport(replyDelay = 1.milliseconds)
        t.autoReplies = false
        t.connect(credentials())
        withTimeout(2_000) { t.events.take(2).toList() }
        return t
    }

    private suspend fun ScriptedChatTransport.next(): TransportEvent = withTimeout(2_000) { events.first() }

    @Test
    fun `the console speaks as the assistant, and as the agent who joins first`() =
        runBlocking {
            val t = consoleTransport()
            assertTrue(t.console.say("Hello from the assistant", ConsoleSender.ASSISTANT))
            val said = assertIs<TransportEvent.MessageReceived>(t.next())
            assertEquals(ParticipantRole.ASSISTANT, said.message.participantRole)
            assertEquals("Hello from the assistant", said.message.body)

            assertTrue(t.console.say("Hello from Alex", ConsoleSender.AGENT))
            assertEquals(ScriptedChatTransport.HUMAN_AGENT_ID, assertIs<TransportEvent.ParticipantJoined>(t.next()).participant.id)
            assertEquals(ParticipantRole.AGENT, assertIs<TransportEvent.MessageReceived>(t.next()).message.participantRole)
            // Already on the contact: no second join.
            assertTrue(t.console.say("Again", ConsoleSender.AGENT))
            assertIs<TransportEvent.MessageReceived>(t.next())
            assertFalse(t.console.agentJoins())
            t.disconnect()
        }

    @Test
    fun `the console offers choices as the SDK reads them`() =
        runBlocking {
            val t = consoleTransport()
            assertTrue(t.console.offerChoices("Pick one", listOf("Yes", "No")))
            val message = assertIs<TransportEvent.MessageReceived>(t.next()).message
            val content = InteractiveContent.decodeForTest(message.interactiveJson!!)
            assertEquals(InteractiveContent.CHOICE_TEMPLATE, content.templateType)
            assertContains(content.rawJson, "\"choiceText\":\"Yes\"")
            t.disconnect()
        }

    @Test
    fun `the console sends each rich message`() =
        runBlocking {
            val t = consoleTransport()
            val expected =
                mapOf(
                    ConsoleModality.CARD to listOf("DefaultCard"),
                    ConsoleModality.CAROUSEL to listOf("DefaultCarousel"),
                    ConsoleModality.DATE_INPUT to listOf("DefaultDateInput"),
                    ConsoleModality.VIDEO to listOf("DefaultVideo"),
                )
            for ((modality, modalities) in expected) {
                assertTrue(t.console.send(modality))
                val message = assertIs<TransportEvent.MessageReceived>(t.next()).message
                assertEquals(modalities, InteractiveContent.decodeForTest(message.interactiveJson!!).modalities.map { it.name }, modality.name)
            }
            assertTrue(t.console.send(ConsoleModality.MARKDOWN))
            assertContains(assertIs<TransportEvent.MessageReceived>(t.next()).message.body, "**bold**")
            assertTrue(t.console.send(ConsoleModality.GUIDE))
            val guide = assertIs<TransportEvent.MessageReceived>(t.next()).message
            assertNotNull(InteractiveContent.decodeForTest(guide.interactiveJson!!).viewSummary)
            assertTrue(t.console.send(ConsoleModality.SIGN_IN))
            assertIs<TransportEvent.MessageReceived>(t.next())
            assertEquals(AuthenticationStatus.PROMPT, assertIs<TransportEvent.AuthenticationChanged>(t.next()).status)
            assertTrue(t.console.send(ConsoleModality.FILE))
            assertIs<TransportEvent.ParticipantJoined>(t.next())
            assertEquals(ScriptedChatTransport.FORM_ID, assertIs<TransportEvent.MessageReceived>(t.next()).message.attachmentId)
            t.disconnect()
        }

    @Test
    fun `typing and receipts come from a person on the contact only`() =
        runBlocking {
            val t = consoleTransport()
            assertFalse(t.console.typing(ConsoleSender.AGENT))
            assertTrue(t.console.typing(ConsoleSender.ASSISTANT))
            assertEquals(TransportEvent.Typing(ParticipantRole.ASSISTANT), t.next())
            t.send(OutboundMessage(UUID.randomUUID(), "hi"))
            val delivered = assertIs<TransportEvent.MessageDelivered>(t.next())
            assertFalse(t.console.receipt(ReceiptKind.READ), "no person, no receipt")

            assertTrue(t.console.agentJoins())
            assertIs<TransportEvent.ParticipantJoined>(t.next())
            assertTrue(t.console.typing(ConsoleSender.AGENT))
            assertEquals(TransportEvent.Typing(ParticipantRole.AGENT), t.next())
            assertTrue(t.console.receipt(ReceiptKind.DELIVERED))
            assertEquals(TransportEvent.Receipt(delivered.serverMessageId, ReceiptKind.DELIVERED), t.next())
            assertTrue(t.console.receipt(ReceiptKind.READ))
            assertEquals(TransportEvent.Receipt(delivered.serverMessageId, ReceiptKind.READ), t.next())

            assertTrue(t.console.agentLeaves())
            assertEquals(ScriptedChatTransport.HUMAN_AGENT_ID, assertIs<TransportEvent.ParticipantLeft>(t.next()).id)
            assertFalse(t.console.agentLeaves())
            t.disconnect()
        }

    @Test
    fun `the console drops the connection and ends the contact, and sends nothing while disconnected`() =
        runBlocking {
            val t = consoleTransport()
            assertTrue(t.console.endContact())
            assertEquals(TransportEvent.ContactEnded(EndReason.AGENT_ENDED), t.next())
            val events = t.events
            assertTrue(t.console.dropConnection())
            assertEquals(TransportEvent.Disconnected(null), withTimeout(2_000) { events.first() })
            assertFalse(t.console.isConnected)
            assertFalse(t.console.say("anyone?", ConsoleSender.ASSISTANT))
            assertFalse(t.console.send(ConsoleModality.CARD))
            assertFalse(t.console.endContact())
            assertFalse(t.console.dropConnection())
            t.disconnect()
        }
}
