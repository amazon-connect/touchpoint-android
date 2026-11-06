package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.AuthenticationCard
import com.amazon.connect.touchpoint.core.Citation
import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.ParticipantSet
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.DeliveryUpdate
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TranscriptNotice
import com.amazon.connect.touchpoint.core.modalities
import com.amazon.connect.touchpoint.core.viewSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.amazon.connect.touchpoint.chat.JSON_MESSAGE
import java.util.UUID

/** A choice an interactive message offers: its id, and its words — `null` for one with none, which the view numbers as web does. */
internal data class Choice(val id: String, val text: String?)

/** Who a row is from, as the UI names them. Resolved to text from resources by the view. */
internal enum class Sender { YOU, SUPPORT, SUPERVISOR }

/**
 * A status worth a line only while something is *not* normal: connected shows nothing. Reconnecting
 * has none either, as on web: the drop shows as its "Connection lost" notice.
 */
internal enum class StatusLine { CONNECTING, ENDED }

/**
 * One row of the transcript: the message plus the presentation-only facts derived from its
 * neighbours. Pure, so it is testable without a view.
 *
 * @property showsSenderName First of a run from one sender: the name is shown once per run, and a
 *   run's later messages sit close under it, the response gap kept for a change of sender.
 * @property showsDeliveryState For the newest outbound message, and for any failed one. A tick on
 *   every bubble is noise; hiding a failure is a lie.
 */
internal data class TranscriptRow(
    val message: Message,
    val showsSenderName: Boolean,
    val showsDeliveryState: Boolean,
) {
    val id: UUID get() = message.clientMessageId
    val isRetryable: Boolean get() = message.deliveryState == DeliveryState.FAILED
}

/**
 * The design guide's transcript rhythm: spacing between items, plus each item's own vertical padding,
 * so a kind of element can be corrected optically on its own. In 4dp units.
 * - Spacing: 4 within one sender's run, 24 between senders (and around a notice, the error, the end).
 * - Padding: a text message 4, a modality (cards, a carousel, an input, a guide) 8, and none at a
 *   participant header (avatar and name) above a message or a footer (time, receipt) under it.
 * So text to text in a run is 12, text to a modality 16, a modality to a modality 20; a customer's
 * message to the assistant's is 32, 28 under a footer, and 24 when a header follows too.
 */
internal object TranscriptSpacing {
    enum class Edge(val units: Int) { NONE(0), TEXT(1), MODALITY(2) }

    private const val RUN = 1
    private const val TURN = 6

    fun units(above: Edge, below: Edge, sameRun: Boolean): Int = above.units + (if (sameRun) RUN else TURN) + below.units
}

internal object TranscriptLayout {
    fun rows(all: List<Message>): List<TranscriptRow> {
        // A slot a modality filled draws no bubble, as web draws none: the modality shows the pick.
        // A filled slot and an answered view draw no customer bubble, as web's draw none.
        val messages = all.filter { it.interactive?.templateType.let { t -> t != InteractiveContent.SLOT_TEMPLATE && t != InteractiveContent.VIEW_RESPONSE_TEMPLATE } }
        // As web: the status shows under the customer's latest turn — and when that turn is an answer
        // to a choice, which web draws without one, under nothing.
        val latest = messages.indexOfLast { it.direction == Direction.OUTBOUND }
        // A file, which web draws without one too, the same.
        val lastOutbound = if (latest >= 0 && (isAnswer(messages[latest]) || messages[latest].attachment != null)) -1 else latest
        return messages.mapIndexed { i, m ->
            TranscriptRow(
                message = m,
                // Judged against the row drawn above it: an answer that draws no bubble (a card picked, a
                // date sent) shows in its modality, so the reply after it still reads as the same run.
                showsSenderName = i == 0 || !sameSender(m, messages[i - 1]),
                showsDeliveryState = m.direction == Direction.OUTBOUND && (m.deliveryState == DeliveryState.FAILED || i == lastOutbound),
            )
        }
    }

    private fun isAnswer(m: Message): Boolean = m.interactive?.templateType == InteractiveContent.CHOICE_TEMPLATE && m.deliveryState != DeliveryState.FAILED

    /** Same side *and* same role: a supervisor barging in is a new sender, not part of the agent's run. */
    private fun sameSender(a: Message, b: Message): Boolean = a.direction == b.direction && a.participantRole == b.participantRole

