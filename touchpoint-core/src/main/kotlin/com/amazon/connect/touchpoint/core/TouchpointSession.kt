package com.amazon.connect.touchpoint.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The session coordinator. One active contact per session — an inherited limit of the
 * upstream chat SDK, documented rather than worked around.
 *
 * Owns transports behind [ContactTransport], normalises their events into one [ContactEvent] stream,
 * drives the store, and applies the two trust-boundary rules the design cares most about:
 * participants are a **set**, and Live Sync dispatch **suspends while a human agent is
 * present**. Every dependency is an interface, so this is fully testable on the JVM.
 *
 * **Confinement.** Every piece of mutable state is touched only on a single-threaded view of the
 * default dispatcher, a single lane: state changes are atomic between
 * suspension points, and a suspension point is where another call may interleave. Public functions
 * may be called from any thread.
 *
 * **Failure.** Every exception a public function throws is a [TouchpointException], except
 * cancellation of the caller's own coroutine, which propagates as cancellation. Work the
 * session launches on its own runs in a scope whose handler absorbs anything that escapes, so
 * nothing reaches the host's uncaught-exception handler.
 *
 * Call [close] when the session is no longer needed; it ends nothing on the wire by itself, so end
 * the contact first.
 */
public class TouchpointSession(
    private val broker: CredentialBroker,
    private val store: ConversationStore,
    private val transports: Map<Modality, ContactTransport>,
    private val reconnection: ReconnectionPolicy = ReconnectionPolicy.DEFAULT,
    /**
     * How long a send may wait for its acknowledgement on a live connection before it is treated as
     * lost and resent. The default suits Connect's latency.
     */
    private val ackTimeout: Duration = 30.seconds,
) : AutoCloseable {
    // Public surface

    private val mutableState = MutableStateFlow<ContactState>(ContactState.Idle)

    /** The contact's lifecycle. A `StateFlow`, so Compose can collect it directly. */
    public val state: StateFlow<ContactState> = mutableState.asStateFlow()

    /**
     * Called with each new message from the other side of the live contact, so the host can decide
     * what to do with it — post a local notification, show an in-app banner, set a badge, or nothing
     * — remote push is out of scope. The SDK posts no notifications and
     * needs no notification permission; it does not know whether the host's chat is on screen, which
     * the host does.
     *
     * - [IncomingMessages.Origin.LIVE]: one call per inbound message as it arrives.
     * - [IncomingMessages.Origin.CATCH_UP]: the inbound messages missed while the connection was down,
     *   once the transcript is reconciled after a reconnect or a [resume] — normally in one call.
     *   Nothing when nothing was missed.
     *
     * Each message is reported at most once, and normally exactly once: one that reaches the live
     * connection before reconciliation stores it comes as live, and a reconciliation interrupted by
     * another drop reports its messages on the next one. One stored by a reconciliation that never
     * completes is not reported; it is in the transcript. Never for the customer's own messages,
     * notices, history stored before, or after the contact ends.
     *
     * Called on the session's own thread: hop to the main thread for UI. What it throws is dropped.
     * It runs only while the app runs: a reply sent while the process is gone is reported, as
     * catch-up, when it comes back and resumes.
     */
    @Volatile public var onIncomingMessages: ((IncomingMessages) -> Unit)? = null

    /**
     * The current contact's id. Stays set after an end so the ended conversation remains addressable;
     * [isActive] is the test for "something is on the wire".
     */
    @Volatile public var contactId: String? = null
        private set

    @Volatile public var modality: Modality? = null
        private set

    /**
     * The contact's events. Each collection is its own subscriber, so the SDK's UI and the host can
     * both observe a contact, and a surface that stops collecting takes no one else's events with it.
     *
     * A collector that starts mid-contact is **caught up**: the bounded replay of this contact's
     * events, plus the current state, dispatch gate and participant set synthesised from live values
     * when the replay no longer carries them. The replay is cleared when a contact ends, so a new
     * contact never re-delivers an old one's messages.
     *
     * Buffered per collector up to [SUBSCRIBER_BUFFER] events, dropping the oldest beyond that, so a
     * collector that stalls cannot hold a whole conversation in memory.
     */
    public val events: Flow<ContactEvent> =
        callbackFlow {
            // The id exists before registration, so a collector cancelled at any point — even as the
            // registering block returns — still removes its slot in `finally`.
            val id = subscriberIds.incrementAndGet()
            // close() completes every collection rather than leaving it suspended forever.
            val closed = scope.coroutineContext[Job]?.invokeOnCompletion { channel.close() }
            try {
                withContext(confined) {
                    catchUp(channel)
                    subscribers[id] = channel
                }
                awaitClose()
            } finally {
                closed?.dispose()
                scope.launch { subscribers.remove(id) }
            }
        }.buffer(SUBSCRIBER_BUFFER, BufferOverflow.DROP_OLDEST)

    /**
     * `true` once a [LiveSyncControl] has been registered. Lets a host tell "chat-only, gating is a
     * no-op by design" from "Live Sync linked but never registered".
     */
    public val isLiveSyncRegistered: Boolean get() = liveSync != null

    /** The sign-in card, when the flow asked the customer to sign in on this contact. Gone with the contact. */
    @Volatile public var authenticationCard: AuthenticationCard? = null
        private set

    /**
     * Whether a contact is live: connecting, connected, or reconnecting. `Idle`, `Suspended` and
     * `Ended` are not.
     */
    public val isActive: Boolean get() = isLive(mutableState.value)

    /**
     * `touchpoint-livesync` registers here on creation. Nothing registered → the gating
     * calls are no-ops. The session **retains** the control: a host that drops its own reference must
     * not silently lose the Live Sync safety gate.
     */
    /** Whether Live Sync may move the app now, and why not when it may not. */
    public val dispatchState: DispatchState get() = dispatch

    /** Why [dispatchState] is suspended; `null` while enabled. */
    public val dispatchChangeReason: DispatchChangeReason? get() = gate.let { g -> g.reason.takeIf { g.state == DispatchState.SUSPENDED } }

    public fun register(liveSync: LiveSyncControl) {
        this.liveSync = liveSync
        // A control registered while dispatch is already off is told so: it must not start enabled.
        scope.launch {
            val (state, reason) = gate
            if (state == DispatchState.SUSPENDED && reason != null && this@TouchpointSession.liveSync === liveSync) {
                hostCallback { liveSync.suspendDispatch(reason) }
            }
        }
    }

    /** Start a chat contact. Throws if a contact is already active. */
    public suspend fun startChat(request: ChatContactRequest): Unit = start(Modality.CHAT) { broker.startChatContact(request) }

    /** Start an outbound voice contact. Throws if a contact is already active. */
    public suspend fun startVoice(request: VoiceContactRequest): Unit = start(Modality.VOICE) { broker.startVoiceContact(request) }

    /** End the contact. Retains the conversation; queued messages become `FAILED`. */
    public suspend fun end(): Unit =
        onSession {
            if (isActive || mutableState.value == ContactState.Suspended) teardown(EndReason.CUSTOMER_ENDED)
        }

    /**
     * Resume after process death or from a notification tap. Order is deliberate: rehydrate,
     * restore the participant set, **default dispatch to suspended**, connect with the persisted
     * credentials, reconcile the transcript with the server, and only then re-enable dispatch — and
     * only if the reconciled set holds no human agent.
     *
     * Throws [TouchpointException.ContactNotFound] when the conversation is unknown **or its
     * credentials are gone**, which is the case for every contact that has ended: those stay readable
     * through [transcript] but are not resumable on the wire.
     */
    public suspend fun resume(contactId: String): Unit =
        onSession {
            claimStart()
            val transport: ContactTransport
            val credentials: ChatContactCredentials
            try {
                val record = store.conversation(contactId) ?: throw TouchpointException.ContactNotFound()
                // Voice is not resumable (a dead meeting); an ended chat has no token.
                if (record.modality != Modality.CHAT) throw TouchpointException.ContactNotFound()
                credentials = broker.storedChatCredentials(contactId) ?: throw TouchpointException.ContactNotFound()
                transport = transports[Modality.CHAT] ?: throw TouchpointException.InvalidConfiguration("no transport registered for chat")
                this.contactId = contactId
                modality = Modality.CHAT
                participants = record.participants
                replay.clear() // before the first emit, so resume's own events are replayable
                authenticationCard = null
                // Safe path first, before anything can dispatch.
                setDispatch(DispatchState.SUSPENDED, DispatchChangeReason.RESUMED_UNRECONCILED)
                emit(ContactEvent.ParticipantsChanged(participants))
                this.credentials = credentials
                this.transport = transport
                attempt = 0
                reconnectStarted = null
                needsReconciliation = true
                contactGeneration++
                // Deliberately not resetting dispatch: resume has just suspended it as the safe default,
                // and reconciliation is what earns the right to lift it.
                setState(ContactState.Connecting)
            } finally {
                starting = false
            }
            startPump(transport)
            connectOrFail(transport, credentials, contactGeneration)
        }

    /**
     * Host-driven read receipt: the host knows what is on screen. Marks every inbound message
     * up to and including the one whose client id is [upTo] as read locally, and sends one `read` receipt on the wire
     * for the newest of them. Idempotent.
     */
    public suspend fun markMessagesRead(upTo: UUID): Unit =
        onSession {
            val id = contactId
            val transport = transport
            if (id == null || transport == null) throw TouchpointException.InvalidConfiguration("no active contact")
            val rows = store.messages(id)
            val index = rows.indexOfFirst { it.clientMessageId == upTo }
            if (index < 0) throw TouchpointException.ContactNotFound()
            val unread = rows.subList(0, index + 1).filter { it.direction == Direction.INBOUND && it.deliveryState == DeliveryState.DELIVERED }
            if (unread.isEmpty()) return@onSession
            unread.lastOrNull { it.serverMessageId != null }?.serverMessageId?.let { serverId ->
                try {
                    transport.markRead(serverId)
                } catch (e: Exception) {
                    throw mapTransportError(rethrowOwnCancellation(e))
                }
            }
            for (m in unread) store.updateDeliveryState(m.clientMessageId, DeliveryState.READ)
        }

    /**
     * Queue a message: persist it as `SENDING` (the optimistic echo), surface it, then flush the
     * outbox. Returns the client id — the idempotency key. Offline, the message simply waits; the next
     * `Connected` flushes it. At-least-once, with client-side deduplication.
     */
    public suspend fun send(body: String): UUID = queue(body, marker = null)

    /**
     * Queue the customer's answer to an interactive message — a quick reply's, a list picker's, an
     * assistant's choice — as Touchpoint web answers it. [text] is the choice's words, which the
     * transcript shows and the response carries, normalised once as web sends them
     * ([InteractiveContent.choiceText]); [choiceId] the choice's own id, where it has one other than its
     * words. The transport builds the response each time it sends. The same outbox, ordering and
     * at-least-once delivery as [send].
     *
     * Beside the encrypted words only the template is stored — and [choiceId] when it differs from
     * [text], in the clear: pass an opaque id there, never words.
     */
    public suspend fun sendChoice(
        text: String,
        choiceId: String = text,
    ): UUID {
        val raw = if (choiceId == text) "" else buildJsonObject { put("choiceId", choiceId) }.toString()
        return queue(InteractiveContent.choiceText(text), InteractiveContent.encode(InteractiveContent(InteractiveContent.CHOICE_TEMPLATE, raw)))
    }

    /**
     * Fill a slot from a modality — a carousel card picked, a date chosen — as web's `sendSlots`: the
     * wire carries web's `structured.slots` response, and the transcript draws no customer bubble, as
     * web's draws none; the modality shows the pick itself. The same outbox as [send], so a pick made
     * offline goes out on reconnect. [value] is stored encrypted, as a body is.
     */
    public suspend fun sendSlot(
        id: String,
        value: String,
    ): UUID = queue(value, InteractiveContent.encode(InteractiveContent(InteractiveContent.SLOT_TEMPLATE, buildJsonObject { put("slotId", id) }.toString())))

    /**
     * Answer a Connect View, as web's `submitView`: the [action] the customer took in Connect's
     * renderer (its `Submit`, say), what they entered as JSON ([data]), and the view's name. The wire
     * carries web's `ViewResource` response, which moves the flow on; the transcript draws no customer
     * bubble for it. The same outbox as [send]; [data] is stored encrypted, as a body is.
     */
    public suspend fun submitView(
        action: String,
        data: String,
        viewName: String? = null,
    ): UUID =
        queue(
            data,
            InteractiveContent.encode(
                InteractiveContent(
                    InteractiveContent.VIEW_RESPONSE_TEMPLATE,
                    buildJsonObject {
                        put("action", action)
                        viewName?.let { put("viewName", it) }
                    }.toString(),
                ),
            ),
        )

    /**
     * A security rejection the SDK's own UI made — a guide page's blocked navigation — to [events] as a
     * [ContactEvent.Failed] that ends nothing, as a transport's are.
     */
    @InternalTouchpointApi
    public suspend fun reportSecurityRejection(error: TouchpointException): Unit = onSession { emit(ContactEvent.Failed(error)) }

    /** A Connect View, resolved for Connect's renderer by the token its message carries. Requires a live contact. */
    public suspend fun describeView(viewToken: String): ResolvedView {
        val transport = onSession { transport.takeIf { isActive } } ?: throw TouchpointException.InvalidConfiguration("no active contact")
        return try {
            transport.describeView(viewToken)
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            throw mapTransportError(e)
        }
    }

    private suspend fun queue(
        body: String,
        marker: String?,
    ): UUID =
        onSession {
            val id = contactId
            val transport = transport
            if (id == null || transport == null) throw TouchpointException.InvalidConfiguration("no active contact")
            val message =
                StoredMessage(
                    UUID.randomUUID(), body = body, direction = Direction.OUTBOUND, participantRole = ParticipantRole.CUSTOMER,
                    deliveryState = DeliveryState.SENDING, interactiveJson = marker,
                )
            store.append(id, message)
            // The optimistic echo is an event like any other: a UI bound only to `events` must show
            // the bubble now, not when it next rebuilds from the store.
            emit(ContactEvent.MessageReceived(Message(message)))
            if (mutableState.value == ContactState.Connected) scheduleFlush(contactGeneration)
            message.clientMessageId
        }

    /**
     * Tell the other side the customer is typing, as Touchpoint web does on each change to the input:
     * at most once every 2.5 seconds — Connect lets a typing event lapse after a few — and only while
     * connected. Best effort: a failure is not the host's to handle, and never fails a send.
     */
    public suspend fun sendTyping() {
        // Decided on the session's own thread, as every other call is: the transport, the state and the
        // throttle together, so a keystroke racing end() sends nothing, and two callers send once.
        val transport =
            onSession {
                val t = transport
                val last = lastTypingSent
                if (t == null || mutableState.value != ContactState.Connected || (last != null && last.elapsedNow() < TYPING_INTERVAL)) {
                    null
                } else {
                    lastTypingSent = TimeSource.Monotonic.markNow()
                    t
                }
            } ?: return
        try {
            transport.sendTyping()
        } catch (e: Exception) {
            rethrowOwnCancellation(e) // anything else: the indicator just does not show
        }
    }

    /**
     * Send a file, as Touchpoint web does: uploaded at once, and in the transcript once Connect has it
     * — not before, and not queued. A failed upload throws, and is the caller's to show; the customer
     * picks the file again (web offers no retry), so the SDK never keeps a copy. Up to
     * [AttachmentUpload.MAX_BYTES]; which types Connect takes is its own setting. Requires a connected
     * contact. Returns the transcript row's client id.
     */
    public suspend fun sendAttachment(file: AttachmentUpload): UUID {
        if (file.sizeBytes > AttachmentUpload.MAX_BYTES) throw TouchpointException.InvalidConfiguration("the file is over ${AttachmentUpload.MAX_BYTES} bytes")
        val (id, transport, generation) =
            onSession {
                val id = contactId
                val t = transport
                if (id == null || t == null || mutableState.value != ContactState.Connected) throw TouchpointException.InvalidConfiguration("no connected contact")
                Triple(id, t, contactGeneration)
            }
        // Off the session's thread: an upload takes as long as the network does, and nothing waits on it.
        val attachmentId =
            try {
                transport.uploadAttachment(file)
            } catch (e: Exception) {
                throw mapTransportError(rethrowOwnCancellation(e))
            }
        return onSession {
            val message =
                StoredMessage(
                    UUID.randomUUID(), body = file.name, direction = Direction.OUTBOUND, participantRole = ParticipantRole.CUSTOMER,
                    deliveryState = DeliveryState.SENT, attachmentId = attachmentId, attachmentContentType = file.contentType, attachmentSize = file.sizeBytes,
                )
            // Stored with the contact it went to, even if that has ended since: the file did reach it.
            // Shown only while it is still this contact, never in the next one's transcript. Its echo
            // may have come first; the store keeps one row either way, and that row's id is returned.
            // A store that fails throws to the caller, as send's does: never an id for a row that is not there.
            persisting { if (contactGeneration == generation) appendAndEmit(id, message) else store.append(id, message) }
            // The row is in; a lookup that fails now does not make the send a failure.
            try {
                store.messageIdForAttachment(id, attachmentId)
            } catch (e: Exception) {
                rethrowOwnCancellation(e)
                null
            } ?: message.clientMessageId
        }
    }

    /**
     * A short-lived address for [attachment], asked for afresh each time as Touchpoint web asks, so it
     * never goes stale. Requires the contact still to be live.
     */
    public suspend fun attachmentUrl(attachment: Attachment): String {
        val transport = onSession { transport } ?: throw TouchpointException.InvalidConfiguration("no active contact")
        return try {
            transport.attachmentUrl(attachment.id)
        } catch (e: Exception) {
            throw mapTransportError(rethrowOwnCancellation(e))
        }
    }

    /** Confined, as the transport it throttles. */
    private var lastTypingSent: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Re-send a message whose delivery failed. A failed row is terminal, so this queues a **new**
     * outbound message: the failed row stays as the record of what happened, and each attempt carries
     * its own idempotency key. Requires an active contact. Returns the new client id.
     */
    public suspend fun resend(failed: Message): UUID {
        if (failed.deliveryState != DeliveryState.FAILED) {
            throw TouchpointException.InvalidConfiguration("resend is for a message whose delivery failed; this one is ${failed.deliveryState.rawValue}")
        }
        // An answer is re-sent as an answer, with its choice; a filled slot and a view's answer as theirs.
        val answer = failed.interactive?.takeIf { it.templateType in RESENT_TEMPLATES }
        return queue(failed.body, answer?.let(InteractiveContent::encode))
    }

    /** The active conversation's transcript, projected. */
    public suspend fun transcript(): List<Message> = contactId?.let { transcript(it) }.orEmpty()

    /**
     * Any stored conversation's transcript — including ended ones, which are viewable but not
     * resumable (retention).
     */
    public suspend fun transcript(contactId: String): List<Message> = persisting { store.messages(contactId).map(::Message) }

    /**
     * One page of the active conversation's transcript, oldest-first, walking backwards from
     * [before]. The UI's "load earlier" path; [MessagePage.hasMore] tells it when to stop offering.
     */
    public suspend fun earlierTranscript(before: Message?, limit: Int = 50): MessagePage {
        val id = contactId ?: return MessagePage(emptyList(), hasMore = false)
        val slice = persisting { store.messages(id, before?.clientMessageId, limit) }
        return MessagePage(slice.messages.map(::Message), slice.hasMore)
    }

    public suspend fun participants(): ParticipantSet = onSession { participants }

    /**
     * The name of everyone who has been on the current contact, by participant id — those who have
     * left included, as their messages still carry them — from the store, so a relaunch keeps them.
     */
    public suspend fun participantNames(): Map<String, String> =
        onSession {
            val stored =
                contactId?.let { id ->
                    try {
                        store.conversation(id)?.participantNames
                    } catch (e: Exception) {
                        rethrowOwnCancellation(e)
                        null
                    }
                }.orEmpty()
            stored + participants.participants.filter { it.displayName.isNotBlank() }.associate { it.id to it.displayName }
        }

    /**
     * The server's own transcript of the contact — its newest [limit] lines (at most 100), oldest first
     * — as Touchpoint web's Download transcript reads it: Connect's times and display names, and the
     * words of messages from every device, not only this one. Throws [TouchpointException] when there
     * is no connection to ask; the local store ([earlierTranscript]) is the fallback.
     */
    public suspend fun serverTranscript(limit: Int = SERVER_TRANSCRIPT_LIMIT): List<TranscriptEntry> {
        val transport = onSession { transport } ?: throw TouchpointException.InvalidConfiguration("no active contact")
        return try {
            transport.fetchTranscriptEntries(limit.coerceIn(1, SERVER_TRANSCRIPT_LIMIT))
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            throw mapTransportError(e)
        }
    }

    // Retention and erasure

    /** Removes one conversation, its messages, and its persisted credentials. Not for the active contact. */
    public suspend fun deleteConversation(contactId: String): Unit =
        onSession {
            if (contactId == this.contactId && isActive) throw TouchpointException.InvalidConfiguration("end() the active contact before deleting it")
            persisting { store.deleteConversation(contactId) }
            broker.forgetContactCredentials(contactId)
        }

    /** Full wipe: every conversation, the field-encryption key, and every persisted contact credential. */
    public suspend fun deleteAllConversations(): Unit =
        onSession {
            if (isActive) throw TouchpointException.InvalidConfiguration("end() the active contact before wiping")
            val ids = persisting { store.allContactIds() }
            persisting { store.deleteAllConversations() }
            for (id in ids) broker.forgetContactCredentials(id)
        }

    /** Prunes conversations past the retention window, reaping their credentials with them. */
    public suspend fun pruneExpiredConversations(): Int =
        onSession {
            val ids = persisting { store.pruneExpired() }
            for (id in ids) broker.forgetContactCredentials(id)
            ids.size
        }

    /**
     * Stops the session's own work and completes every [events] collection. Does not end the contact
     * on the wire — call [end] first — and does not close the store, which the host owns.
     */
    override fun close() {
        scope.cancel()
    }

    // Internals

    private val confined =
        @OptIn(ExperimentalCoroutinesApi::class)
        Dispatchers.Default.limitedParallelism(1)
    private val scope =
        CoroutineScope(SupervisorJob() + confined + CoroutineName("touchpoint-session") + CoroutineExceptionHandler { _, _ -> })

    private val subscribers = LinkedHashMap<Long, SendChannel<ContactEvent>>()
    private val subscriberIds = AtomicLong()

    /** This contact's events, for catching a late collector up. Bounded: the transcript is read from the store. */
    private val replay = ArrayDeque<ContactEvent>()

    @Volatile private var liveSync: LiveSyncControl? = null
    private var transport: ContactTransport? = null
    private var credentials: ExpiringCredentials? = null
    private var participants = ParticipantSet()
    /**
     * The dispatch gate, and why it is in its current state (`null` until something has changed it).
     * One value, written at once: the public getters are read from any thread, and must never see a
     * suspended gate without its reason.
     */
    private data class Gate(val state: DispatchState, val reason: DispatchChangeReason?)

    @Volatile private var gate = Gate(DispatchState.ENABLED, null)
    private val dispatch: DispatchState get() = gate.state
    private var pump: Job? = null
    private var pumpToken = 0L

    /** The transport's flow ended; the next reconnect collects afresh before connecting. */
    private var pumpEnded = false
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var reconnectStarted: TimeMark? = null

    /** Set by `resume` and `Disconnected`; consumed on the next `Connected` to pull the server transcript. */
    private var needsReconciliation = false

    /** The inbound messages reconciliation has stored for [contactId] and not yet reported as catch-up. */
    private var pendingCatchUp: Pair<String, MutableList<Message>>? = null
    private var reconcileAttempt = 0
    private var reconcileJob: Job? = null

    /**
     * From a `Connected` that needs reconciliation until a reconcile completes: the participant
     * history is unconfirmed, so a leave cannot lift dispatch, and live joins and leaves are recorded
     * to be re-applied over the history.
     */
    private var reconciling = false

    // Outbox state
    private var flushJob: Job? = null
    private var flushRetry: Job? = null
    private var flushRequested = false
    private var flushAttempt = 0
    private var flushFirstFailure: TimeMark? = null

    /** From a `Connected` that reconciles until that reconcile succeeds or gives up: the flush waits for it. */
    private var flushHeld = false

    /** Identity of the current flush job, so a stale one that outlives a reconnect cannot clear its successor. */
    private var flushToken = 0L

    /**
     * Sent, acknowledgement not yet persisted, with the send time. Cleared when the ack lands, the
     * connection drops (reconciliation then adopts what the server has; the rest is resent), or the
     * contact ends. An entry older than [ackTimeout] is treated as lost and resent.
     */
    private val inFlight = HashMap<UUID, TimeMark>()
    private var ackWatchdog: Job? = null
    private val liveParticipantEvents = ArrayList<TransportEvent>()
    private val reconcilePending: Boolean get() = needsReconciliation || reconciling

    /**
     * Held from a start or resume's first check until its state is `Connecting`, across the vend and
     * the store reads, so a second call that arrives meanwhile is refused rather than also passing
     * the "already active" check.
     */
    private var starting = false

    /**
     * Bumped on every start, resume and teardown. Long-running work captures it and stops at the
     * first suspension after which it no longer matches: a result for a contact that has since ended
     * must not be emitted, persisted against the wrong row, or — above all — lift dispatch.
     */
    private var contactGeneration = 0L

    private val isTerminal: Boolean get() = mutableState.value is ContactState.Ended

    private suspend fun <T> onSession(block: suspend () -> T): T = withContext(confined) { block() }

    private fun claimStart() {
        if (!scope.isActive) throw TouchpointException.InvalidConfiguration("the session is closed")
        if (isActive || starting) throw TouchpointException.InvalidConfiguration("a contact is already active; call end() first")
        starting = true
    }

    private suspend fun start(
        modality: Modality,
        vend: suspend () -> ExpiringCredentials,
    ): Unit =
        onSession {
            claimStart()
            val transport: ContactTransport
            val credentials: ExpiringCredentials
            try {
                transport = transports[modality] ?: throw TouchpointException.InvalidConfiguration("no transport registered for ${modality.rawValue}")
                credentials = vend()
                val id = contactIdOf(credentials)
                try {
                    persisting { store.upsertConversation(id, modality) }
                } catch (e: Throwable) {
                    // The broker has already persisted the token; with no conversation row nothing
                    // would ever reap it.
                    withContext(NonCancellable) { hostCallback { broker.forgetContactCredentials(id) } }
                    throw e
                }
                contactId = id
                this.modality = modality
                this.credentials = credentials
                this.transport = transport
                participants = ParticipantSet()
                attempt = 0
                reconnectStarted = null // a new contact gets a fresh reconnection budget
                contactGeneration++
                replay.clear() // a new contact starts a new replay
                authenticationCard = null
                // Dispatch is per contact. A fresh contact has no participants, so inheriting the
                // previous one's suspension would leave Live Sync off with nothing to re-enable it.
                gate = Gate(DispatchState.ENABLED, null)
                setState(ContactState.Connecting)
            } finally {
                starting = false
            }
            startPump(transport)
            connectOrFail(transport, credentials, contactGeneration)
        }

    /**
     * A connect failure is thrown to the caller because there is one; the stream also sees the
     * terminal events so a UI bound only to `events` is not left in `Connecting`. A caller cancelled
     * mid-connect ends the contact as a lost connection, which keeps its credentials resumable.
     *
     * Only while [generation] is still the contact's: a connect that fails after `end()` — the normal
     * way an aborted connect reports — must not end a contact that has since replaced it, nor end
     * the same one twice.
     */
    private suspend fun connectOrFail(
        transport: ContactTransport,
        credentials: ExpiringCredentials,
        generation: Long,
    ) {
        try {
            transport.connect(credentials)
        } catch (e: Exception) {
            val stillOurs = generation == contactGeneration
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                if (stillOurs) teardown(EndReason.CONNECTION_LOST)
                throw e
            }
            val error = mapTransportError(e)
            if (stillOurs) fail(error, EndReason.CONNECTION_LOST)
            throw error
        }
    }

    private fun setState(next: ContactState) {
        if (mutableState.value == next) return
        mutableState.value = next
        emit(ContactEvent.StateChanged(next))
    }

    private fun emit(event: ContactEvent) {
        if (isReplayable(event)) {
            replay.addLast(event)
            while (replay.size > REPLAY_LIMIT) replay.removeFirst()
        }
        // A collector that has gone removes its own slot; one whose channel is already closed is
        // reclaimed here too, so a slot can never outlive its consumer for long.
        val dead = subscribers.entries.filter { it.value.trySend(event).isClosed }.map { it.key }
        dead.forEach(subscribers::remove)
    }

    /**
     * Replay, then the persistent conditions **only if the replay did not already carry them**. The
     * replay is bounded, so the event that set a condition can have been evicted — and a surface that
     * believed dispatch was enabled would be the failure the gate exists to prevent — while a host reacting to every
     * transition must not be handed the same one twice.
     */
    private fun catchUp(channel: SendChannel<ContactEvent>) {
        for (event in replay) channel.trySend(event)
        val state = mutableState.value
        if (state != ContactState.Idle && replay.none { it is ContactEvent.StateChanged && it.state == state }) {
            channel.trySend(ContactEvent.StateChanged(state))
        }
        // Only the suspended state is synthesised, with the reason that caused it: `ENABLED` is the
        // default, so announcing it would invent a transition that never happened.
        val (gateState, reason) = gate
        if (gateState == DispatchState.SUSPENDED && reason != null &&
            replay.none { it is ContactEvent.LiveSyncDispatchChanged && it.state == gateState }
        ) {
            channel.trySend(ContactEvent.LiveSyncDispatchChanged(DispatchState.SUSPENDED, reason))
        }
        // The sign-in card holds the composer while pending: a late surface must see it even when the
        // event that set it was evicted.
        val card = authenticationCard
        if (card != null && replay.none { it is ContactEvent.AuthenticationChanged && it.card == card }) {
            channel.trySend(ContactEvent.AuthenticationChanged(card))
        }
        // Not once the contact is terminal: the set belonged to a conversation that is over.
        if (participants.count > 0 && !isTerminal &&
            replay.none { it is ContactEvent.ParticipantsChanged && it.participants == participants }
        ) {
            channel.trySend(ContactEvent.ParticipantsChanged(participants))
        }
    }

    /**
     * Collects the transport's events. A flow that ends or throws while the contact is live — a
     * transport that completes its flow per connection, or one with a bug — is a lost connection,
     * not a silent stall: the session reconnects and collects afresh.
     */
    private fun startPump(transport: ContactTransport) {
        pump?.cancel()
        val generation = contactGeneration
        val token = ++pumpToken
        // Undispatched, so collection has begun by the time `connect` is called; a transport still
        // buffers until its collector attaches (the ordering contract on ContactTransport).
        pump =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var ended: String? = null
                try {
                    transport.events.collect { event ->
                        if (generation == contactGeneration) handle(event)
                    }
                } catch (e: Exception) {
                    rethrowOwnCancellation(e)
                    ended = typeName(e)
                }
                // Ours alone: a pump that a newer one replaced, or a contact that has ended, is not news.
                if (token == pumpToken && generation == contactGeneration && this@TouchpointSession.isActive) {
                    pumpEnded = true
                    // Already reconnecting — the flow ended behind the `Disconnected` it just
                    // delivered — is the expected end of a per-connection flow, not a second drop.
                    if (mutableState.value !is ContactState.Reconnecting) handle(TransportEvent.Disconnected(ended ?: "event stream ended"))
                }
            }
    }

    private suspend fun handle(event: TransportEvent) {
        when (event) {
            TransportEvent.Connected -> {
                attempt = 0
                reconnectStarted = null
                setState(ContactState.Connected)
                // Off the pump: reconciliation can take many round trips, and the pump must keep
                // delivering joins, disconnects and ends meanwhile.
                // A second `Connected` with nothing new to reconcile leaves a running reconcile alone:
                // cancelling it would leave dispatch suspended with nothing left to lift it.
                // Reconcile, then flush: reconciliation adopts any send whose acknowledgement was lost,
                // and a flush that ran first would send that message a second time.
                val generation = contactGeneration
                if (needsReconciliation) {
                    needsReconciliation = false
                    reconciling = true
                    flushHeld = true // synchronously: a send() that resumes before the job runs must still wait
                    reconcileAttempt = 0
                    liveParticipantEvents.clear()
                    reconcileJob?.cancel()
                    reconcileJob = scope.launch { if (reconcile() && currentCoroutineContext().isActive) releaseFlush(generation) }
                } else {
                    scheduleFlush(generation)
                }
            }

            TransportEvent.Reestablished -> {
                if (!isActive) return
                // As a Disconnected followed by a Connected, without the reconnect the session would
                // otherwise make: the transport already has one.
                // Before anything that suspends, as for Disconnected: what was in flight on the
                // connection that silently dropped is decided by reconciliation, and nothing sends first.
                stopFlushing()
                flushHeld = true
                if (dispatch == DispatchState.ENABLED) setDispatch(DispatchState.SUSPENDED, DispatchChangeReason.RESUMED_UNRECONCILED)
                needsReconciliation = true
                reconciling = false
                reconcileJob?.cancel()
                reconcileJob = null
                handle(TransportEvent.Connected)
            }

            is TransportEvent.Disconnected -> {
                if (!isActive) return
                val wasConnected = mutableState.value == ContactState.Connected
                // Before anything that suspends: until reconciliation decides what the server has, no
                // flush may send on this connection — nor a send() that arrives meanwhile.
                stopFlushing()
                flushHeld = true
                // Web appends "Connection lost" on every broken connection. Only a drop from a live one:
                // a failed reconnect attempt is the same outage.
                if (wasConnected) postNotice(TranscriptNotice.Kind.CONNECTION_LOST)
                // The live reconnect race. Live Sync must not replay ahead of reconciliation,
                // so dispatch goes off now and comes back only after the next `Connected` reconciles.
                if (dispatch == DispatchState.ENABLED) setDispatch(DispatchState.SUSPENDED, DispatchChangeReason.RESUMED_UNRECONCILED)
                needsReconciliation = true
                reconciling = false
                reconcileJob?.cancel()
                reconcileJob = null
                // Again after the suspension: nothing sent on the dead connection can be acknowledged
                // on it, and a request that coalesced meanwhile goes with it. Reconciliation decides
                // what the server has; the next Connected holds the outbox until it has.
                stopFlushing()
                scheduleReconnect(event.error)
            }

            is TransportEvent.MessageReceived -> {
                val id = contactId ?: return
                // The wire-level `delivered` receipt is the transport's job; `read` is the host's.
                reportingPersistence {
                    val new = appendAndEmit(id, event.message)
                    // Checked after the store by contact id: an end and a new start in between must not
                    // report the old contact's message under the new one.
                    if (new != null && contactId == id && isActive) incoming(IncomingMessages(id, listOf(new), IncomingMessages.Origin.LIVE))
                }
            }

            is TransportEvent.MessageDelivered ->
                reportingPersistence {
                    if (store.updateDeliveryState(event.clientMessageId, DeliveryState.SENT, event.serverMessageId, event.sequence)) {
                        emit(
                            ContactEvent.DeliveryStateChanged(
                                DeliveryUpdate(event.clientMessageId, DeliveryState.SENT, MessageId(event.serverMessageId), event.sequence),
                            ),
                        )
                    }
                    inFlight.remove(event.clientMessageId) // after the persist: until then the flush must still see it in flight
                }

            is TransportEvent.Receipt -> {
                val id = contactId ?: return
                reportingPersistence {
                    store.applyReceipt(id, event.serverMessageId, event.kind)?.let { clientId ->
                        emit(ContactEvent.DeliveryStateChanged(DeliveryUpdate(clientId, event.kind.deliveryState, MessageId(event.serverMessageId))))
                    }
                }
            }

            is TransportEvent.ParticipantJoined -> {
                if (reconciling) liveParticipantEvents += event
                val hadAgent = participants.humanAgentPresent
                // Web: an agent only, and once per arrival — a repeated join frame is not a second agent.
                if (event.participant.role == ParticipantRole.AGENT && event.participant.id !in participants) postNotice(TranscriptNotice.Kind.AGENT_JOINED)
                participants = participants.join(event.participant)
                persistParticipants()
                if (!hadAgent && participants.humanAgentPresent) setDispatch(DispatchState.SUSPENDED, DispatchChangeReason.HUMAN_AGENT_JOINED)
            }

            is TransportEvent.ParticipantLeft -> {
                if (reconciling) liveParticipantEvents += event
                val hadAgent = participants.humanAgentPresent
                if (participants.participants.any { it.id == event.id && it.role == ParticipantRole.AGENT }) postNotice(TranscriptNotice.Kind.AGENT_LEFT)
                participants = participants.leave(event.id)
                persistParticipants()
                // Not before the history is in: a leave is not evidence that no one else is on the
                // contact until reconciliation has said who was. Reconciliation decides then.
                if (hadAgent && !participants.humanAgentPresent && !reconcilePending) {
                    setDispatch(DispatchState.ENABLED, DispatchChangeReason.HUMAN_AGENT_LEFT)
                }
            }

            is TransportEvent.ContactEnded -> teardown(event.reason)

            // Momentary, and only while live: what the other side is doing now, never stored.
            is TransportEvent.Typing -> if (event.role != ParticipantRole.CUSTOMER) emit(ContactEvent.Typing(event.role))
            is TransportEvent.InterimMessage -> emit(ContactEvent.InterimMessage(event.text))
            // Refused for the customer's safety: the host's to log (I6); the contact goes on.
            is TransportEvent.SecurityRejection -> emit(ContactEvent.Failed(event.error))

            is TransportEvent.AuthenticationChanged -> {
                if (!isActive) return
                // A card keeps the place it was first drawn: after the newest message when the flow asked.
                val current = authenticationCard
                val after = if (current?.id == event.id) current.afterMessageId else newestMessageId()
                val card = AuthenticationCard(event.id, event.status, after)
                authenticationCard = card
                emit(ContactEvent.AuthenticationChanged(card))
            }
        }
    }

    // Sign-in, Connect's "Authenticate Customer"

    /**
     * The sign-in page for the card on screen, web's `startAuthentication`: open its URL, and hand
     * what comes back on its redirect to [completeAuthentication]. Throws when there is nothing to
     * sign in to, or Connect's 60 s window has passed — the card then says it expired.
     */
    public suspend fun beginAuthentication(): AuthenticationRequest = signingIn { it.beginAuthentication() }

    /**
     * The redirect the sign-in page came back with — the host's activity receives it on the
     * redirect's scheme. Checked against what Connect sent, then relayed to the host's backend; the
     * card resolves on Connect's own event.
     */
    public suspend fun completeAuthentication(redirect: String): Unit = signingIn { it.completeAuthentication(redirect) }

    /**
     * Continue without signing in: the flow takes its opted-out branch. With [unlessRedirected], only
     * if the page's redirect has not come back — what the chat does when the customer returns from a
     * page they closed.
     */
    public suspend fun cancelAuthentication(unlessRedirected: Boolean = false) {
        val transport = onSession { transport.takeIf { isActive } } ?: return
        try {
            transport.cancelAuthentication(unlessRedirected)
        } catch (e: Exception) {
            rethrowOwnCancellation(e) // cancelled either way, as web's
        }
    }

    /** A sign-in call on the live transport; anything it throws but cancellation is a [TouchpointException], its message kept out. */
    private suspend inline fun <T> signingIn(block: (ContactTransport) -> T): T {
        val transport = onSession { transport.takeIf { isActive } } ?: throw TouchpointException.InvalidConfiguration("no active contact")
        return try {
            block(transport)
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            throw mapTransportError(e)
        }
    }

    // Outbox

    /**
     * Start a flush for [generation] unless one is running, in which case ask it to go round again
     * (it works off a snapshot; the newcomer may have been appended after it). A flush in progress, a
     * backoff retry pending, or a reconcile still holding the outbox: coalesce into it — a newcomer
     * must never run ahead of the delay a failure earned, nor ahead of the reconcile that decides
     * which of our messages the server already has.
     */
    private fun scheduleFlush(generation: Long) {
        if (generation != contactGeneration || mutableState.value != ContactState.Connected) return
        if (flushJob != null || flushRetry != null || flushHeld) {
            flushRequested = true
            return
        }
        val token = ++flushToken
        flushJob =
            scope.launch {
                flushOutbox(generation)
                if (token != flushToken) return@launch // a stale flush clears only its own reference
                flushJob = null
                if (flushRequested) scheduleFlush(generation) // a request that arrived as we finished
            }
    }

    private fun releaseFlush(generation: Long) {
        flushHeld = false
        scheduleFlush(generation)
    }

    /**
     * Send every unacknowledged outbound message **in composition order, one at a time**, stopping at
     * the first failure so a later message never overtakes an earlier one. Each row is re-checked
     * just before its send: one acknowledged, or adopted by reconciliation, since the snapshot is no
     * longer pending. A failure on a healthy connection retries with the reconnection backoff; the
     * row stays `SENDING` and the host sees `Failed` each time. Generation-guarded: a result for a
     * contact that ended while the send was in flight is discarded.
     */
    private suspend fun flushOutbox(generation: Long) {
        // Cancelled too: a disconnect cancels this job, and a loop suspended in `send` at that moment
        // must not resume alongside the reconnect's new flush.
        suspend fun live() = generation == contactGeneration && mutableState.value == ContactState.Connected && currentCoroutineContext().isActive
        do {
            flushRequested = false
            if (!live()) return
            val id = contactId ?: return
            val transport = transport ?: return
            val pending: List<StoredMessage>
            try {
                val snapshot = store.outboxSnapshot(id)
                pending = snapshot.sendable
                // A pending row whose body cannot be read can never be sent: fail it now, visibly.
                for (clientId in snapshot.unreadable) {
                    store.updateDeliveryState(clientId, DeliveryState.FAILED)
                    emit(ContactEvent.DeliveryStateChanged(DeliveryUpdate(clientId, DeliveryState.FAILED)))
                }
            } catch (e: Exception) {
                rethrowOwnCancellation(e)
                if (!live()) return // a flush already stopped reports nothing, and earns no retry
                flushFailed(TouchpointException.PersistenceFailure(typeName(e)), generation)
                return
            }
            if (pending.isEmpty()) {
                flushAttempt = 0
                flushFirstFailure = null
                return
            }
            for (m in pending) {
                if (!live()) return
                val stillPending =
                    try {
                        store.isPending(m.clientMessageId)
                    } catch (e: Exception) {
                        rethrowOwnCancellation(e)
                        if (!live()) return
                        flushFailed(TouchpointException.PersistenceFailure(typeName(e)), generation) // stop, don't skip: order holds
                        return
                    }
                if (!stillPending) continue
                val sentAt = inFlight[m.clientMessageId]
                if (sentAt != null && sentAt.elapsedNow() < ackTimeout) continue // its ack is still expected
                inFlight[m.clientMessageId] = TimeSource.Monotonic.markNow()
                try {
                    val answer = m.interactiveJson?.let(InteractiveContent::decodeOrNull)
                    transport.send(OutboundMessage(m.clientMessageId, m.body, answer?.templateType, answer?.let(::choiceIdOf)))
                } catch (e: Exception) {
                    rethrowOwnCancellation(e)
                    if (!live()) return
                    inFlight.remove(m.clientMessageId)
                    flushFailed(mapTransportError(e), generation)
                    return
                }
                if (!live()) return
            }
            flushAttempt = 0
            flushFirstFailure = null
        } while (flushRequested)
        armAckWatchdog(generation)
    }

    /**
     * If anything still awaits its acknowledgement, wake a flush after [ackTimeout], so an ack lost on
     * a socket that never drops is recovered rather than waited on for ever.
     */
    private fun armAckWatchdog(generation: Long) {
        ackWatchdog?.cancel()
        if (inFlight.isEmpty()) {
            ackWatchdog = null
            return
        }
        ackWatchdog =
            scope.launch {
                delay(ackTimeout)
                if (inFlight.isNotEmpty()) scheduleFlush(generation)
            }
    }

    /**
     * One failure path for transport and persistence errors alike: surface it, then retry with the
     * reconnection backoff while the connection is healthy. The retry re-reads the whole outbox, so
     * anything queued meanwhile rides with it. Budget spent → this burst gives up; the next `send()`
     * starts a fresh one.
     */
    private fun flushFailed(
        error: TouchpointException,
        generation: Long,
    ) {
        emit(ContactEvent.Failed(error))
        flushAttempt++
        val first = flushFirstFailure ?: TimeSource.Monotonic.markNow().also { flushFirstFailure = it }
        if (flushAttempt > reconnection.maxAttempts || first.elapsedNow() > reconnection.totalBudget) {
            flushAttempt = 0
            flushFirstFailure = null
            armAckWatchdog(generation) // earlier sends in this burst may still be awaiting their acks
            return
        }
        val wait = reconnection.delay(flushAttempt)
        flushRequested = false
        flushRetry =
            scope.launch {
                delay(wait)
                flushRetry = null
                scheduleFlush(generation)
            }
    }

    /** On a drop or the end of the contact: no flush, retry or watchdog survives it, and nothing is in flight. */
    private fun stopFlushing() {
        flushJob?.cancel()
        flushJob = null
        flushRetry?.cancel()
        flushRetry = null
        flushRequested = false
        flushAttempt = 0
        flushFirstFailure = null
        flushHeld = false
        ackWatchdog?.cancel()
        ackWatchdog = null
        inFlight.clear()
    }

    /**
     * Reconcile with the server after a resume or reconnect. Pages **backwards from the newest**
     * until the transcript is exhausted or [RECONCILE_MAX_PAGES] is hit, because the gate decision
     * needs the *whole* participant history: an agent's join can be older than the newest page, and
     * lifting dispatch on a partial view would be the failure the gate exists to prevent. Messages and participant
     * events are applied oldest first once every page is in hand, so a leave after a join reads
     * correctly and a host appending `MessageReceived` in arrival order renders server order. Adds
     * and deduplicates only — server-side edits and deletions are out of scope for v1.
     *
     * A failure is retried with the reconnection backoff while connected — on a healthy connection
     * nothing else would ever lift the suspension. Returns whether it completed.
     */
    private suspend fun reconcile(): Boolean {
        val id = contactId ?: return false
        val transport = transport ?: return false
        val generation = contactGeneration

        // True while this reconcile still belongs to the live contact. Checked after every
        // suspension: `end()` or a failure can tear the contact down while a page is in flight.
        fun live() = generation == contactGeneration && mutableState.value == ContactState.Connected
        try {
            val pages = mutableListOf<TranscriptPage>()
            var token: String? = null
            do {
                val page = transport.fetchTranscript(token, RECONCILE_PAGE_SIZE)
                if (!live()) return false
                pages += page
                token = page.nextToken
            } while (token != null && pages.size < RECONCILE_MAX_PAGES)
            val truncated = token != null

            for (page in pages.asReversed()) { // oldest page first
                for (m in page.messages) {
                    // Kept across a reconcile a drop interrupts: the next one reports them.
                    appendAndEmit(id, m)?.let { new -> catchUp(id).add(new) }
                    if (!live()) return false
                }
                for (e in page.participantEvents) fold(e)
            }
            // The live events seen while the pages were in flight are newer than some of what the
            // pages said. They are a suffix of the true history, so re-applying them last leaves
            // every participant in its latest state — a page read before a live leave cannot
            // resurrect the agent who left.
            liveParticipantEvents.forEach(::fold)
            liveParticipantEvents.clear()
            reconciling = false
            persistParticipants()
            if (!live()) return false
            emit(ContactEvent.TranscriptReconciled)
            reportCatchUp(id)
            // Lift the suspension only on a complete history with no human present. A truncated
            // history is not evidence of absence, so dispatch stays off and the host is told.
            if (dispatch == DispatchState.SUSPENDED) {
                if (truncated) {
                    emit(
                        ContactEvent.Failed(
                            TouchpointException.TransportFailure(
                                "transcript longer than ${RECONCILE_MAX_PAGES * RECONCILE_PAGE_SIZE} items; Live Sync dispatch stays suspended",
                            ),
                        ),
                    )
                } else if (!participants.humanAgentPresent) {
                    setDispatch(DispatchState.ENABLED, DispatchChangeReason.TRANSCRIPT_RECONCILED)
                }
            }
            return true
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            if (!live()) return false
            // Not fatal — the live stream still flows — but dispatch stays suspended, because we
            // could not establish that no agent is present.
            emit(ContactEvent.Failed(mapTransportError(e)))
            reconcileAttempt++
            if (reconcileAttempt > reconnection.maxAttempts) {
                // Give up holding the outbox: at-least-once beats a queue wedged for the life of the
                // connection. The history stays unconfirmed, so dispatch stays off.
                emit(
                    ContactEvent.Failed(
                        TouchpointException.TransportFailure("transcript reconciliation gave up; queued messages are sent without it and Live Sync dispatch stays suspended"),
                    ),
                )
                // No later reconcile is coming on this healthy connection: what the attempts stored is reported now.
                reportCatchUp(id)
                releaseFlush(generation)
                return false
            }
            val wait = reconnection.delay(reconcileAttempt)
            reconcileJob =
                scope.launch {
                    delay(wait)
                    if (generation == contactGeneration && mutableState.value == ContactState.Connected && reconcile() && currentCoroutineContext().isActive) releaseFlush(generation)
                }
            return false
        }
    }

    private fun fold(event: TransportEvent) {
        when (event) {
            is TransportEvent.ParticipantJoined -> participants = participants.join(event.participant)
            is TransportEvent.ParticipantLeft -> participants = participants.leave(event.id)
            else -> Unit
        }
    }

    private fun catchUp(contactId: String): MutableList<Message> =
        pendingCatchUp?.takeIf { it.first == contactId }?.second ?: mutableListOf<Message>().also { pendingCatchUp = contactId to it }

    private suspend fun reportCatchUp(contactId: String) {
        val caught = pendingCatchUp?.takeIf { it.first == contactId }?.second?.toList().orEmpty()
        pendingCatchUp = null
        if (caught.isNotEmpty()) incoming(IncomingMessages(contactId, caught, IncomingMessages.Origin.CATCH_UP))
    }

    private suspend fun incoming(messages: IncomingMessages) {
        val handler = onIncomingMessages ?: return
        hostCallback { handler(messages) }
    }

    /**
     * Store a message from the server and tell the host what changed: a new row surfaces as
     * `MessageReceived`; an adopted echo (lost ack) as the `SENT` transition its `SENDING` bubble is
     * waiting for; a true duplicate is silent. Returns the new row when it is an inbound one — what
     * [onIncomingMessages] reports — and `null` otherwise.
     */
    private suspend fun appendAndEmit(
        contactId: String,
        message: StoredMessage,
    ): Message? {
        when (val outcome = store.append(contactId, message)) {
            ConversationStore.AppendOutcome.Appended -> {
                val new = Message(message)
                emit(ContactEvent.MessageReceived(new))
                return new.takeIf { it.direction == Direction.INBOUND }
            }
            is ConversationStore.AppendOutcome.Adopted -> {
                inFlight.remove(outcome.clientMessageId)
                emit(
                    ContactEvent.DeliveryStateChanged(
                        DeliveryUpdate(outcome.clientMessageId, DeliveryState.SENT, message.serverMessageId?.let(::MessageId), message.sequence),
                    ),
                )
            }
            ConversationStore.AppendOutcome.Duplicate -> Unit
        }
        return null
    }

    /**
     * Emits a notice anchored after the newest stored message: read from the store rather than
     * tracked, so a resumed contact — whose history this session never emitted — anchors right.
     */
    private suspend fun postNotice(kind: TranscriptNotice.Kind) {
        emit(ContactEvent.Notice(TranscriptNotice(kind, newestMessageId())))
    }

    private suspend fun newestMessageId(): UUID? =
        contactId?.let { id ->
            try {
                store.messages(id, before = null, limit = 1).messages.lastOrNull()?.clientMessageId
            } catch (e: Exception) {
                rethrowOwnCancellation(e) // unanchored, it leads the transcript: still said
                null
            }
        }

    private suspend fun persistParticipants() {
        val id = contactId ?: return
        try {
            store.setParticipants(id, participants)
        } catch (e: Exception) {
            rethrowOwnCancellation(e) // the in-memory set stays authoritative for this process
        }
        emit(ContactEvent.ParticipantsChanged(participants))
    }

    private suspend fun setDispatch(
        next: DispatchState,
        reason: DispatchChangeReason,
    ) {
        gate = Gate(next, reason)
        val control = liveSync
        if (control != null) {
            hostCallback {
                when (next) {
                    DispatchState.SUSPENDED -> control.suspendDispatch(reason)
                    DispatchState.ENABLED -> control.resumeDispatch()
                }
            }
        }
        emit(ContactEvent.LiveSyncDispatchChanged(next, reason))
    }

    /** Bounded: attempt count and total time budget, then `Ended(CONNECTION_LOST)`. */
    private suspend fun scheduleReconnect(error: String?) {
        reconnectJob?.cancel()
        // Chat retains the conversation and its outbox and reconnects within the budget. Voice cannot
        // rejoin a dead Chime meeting on new credentials, so it ends.
        val modality = modality
        if (modality != null && CredentialBroker.refreshFailurePolicy(modality) == CredentialBroker.RefreshFailurePolicy.END_CONTACT) {
            fail(TouchpointException.TransportFailure(error ?: "connection lost"), EndReason.CONNECTION_LOST)
            return
        }
        val started = reconnectStarted ?: TimeSource.Monotonic.markNow().also { reconnectStarted = it }
        attempt++
        if (attempt > reconnection.maxAttempts || started.elapsedNow() > reconnection.totalBudget) {
            fail(TouchpointException.TransportFailure(error ?: "connection lost after ${attempt - 1} attempt(s)"), EndReason.CONNECTION_LOST)
            return
        }
        setState(ContactState.Reconnecting(attempt))
        val wait = reconnection.delay(attempt)
        val generation = contactGeneration
        reconnectJob =
            scope.launch {
                delay(wait)
                if (generation != contactGeneration || mutableState.value !is ContactState.Reconnecting) return@launch
                val transport = transport ?: return@launch
                val credentials = credentials ?: return@launch
                try {
                    transport.connect(credentials)
                    // Collected afresh only once connected: a transport whose flow is per connection
                    // makes the new one in `connect`, and buffers its events until collected.
                    if (pumpEnded && generation == contactGeneration) {
                        pumpEnded = false
                        startPump(transport)
                    }
                } catch (e: Exception) {
                    rethrowOwnCancellation(e)
                    if (generation == contactGeneration) scheduleReconnect(describe(mapTransportError(e)))
                }
            }
    }

    private suspend fun fail(
        error: TouchpointException,
        reason: EndReason,
    ) {
        emit(ContactEvent.Failed(error))
        teardown(reason)
    }

    /**
     * Runs to completion even if the coroutine that triggered it is cancelled — including the pump
     * or reconnect job it cancels itself — so a contact is never left half torn down.
     */
    private suspend fun teardown(reason: EndReason): Unit =
        withContext(NonCancellable) {
            contactGeneration++ // anything still awaiting for this contact stops at its next check
            lastTypingSent = null // a new contact's first keystroke is not throttled by the last one's
            reconnectJob?.cancel()
            reconnectJob = null
            reconcileJob?.cancel()
            reconcileJob = null
            stopFlushing()
            pump?.cancel()
            pump = null
            val transport = transport
            // A lost connection keeps the participant on the contact, so `resume` can try again;
            // every other end is final.
            if (transport != null) hostCallback { if (reason == EndReason.CONNECTION_LOST) transport.release() else transport.disconnect() }
            val control = liveSync
            if (control != null) hostCallback { control.teardown() } // the SDK owns Live Sync teardown
            val id = contactId
            if (id != null) {
                // At-least-once holds only for the contact lifetime; never drop an outbox
                // silently. Each stuck row gets its own `FAILED` transition, and a store error is
                // surfaced rather than quietly skipping the sweep.
                try {
                    for (clientId in store.pendingClientIds(id)) { // raw ids: an unreadable row is failed too
                        try {
                            store.updateDeliveryState(clientId, DeliveryState.FAILED)
                            emit(ContactEvent.DeliveryStateChanged(DeliveryUpdate(clientId, DeliveryState.FAILED)))
                        } catch (e: Exception) {
                            emit(ContactEvent.Failed(TouchpointException.PersistenceFailure("outbox sweep: ${typeName(e)}")))
                        }
                    }
                } catch (e: Exception) {
                    emit(ContactEvent.Failed(TouchpointException.PersistenceFailure("outbox sweep: ${typeName(e)}")))
                }
                // A token for an ended contact is dead weight in the secure store. After a lost
                // connection the contact may still be alive server-side, so the token stays and
                // `resume` can try again.
                // Through hostCallback: a secure store that throws, even a CancellationException of its
                // own, must not stop the teardown before `Ended`.
                if (reason != EndReason.CONNECTION_LOST) hostCallback { broker.forgetContactCredentials(id) }
            }
            this@TouchpointSession.transport = null
            credentials = null
            needsReconciliation = false
            reconciling = false
            liveParticipantEvents.clear()
            pumpEnded = false
            authenticationCard = null // a card belongs to a contact that is over; no lock outlives it
            setState(ContactState.Ended(reason))
            emit(ContactEvent.ContactEnded(reason))
            // Keep only the terminal events: a surface opening after the end should see that it
            // ended, not replay the finished conversation. Dispatch goes with it — Live Sync was torn
            // down above, so telling a new collector it is "suspended" would describe nothing.
            // Notices stay: messages come back from the store, but a notice exists only here, and an
            // ended chat on web still shows the agent leaving or the connection dropping.
            val notices = replay.filterIsInstance<ContactEvent.Notice>()
            replay.clear()
            replay.addAll(notices)
            replay.addLast(ContactEvent.StateChanged(ContactState.Ended(reason)))
            replay.addLast(ContactEvent.ContactEnded(reason))
            gate = Gate(DispatchState.ENABLED, null)
        }

    /**
     * A call into host or transport code whose failure must not stop ours: a `LiveSyncControl` that
     * throws, or a transport that fails to disconnect, is absorbed.
     */
    private suspend inline fun hostCallback(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
        }
    }

    /** Store errors on the session's own paths are surfaced as events: there is no caller to throw to. */
    private suspend inline fun reportingPersistence(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            emit(ContactEvent.Failed(e as? TouchpointException ?: TouchpointException.PersistenceFailure(typeName(e))))
        }
    }

    /** Store errors on a host call are thrown to it, as a [TouchpointException]. */
    private suspend inline fun <T> persisting(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            throw e as? TouchpointException ?: TouchpointException.PersistenceFailure(typeName(e))
        }

    private companion object {
        /** Per-contact replay for late collectors. */
        const val REPLAY_LIMIT = 200

        /** Per-collector buffer: generous enough that a briefly busy consumer loses nothing. */
        const val SUBSCRIBER_BUFFER = 500
        const val RECONCILE_PAGE_SIZE = 100

        /** 20 pages × 100 items. Connect caps a chat at far fewer; this bounds a runaway server. */
        const val RECONCILE_MAX_PAGES = 20

        fun isLive(state: ContactState): Boolean =
            state == ContactState.Connecting || state == ContactState.Connected || state is ContactState.Reconnecting

        /**
         * What a late collector needs to reconstruct the contact: state, messages, delivery states,
         * participants, and the persistent dispatch condition. Excluded are the momentary ones: a
         * `Failed` from a blip that has since recovered would raise an alert for a condition that no
         * longer holds, and `TranscriptReconciled` describes an event, not a state. `PermissionRequired`
         * waits for voice, which is where tracking an *outstanding* request belongs.
         */
        fun isReplayable(event: ContactEvent): Boolean =
            when (event) {
                is ContactEvent.StateChanged, is ContactEvent.MessageReceived, is ContactEvent.DeliveryStateChanged,
                is ContactEvent.ParticipantsChanged, is ContactEvent.ContactEnded, is ContactEvent.LiveSyncDispatchChanged,
                is ContactEvent.Notice, is ContactEvent.AuthenticationChanged,
                -> true
                is ContactEvent.PermissionRequired, is ContactEvent.Failed, ContactEvent.TranscriptReconciled,
                is ContactEvent.Typing, is ContactEvent.InterimMessage,
                -> false
            }

        fun contactIdOf(credentials: ExpiringCredentials): String =
            when (credentials) {
                is ChatContactCredentials -> credentials.contactId
                is VoiceContactCredentials -> credentials.contactId
                else -> UUID.randomUUID().toString()
            }

        /** Only the type of a foreign exception is kept: its message could carry content or a token. */
        fun mapTransportError(error: Exception): TouchpointException = error as? TouchpointException ?: TouchpointException.TransportFailure(typeName(error))

        fun describe(error: TouchpointException): String = (error as? TouchpointException.TransportFailure)?.detail ?: error.toString()

        fun typeName(error: Throwable): String = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
    }
}

