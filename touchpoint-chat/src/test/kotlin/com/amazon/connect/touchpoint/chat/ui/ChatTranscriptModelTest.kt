package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.TranscriptNotice
import com.amazon.connect.touchpoint.core.AuthenticationCard
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.DeliveryUpdate
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.MessageId
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantSet
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The transcript's state machine. */
class ChatTranscriptModelTest {
    private fun msg(
        body: String,
        direction: Direction,
        role: ParticipantRole = ParticipantRole.AGENT,
        seq: Long?,
        state: DeliveryState = DeliveryState.DELIVERED,
        id: UUID = UUID.randomUUID(),
    ) = Message(StoredMessage(id, seq?.let { "s$it" }, body, direction, role, sequence = seq, deliveryState = state))

    private val ChatTranscriptModel.s get() = state.value

    @Test
    fun `consecutive messages from one sender group, so the name is shown once`() {
        val rows =
            TranscriptLayout.rows(
                listOf(
                    msg("one", Direction.INBOUND, seq = 1), msg("two", Direction.INBOUND, seq = 2),
                    msg("mine", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 3, DeliveryState.SENT),
                    msg("three", Direction.INBOUND, seq = 4),
                ),
            )
        assertEquals(listOf(true, false, true, true), rows.map { it.showsSenderName })
    }

