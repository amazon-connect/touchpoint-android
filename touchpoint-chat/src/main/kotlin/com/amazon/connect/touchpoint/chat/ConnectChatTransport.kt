package com.amazon.connect.touchpoint.chat

import com.amazon.connect.chat.sdk.network.AWSClient
import android.content.Context
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.AuthenticationRequest
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
import com.amazon.connect.touchpoint.core.ResolvedView
import com.amazon.connect.touchpoint.core.StoredMessage
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TranscriptEntry
import com.amazon.connect.touchpoint.core.TranscriptPage
import com.amazon.connect.touchpoint.core.TransportEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The production chat transport: translates the upstream SDK's callback API into the
 * coordinator's [TransportEvent] stream and closes the upstream gaps the design lists — no
 * `resendFailedMessage`, the server id of every send, `ParticipantId` on every row, typed errors at
 * the boundary.
 *
 * **Receipts.** `delivered` for every message a person sent — agent, supervisor, or a role this
 * version does not recognise — once per message per connection. The upstream SDK also
 * sends one for agent messages and cannot be told not to; it coalesces them, so it is
 * not relied on. `read` stays host-driven ([markRead]).
 *
 * **Reconnects the SDK makes itself** — after a network blip or a spell in the background — surface
 * as [TransportEvent.Reestablished], so the session reconciles what it missed. One missed
 * heartbeat is one `Disconnected`, and a connect the SDK never reports up fails after a timeout.
 *
 * **Ordering contract** (see [ContactTransport]): events are buffered until a collector attaches, so
 * a consumer that connects before collecting loses nothing.
 *
 * **Our own echoes are dropped** on the live stream. The server id of a sent message comes back in
 * the send's acknowledgement, so the websocket echo carries nothing new; emitting it would race the
 * acknowledgement. Transcript pages keep them — that is how reconciliation adopts a lost ack.
 *
 * One per process, as the upstream SDK's session is a process singleton.
 */