/** One page of a transcript walked backwards: oldest-first, with whether anything older remains. */
public data class MessagePage(val messages: List<Message>, val hasMore: Boolean)

/**
 * A `CancellationException` is ours — rethrown — only when our own coroutine is cancelled. One that a
 * transport or host callback raises while we are still active is a failure like any other.
 */
internal suspend fun rethrowOwnCancellation(e: Exception): Exception {
    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
    return e
}

/** An answer's own choice id, where it has one other than its words; `null` otherwise. */
/** A choice's own id, or a slot's: what the transport needs beside the words. */
private fun choiceIdOf(answer: InteractiveContent): String? =
    if (answer.rawJson.isEmpty()) {
        null
    } else {
        when (answer.templateType) {
            // The view's action and name travel together, as the transport needs both.
            InteractiveContent.VIEW_RESPONSE_TEMPLATE -> answer.rawJson
            else -> {
                val key = if (answer.templateType == InteractiveContent.SLOT_TEMPLATE) "slotId" else "choiceId"
                runCatching { Json.parseToJsonElement(answer.rawJson).jsonObject[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }
        }
    }

/** The interactive answers a resend keeps as they were sent. */
private val RESENT_TEMPLATES = setOf(InteractiveContent.CHOICE_TEMPLATE, InteractiveContent.SLOT_TEMPLATE, InteractiveContent.VIEW_RESPONSE_TEMPLATE)

/** Touchpoint web's typing throttle. */
private val TYPING_INTERVAL = 2_500.milliseconds

/** Web's Download transcript reads the newest 100. */
private const val SERVER_TRANSCRIPT_LIMIT = 100