    @Test
    fun `an answer that draws no bubble leaves the run whole, so the reply after it sits with the question`() {
        fun slot(seq: Long) = Message(StoredMessage(UUID.randomUUID(), null, "2026-10-06", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = seq, deliveryState = DeliveryState.SENT, interactiveJson = InteractiveContent.encode(InteractiveContent(InteractiveContent.SLOT_TEMPLATE, """{"slotId":"Date"}"""))))
        val rows =
            TranscriptLayout.rows(
                listOf(
                    msg("When did you first notice it?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1), slot(2),
                    msg("Thanks.", Direction.INBOUND, ParticipantRole.ASSISTANT, 3), msg("Here is your dispute.", Direction.INBOUND, ParticipantRole.ASSISTANT, 4),
                    slot(5),
                    msg("One more thing", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 6, DeliveryState.SENT),
                ),
            )
        assertEquals(listOf("When did you first notice it?", "Thanks.", "Here is your dispute.", "One more thing"), rows.map { it.message.body })
        assertEquals(listOf(true, false, false, true), rows.map { it.showsSenderName })
    }

    @Test
    fun `the guide's rhythm - spacing plus each item's own padding, in 4dp units`() {
        assertEquals(3, TranscriptSpacing.units(TranscriptSpacing.Edge.TEXT, TranscriptSpacing.Edge.TEXT, sameRun = true)) // 12: text to text in a run
        assertEquals(4, TranscriptSpacing.units(TranscriptSpacing.Edge.TEXT, TranscriptSpacing.Edge.MODALITY, sameRun = true)) // 16: text to a modality
        assertEquals(4, TranscriptSpacing.units(TranscriptSpacing.Edge.MODALITY, TranscriptSpacing.Edge.TEXT, sameRun = true))
        assertEquals(5, TranscriptSpacing.units(TranscriptSpacing.Edge.MODALITY, TranscriptSpacing.Edge.MODALITY, sameRun = true)) // 20
        assertEquals(8, TranscriptSpacing.units(TranscriptSpacing.Edge.TEXT, TranscriptSpacing.Edge.TEXT, sameRun = false)) // 32: a new sender
        assertEquals(7, TranscriptSpacing.units(TranscriptSpacing.Edge.NONE, TranscriptSpacing.Edge.TEXT, sameRun = false)) // 28: under a footer
        assertEquals(6, TranscriptSpacing.units(TranscriptSpacing.Edge.NONE, TranscriptSpacing.Edge.NONE, sameRun = false)) // 24: footer, then a header
    }

    @Test
    fun `a supervisor is a distinct sender from an agent, so a barge-in is visible`() {
        val rows = TranscriptLayout.rows(listOf(msg("agent", Direction.INBOUND, seq = 1), msg("supervisor", Direction.INBOUND, ParticipantRole.SUPERVISOR, 2)))
        assertEquals(listOf(true, true), rows.map { it.showsSenderName })
        assertEquals(Sender.SUPERVISOR, TranscriptLayout.sender(rows[1].message))
    }

    @Test
    fun `only the newest outbound message shows its delivery state, earlier ones only if failed`() {
        val rows =
            TranscriptLayout.rows(
                listOf(
                    msg("old", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 1, DeliveryState.READ),
                    msg("failed", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.FAILED),
                    msg("newest", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 3, DeliveryState.SENT),
                ),
            )
        assertEquals(listOf(false, true, true), rows.map { it.showsDeliveryState })
        assertTrue(rows[1].isRetryable)
        assertFalse(rows[2].isRetryable)
    }

    @Test
    fun `an announcement is queued only for an inbound message that is new`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.MessageReceived(msg("hello", Direction.INBOUND, seq = 1)))
        assertEquals(listOf(Sender.SUPPORT), model.s.announcements.map(TranscriptLayout::sender))
        model.clearAnnouncement()
        model.ingest(ContactEvent.MessageReceived(msg("mine", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 2, DeliveryState.SENT)))
        assertTrue(model.s.announcements.isEmpty()) // the user just typed it
    }

    @Test
    fun `the status line shows only while not connected, and an ended contact cannot send`() {
        val model = ChatTranscriptModel()
        assertNull(model.s.statusLine)
        model.ingest(ContactEvent.StateChanged(ContactState.Connecting))
        assertEquals(StatusLine.CONNECTING, model.s.statusLine)
        // As web: a drop says so in its notice, not in a reconnecting line.
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(2)))
        assertNull(model.s.statusLine)
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        assertNull(model.s.statusLine)
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.AGENT_ENDED)))
        assertEquals(StatusLine.ENDED, model.s.statusLine)
        assertFalse(model.s.canSend)
    }

    @Test
    fun `canSend follows the contact - composing while reconnecting is what the outbox is for`() {
        val model = ChatTranscriptModel()
        assertFalse(model.s.canSend)
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        assertTrue(model.s.canSend)
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        assertTrue(model.s.canSend)
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.CUSTOMER_ENDED)))
        assertFalse(model.s.canSend)
    }

    @Test
    fun `a delivery change replaces the row in place by client id`() {
        val model = ChatTranscriptModel()
        val id = UUID.randomUUID()
        model.ingest(ContactEvent.MessageReceived(msg("mine", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING, id)))
        model.ingest(ContactEvent.DeliveryStateChanged(DeliveryUpdate(id, DeliveryState.SENT)))
        assertEquals(1, model.s.messages.size)
        assertEquals(DeliveryState.SENT, model.s.messages[0].deliveryState)
    }

    @Test
    fun `paging asks for earlier messages once per page and stops when the transcript is exhausted`() =
        runBlocking {
            val model = ChatTranscriptModel()
            model.setInitial(listOf(msg("newest", Direction.INBOUND, seq = 10)))
            assertFalse(model.s.canLoadEarlier)
            model.setInitial(listOf(msg("newest", Direction.INBOUND, seq = 10)), hasMore = true)
            var calls = 0
            model.loadEarlier { calls++; listOf(msg("older", Direction.INBOUND, seq = 1)) to true }
            assertEquals(listOf("older", "newest"), model.s.messages.map { it.body })
            model.loadEarlier { calls++; emptyList<Message>() to false }
            assertFalse(model.s.canLoadEarlier)
            model.loadEarlier { calls++; emptyList<Message>() to false }
            assertEquals(2, calls)
        }

    @Test
    fun `a page overlapping rows the live stream delivered does not duplicate them`() =
        runBlocking {
            val model = ChatTranscriptModel()
            val shared = UUID.randomUUID()
            model.setInitial(listOf(msg("already here", Direction.INBOUND, seq = 5, id = shared)), hasMore = true)
            model.loadEarlier { listOf(msg("older", Direction.INBOUND, seq = 1), msg("already here", Direction.INBOUND, seq = 5, id = shared)) to false }
            assertEquals(listOf("older", "already here"), model.s.messages.map { it.body })
        }

    @Test
    fun `a failed page is reported and stays retryable`() =
        runBlocking {
            val model = ChatTranscriptModel()
            model.setInitial(listOf(msg("newest", Direction.INBOUND, seq = 2)), hasMore = true)
            model.loadEarlier { throw TouchpointException.PersistenceFailure("x") }
            assertTrue(model.s.pagingFailed)
            assertTrue(model.s.canLoadEarlier)
            model.loadEarlier { listOf(msg("older", Direction.INBOUND, seq = 1)) to false }
            assertFalse(model.s.pagingFailed)
        }

    @Test
    fun `reconciled history delivered after the live tail is placed in transcript order`() {
        val model = ChatTranscriptModel()
        model.ingest(ContactEvent.MessageReceived(msg("newest", Direction.INBOUND, seq = 30)))
        val echo = UUID.randomUUID()
        model.ingest(ContactEvent.MessageReceived(msg("my echo", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING, echo)))
        model.ingest(ContactEvent.MessageReceived(msg("oldest", Direction.INBOUND, seq = 10)))
        model.ingest(ContactEvent.MessageReceived(msg("middle", Direction.INBOUND, seq = 20)))
        assertEquals(listOf("oldest", "middle", "newest", "my echo"), model.s.messages.map { it.body })
    }

    @Test
    fun `after a failed first load the replayed backlog is silent, and live arrivals are announced once connected`() {
        val model = ChatTranscriptModel()
        model.initialLoadFailed()
        model.ingest(ContactEvent.MessageReceived(msg("replayed", Direction.INBOUND, seq = 1)))
        model.ingest(ContactEvent.MessageReceived(msg("replayed too", Direction.INBOUND, seq = 2)))
        assertTrue(model.s.announcements.isEmpty())
        assertEquals(2, model.s.messages.size)
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.MessageReceived(msg("live", Direction.INBOUND, seq = 3)))
        assertEquals(1, model.s.announcements.size)
    }

    @Test
    fun `a replayed or reconciled backlog is not read aloud`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.MessageReceived(msg("newest", Direction.INBOUND, seq = 30)))
        assertEquals(1, model.s.announcements.size)
        model.clearAnnouncement()
        model.ingest(ContactEvent.MessageReceived(msg("older", Direction.INBOUND, seq = 10)))
        model.ingest(ContactEvent.MessageReceived(msg("middle", Direction.INBOUND, seq = 20)))
        assertTrue(model.s.announcements.isEmpty())
        val known = UUID.randomUUID()
        model.ingest(ContactEvent.MessageReceived(msg("known", Direction.INBOUND, seq = 40, id = known)))
        model.clearAnnouncement()
        model.ingest(ContactEvent.MessageReceived(msg("known", Direction.INBOUND, seq = 40, id = known)))
        assertTrue(model.s.announcements.isEmpty())
    }

    @Test
    fun `a reply is announced even when an unacknowledged echo sits at the tail`() {
        val model = ChatTranscriptModel()
        model.setInitial(listOf(msg("earlier", Direction.INBOUND, seq = 5)))
        model.ingest(ContactEvent.MessageReceived(msg("my message", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        model.ingest(ContactEvent.MessageReceived(msg("agent reply", Direction.INBOUND, seq = 6)))
        assertEquals(listOf(Sender.SUPPORT), model.s.announcements.map(TranscriptLayout::sender))
        assertEquals(listOf("earlier", "agent reply", "my message"), model.s.messages.map { it.body })
    }

    @Test
    fun `an acknowledged echo leaves the unsequenced tail, so a later reply lands after it`() {
        val model = ChatTranscriptModel()
        val mine = UUID.randomUUID()
        model.setInitial(listOf(msg("earlier", Direction.INBOUND, seq = 5)))
        model.ingest(ContactEvent.MessageReceived(msg("my message", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING, mine)))
        model.ingest(ContactEvent.DeliveryStateChanged(DeliveryUpdate(mine, DeliveryState.SENT, MessageId("s6"), 6)))
        model.ingest(ContactEvent.MessageReceived(msg("agent reply", Direction.INBOUND, seq = 7)))
        assertEquals(listOf("earlier", "my message", "agent reply"), model.s.messages.map { it.body })
        assertEquals(6L, model.s.messages[1].sequence)
        assertEquals(MessageId("s6"), model.s.messages[1].serverMessageId)
    }

    @Test
    fun `two arrivals in one frame are both announced, identical or not, and an empty queue drains harmlessly`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.MessageReceived(msg("one", Direction.INBOUND, seq = 1)))
        model.ingest(ContactEvent.MessageReceived(msg("two", Direction.INBOUND, seq = 2)))
        assertEquals(listOf("one", "two"), model.s.announcements.map { it.body })
        model.clearAnnouncement()
        model.clearAnnouncement()
        model.clearAnnouncement()
        assertTrue(model.s.announcements.isEmpty())
    }

    // Android-specific: the mocks' error banner and choice buttons

    @Test
    fun `a connection failure raises the error row until the contact is connected again, or the user dismisses it`() {
        val model = ChatTranscriptModel()
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        model.ingest(ContactEvent.Failed(TouchpointException.TransportFailure("x")))
        assertTrue(model.s.problem)
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        assertFalse(model.s.problem)
        model.ingest(ContactEvent.StateChanged(ContactState.Connecting))
        model.ingest(ContactEvent.Failed(TouchpointException.HttpFailure(503, null)))
        assertTrue(model.s.problem)
        model.dismissProblem()
        assertFalse(model.s.problem)
    }

    @Test
    fun `a failure that is not the connection's raises no row - web's row says the connection was lost`() {
        val model = ChatTranscriptModel()
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        // While connected: a reconcile or flush error is the session's to retry, a send's shows on the message.
        model.ingest(ContactEvent.Failed(TouchpointException.TransportFailure("reconcile")))
        assertFalse(model.s.problem)
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        // A store error is the host's to log, connected or not.
        model.ingest(ContactEvent.Failed(TouchpointException.PersistenceFailure("disk")))
        assertFalse(model.s.problem)
    }

    @Test
    fun `the newest inbound interactive message offers its choices, and only while it is the newest`() {
        val model = ChatTranscriptModel()
        val raw = """{"templateType":"QuickReply","data":{"content":{"title":"How can we help?","elements":[{"title":"Check booking status"},{"title":"Cancel booking"},{"title":""}]}}}"""
        val quick =
            Message(
                StoredMessage(
                    UUID.randomUUID(), "q1", "How can we help?", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = 1,
                    deliveryState = DeliveryState.DELIVERED, interactiveJson = InteractiveContent.encode(InteractiveContent("QuickReply", raw)),
                ),
            )
        model.ingest(ContactEvent.MessageReceived(quick))
        // A choice with no title is kept, to be numbered as web numbers it, under web's id.
        assertEquals(listOf(Choice("Check booking status", "Check booking status"), Choice("Cancel booking", "Cancel booking"), Choice("choice-2", null)), model.s.choices)
        model.ingest(ContactEvent.MessageReceived(msg("Check booking status", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertTrue(model.s.choices.isEmpty())
    }

    private fun interactive(template: String, raw: String, seq: Long = 1) =
        Message(
            StoredMessage(
                UUID.randomUUID(), "i$seq", "t", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = seq,
                deliveryState = DeliveryState.DELIVERED, interactiveJson = InteractiveContent.encode(InteractiveContent(template, raw)),
            ),
        )

    @Test
    fun `choices as web reads them - quick reply and list picker, web's own template, JSON messages, and nothing else`() {
        val picker = """{"data":{"content":{"title":"Pick","elements":null,"replies":[{"title":"Red"},{"title":"Blue"}]}}}"""
        assertEquals(listOf(Choice("Red", "Red"), Choice("Blue", "Blue")), TranscriptLayout.choices(interactive("ListPicker", picker)))
        val modality = """{"data":{"content":{"modalityPayload":{"type":"message","message":"Which?","choices":[{"choiceId":"c1","choiceText":"Rewards"},{"choiceText":"Debit"}]}}}}"""
        assertEquals(listOf(Choice("c1", "Rewards"), Choice("choice-1", "Debit")), TranscriptLayout.choices(interactive("TouchpointModality", modality)))
        val json = """{"messages":[{"text":"a","choices":[{"id":"x","text":"X"}]},{"text":"b","choices":[{"choiceId":"y","label":"Y"},{}]}]}"""
        assertEquals(listOf(Choice("y", "Y"), Choice("choice-1", null)), TranscriptLayout.choices(interactive(com.amazon.connect.touchpoint.chat.JSON_MESSAGE, json)))
        val panel = """{"data":{"content":{"title":"Info","elements":[{"title":"Not a choice"}]}}}"""
        assertTrue(TranscriptLayout.choices(interactive("Panel", panel)).isEmpty())
        // Bounded: a payload is not a reason to build thousands of rows.
        val many = """{"data":{"content":{"elements":[${(1..5_000).joinToString(",") { "{}" }}]}}}"""
        assertEquals(100, TranscriptLayout.choices(interactive("QuickReply", many)).size)
    }

    @Test
    fun `an answer carries no status row, and takes the one under the customer's last text too - as web`() {
        val marker = InteractiveContent.encode(InteractiveContent("TouchpointModality", ""))
        val text = msg("my card", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 2, DeliveryState.READ)
        val answer = Message(StoredMessage(UUID.randomUUID(), "s3", "Rewards", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 3, deliveryState = DeliveryState.SENT, interactiveJson = marker))
        assertEquals(listOf(false, false), TranscriptLayout.rows(listOf(text, answer)).map { it.showsDeliveryState })
        // A failed answer still says so, with Try again.
        val failed = Message(StoredMessage(UUID.randomUUID(), null, "Rewards", Direction.OUTBOUND, ParticipantRole.CUSTOMER, deliveryState = DeliveryState.FAILED, interactiveJson = marker))
        assertEquals(listOf(false, true), TranscriptLayout.rows(listOf(text, failed)).map { it.showsDeliveryState })
    }

    @Test
    fun `an answer is the customer's turn - the choices go, and Thinking follows, as web`() {
        val model = ChatTranscriptModel()
        model.setInitial(listOf(interactive("QuickReply", """{"data":{"content":{"elements":[{"title":"Yes"}]}}}""")))
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        assertEquals(listOf(Choice("Yes", "Yes")), model.s.choices)
        val marker = InteractiveContent.encode(InteractiveContent("TouchpointModality", ""))
        model.ingest(
            ContactEvent.MessageReceived(
                Message(StoredMessage(UUID.randomUUID(), null, "Yes", Direction.OUTBOUND, ParticipantRole.CUSTOMER, deliveryState = DeliveryState.SENDING, interactiveJson = marker)),
            ),
        )
        assertTrue(model.s.choices.isEmpty())
        assertTrue(model.s.awaitingReply)
    }

    @Test
    fun `a malformed interactive payload offers no choices rather than failing`() {
        val bad =
            Message(
                StoredMessage(
                    UUID.randomUUID(), "q1", "", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = 1, deliveryState = DeliveryState.DELIVERED,
                    interactiveJson = InteractiveContent.encode(InteractiveContent("QuickReply", "{")),
                ),
            )
        assertTrue(TranscriptLayout.choices(bad).isEmpty())
    }

    @Test
    fun `thinking follows the customer's send until a reply, the end, or a person taking over`() {
        val model = ChatTranscriptModel()
        model.setInitial(listOf(msg("Hi! How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1)))
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        assertFalse(model.s.awaitingReply)
        model.ingest(ContactEvent.MessageReceived(msg("my card", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertTrue(model.s.awaitingReply)
        model.ingest(ContactEvent.MessageReceived(msg("Which card?", Direction.INBOUND, ParticipantRole.ASSISTANT, 3)))
        assertFalse(model.s.awaitingReply)

        // A send that fails is not awaiting anything.
        val failing = UUID.randomUUID()
        model.ingest(ContactEvent.MessageReceived(msg("it failed", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING, failing)))
        assertTrue(model.s.awaitingReply)
        model.ingest(ContactEvent.DeliveryStateChanged(DeliveryUpdate(failing, DeliveryState.FAILED)))
        assertFalse(model.s.awaitingReply)

        model.ingest(ContactEvent.MessageReceived(msg("the rewards one", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.AGENT_ENDED)))
        assertFalse(model.s.awaitingReply)

        // A person answering types rather than thinks: web shows no "Thinking..." then.
        val withAgent = ChatTranscriptModel()
        withAgent.setInitial(emptyList())
        withAgent.ingest(ContactEvent.StateChanged(ContactState.Connected))
        withAgent.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(Participant("a1", ParticipantRole.AGENT, "Sam"))))
        withAgent.ingest(ContactEvent.MessageReceived(msg("hello?", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertTrue(withAgent.s.humanAgentActive)
        assertFalse(withAgent.s.awaitingReply)
    }

    @Test
    fun `an agent who has left is still named by what the store kept, and a new contact starts afresh`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList(), names = mapOf("a1" to "Sam"))
        assertEquals("Sam", model.s.participantNames["a1"])
        model.setInitial(emptyList(), newContact = true)
        assertTrue(model.s.participantNames.isEmpty())
    }

    @Test
    fun `the session's notices are kept once each, where they happened, and a first page drops them`() {
        val model = ChatTranscriptModel()
        val earlier = msg("earlier", Direction.INBOUND, seq = 1)
        model.setInitial(listOf(earlier))
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        val joined = TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, earlier.clientMessageId)
        model.ingest(ContactEvent.Notice(joined))
        model.ingest(ContactEvent.Notice(joined)) // a re-subscription replays it
        model.ingest(ContactEvent.Notice(TranscriptNotice(TranscriptNotice.Kind.AGENT_LEFT, null)))
        assertEquals(listOf(TranscriptNotice.Kind.AGENT_JOINED, TranscriptNotice.Kind.AGENT_LEFT), model.s.notices.map { it.kind })
        assertEquals(earlier.clientMessageId, model.s.notices.first().afterMessageId)
        assertTrue(model.s.notices.none { it.kind == TranscriptNotice.Kind.CONNECTION_LOST })

        // A first page is a new conversation: nothing carries over.
        model.setInitial(emptyList())
        assertTrue(model.s.notices.isEmpty())
    }

    @Test
    fun `a drop shows web's failure once - its notice - not again for each attempt to reconnect`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.Notice(TranscriptNotice(TranscriptNotice.Kind.CONNECTION_LOST, null)))
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        model.ingest(ContactEvent.Failed(TouchpointException.TransportFailure("refused")))
        assertFalse(model.s.problem)
        assertEquals(1, model.s.notices.size)
        // Back, then down again without a notice — a connection never made — and the row is the problem's.
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        model.ingest(ContactEvent.Failed(TouchpointException.TransportFailure("refused")))
        assertTrue(model.s.problem)
    }

    @Test
    fun `a supervisor barging in is not an agent joining - as web, notices and agent rules are the agent's`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        val agent = Participant("a1", ParticipantRole.AGENT, "Sam")
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(agent)))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(agent).join(Participant("s1", ParticipantRole.SUPERVISOR, "Lee"))))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(agent)))
        assertTrue(model.s.notices.isEmpty())

        val supervised = ChatTranscriptModel()
        supervised.setInitial(emptyList())
        supervised.ingest(ContactEvent.StateChanged(ContactState.Connected))
        supervised.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(Participant("s1", ParticipantRole.SUPERVISOR, "Lee"))))
        assertFalse(supervised.s.humanAgentActive)
    }

    @Test
    fun `a restart's new contact starts with no agent, and the old one's is not reported leaving`() {
        val model = ChatTranscriptModel()
        val customer = Participant("c1", ParticipantRole.CUSTOMER, "Me")
        model.setInitial(emptyList())
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(customer)))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(customer).join(Participant("a1", ParticipantRole.AGENT, "Sam"))))
        assertTrue(model.s.humanAgentActive)
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.CUSTOMER_ENDED)))

        // The session resets its participants for the new contact without an event.
        model.ingest(ContactEvent.StateChanged(ContactState.Connecting))
        model.setInitial(emptyList(), newContact = true)
        assertFalse(model.s.humanAgentActive)
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(customer.copy(id = "c2"))))
        assertTrue(model.s.notices.isEmpty())
        model.ingest(ContactEvent.MessageReceived(msg("help", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertTrue(model.s.awaitingReply) // "Thinking..." again: no agent on this contact
    }

    @Test
    fun `typing replaces thinking, lapses unless reported again, and a reply clears every interim line - as web's one interim line`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.MessageReceived(msg("help", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertTrue(model.s.awaitingReply)
        model.ingest(ContactEvent.Typing(ParticipantRole.AGENT))
        assertTrue(model.s.typing)
        assertFalse(model.s.awaitingReply) // web shows one interim line; typing takes thinking's place
        val first = model.s.typingTick
        model.ingest(ContactEvent.Typing(ParticipantRole.AGENT))
        model.typingLapsed(first) // the first report's lapse: a newer one has come since
        assertTrue(model.s.typing)
        model.typingLapsed(model.s.typingTick)
        assertFalse(model.s.typing)

        model.ingest(ContactEvent.InterimMessage("Looking that up…"))
        assertEquals("Looking that up…", model.s.interimText)
        model.ingest(ContactEvent.Typing(ParticipantRole.ASSISTANT))
        model.ingest(ContactEvent.MessageReceived(msg("Found it.", Direction.INBOUND, ParticipantRole.ASSISTANT, 2)))
        assertFalse(model.s.typing)
        assertNull(model.s.interimText)

        // Typing replaces the assistant's line, so a lapse leaves nothing — not the old line again.
        model.ingest(ContactEvent.InterimMessage("Transferring you…"))
        model.ingest(ContactEvent.Typing(ParticipantRole.AGENT))
        assertNull(model.s.interimText)
        model.typingLapsed(model.s.typingTick)
        assertFalse(model.s.typing)
        assertNull(model.s.interimText)

        // A send puts "Thinking..." in place of typing or the assistant's line, at once.
        model.ingest(ContactEvent.InterimMessage("Looking that up…"))
        model.ingest(ContactEvent.MessageReceived(msg("hello?", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertNull(model.s.interimText)
        assertTrue(model.s.awaitingReply)
        model.ingest(ContactEvent.Typing(ParticipantRole.ASSISTANT))
        model.ingest(ContactEvent.MessageReceived(msg("and?", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertFalse(model.s.typing)
        assertTrue(model.s.awaitingReply)
        // An interim line with no words clears the line.
        model.ingest(ContactEvent.InterimMessage("Checking…"))
        model.ingest(ContactEvent.InterimMessage(""))
        assertNull(model.s.interimText)

        model.ingest(ContactEvent.Typing(ParticipantRole.AGENT))
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.AGENT_ENDED)))
        assertFalse(model.s.typing)
        model.ingest(ContactEvent.Typing(ParticipantRole.AGENT)) // nothing types into an ended chat
        assertFalse(model.s.typing)
    }

    @Test
    fun `the state's toString carries no words`() {
        val state = TranscriptState(messages = listOf(msg("my card number", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 1)), interimText = "Looking up your balance")
        assertFalse("card" in state.toString())
        assertFalse("balance" in state.toString())
    }

    @Test
    fun `the welcome screen lasts until the customer first sends`() {
        val model = ChatTranscriptModel()
        assertTrue(model.s.welcome)
        model.setInitial(listOf(msg("Hi! How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1)))
        model.ingest(ContactEvent.MessageReceived(msg("More from us", Direction.INBOUND, ParticipantRole.ASSISTANT, 2)))
        assertTrue(model.s.welcome)
        model.ingest(ContactEvent.MessageReceived(msg("hello", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING)))
        assertFalse(model.s.welcome)
    }

    @Test
    fun `a slot the customer filled, or a view answered, draws no bubble, as web's draw none`() {
        val slot = Message(StoredMessage(UUID.randomUUID(), null, "harbour", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 2, deliveryState = DeliveryState.SENT, interactiveJson = InteractiveContent.encode(InteractiveContent(InteractiveContent.SLOT_TEMPLATE, """{"slotId":"Charge"}"""))))
        val view = Message(StoredMessage(UUID.randomUUID(), null, "{}", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 3, deliveryState = DeliveryState.SENT, interactiveJson = InteractiveContent.encode(InteractiveContent(InteractiveContent.VIEW_RESPONSE_TEMPLATE, """{"action":"Submit"}"""))))
        val rows = TranscriptLayout.rows(listOf(msg("Which charge?", Direction.INBOUND, seq = 1), slot, view, msg("Thanks", Direction.INBOUND, seq = 4)))
        assertEquals(listOf("Which charge?", "Thanks"), rows.map { it.message.body })
    }

    @Test
    fun `a greeting with cards or a guide is the transcript's, not the welcome's`() {
        val model = ChatTranscriptModel()
        val cards = InteractiveContent(InteractiveContent.CHOICE_TEMPLATE, """{"data":{"content":{"modalityPayload":{"modalities":{"DefaultCard":{"id":"a"}}}}}}""")
        model.setInitial(listOf(Message(StoredMessage(UUID.randomUUID(), "s1", "Here you go", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = 1, deliveryState = DeliveryState.DELIVERED, interactiveJson = InteractiveContent.encode(cards)))))
        assertFalse(model.s.welcome)
    }

    @Test
    fun `a pending sign-in card holds the composer and leaves the welcome, and lets go once it resolves or the chat ends`() {
        val model = ChatTranscriptModel()
        model.setInitial(listOf(msg("Let's get you signed in.", Direction.INBOUND, ParticipantRole.ASSISTANT, 1)))
        model.ingest(ContactEvent.StateChanged(ContactState.Connected))
        model.ingest(ContactEvent.InterimMessage("One moment"))
        val id = UUID.randomUUID()
        model.ingest(ContactEvent.AuthenticationChanged(AuthenticationCard(id, AuthenticationStatus.PROMPT, null)))
        assertTrue(model.s.composerLocked)
        assertFalse(model.s.welcome)
        assertNull(model.s.interimText) // web clears its interim line on every sign-in event
        model.ingest(ContactEvent.AuthenticationChanged(AuthenticationCard(id, AuthenticationStatus.IN_PROGRESS, null)))
        assertTrue(model.s.composerLocked)
        model.ingest(ContactEvent.AuthenticationChanged(AuthenticationCard(id, AuthenticationStatus.SUCCESS, null)))
        assertFalse(model.s.composerLocked)
        model.ingest(ContactEvent.AuthenticationChanged(AuthenticationCard(UUID.randomUUID(), AuthenticationStatus.PROMPT, null)))
        assertTrue(model.s.composerLocked)
        model.ingest(ContactEvent.StateChanged(ContactState.Ended(EndReason.AGENT_ENDED)))
        assertFalse(model.s.composerLocked)
    }

    @Test
    fun `the banner clears when a message gets through`() {
        val model = ChatTranscriptModel()
        val id = UUID.randomUUID()
        model.ingest(ContactEvent.MessageReceived(msg("retry me", Direction.OUTBOUND, ParticipantRole.CUSTOMER, null, DeliveryState.SENDING, id)))
        model.ingest(ContactEvent.StateChanged(ContactState.Reconnecting(1)))
        model.ingest(ContactEvent.Failed(TouchpointException.TransportFailure("x")))
        assertTrue(model.s.problem)
        model.ingest(ContactEvent.DeliveryStateChanged(DeliveryUpdate(id, DeliveryState.SENT)))
        assertFalse(model.s.problem)
    }

    @Test
    fun `participant info names an agent as Connect does, even after they leave, and a new conversation forgets them`() {
        val model = ChatTranscriptModel()
        model.setInitial(emptyList())
        val agent = Participant("a1", ParticipantRole.AGENT, "Sam")
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet().join(agent).join(Participant("x", ParticipantRole.AGENT, ""))))
        model.ingest(ContactEvent.ParticipantsChanged(ParticipantSet()))
        assertEquals(mapOf("a1" to "Sam"), model.s.participantNames) // no blank names
        model.setInitial(emptyList(), newContact = true)
        assertTrue(model.s.participantNames.isEmpty())
    }

    @Test
    fun `the host hears of an end once - not the replayed end of a contact already over, nor Restart's, and again for the next one`() =
        runBlocking {
            val end = ContactEvent.ContactEnded(EndReason.AGENT_ENDED)
            val stale = EndWatch().apply { arm(alreadyEnded = true) }
            assertNull(stale.ended(ContactEvent.StateChanged(ContactState.Ended(EndReason.AGENT_ENDED))))
            assertNull(stale.ended(end))
            // A new conversation re-arms it.
            stale.ended(ContactEvent.StateChanged(ContactState.Connecting))
            assertEquals(EndReason.CUSTOMER_ENDED, stale.ended(ContactEvent.ContactEnded(EndReason.CUSTOMER_ENDED)))

            val live = EndWatch().apply { arm(alreadyEnded = false) }
            assertEquals(EndReason.AGENT_ENDED, live.ended(end))
            assertNull(live.ended(end))

            // Restart: its end is not reported, the new conversation's is.
            val restarted = EndWatch().apply { arm(alreadyEnded = false) }
            restarted.quietly { }
            assertNull(restarted.ended(ContactEvent.ContactEnded(EndReason.CUSTOMER_ENDED)))
            restarted.ended(ContactEvent.StateChanged(ContactState.Connecting))
            assertEquals(EndReason.AGENT_ENDED, restarted.ended(end))

            // A Restart whose end fails ended nothing: a later end is news.
            val refused = EndWatch().apply { arm(alreadyEnded = false) }
            runCatching { refused.quietly { throw TouchpointException.TransportFailure("offline") } }
            assertEquals(EndReason.AGENT_ENDED, refused.ended(end))
        }

    @Test
    fun `the sheet is web's half-screen panel on a tablet - half the window from md, 632 from xl - and a bottom sheet below`() {
        assertNull(sheetPanelWidth(androidx.compose.ui.unit.Dp(767f)))
        assertEquals(androidx.compose.ui.unit.Dp(400f), sheetPanelWidth(androidx.compose.ui.unit.Dp(800f)))
        assertEquals(androidx.compose.ui.unit.Dp(632f), sheetPanelWidth(androidx.compose.ui.unit.Dp(1280f)))
    }

    @Test
    fun `a file the customer sends takes the delivery row away, as web draws it`() {
        val text = msg("hi", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 1, DeliveryState.DELIVERED)
        val file = Message(StoredMessage(UUID.randomUUID(), "s2", "photo.png", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 2, deliveryState = DeliveryState.SENT, attachmentId = "att-1"))
        val rows = TranscriptLayout.rows(listOf(text, file))
        assertFalse(rows.any { it.showsDeliveryState })
    }

    @Test
    fun `mini is web's floating card from 768 wide - 420 wide, the height less 16, at most 1080 - and a compact card on a phone`() {
        fun size(w: Float, h: Float) = floatingSize(androidx.compose.ui.unit.Dp(w), androidx.compose.ui.unit.Dp(h))
        fun dp(w: Float, h: Float) = androidx.compose.ui.unit.DpSize(androidx.compose.ui.unit.Dp(w), androidx.compose.ui.unit.Dp(h))
        assertEquals(dp(396f, 411.75f), size(412f, 915f))
        assertEquals(dp(420f, 1080f), size(1024f, 1366f))
        assertEquals(dp(420f, 988f), size(1366f, 1004f))
        assertEquals(dp(420f, 884f), size(768f, 900f))
        assertEquals(dp(420f, 405f), size(767f, 900f))
        assertEquals(dp(0f, 0f), size(0f, 0f))
    }

    @Test
    fun `the sheet's sizes - half is a bottom sheet on a phone and web's panel on a tablet, and the default is full on a phone`() {
        val phone = androidx.compose.ui.unit.Dp(412f)
        val tablet = androidx.compose.ui.unit.Dp(1280f)
        assertEquals(SheetForm.Bottom, sheetForm(phone, TouchpointWindowSize.HALF))
        assertEquals(SheetForm.Panel(androidx.compose.ui.unit.Dp(632f)), sheetForm(tablet, TouchpointWindowSize.HALF))
        assertEquals(SheetForm.Full, sheetForm(phone, TouchpointWindowSize.FULL))
        assertEquals(SheetForm.Full, sheetForm(tablet, TouchpointWindowSize.FULL))
        assertEquals(SheetForm.Mini, sheetForm(phone, TouchpointWindowSize.MINI))
        assertEquals(SheetForm.Full, sheetForm(phone, null))
        assertEquals(SheetForm.Panel(androidx.compose.ui.unit.Dp(632f)), sheetForm(tablet, null))
        // A host is told a size, never null: the one the default looks like there, so mini can expand back to it.
        assertEquals(TouchpointWindowSize.FULL, defaultWindowSize(phone))
        assertEquals(TouchpointWindowSize.HALF, defaultWindowSize(tablet))
        for (w in listOf(phone, tablet)) assertEquals(sheetForm(w, null), sheetForm(w, defaultWindowSize(w)))
    }
}
