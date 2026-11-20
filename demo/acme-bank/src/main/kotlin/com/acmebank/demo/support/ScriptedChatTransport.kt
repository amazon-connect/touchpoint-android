package com.acmebank.demo.support

import com.acmebank.demo.PartnerBrand
import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.AuthenticationRequest
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.ExpiringCredentials
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.Participant
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.StoredMessage
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [ContactTransport] with no network behind it, for the demo's offline mode: Sam, a scripted
 * assistant who gives a tour of what the SDK can show. His greeting is a menu — cards, a carousel, a
 * date input, a video, formatted text, a guide card, the sign-in card, a handover to Alex (a person,
 * with receipts, typing and a file), a dropped connection, the end — and every stop on the tour ends
 * with the menu again, so someone trying the app can poke around without a script to follow. Free
 * text is matched on its obvious words; anything else gets an honest "I'm scripted" and the menu.
 *
 * The other way to drive it is [console]: the developer plays the other side, sending as the
 * assistant or the agent, with the tour's automatic replies paused if they like ([autoReplies]).
 *
 * As on Connect, only a person sends receipts: once Alex has joined, he answers, and his goodbye is
 * followed by "Agent has left" and the end. For a screen recording, [pace] stretches every wait (slow
 * motion), so each state shows: connecting, the greeting, "One moment…" before a rich reply, and a
 * person's receipts.
 *
 * One event flow per connection, completing on [disconnect] — the shape the session's pump expects
 * (ContactTransport) — and events before a collector attaches are buffered. The next connection's
 * channel exists from the moment the last one closes: the session subscribes *before* it connects,
 * and a subscriber handed the closed channel would see an instant drop and reconnect for nothing.
 */