public class ConnectChatTransport internal constructor(
    private val driver: ChatSessionDriving,
    /** How long a connect may wait for the SDK to report the connection up before it counts as failed. */
    private val connectTimeout: Duration = 30.seconds,
    /** Sign-in, when the host configured it; `null` leaves Connect's sign-in events unheard. */
    private val authentication: ChatAuthentication? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Instant = Instant::now,
    /** The host routes Participant Service calls through its own client: a chat with no token is expected. */
    private val usesCustomChatClient: Boolean = false,
) : ContactTransport {
    // Everything below is touched only on this single-threaded view.
    private val confined =
        @OptIn(ExperimentalCoroutinesApi::class)
        Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined + CoroutineName("touchpoint-chat-transport") + CoroutineExceptionHandler { _, _ -> })

    /**
     * SDK callbacks arrive on the SDK's threads. Each is enqueued synchronously here and consumed by
     * one coroutine, so the order the SDK raised them in is the order the coordinator sees.
     */
    private sealed interface Inbound {
        data object Connected : Inbound

        data object ReEstablished : Inbound

        data object Broken : Inbound

        data object Ended : Inbound

        class Item(val item: RawTranscriptItem) : Inbound

        class Typing(val role: String) : Inbound

        class Receipt(val kind: ReceiptKind, val messageId: String) : Inbound

        class Authentication(val contentType: String, val content: String?, val absoluteTime: String?) : Inbound
    }

    private val inbox = Channel<Inbound>(Channel.UNLIMITED)
    private var handlersInstalled = false

    private var subscriber: SendChannel<TransportEvent>? = null
    private val buffered = ArrayList<TransportEvent>()

    /**
     * `false` between [disconnect] and the next [connect]: frames the SDK raises in that window belong
     * to a dead connection and are dropped, not buffered for a later collector.
     */
    private var connected = false

    /**
     * Bumped on every connect, disconnect and release: work begun on one connection — a send's
     * acknowledgement, a delayed end — checks it before it reports, so nothing reaches the next one.
     */
    private var epoch = 0L

    /** The SDK has said the connection is up since the last connect. */
    private var up = false

    /** A drop has been reported since the connection was last up: one drop is one `Disconnected`. */
    private var dropReported = false
    private var connectWatchdog: Job? = null

    /**
     * Wire receipts go out on their own lane so a slow round trip never delays the frames behind it.
     * Per connection: rotated on [connect], closed on [disconnect], so a receipt queued before a drop
     * never goes out on a later connection.
     */
    private var receiptLane: SendChannel<String>? = null
    private var receiptJob: Job? = null
    private val receipted = HashSet<String>()
    /** Interim lines already shown on this connection, by item id. */
    private val interimsSeen = HashSet<String>()

    /** The sign-in on the current contact; a card belongs to one contact, and survives its reconnects. */
    private var signIn: SignIn? = null
    private var signInContact: String? = null
    private var authenticationEventsEnabled = false

    override val events: Flow<TransportEvent> =
        callbackFlow {
            val me = channel
            withContext(confined) {
                subscriber?.close()
                subscriber = me
                buffered.forEach { me.trySend(it) }
                buffered.clear()
            }
            awaitClose { scope.launch { if (subscriber === me) subscriber = null } }
        }.buffer(Channel.UNLIMITED)

    override suspend fun connect(credentials: ExpiringCredentials): Unit =
        withContext(confined) {
            val chat =
                credentials as? ChatContactCredentials
                    ?: throw TouchpointException.InvalidConfiguration("ConnectChatTransport needs ChatContactCredentials, got ${credentials.javaClass.simpleName}")
            // **Deviation:** web only warns when there is neither a token nor a custom client,
            // and the session then fails to authenticate; failing here names the cause.
            if (chat.participantToken == null && !usesCustomChatClient) throw TouchpointException.InvalidConfiguration("no participant token and no custom chat client")
            installHandlers()
            if (authentication != null) {
                if (!authenticationEventsEnabled) {
                    authenticationEventsEnabled = true
                    driver.enableAuthenticationEvents()
                }
                if (signInContact != chat.contactId) {
                    signInContact = chat.contactId
                    signIn = SignIn(authentication, driver, ::yieldEvent, now, wallClock)
                }
            }
            openReceiptLane()
            connected = true
            up = false
            dropReported = false
            val mine = ++epoch
            try {
                driver.connect(chat.contactId, chat.participantId, chat.participantToken)
            } catch (e: Exception) {
                // Cleaned up for cancellation too: a cancelled connect must not leave the lane open.
                connected = false
                closeReceiptLane()
                buffered.clear() // frames from a handshake that never came up must not reach a later collector
                throw map(rethrowOwnCancellation(e))
            }
            // The SDK reports the connection up later, from its socket — or, when the socket fails,
            // not at all (WebSocketManager.kt:219-224). Silence is a failed connect, not a wait for ever.
            connectWatchdog?.cancel()
            connectWatchdog =
                scope.launch {
                    delay(connectTimeout)
                    if (epoch == mine && connected && !up) reportDrop("connect timeout")
                }
        }

    override suspend fun send(message: OutboundMessage) {
        val mine = withContext(confined) { epoch }
        val ack =
            try {
                // An answer goes as web's interactive response; anything else, or one Connect would
                // refuse, as text — never a message stuck at the head of the outbox.
                val response =
                    when (message.interactiveTemplate) {
                        InteractiveContent.CHOICE_TEMPLATE -> InteractiveResponse.envelope(message.body, message.choiceId ?: message.body)
                        InteractiveContent.SLOT_TEMPLATE -> message.choiceId?.let { InteractiveResponse.slotEnvelope(it, message.body) }
                        InteractiveContent.VIEW_RESPONSE_TEMPLATE -> message.choiceId?.let { InteractiveResponse.viewEnvelope(it, message.body) }
                        else -> null
                    }
                driver.sendMessage(response ?: message.body, interactiveResponse = response != null)
            } catch (e: Exception) {
                throw map(rethrowOwnCancellation(e))
            }
        // An acknowledgement that outlived its connection belongs to no one now: on the next contact
        // it would name a row that is not there.
        withContext(confined) {
            if (epoch == mine && connected) yieldEvent(TransportEvent.MessageDelivered(message.clientMessageId, ack.serverMessageId, sequence(ack.absoluteTime)))
        }
    }

    override suspend fun sendTyping() {
        try {
            driver.sendTyping()
        } catch (e: Exception) {
            throw map(rethrowOwnCancellation(e))
        }
    }

    override suspend fun disconnect(): Unit = stop { driver.disconnect() }

    override suspend fun release(): Unit = stop { driver.release() }

    private suspend fun stop(upstream: suspend () -> Unit): Unit =
        withContext(confined) {
            try {
                upstream()
            } catch (e: Exception) {
                rethrowOwnCancellation(e) // a failed disconnect still ends our side of the connection
            }
            connected = false
            epoch++
            connectWatchdog?.cancel()
            connectWatchdog = null
            closeReceiptLane()
            subscriber?.close()
            subscriber = null
            buffered.clear() // nothing from this connection may leak into the next collector
        }

    override suspend fun uploadAttachment(file: AttachmentUpload): String =
        try {
            driver.uploadAttachment(file)
        } catch (e: Exception) {
            throw map(rethrowOwnCancellation(e))
        }

    override suspend fun attachmentUrl(attachmentId: String): String =
        try {
            driver.attachmentUrl(attachmentId)
        } catch (e: Exception) {
            throw map(rethrowOwnCancellation(e))
        }

    override suspend fun beginAuthentication(): AuthenticationRequest =
        withContext(confined) {
            val machine = signIn ?: throw TouchpointException.InvalidConfiguration("this contact does not offer sign-in")
            try {
                machine.begin()
            } catch (e: Exception) {
                throw map(rethrowOwnCancellation(e))
            }
        }

    override suspend fun completeAuthentication(redirect: String): Unit =
        withContext(confined) {
            val machine = signIn ?: throw TouchpointException.InvalidConfiguration("this contact does not offer sign-in")
            try {
                machine.complete(redirect)
            } catch (e: Exception) {
                throw map(rethrowOwnCancellation(e))
            }
        }

    override suspend fun cancelAuthentication(unlessRedirected: Boolean): Unit = withContext(confined) { signIn?.cancel(unlessRedirected) ?: Unit }

    override suspend fun describeView(viewToken: String): ResolvedView =
        try {
            driver.describeView(viewToken)
        } catch (e: Exception) {
            throw map(rethrowOwnCancellation(e))
        }

    override suspend fun markRead(upToServerMessageId: String) {
        try {
            driver.sendReceipt(ReceiptKind.READ, upToServerMessageId)
        } catch (e: Exception) {
            throw map(rethrowOwnCancellation(e))
        }
    }

    override suspend fun fetchTranscript(before: String?, limit: Int): TranscriptPage {
        val raw =
            try {
                driver.fetchTranscript(before, limit)
            } catch (e: Exception) {
                throw map(rethrowOwnCancellation(e))
            }
        val messages = ArrayList<StoredMessage>()
        val participantEvents = ArrayList<TransportEvent>()
        for (item in raw.items) {
            when (val n = normalise(item)) {
                is Normalised.Message -> messages += n.message
                is Normalised.Participant -> participantEvents += n.event
                is Normalised.Interim, Normalised.Ignore -> Unit // history: an interim line has passed
            }
        }
        return TranscriptPage(messages, participantEvents, raw.nextToken?.takeIf { it.isNotEmpty() })
    }

    /**
     * The server's newest page as transcript lines: a message's words as the view shows them — an
     * interactive message by its title, a JSON message by its text, never raw JSON (where web's file
     * shows the JSON) — and no interim lines, events or receipts.
     */
    override suspend fun fetchTranscriptEntries(limit: Int): List<TranscriptEntry> {
        val raw =
            try {
                driver.fetchTranscript(null, limit)
            } catch (e: Exception) {
                throw map(rethrowOwnCancellation(e))
            }
        return raw.items.mapNotNull { item ->
            // As web's file: a file has no content of its own, so no line.
            if (item.attachmentId != null) return@mapNotNull null
            val text = item.text?.takeIf { it.isNotEmpty() && item.kind == RawTranscriptItem.Kind.MESSAGE } ?: return@mapNotNull null
            val words =
                when (item.contentType) {
                    ConnectContentType.INTERACTIVE, ConnectContentType.INTERACTIVE_RESPONSE ->
                        if (modalityType(text) == "interim") null else interactiveSummary(text).second ?: actionOf(text)
                    ConnectContentType.JSON -> jsonText(text)
                    else -> text
                }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TranscriptEntry(item.absoluteTime, item.displayName.ifBlank { item.participantRole }, ParticipantRole.fromConnectRole(item.participantRole), words)
        }
    }

    // Live frames

    private fun installHandlers() {
        if (handlersInstalled) return
        handlersInstalled = true
        driver.setHandlers(
            ChatDriverHandlers(
                onConnected = { inbox.trySend(Inbound.Connected) },
                onReEstablished = { inbox.trySend(Inbound.ReEstablished) },
                onConnectionBroken = { inbox.trySend(Inbound.Broken) },
                onChatEnded = { inbox.trySend(Inbound.Ended) },
                onItem = { inbox.trySend(Inbound.Item(it)) },
                onReceipt = { kind, id -> inbox.trySend(Inbound.Receipt(kind, id)) },
                onTyping = { role -> inbox.trySend(Inbound.Typing(role)) },
                onAuthenticationEvent = { type, content, time -> inbox.trySend(Inbound.Authentication(type, content, time)) },
            ),
        )
        scope.launch {
            for (inbound in inbox) {
                try {
                    process(inbound)
                } catch (e: Exception) {
                    // A frame we cannot process must not stop every frame after it: report a drop,
                    // and let the session's reconnect and reconciliation repair what was missed.
                    if (connected) reportDrop(e.javaClass.simpleName)
                }
            }
        }
    }

    private fun process(inbound: Inbound) {
        if (!connected) return
        when (inbound) {
            Inbound.Connected -> {
                up = true
                dropReported = false
                yieldEvent(TransportEvent.Connected)
            }
            Inbound.ReEstablished -> {
                // Before the first `Connected` this is just the connection coming up.
                val wasUp = up
                up = true
                dropReported = false
                yieldEvent(if (wasUp) TransportEvent.Reestablished else TransportEvent.Connected)
            }
            Inbound.Broken -> reportDrop(null)
            Inbound.Ended -> {
                // The SDK hands items to its callbacks over extra hops that its events do not take
                // (ChatSession.kt:268-275), so an agent's last message can trail the end. Let it land.
                val mine = epoch
                scope.launch {
                    delay(END_SETTLE)
                    if (epoch == mine && connected) yieldEvent(TransportEvent.ContactEnded(EndReason.AGENT_ENDED))
                }
            }
            is Inbound.Item -> handle(inbound.item)
            is Inbound.Receipt -> yieldEvent(TransportEvent.Receipt(inbound.messageId, inbound.kind))
            is Inbound.Typing -> yieldEvent(TransportEvent.Typing(ParticipantRole.fromConnectRole(inbound.role)))
            is Inbound.Authentication -> signIn?.onEvent(inbound.contentType, inbound.content, inbound.absoluteTime)
        }
    }

    private fun reportDrop(reason: String?) {
        if (dropReported) return
        dropReported = true
        up = false
        yieldEvent(TransportEvent.Disconnected(reason))
    }

    private fun handle(item: RawTranscriptItem) {
        // Our echo; see the type comment. A file's is kept: the upload returns no message, so the echo
        // brings the server's id and place, and the store keys it on the attachment.
        if (item.kind == RawTranscriptItem.Kind.MESSAGE && item.isOutgoing && item.attachmentId == null) return
        when (val n = normalise(item)) {
            is Normalised.Message -> {
                yieldEvent(TransportEvent.MessageReceived(n.message))
                val sid = n.message.serverMessageId
                // `delivered` for what a person sent. The SDK also sends one for
                // AGENT messages, but coalesced and not reliably for the newest, so a
                // duplicate is the price of not depending on it. History the SDK replays is not
                // receipted again.
                if (n.message.participantRole.isHuman && !item.isFromPastSession && sid != null && receipted.add(sid)) {
                    receiptLane?.trySend(sid) // queued, not awaited
                }
            }
            is Normalised.Participant -> yieldEvent(n.event)
            // Live only, and not from a past session's replay: an interim line is about now.
            // The SDK re-delivers a stored frame when a receipt for it arrives: an interim line is shown once.
            is Normalised.Interim -> if (!item.isFromPastSession && interimsSeen.add(n.id)) yieldEvent(TransportEvent.InterimMessage(n.text))
            Normalised.Ignore -> Unit
        }
    }

    /**
     * A collector that went away leaves a closed channel behind; buffer for the next one instead, or
     * the next contact's `Connected` is lost in the window before its collector attaches.
     */
    private fun yieldEvent(event: TransportEvent) {
        val s = subscriber
        if (s == null || s.trySend(event).isFailure) {
            subscriber = null
            buffered += event
        }
    }

    private fun openReceiptLane() {
        closeReceiptLane()
        val lane = Channel<String>(Channel.UNLIMITED)
        receiptLane = lane
        receiptJob =
            scope.launch(Dispatchers.Default) {
                for (sid in lane) {
                    // A receipt already taken off the lane when the connection dropped must not go out
                    // on the next one: check before sending, not only at the next receive.
                    if (!isActive) break
                    try {
                        driver.sendReceipt(ReceiptKind.DELIVERED, sid)
                    } catch (e: Exception) {
                        rethrowOwnCancellation(e) // advisory: a lost receipt is not worth failing for
                    }
                }
            }
    }

    private fun closeReceiptLane() {
        receiptLane?.close()
        receiptLane = null
        receiptJob?.cancel() // queued or in-flight receipts for the dead connection are abandoned
        receiptJob = null
        receipted.clear()
        interimsSeen.clear()
    }

    internal sealed interface Normalised {
        data class Message(val message: StoredMessage) : Normalised

        data class Participant(val event: TransportEvent) : Normalised

        /** An assistant's interim line: shown until the next message, never stored. Empty: the line clears. */
        data class Interim(val text: String, val id: String) : Normalised {
            override fun toString(): String = "Interim(text=<redacted>)"
        }

        data object Ignore : Normalised
    }

    public companion object {
        /**
         * The production transport for an Amazon Connect instance in [region], e.g. `"ca-central-1"`.
         * Configures the process-wide upstream chat session, replacing any configuration the host
         * gave it: one chat per process, and this transport owns it.
         * Register it with the session: `TouchpointSession(…, transports = mapOf(Modality.CHAT to transport))`.
         *
         * @throws TouchpointException.InvalidConfiguration for a region name the AWS SDK does not know.
         */
        public fun live(
            context: Context,
            region: String,
        ): ConnectChatTransport = live(context, region, authentication = null)

        /**
         * [live], with Connect's "Authenticate Customer" sign-in card: when the flow asks, the chat
         * shows web's card, and the sign-in page comes back on [ChatAuthentication.redirectUri]. `null`:
         * the flow's sign-in step is not offered, and Connect eventually takes its timeout branch.
         */
        public fun live(
            context: Context,
            region: String,
            authentication: ChatAuthentication?,
        ): ConnectChatTransport = ConnectChatTransport(ConnectChatSessionDriver.create(context, region), authentication = authentication)

        /**
         * [live], with every Participant Service call routed through the host's own backend — web's
         * `customChatClient`, so the participant token never reaches the device. Subclass the chat
         * SDK's `AWSClient` (the counterpart of chatjs's `ChatClient`, which web re-exports) and
         * override what the session uses: `createParticipantConnection`,
         * `disconnectParticipantConnection`, `sendMessage`, `sendEvent`, `getTranscript`, and the
         * attachment and `describeView` calls if the flow uses them — in the chat SDK 3.0, with the AWS
         * SDK for Kotlin's `connectparticipant` request and response types. The host's
         * `startChatContact` may then return no participant token.
         *
         * What still reaches the device: `createParticipantConnection` must return a websocket URL and
         * a connection token, because the chat SDK opens the socket itself, as chatjs does on web.
         *
         * Sign-in ([authentication]) also needs a listen-only socket for Connect's sign-in events,
         * which the chat SDK drops; without the token the SDK cannot mint it, so [customChatClient]
         * must also implement [TouchpointSignInSocketProviding]. Web hears those events on
         * its one socket and needs no such hook.
         *
         * @throws TouchpointException.InvalidConfiguration with [authentication] and a client that does
         *   not implement [TouchpointSignInSocketProviding].
         */
        public fun live(
            context: Context,
            region: String,
            authentication: ChatAuthentication?,
            customChatClient: AWSClient,
        ): ConnectChatTransport {
            if (authentication != null && customChatClient !is TouchpointSignInSocketProviding) {
                throw TouchpointException.InvalidConfiguration("sign-in with a custom chat client needs it to implement TouchpointSignInSocketProviding")
            }
            return ConnectChatTransport(ConnectChatSessionDriver.create(context, region, customChatClient), authentication = authentication, usesCustomChatClient = true)
        }

        private val json = Json { ignoreUnknownKeys = true }
        private val END_SETTLE = 500.milliseconds

        internal fun normalise(item: RawTranscriptItem): Normalised {
            val role = ParticipantRole.fromConnectRole(item.participantRole)
            return when (item.kind) {
                RawTranscriptItem.Kind.EVENT ->
                    // The SDK's history replay drops ParticipantId: a join or leave without one is
                    // ignored below, never keyed on nothing.
                    when (item.contentType) {
                        ConnectContentType.JOINED ->
                            item.participantId?.let { Normalised.Participant(TransportEvent.ParticipantJoined(Participant(it, role, item.displayName))) }
                                ?: Normalised.Ignore
                        ConnectContentType.LEFT ->
                            item.participantId?.let { Normalised.Participant(TransportEvent.ParticipantLeft(it)) } ?: Normalised.Ignore
                        else -> Normalised.Ignore // typing, receipts-as-items, transfer: not transcript content
                    }
                RawTranscriptItem.Kind.MESSAGE -> {
                    val outgoing = item.isOutgoing || role == ParticipantRole.CUSTOMER
                    // A file: its name as the words, as web shows it; what it is beside them.
                    if (item.attachmentId != null) {
                        return Normalised.Message(
                            StoredMessage(
                                clientMessageId = UUID.randomUUID(),
                                serverMessageId = item.id,
                                body = item.text.orEmpty(),
                                direction = if (outgoing) Direction.OUTBOUND else Direction.INBOUND,
                                participantRole = role,
                                participantId = item.participantId,
                                sequence = sequence(item.absoluteTime),
                                createdAt = instant(item.absoluteTime) ?: Instant.now(),
                                deliveryState = if (outgoing) DeliveryState.SENT else DeliveryState.DELIVERED,
                                attachmentId = item.attachmentId,
                                attachmentContentType = item.contentType,
                            ),
                        )
                    }
                    var body = item.text.orEmpty()
                    var interactive: String? = null
                    if (item.contentType == ConnectContentType.INTERACTIVE && item.text != null) {
                        val (template, title) = interactiveSummary(item.text)
                        // An assistant's interim line ("Looking that up…") is not a message: web shows it
                        // as the interim line, until the next message; never a blank bubble.
                        if (template == InteractiveContent.CHOICE_TEMPLATE && modalityType(item.text) == "interim") {
                            // As web: the payload's message, and none clears the line.
                            return Normalised.Interim(interimText(item.text).orEmpty(), item.id)
                        }
                        interactive = InteractiveContent.encode(InteractiveContent(template, item.text))
                        // The payload lives in interactiveJson only; raw JSON must never reach a transcript.
                        body = title.orEmpty()
                    } else if (item.contentType == ConnectContentType.JSON && item.text != null) {
                        // A JSON message, as web reads one: its messages' text, its choices kept with it.
                        interactive = InteractiveContent.encode(InteractiveContent(JSON_MESSAGE, item.text))
                        body = jsonText(item.text)
                    } else if (item.contentType == ConnectContentType.INTERACTIVE_RESPONSE && item.text != null) {
                        // The customer's answer — ours, or from another device. Shown as its words; only
                        // the template is kept beside them, since the response repeats the words.
                        val (template, title) = interactiveSummary(item.text)
                        interactive = InteractiveContent.encode(InteractiveContent(template, rawJson = ""))
                        body = title ?: actionOf(item.text).orEmpty()
                    }
                    Normalised.Message(
                        StoredMessage(
                            clientMessageId = UUID.randomUUID(),
                            serverMessageId = item.id,
                            body = body,
                            direction = if (outgoing) Direction.OUTBOUND else Direction.INBOUND,
                            participantRole = role,
                            participantId = item.participantId,
                            sequence = sequence(item.absoluteTime),
                            createdAt = instant(item.absoluteTime) ?: Instant.now(),
                            deliveryState = if (outgoing) DeliveryState.SENT else DeliveryState.DELIVERED,
                            interactiveJson = interactive,
                        ),
                    )
                }
            }
        }

        /**
         * Template type and a human-readable title from an interactive payload, leniently: the
         * content's title, else its subtitle, as web reads a quick reply or list picker; for web's own
         * template, an assistant's message.
         */
        private fun interactiveSummary(text: String): Pair<String, String?> {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return "unknown" to null
            val template = obj.string("templateType") ?: "unknown"
            val content = runCatching { obj["data"]?.jsonObject?.get("content")?.jsonObject }.getOrNull()
            if (template == InteractiveContent.CHOICE_TEMPLATE && content?.get("modalityPayload") != null) {
                val payload = runCatching { content["modalityPayload"]?.jsonObject }.getOrNull()
                return template to (payload?.string("message") ?: content.string("title"))
            }
            val title = content?.string("title")?.takeIf { it.isNotBlank() } ?: content?.string("subtitle")
            return template to title
        }

        /** An interim line's words: the payload's message, as web reads it. */
        private fun interimText(text: String): String? =
            runCatching { json.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("content")?.jsonObject?.get("modalityPayload")?.jsonObject?.string("message") }.getOrNull()

        /** Web's own template's kind: "message", or "interim" for a line in progress. */
        private fun modalityType(text: String): String? =
            runCatching { json.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("content")?.jsonObject?.get("modalityPayload")?.jsonObject?.string("type") }.getOrNull()

        /** A JSON message's words, as web reads them: each message's text, or the top-level text. */
        private fun jsonText(text: String): String {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
            val messages = runCatching { obj["messages"]?.jsonArray }.getOrNull()
            if (messages != null && messages.isNotEmpty()) {
                return messages.mapNotNull { runCatching { it.jsonObject.string("text") }.getOrNull() }.filter { it.isNotBlank() }.joinToString("\n\n")
            }
            return obj.string("text").orEmpty()
        }

        /** An interactive response's `action`: its words, where it carries no title. */
        private fun actionOf(text: String): String? = runCatching { json.parseToJsonElement(text).jsonObject.string("action") }.getOrNull()

        private fun JsonObject.string(key: String): String? = runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

        /** Server `AbsoluteTime` → milliseconds since 1970: the ordering authority. `null` if it does not parse. */
        internal fun sequence(absoluteTime: String): Long? = instant(absoluteTime)?.toEpochMilli()

        internal fun instant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()

        /** Only a foreign exception's type is kept: its message can carry a URL with a token in it. */
        internal fun map(error: Exception): TouchpointException =
            error as? TouchpointException ?: TouchpointException.TransportFailure(error.javaClass.simpleName.ifEmpty { error.javaClass.name })

        private suspend fun rethrowOwnCancellation(e: Exception): Exception {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            return e
        }
    }
}