    fun sender(m: Message): Sender =
        when {
            m.direction == Direction.OUTBOUND -> Sender.YOU
            m.participantRole == ParticipantRole.SUPERVISOR -> Sender.SUPERVISOR
            else -> Sender.SUPPORT
        }

    /**
     * The choices a message offers, as web reads them (connect/conversation.ts):
     * - a quick reply or list picker: its elements, else its replies — id and words its title, or
     *   `choice-N` and none;
     * - web's own template, an assistant's message: its `choices`, by `choiceId` and `choiceText`;
     * - a JSON message: the last of its messages with choices, by `choiceId` / `id` and
     *   `choiceText` / `text` / `label`.
     * Any other message offers none. Bounded at [MAX_CHOICES]: a payload is not a reason to jank.
     */
    fun choices(m: Message): List<Choice> {
        val interactive = m.interactive ?: return emptyList()
        if (interactive.rawJson.isEmpty()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(interactive.rawJson).jsonObject
            when (interactive.templateType) {
                "QuickReply", "ListPicker" -> {
                    val content = root.obj("data")?.obj("content")
                    (content?.array("elements") ?: content?.array("replies"))?.mapIndexed { i, el ->
                        val title = (el as? JsonObject)?.string("title")?.takeIf { it.isNotBlank() }
                        Choice(title ?: "choice-$i", title)
                    }
                }
                InteractiveContent.CHOICE_TEMPLATE -> {
                    val payload = root.obj("data")?.obj("content")?.obj("modalityPayload")
                    if (payload?.string("type") != "message") {
                        null
                    } else {
                        payload.array("choices")?.mapIndexed { i, c ->
                            val o = c as? JsonObject
                            Choice(o?.string("choiceId") ?: "choice-$i", o?.string("choiceText"))
                        }
                    }
                }
                JSON_MESSAGE -> {
                    val last = root.array("messages")?.lastOrNull { (it as? JsonObject)?.array("choices")?.isNotEmpty() == true } as? JsonObject
                    last?.array("choices")?.mapIndexed { i, c ->
                        val o = c as? JsonObject
                        Choice(o?.string("choiceId") ?: o?.string("id") ?: "choice-$i", o?.string("choiceText") ?: o?.string("text") ?: o?.string("label"))
                    }
                }
                else -> null
            }.orEmpty().take(MAX_CHOICES)
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    /** An array, or `null` — a JSON `null` too, so `"elements": null` falls back as web's `??` does. */
    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val MAX_CHOICES = 100

    private val json = Json { ignoreUnknownKeys = true }
}

/**
 * What the transcript shows, as one immutable value. The view renders it; nothing here touches a
 * resource or a widget.
 *
 * @property announcements Spoken by TalkBack, oldest first. A queue, not a slot: two messages
 *   arriving in one frame would otherwise collapse into one announcement.
 * @property problem The connection failed (web's failure row), until a message gets through, the
 *   contact is connected again, or the user dismisses it.
 */
internal data class TranscriptState(
    val messages: List<Message> = emptyList(),
    val announcements: List<Message> = emptyList(),
    val contactState: ContactState = ContactState.Idle,
    val canLoadEarlier: Boolean = false,
    val pagingFailed: Boolean = false,
    val initialLoaded: Boolean = false,
    val problem: Boolean = false,
    /** A human agent is in the conversation: web disables Restart while one is and the chat has not ended. */
    val humanAgentActive: Boolean = false,
    /** Web's "Thinking…": the customer has sent and no reply has come yet. Not while a person is answering. */
    val awaitingReply: Boolean = false,
    /** Web's notices — "Agent has joined", "Agent has left", "Connection lost" — each placed after the message it followed. */
    val notices: List<TranscriptNotice> = emptyList(),
    /** Web's "Typing...": someone on the other side is typing; lapses five seconds after the last word of it. */
    val typing: Boolean = false,
    /** Counts typing reports, so a lapse timer knows whether a newer one has come since it started. */
    val typingTick: Int = 0,
    /** An assistant's interim line ("Looking that up…"), until the next message. */
    val interimText: String? = null,
    /** Who is who, by participant id: participant info names an agent by the name Connect gives. */
    val participantNames: Map<String, String> = emptyMap(),
    /** Connect's sign-in card, when the flow asked the customer to sign in: web's one authentication notice. */
    val authenticationCard: AuthenticationCard? = null,
    /** The session's contact is a call, not this chat: nothing is on its way here, so no "Thinking...". */
    val inCall: Boolean = false,
) {
    val ended: Boolean get() = contactState is ContactState.Ended

    /** Content stays out of logs and test failures: counts and flags only. */
    override fun toString(): String =
        "TranscriptState(messages=${messages.size}, announcements=${announcements.size}, contactState=$contactState, problem=$problem, " +
            "humanAgentActive=$humanAgentActive, awaitingReply=$awaitingReply, notices=${notices.size}, typing=$typing, interim=${interimText != null}, " +
            "authentication=${authenticationCard?.status})"

    /**
     * Web's welcome screen: until the customer first sends, the greeting is shown centred and larger,
     * not as a transcript; before the greeting arrives there is only the loader. The surface also
     * leaves it for anything else in the conversation — a notice, a failure, earlier pages.
     */
    val welcome: Boolean
        get() =
            messages.none { it.direction == Direction.OUTBOUND } && authenticationCard == null &&
                // The welcome draws words: cards, a video or a guide are the transcript's to show.
                messages.none { m -> m.interactive?.let { it.modalities.isNotEmpty() || it.viewSummary != null } == true }

    val rows: List<TranscriptRow> get() = TranscriptLayout.rows(messages)

    val statusLine: StatusLine?
        get() =
            when (contactState) {
                ContactState.Connecting -> StatusLine.CONNECTING
                is ContactState.Ended -> StatusLine.ENDED
                ContactState.Idle, ContactState.Connected, ContactState.Suspended, is ContactState.Reconnecting -> null
            }

    /** Composing while reconnecting is what the outbox is for: only an ended, or unstarted, contact disables it. */
    /** A contact to send to; a call is not one: it carries no messages. */
    val canSend: Boolean
        get() = !inCall && (contactState == ContactState.Connected || contactState == ContactState.Connecting || contactState is ContactState.Reconnecting)

    /** Web disables the input while sign-in is prompting or in progress. */
    val composerLocked: Boolean get() = !ended && authenticationCard?.status?.isPending == true

    /** The choices the newest message offers, if it is an interactive one from the other side. Read once per state. */
    val choices: List<Choice> by lazy {
        messages.lastOrNull()?.takeIf { it.direction == Direction.INBOUND }?.let(TranscriptLayout::choices).orEmpty()
    }

    /** The message whose choices are on offer, if any. */
    val choiceMessageId: UUID? get() = if (choices.isEmpty()) null else messages.lastOrNull()?.clientMessageId
}

/**
 * The transcript's state machine, fed by `TouchpointSession.events`; owns no networking.
 * Called from the main thread only. One per session: the
 * view makes a new one when it is handed another session, so one contact's transcript can never show
 * under another.
 */
internal class ChatTranscriptModel {
    private val mutable = MutableStateFlow(TranscriptState())
    val state: StateFlow<TranscriptState> = mutable.asStateFlow()

    private var loadingEarlier = false

    /**
     * Highest server sequence seen. "New" is keyed off this, not tail position: an unacknowledged
     * echo is pinned to the tail, so a genuinely new reply inserts before it.
     */
    private var highestSequenceSeen: Long? = null

    /** Set by a failed first load; cleared when the contact connects, after which arrivals count as live. */
    private var announceOnceConnected = false

    private inline fun update(block: (TranscriptState) -> TranscriptState) {
        mutable.value = block(mutable.value)
    }

    /**
     * Seed with the first (newest) page; [hasMore] decides whether "load earlier" is offered.
     * [newContact]: the page is another contact's than the last one loaded — a restart — so who was on
     * the old one does not carry over either (the session resets its participants without an event).
     * [names]: who has been on the contact, from the store, so an agent who has left is still named.
     */
    fun setInitial(messages: List<Message>, hasMore: Boolean = false, newContact: Boolean = false, names: Map<String, String> = emptyMap()) {
        highestSequenceSeen = messages.mapNotNull { it.sequence }.maxOrNull()
        lostSinceConnected = false
        // A first page is a conversation's start — a restart's new contact included: nothing carries over.
        update {
            it.copy(
                messages = messages, canLoadEarlier = hasMore, pagingFailed = false, initialLoaded = true, notices = emptyList(), awaitingReply = false,
                humanAgentActive = it.humanAgentActive && !newContact, typing = false, interimText = null,
                participantNames = (if (newContact) emptyMap() else it.participantNames) + names,
                authenticationCard = it.authenticationCard.takeUnless { newContact },
            )
        }
    }

    /**
     * The first page could not be read: reported like a later failure, so a blank transcript is never
     * mistaken for an empty conversation. The replayed backlog that follows is history, not news, so
     * nothing is announced until the contact connects.
     */
    fun initialLoadFailed() {
        announceOnceConnected = true
        update { it.copy(pagingFailed = true) }
    }

    fun ingest(event: ContactEvent) {
        when (event) {
            is ContactEvent.MessageReceived -> {
                val m = event.message
                // Announce only a message that arrives as the newest row: reconciliation and the
                // session's catch-up deliver history through this case too, and reading a finished
                // conversation aloud on resume is worse than silence.
                val isNewestArrival = upsert(m)
                if (m.direction == Direction.INBOUND && isNewestArrival && mutable.value.initialLoaded) {
                    update { it.copy(announcements = it.announcements + m) }
                }
                // "Thinking…" follows the customer's send until the next reply — unless a person has
                // taken over, who is typing rather than thinking (web's rule).
                if (isNewestArrival) {
                    update {
                        when {
                            // A reply ends every interim line: thinking, typing, an assistant's own.
                            m.direction == Direction.INBOUND -> it.copy(awaitingReply = false, typing = false, interimText = null)
                            // As web, each send puts its line in place of whatever was there: "Thinking..."
                            // — or, while a person answers, nothing.
                            m.deliveryState == DeliveryState.SENDING -> it.copy(awaitingReply = !it.humanAgentActive, typing = false, interimText = null)
                            else -> it
                        }
                    }
                }
            }
            is ContactEvent.DeliveryStateChanged -> {
                val i = mutable.value.messages.indexOfFirst { it.clientMessageId == event.update.clientMessageId }
                if (i >= 0) replace(i, mutable.value.messages[i].applying(event.update))
                // A message getting through is the news that the problem has passed.
                if (event.update.state == DeliveryState.SENT) update { it.copy(problem = false) }
                // A send that failed is not being thought about: "Thinking..." would wait forever.
                if (event.update.state == DeliveryState.FAILED) update { it.copy(awaitingReply = false) }
            }
            is ContactEvent.StateChanged -> {
                val s = event.state
                val ended = s is ContactState.Ended
                update {
                    it.copy(
                        contactState = s, problem = it.problem && s != ContactState.Connected, awaitingReply = it.awaitingReply && !ended,
                        typing = it.typing && !ended, interimText = it.interimText.takeUnless { ended },
                    )
                }
                if (s == ContactState.Connected) lostSinceConnected = false
                if (s == ContactState.Connected && announceOnceConnected) {
                    announceOnceConnected = false
                    update { it.copy(initialLoaded = true) }
                }
            }
            // Web's failure row is the connection's: lost, or never made. A failure while connected
            // is the session's own business — a send that fails shows on its message, a store or
            // Live Sync error is the host's to log — and "Connection lost" would be untrue.
            // And once only: a drop already says "Connection lost" in its notice, and a failed attempt
            // to reconnect after it is the same outage.
            is ContactEvent.Failed ->
                if (event.error.isConnection && mutable.value.contactState != ContactState.Connected && !lostSinceConnected) update { it.copy(problem = true) }
            // A re-subscription replays them: kept once.
            is ContactEvent.Notice ->
                if (mutable.value.notices.none { it.id == event.notice.id }) {
                    if (event.notice.kind == TranscriptNotice.Kind.CONNECTION_LOST) lostSinceConnected = true
                    update { it.copy(notices = it.notices + event.notice) }
                }
            is ContactEvent.ParticipantsChanged -> participantsChanged(event.participants)
            // Web clears its interim line on every sign-in event.
            is ContactEvent.AuthenticationChanged ->
                if (mutable.value.authenticationCard != event.card) update { it.copy(authenticationCard = event.card, interimText = null) }
            // As web's one interim line, the newest wins: typing, or the assistant's line, replaces "Thinking...".
            is ContactEvent.Typing -> if (!mutable.value.ended) update { it.copy(typing = true, typingTick = it.typingTick + 1, awaitingReply = false, interimText = null) }
            // An interim line with no words clears the line, as web's does.
            is ContactEvent.InterimMessage -> if (!mutable.value.ended) update { it.copy(interimText = event.text.ifBlank { null }, typing = false, awaitingReply = false) }
            else -> Unit
        }
    }

    fun clearAnnouncement() = update { it.copy(announcements = it.announcements.drop(1)) }

    /** Five seconds after a typing report: "Typing..." goes, unless another has come since. */
    fun typingLapsed(tick: Int) = update { if (it.typingTick == tick) it.copy(typing = false) else it }

    /** Since the last connect, a drop has said "Connection lost": a failure to reconnect does not say it again. */
    private var lostSinceConnected = false

    /**
     * [TranscriptState.humanAgentActive] is web's `agentActive`, from agents alone: a supervisor
     * barging in is not an agent joining. The notices of who joined and left are the session's.
     */
    private fun participantsChanged(set: ParticipantSet) {
        val active = set.participants.any { it.role == ParticipantRole.AGENT }
        // Names are kept for those who have left: their messages still carry them.
        val names = set.participants.filter { it.displayName.isNotBlank() }.associate { it.id to it.displayName }
        update { it.copy(humanAgentActive = active, awaitingReply = it.awaitingReply && !active, participantNames = it.participantNames + names) }
    }

    fun dismissProblem() = update { it.copy(problem = false) }

    /**
     * Page further back. `more` says whether the server had an older page, so the control disappears
     * exactly once. A failure is retryable, and visible.
     */
    suspend fun loadEarlier(fetch: suspend () -> Pair<List<Message>, Boolean>) {
        if (!mutable.value.canLoadEarlier || loadingEarlier) return
        loadingEarlier = true
        try {
            val (older, more) = fetch()
            update { s ->
                // A page can overlap rows the live stream already delivered; one id, one row.
                val known = s.messages.mapTo(HashSet()) { it.clientMessageId }
                s.copy(messages = older.filter { it.clientMessageId !in known } + s.messages, canLoadEarlier = more, pagingFailed = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            update { it.copy(pagingFailed = true) }
        } finally {
            loadingEarlier = false
        }
    }

    /** Returns whether the row is newer than anything seen — the only case that is "a new message". */
    private fun upsert(m: Message): Boolean {
        val i = mutable.value.messages.indexOfFirst { it.clientMessageId == m.clientMessageId }
        if (i >= 0) {
            replace(i, m)
            return false
        }
        update { it.copy(messages = inserted(it.messages, m)) }
        val seq = m.sequence ?: return true // a fresh local echo is by definition new
        val isNew = highestSequenceSeen?.let { seq > it } ?: true
        track(seq)
        return isNew
    }

    private fun track(sequence: Long?) {
        if (sequence != null) highestSequenceSeen = maxOf(sequence, highestSequenceSeen ?: sequence)
    }

    /**
     * The one update path for a row already shown. A row whose sequence changed — an echo acquiring
     * the server's — is re-placed rather than updated where it sits, and tracked here so no caller forgets.
     */
    private fun replace(index: Int, m: Message) {
        val current = mutable.value.messages
        if (current[index].sequence != m.sequence) {
            update { it.copy(messages = inserted(current.filterIndexed { j, _ -> j != index }, m)) }
            track(m.sequence)
        } else {
            update { it.copy(messages = current.toMutableList().also { list -> list[index] = m }) }
        }
    }

    /**
     * In **transcript order**, as the store returns it: server sequence ascending, then unreconciled
     * echoes pinned to the tail in arrival order. Reconciliation delivers history after the live tail;
     * appending it would show it below newer messages.
     */
    private fun inserted(list: List<Message>, m: Message): List<Message> {
        val seq = m.sequence ?: return list + m
        val at = list.indexOfFirst { other -> other.sequence?.let { it > seq } ?: true }.takeIf { it >= 0 } ?: list.size
        return list.toMutableList().also { it.add(at, m) }
    }
}

/** A failure of the connection itself — what web reports as "Connection lost" or "Failed to connect". */
private val TouchpointException.isConnection: Boolean
    get() =
        this is TouchpointException.TransportFailure || this is TouchpointException.HttpFailure ||
            this is TouchpointException.CredentialVendingFailed || this is TouchpointException.ContactNotFound

/** `Message` is immutable by design; a delivery update rebuilds it from the stored shape. */
private fun Message.applying(update: DeliveryUpdate): Message =
    Message(
        StoredMessage(
            clientMessageId = clientMessageId,
            serverMessageId = (update.serverMessageId ?: serverMessageId)?.rawValue,
            body = body,
            direction = direction,
            participantRole = participantRole,
            participantId = participantId,
            sequence = update.sequence ?: sequence,
            createdAt = createdAt,
            deliveryState = update.state,
            interactiveJson = interactive?.let(InteractiveContent::encode),
            citationsJson = citations.takeIf { it.isNotEmpty() }?.let(Citation::encode),
            isStreaming = isStreaming,
        ),
    )