class ScriptedChatTransport(
    private val replyDelay: Duration = 900.milliseconds,
    /** Slow motion: every wait multiplied by this, read at each one, so the switch takes effect at once. */
    private val pace: () -> Double = { 1.0 },
) : ContactTransport {
    private fun paced(wait: Duration): Duration = wait * maxOf(1.0, pace())

    /**
     * Whether the tour answers by itself. Off, the customer's messages are only delivered: what comes
     * back is what the developer sends from the [console]. Read at each message, so the switch takes
     * effect at once; off also skips the greeting of a contact that has not been greeted yet.
     */
    @Volatile var autoReplies: Boolean = true

    /** Bumped by [disconnect], so a connect held for slow motion, or an upload, knows it was abandoned. */
    @Volatile private var disconnects = 0

    /** The contact connected for, and those a person has taken over: the transport outlives a contact. */
    @Volatile private var contactId = "unknown"
    private val agentPresent: Boolean get() = contactId in handedOver
    @Volatile private var channel = Channel<TransportEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replies = mutableListOf<Job>()

    @Volatile private var connected = false
    private var lastSequence = 0L

    /** The customer's newest message, as the server knows it: what the console's receipts acknowledge. */
    @Volatile private var lastCustomerMessage: String? = null

    override val events: Flow<TransportEvent> get() = channel.receiveAsFlow()

    /**
     * Server ordering: wall-clock milliseconds plus a counter — monotonic within a run, and larger
     * than anything a previous run stored for the same conversation, so a resumed transcript keeps its order.
     */
    @Synchronized
    private fun nextSequence(): Long {
        lastSequence = maxOf(lastSequence + 1, System.currentTimeMillis())
        return lastSequence
    }

    override suspend fun connect(credentials: ExpiringCredentials) {
        // Offline, connecting is instant and the SDK's Connecting never shows: slow motion holds it.
        if (pace() > 1) {
            val generation = disconnects
            delay(paced(CONNECT_HOLD))
            if (generation != disconnects) throw TouchpointException.TransportFailure("disconnected while connecting")
        }
        connected = true
        channel.trySend(TransportEvent.Connected)
        // Sam is the assistant: not a person, so he neither trips Live Sync's
        // gate nor joins with "Agent has joined".
        channel.trySend(TransportEvent.ParticipantJoined(Participant(AGENT_ID, ParticipantRole.ASSISTANT, AGENT_NAME)))
        // Greet once per contact. A resume connects again for the same contact, and a real agent does
        // not say hello twice; the greeting's server id comes from the contact id, so even across a
        // relaunch the store's deduplication drops the repeat.
        val contactId = (credentials as? ChatContactCredentials)?.contactId ?: "unknown"
        this.contactId = contactId
        if (autoReplies && greeted.add(contactId)) schedule(greeting(), "demo-greeting-$contactId")
    }

    override suspend fun send(message: OutboundMessage) {
        if (!connected) throw TouchpointException.TransportFailure("not connected")
        val serverId = "demo-${UUID.randomUUID()}"
        lastCustomerMessage = serverId
        channel.trySend(TransportEvent.MessageDelivered(message.clientMessageId, serverId, nextSequence()))
        if (!autoReplies) return
        // Once a person has taken over, the person answers: not the script's assistant.
        if (agentPresent) return agentAnswers(message.body, serverId)
        // A modality's pick (a card tapped, a date picked) fills a slot: answered by the slot, as a
        // flow answers it.
        val slot = message.choiceId.takeIf { message.interactiveTemplate == InteractiveContent.SLOT_TEMPLATE }
        schedule(if (slot != null) slotReply(slot, message.body) else scriptedReply(message.body))
    }

    override suspend fun disconnect() {
        disconnects++
        connected = false
        cancelReplies(except = null)
        rotate()
    }

    /**
     * A person's reply: their client acknowledges the message first — delivered, then read as they
     * start typing — which is what Connect relays from an agent's workspace. The assistant sends no
     * receipts, so the script's does not either.
     */
    private fun agentAnswers(
        body: String,
        serverId: String,
    ) {
        val wait = paced(replyDelay)
        launchReply {
            delay(wait * 0.3)
            if (!connected) return@launchReply
            channel.trySend(TransportEvent.Receipt(serverId, ReceiptKind.DELIVERED))
            delay(wait * 0.4)
            if (!connected) return@launchReply
            channel.trySend(TransportEvent.Receipt(serverId, ReceiptKind.READ))
            channel.trySend(TransportEvent.Typing(ParticipantRole.AGENT))
            delay(wait * 0.6)
            if (!connected) return@launchReply
            if (isWrapUp(body)) agentSignsOff(wait * 0.6) else agentSays(agentReply(body))
        }
    }

    /** How a person ends a chat on Connect: a goodbye, they leave, and the contact ends. */
    private suspend fun agentSignsOff(beat: Duration) {
        agentSays("Thanks for chatting with ${PartnerBrand.current.bankName}, take care!")
        delay(beat)
        if (!connected) return
        agentLeaves()
        delay(beat)
        if (!connected) return
        channel.trySend(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
    }

    private fun agentJoins(): Boolean {
        if (!connected) return false
        if (handedOver.add(contactId)) {
            channel.trySend(TransportEvent.ParticipantJoined(Participant(HUMAN_AGENT_ID, ParticipantRole.AGENT, HUMAN_AGENT_NAME)))
        }
        return true
    }

    private fun agentLeaves(): Boolean {
        if (!connected || !handedOver.remove(contactId)) return false
        channel.trySend(TransportEvent.ParticipantLeft(HUMAN_AGENT_ID))
        return true
    }

    private fun agentSays(text: String): Boolean {
        if (!connected) return false
        channel.trySend(
            TransportEvent.MessageReceived(
                StoredMessage(
                    UUID.randomUUID(), "demo-${UUID.randomUUID()}", text, Direction.INBOUND, ParticipantRole.AGENT,
                    participantId = HUMAN_AGENT_ID, sequence = nextSequence(), deliveryState = DeliveryState.DELIVERED,
                ),
            ),
        )
        return true
    }

    /** The agent's form: files exist only once a person is in the chat, as on Connect. */
    private fun agentSendsForm(): Boolean {
        if (!connected) return false
        channel.trySend(
            TransportEvent.MessageReceived(
                StoredMessage(
                    UUID.randomUUID(), "demo-${UUID.randomUUID()}", FORM_NAME, Direction.INBOUND, ParticipantRole.AGENT,
                    participantId = HUMAN_AGENT_ID, sequence = nextSequence(), deliveryState = DeliveryState.DELIVERED,
                    attachmentId = FORM_ID, attachmentContentType = "application/pdf", attachmentSize = FORM_SIZE,
                ),
            ),
        )
        return true
    }

    /**
     * Connect takes the customer's file; the agent acknowledges it. A restart during the upload must
     * not put this file in the next contact: it fails instead.
     */
    override suspend fun uploadAttachment(file: AttachmentUpload): String {
        if (!connected) throw TouchpointException.TransportFailure("not connected")
        val generation = disconnects
        delay(paced(replyDelay)) // long enough to see the upload chip pulse
        if (generation != disconnects || !connected) throw TouchpointException.TransportFailure("disconnected during upload")
        if (autoReplies) {
            launchReply {
                delay(paced(replyDelay))
                agentSays("Got it, thanks — I'll attach that to your case.")
            }
        }
        return "demo-${UUID.randomUUID()}"
    }

    /** The agent's form: a public test PDF, so saving it needs a connection; nothing else is fetchable. */
    override suspend fun attachmentUrl(attachmentId: String): String =
        if (attachmentId == FORM_ID) FORM_URL else throw TouchpointException.InvalidConfiguration("offline demo: no such file")

    /** This connection's flow completes; the next one's channel exists already, for a subscriber that comes first. */
    private fun rotate() {
        val ended = channel
        channel = Channel(Channel.UNLIMITED)
        ended.close()
    }

    private fun launchReply(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(block = block)
        synchronized(replies) { replies += job }
    }

    private fun cancelReplies(except: Job?) {
        synchronized(replies) {
            replies.filter { it !== except }.forEach { it.cancel() }
            replies.clear()
        }
    }

    /**
     * The socket drops and the session reconnects by itself: web's "Connection lost". As a
     * disconnect, nothing still scheduled reaches the next connection.
     */
    private fun dropConnection(except: Job?): Boolean {
        if (!connected) return false
        connected = false
        channel.trySend(TransportEvent.Disconnected(null))
        cancelReplies(except)
        rotate()
        return true
    }

    /**
     * One turn of the tour: the words, and the replies it offers. Choices travel as a
     * TouchpointModality message — what a Connect flow built for Touchpoint sends — so the SDK reads
     * them as it would off the wire, and the offline demo goes the way a live contact does.
     */
    data class Reply(
        val text: String,
        val choices: List<String> = emptyList(),
        /** The assistant closes the contact after saying this: the offline demo's way to the ended state. */
        val endsContact: Boolean = false,
        /** A person takes over after this: the agent-joined notice, and the agent typing. */
        val handsOver: Boolean = false,
        /** The socket drops after this and the session reconnects by itself: web's "Connection lost". */
        val dropsConnection: Boolean = false,
        /** The flow's sign-in step follows: web's card, which holds the composer until it resolves. */
        val promptsSignIn: Boolean = false,
        /** Web's modalities, as JSON keyed by name: the shape a flow sends. */
        val modalitiesJson: String? = null,
        /**
         * A Connect View, as a flow's Show view block sends it: the guide card, drawn in place of the
         * message. Its JSON is the `ViewResource` content's `data`.
         */
        val viewJson: String? = null,
        /** Sent a beat after this one: a guide card's note, which cannot ride on the card itself. */
        val followUp: Reply? = null,
    ) {
        val interactiveJson: String?
            get() {
                if (viewJson != null) return InteractiveContent.encode(InteractiveContent(VIEW_TEMPLATE, """{"data":$viewJson}"""))
                if (choices.isEmpty() && modalitiesJson == null) return null
                val modalities = modalitiesJson?.let { Json.parseToJsonElement(it) }
                val raw =
                    buildJsonObject {
                        put("templateType", InteractiveContent.CHOICE_TEMPLATE)
                        putJsonObject("data") {
                            putJsonObject("content") {
                                putJsonObject("modalityPayload") {
                                    put("type", "message")
                                    put("message", text)
                                    put("choices", JsonArray(choices.map { c -> buildJsonObject { put("choiceId", c.lowercase().replace(' ', '-')); put("choiceText", c) } }))
                                    modalities?.let { put("modalities", it) }
                                }
                            }
                        }
                    }
                return InteractiveContent.encode(InteractiveContent(InteractiveContent.CHOICE_TEMPLATE, raw.toString()))
            }
    }

    private fun schedule(
        reply: Reply,
        serverId: String? = null,
    ) {
        val wait = paced(replyDelay)
        launchReply {
            // The flow's interim line before a rich reply (the cards, the date input, the video),
            // in place of "Thinking...".
            if (reply.modalitiesJson != null) {
                delay(wait * 0.4)
                if (!connected) return@launchReply
                channel.trySend(TransportEvent.InterimMessage(INTERIM))
                delay(wait * 0.6)
            } else {
                delay(wait)
            }
            if (!connected) return@launchReply
            assistantSays(reply, serverId, coroutineContext[Job])
            if (reply.handsOver) handOver()
            reply.followUp?.let {
                delay(wait)
                if (connected) assistantSays(it, null, coroutineContext[Job])
            }
        }
    }

    /** The assistant's message, then what it leads to: the sign-in card, the end, or a dropped connection. */
    private fun assistantSays(
        reply: Reply,
        serverId: String? = null,
        current: Job? = null,
    ): Boolean {
        if (!connected) return false
        channel.trySend(
            TransportEvent.MessageReceived(
                StoredMessage(
                    UUID.randomUUID(), serverId ?: "demo-${UUID.randomUUID()}", reply.text, Direction.INBOUND, ParticipantRole.ASSISTANT,
                    participantId = AGENT_ID, sequence = nextSequence(), deliveryState = DeliveryState.DELIVERED, interactiveJson = reply.interactiveJson,
                ),
            ),
        )
        when {
            reply.promptsSignIn -> promptSignIn()
            reply.endsContact -> channel.trySend(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
            reply.dropsConnection -> dropConnection(except = current)
        }
        return true
    }

    // Sign-in, offline: the card appears; with no identity provider behind it, only
    // "Continue without signing in" leads anywhere.

    @Volatile private var signInCard: UUID? = null

    private fun promptSignIn() {
        if (!connected) return
        val card = UUID.randomUUID()
        signInCard = card
        channel.trySend(TransportEvent.AuthenticationChanged(card, AuthenticationStatus.PROMPT))
    }

    /** No identity provider offline: the card says so rather than opening a page that cannot load. */
    override suspend fun beginAuthentication(): AuthenticationRequest {
        signInCard?.let { channel.trySend(TransportEvent.AuthenticationChanged(it, AuthenticationStatus.FAILED)) }
        signInCard = null
        throw TouchpointException.InvalidConfiguration("offline demo: sign-in needs live mode")
    }

    override suspend fun cancelAuthentication(unlessRedirected: Boolean) {
        val card = signInCard ?: return
        signInCard = null
        channel.trySend(TransportEvent.AuthenticationChanged(card, AuthenticationStatus.CANCELLED))
        if (autoReplies) schedule(Reply("No problem, you can keep going without signing in. What next?", TOPICS))
    }

    /** The agent joins, types for a moment, then speaks, then sends the dispute form: the order Connect delivers them in. */
    private suspend fun handOver() {
        agentJoins()
        delay(paced(TYPING_AFTER))
        if (!connected) return
        channel.trySend(TransportEvent.Typing(ParticipantRole.AGENT))
        delay(paced(replyDelay))
        agentSays("Hi, I'm $HUMAN_AGENT_NAME. I can see your conversation so far. How can I help?")
        delay(paced(replyDelay))
        agentSays("I've attached our dispute form in case you'd like to fill it in. You can send me a file with the paperclip too.")
        agentSendsForm()
    }

    /**
     * The developer playing the other side: everything here goes out at once, with none of the tour's
     * pauses, and reports whether it went (false while no chat is connected).
     */
    val console: AgentConsoleControls =
        object : AgentConsoleControls {
            override val isConnected: Boolean get() = connected

            override var autoReplies: Boolean
                get() = this@ScriptedChatTransport.autoReplies
                set(value) {
                    this@ScriptedChatTransport.autoReplies = value
                }

            override fun say(text: String, sender: ConsoleSender): Boolean =
                when (sender) {
                    ConsoleSender.ASSISTANT -> assistantSays(Reply(text))
                    // A person's message comes from someone on the contact: they join first, as on Connect.
                    ConsoleSender.AGENT -> this@ScriptedChatTransport.agentJoins() && agentSays(text)
                }

            override fun offerChoices(text: String, choices: List<String>): Boolean = assistantSays(Reply(text, choices))

            override fun send(modality: ConsoleModality): Boolean =
                when (modality) {
                    ConsoleModality.FILE -> this@ScriptedChatTransport.agentJoins() && agentSendsForm()
                    else -> assistantSays(modalityReply(modality))
                }

            override fun typing(sender: ConsoleSender): Boolean {
                if (!connected) return false
                if (sender == ConsoleSender.AGENT && !agentPresent) return false
                channel.trySend(TransportEvent.Typing(if (sender == ConsoleSender.AGENT) ParticipantRole.AGENT else ParticipantRole.ASSISTANT))
                return true
            }

            override fun receipt(kind: ReceiptKind): Boolean {
                // Only a person sends receipts, as on Connect.
                val id = lastCustomerMessage ?: return false
                if (!connected || !agentPresent) return false
                channel.trySend(TransportEvent.Receipt(id, kind))
                return true
            }

            override fun agentJoins(): Boolean = connected && !agentPresent && this@ScriptedChatTransport.agentJoins()

            override fun agentLeaves(): Boolean = this@ScriptedChatTransport.agentLeaves()

            override fun dropConnection(): Boolean = this@ScriptedChatTransport.dropConnection(except = null)

            override fun endContact(): Boolean {
                if (!connected) return false
                channel.trySend(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
                return true
            }
        }

    companion object {
        const val AGENT_ID = "agent-demo"
        const val AGENT_NAME = "Sam"
        const val HUMAN_AGENT_ID = "agent-alex"
        const val HUMAN_AGENT_NAME = "Alex"
        private val TYPING_AFTER = 300.milliseconds

        /** How long slow motion holds the SDK's Connecting, before the pace. */
        private val CONNECT_HOLD = 700.milliseconds
        private val greeted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val handedOver: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** A Connect View's template: what a flow's Show view block sends. */
        private const val VIEW_TEMPLATE = "ViewResource"

        /** The flow's interim line before a rich reply (web's interim message). */
        const val INTERIM = "One moment…"

        const val FORM_ID = "demo-dispute-form"
        val FORM_NAME get() = "${PartnerBrand.current.bankShortName} dispute form.pdf"
        private const val FORM_SIZE = 13_264L
        private const val FORM_URL = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"

        // The tour's menu. Each stop's words are also what a customer can type: scriptedReply matches them.
        const val SHOW_CARDS = "Show me cards"
        const val PICK_DATE = "Pick a date"
        const val PLAY_VIDEO = "Play a video"
        const val FORMATTED_TEXT = "Formatted text"
        const val OPEN_GUIDE = "Open a guide"

        /** The flow's sign-in, offline: the card, and continue-without. */
        const val SIGN_IN = "Sign in"
        const val TALK_TO_AGENT = "Talk to an agent"
        const val MORE = "More options"
        const val BACK_TO_MENU = "Back to the menu"

        /** The choice that closes the conversation, offered once the customer has been helped. */
        const val WRAP_UP = "That's all"

        /** The long suggestion: two lines on a phone, so a review sees one that does not fit. */
        const val LONG_TOPIC = "I don't recognise a charge on my card statement from last week"

        /** The greeting's menu, and the one every stop on the tour ends with. */
        val TOPICS = listOf(SHOW_CARDS, PICK_DATE, PLAY_VIDEO, FORMATTED_TEXT, OPEN_GUIDE, SIGN_IN, TALK_TO_AGENT, MORE)

        /** The rest of the tour: a bank's everyday questions, a file, a dropped connection, the end. */
        val MORE_TOPICS = listOf("Replacement card", LONG_TOPIC, "Reset password", "Send a file", "Drop the connection", WRAP_UP)

        fun greeting(): Reply =
            Reply(
                "Hi, I'm $AGENT_NAME from ${PartnerBrand.current.bankName} support. This is the offline demo: my replies are scripted, " +
                    "and each one shows something the chat can do. Where shall we start?",
                TOPICS,
            )

        fun isWrapUp(body: String): Boolean =
            body.lowercase().let { "that's all" in it || "goodbye" in it || "end chat" in it || "end the chat" in it || Regex("\\bbye\\b").containsMatchIn(it) }

        fun agentReply(body: String): String =
            if ("thank" in body.lowercase()) {
                "You're welcome. Anything else? Say goodbye whenever you're done."
            } else {
                "Thanks, I've added that to your case. Anything else? Say goodbye whenever you're done."
            }

        private val CAROUSEL_JSON =
            """{"DefaultCarousel":{"${'$'}saveAs":{"type":"slot","id":"Charge"},"cards":[""" +
                """{"id":"streamly","rows":[{"label":"Merchant","value":"Streamly"},{"label":"Amount","value":"$15.99"},{"label":"Date","value":"Oct 2"}]},""" +
                """{"id":"harbour","rows":[{"label":"Merchant","value":"Harbour Coffee"},{"label":"Amount","value":"$6.75"},{"label":"Date","value":"Oct 3"}]},""" +
                """{"id":"riverside","rows":[{"label":"Merchant","value":"Riverside Auto"},{"label":"Amount","value":"$240.18"},{"label":"Date","value":"Oct 6"}]}]}}"""
        private const val CARD_JSON =
            """{"DefaultCard":{"id":"card","rows":[{"label":"Card","value":"Rewards Visa"},{"label":"Balance","value":"$1,284.50"},{"label":"Payment due","value":"Nov 21"}]}}"""
        private val DATE_JSON = """{"DefaultDateInput":{"${'$'}saveAs":{"type":"slot","id":"TourDate"}}}"""

        // A public test stream: one that loads.
        private const val VIDEO_URL = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
        private const val VIDEO_JSON = """{"DefaultVideo":{"url":"$VIDEO_URL"}}"""
        private const val GUIDE_JSON =
            """{"content":{"viewData":{"Heading":"Before we connect you","SubHeading":"Tell us a little about your request."}}}"""

        private const val MARKDOWN =
            "### Formatted text\n\nMessages can carry **bold**, *italic*, ~~struck~~ and `code`, a [link](https://example.com), and lists:\n\n" +
                "- a bulleted one\n- with two items\n\n1. or a numbered one\n2. that keeps count\n\n> and a quote.\n\nWhat next?"

        /** What the console sends for each modality: the same messages the tour does. */
        fun modalityReply(modality: ConsoleModality): Reply =
            when (modality) {
                ConsoleModality.CARD -> Reply("Here's your card.", modalitiesJson = CARD_JSON)
                ConsoleModality.CAROUSEL -> Reply("Here are your recent charges. Which one would you like to review?", modalitiesJson = CAROUSEL_JSON)
                ConsoleModality.DATE_INPUT -> Reply("Which date works for you?", modalitiesJson = DATE_JSON)
                ConsoleModality.VIDEO -> Reply("Here's a short video.", modalitiesJson = VIDEO_JSON)
                ConsoleModality.GUIDE -> Reply("Before we connect you", viewJson = GUIDE_JSON)
                ConsoleModality.SIGN_IN -> Reply("Let's get you signed in.", promptsSignIn = true)
                ConsoleModality.MARKDOWN -> Reply(MARKDOWN.removeSuffix("\n\nWhat next?"))
                // A file is a person's to send (the console has the agent send it); the assistant only says so.
                ConsoleModality.FILE -> Reply("Files come from a person: an agent sends them once they join.")
            }

        /**
         * The cards journey, and the tour's own date input, answered by the slot the customer filled:
         * a carousel's pick, then a card with a date input, then the bank's own summary with a video.
         */
        fun slotReply(
            slot: String,
            value: String = "",
        ): Reply =
            when (slot) {
                "Charge" ->
                    Reply(
                        "When did you first notice this charge?",
                        modalitiesJson =
                            """{"DefaultCard":{"id":"charge","rows":[{"label":"Merchant","value":"Harbour Coffee"},{"label":"Amount","value":"$6.75"},{"label":"Date","value":"Oct 3"}]},""" +
                                """"DefaultDateInput":{"${'$'}saveAs":{"type":"slot","id":"Noticed"}}}""",
                    )
                "TourDate" ->
                    Reply(
                        "Got it${if (value.isBlank()) "" else ": $value"}. A date input fills a slot in the flow, as a carousel's pick does. What next?",
                        TOPICS,
                    )
                else ->
                    Reply(
                        "Thanks. Here is your dispute, and a short video on what happens next. The summary is the app's own modality, drawn by the app. What next?",
                        TOPICS,
                        modalitiesJson =
                            """{"AcmeDisputeSummary":{"merchant":"Harbour Coffee","amount":"$6.75","status":"Submitted for review"},""" +
                                """"DefaultVideo":{"url":"$VIDEO_URL"}}""",
                    )
            }

        private fun word(text: String, vararg words: String): Boolean = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }

        /** The tour's answer to what the customer typed or tapped: matched on its obvious words, in order. */
        fun scriptedReply(body: String): Reply {
            val text = body.lowercase().trim()
            return when {
                isWrapUp(text) -> Reply("Glad I could help. Have a good day!", endsContact = true)
                text == SIGN_IN.lowercase() ->
                    Reply(
                        "Let's get you signed in. Offline there's no identity provider behind the card, so choose Continue without signing in.",
                        promptsSignIn = true,
                    )
                "drop" in text || word(text, "disconnect") ->
                    Reply("Dropping the connection now. The app reconnects on its own and catches up. What next?", TOPICS, dropsConnection = true)
                "agent" in text || "a person" in text || "human" in text -> Reply("Of course. I'm connecting you to an agent now.", handsOver = true)
                word(text, "more") -> Reply("Here's the rest of the tour.", MORE_TOPICS + BACK_TO_MENU)
                "tracking" in text -> Reply("It's with the courier and due on Thursday. I'll text you when it's out for delivery. What next?", TOPICS)
                text == "no thanks" -> Reply("No problem. What next?", TOPICS)
                "card" in text && ("replace" in text || "where" in text || "new" in text) ->
                    Reply(
                        "Your replacement card was posted two business days ago and usually arrives within five. Would you like me to check the tracking?",
                        listOf("Check tracking", "No thanks"),
                    )
                // Markdown on purpose: the demo is where the SDK's rendering shows — a list, bold, a link.
                "charge" in text || "dispute" in text || "recogni" in text ->
                    Reply(
                        "Here's how to dispute a charge:\n\n1. Open the transaction\n2. Tap **Dispute**\n3. Tell us what happened\n\n" +
                            "Most disputes are resolved in **5 business days**. See the [dispute policy](https://example.com/disputes) for what qualifies.",
                        TOPICS,
                    )
                "cards" in text || "carousel" in text -> modalityReply(ConsoleModality.CAROUSEL)
                word(text, "card") -> modalityReply(ConsoleModality.CARD).copy(text = "Here's your card: a card is a few labelled rows. What next?", choices = TOPICS)
                word(text, "date", "calendar") -> modalityReply(ConsoleModality.DATE_INPUT)
                word(text, "video") -> modalityReply(ConsoleModality.VIDEO).copy(text = "Here's a short video. It plays in the chat. What next?", choices = TOPICS)
                "format" in text || "markdown" in text -> Reply(MARKDOWN, TOPICS)
                word(text, "guide", "view", "form") ->
                    modalityReply(ConsoleModality.GUIDE).copy(
                        followUp =
                            Reply(
                                "That's a guide card, as a flow's Show view block sends it. Offline it can't open: a guide loads Connect's form from your instance, so it needs live mode. What next?",
                                TOPICS,
                            ),
                    )
                word(text, "file", "attach", "attachment", "upload", "paperclip") ->
                    Reply(
                        "Files go to a person, as on Connect: the paperclip appears once an agent joins, and the agent can send you one too.",
                        listOf(TALK_TO_AGENT, MORE),
                    )
                "password" in text || "sign in" in text || "log in" in text ->
                    Reply("To reset your password, go to Settings and choose Security. You'll need your access card number. What next?", TOPICS)
                "thank" in text -> Reply("You're welcome. Anything else I can help with today?", TOPICS + WRAP_UP)
                word(text, "menu", "help", "options", "start") -> Reply("Here's what I can show you.", TOPICS)
                else ->
                    Reply(
                        "I'm the offline demo's scripted assistant, so that one's beyond me: in live mode, your assistant or a Connect agent answers here. " +
                            "Pick something to try, or answer as anyone you like from the agent console.",
                        TOPICS,
                    )
            }
        }
    }
}
