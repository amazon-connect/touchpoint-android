@file:OptIn(com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.livesync.internal.PayloadSchema
import java.util.UUID
import com.amazon.connect.touchpoint.core.DispatchChangeReason
import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.LiveSyncControl
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.ReconnectionPolicy
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.livesync.internal.ContextPoster
import com.amazon.connect.touchpoint.livesync.internal.HttpRequestSpec
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncHttp
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncSleeper
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransport
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransportEvent
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** State of the action socket. */
public sealed interface LiveSyncConnectionState {
    /** Created but not connected yet. */
    public data object Idle : LiveSyncConnectionState

    /** The first connection attempt is in flight. */
    public data object Connecting : LiveSyncConnectionState

    /** Connected; invocations are flowing and context is being delivered. */
    public data object Connected : LiveSyncConnectionState

    /** The socket dropped and is being reopened; [attempt] counts consecutive failures. */
    public data class Reconnecting(val attempt: Int) : LiveSyncConnectionState

    /** Closed, by [LiveSyncClient.disconnect] or because the policy allows no retries. */
    public data object Disconnected : LiveSyncConnectionState
}

/**
 * Keeps the app's state in sync with a parallel conversation: the dispatch gate a human agent
 * closes, the idle and stale-contact guards, navigation.
 *
 * A client opens a receive-only WebSocket to the Live Sync backend for one Amazon Connect contact,
 * tells the conversation engine what the app can currently do through [sendContext], and calls
 * [onAction] whenever the customer asks for one of those things.
 *
 * ```kotlin
 * val client = LiveSyncClient(
 *     LiveSyncConfiguration(region = "us-west-2", conversationId = contactId, channel = LiveSyncChannel.from(Locale.getDefault())),
 *     credentials = broker,           // a CredentialBroker; keys are fetched per connection
 *     consent = ConsentDecision.HELD, // nothing is posted until the customer grants it
 *     autoConnect = false,
 * )
 * client.onAction = { invocation ->
 *     when (invocation.action) {
 *         "set_contact_info" -> model.apply(invocation.decode<ContactInfo>())
 *         else -> Unit
 *     }
 * }
 * client.connect()
 * client.setConsent(ConsentDecision.GRANTED)   // from the consent sheet
 * ```
 *
 * The client's state is confined to one coroutine lane, so any thread may call it. [onAction] and
 * [onError] are delivered on [callbackDispatcher] — the main thread by
 * default, so a handler may touch view state directly — one at a time and in
 * arrival order.
 *
 * Call [close] when the conversation is over: there is no destructor to close the
 * socket for you.
 */
public class LiveSyncClient internal constructor(
    public val configuration: LiveSyncConfiguration,
    private val credentials: LiveSyncCredentialSource,
    consent: ConsentDecision,
    /**
     * Backoff between reconnect attempts. `maxAttempts` caps the *exponent*, not the number of tries:
     * Live Sync must never give up while the call may still be live, so after the cap every attempt
     * waits the capped delay (plus one-sided jitter) and tries again. `maxAttempts == 0` disables
     * reconnecting altogether.
     */
    public val reconnection: ReconnectionPolicy,
    autoConnect: Boolean,
    private val callbackDispatcher: CoroutineDispatcher,
    private val poster: ContextPoster,
    private val transports: LiveSyncTransportFactory,
    private val sleeper: LiveSyncSleeper,
    /**
     * How long a connected socket may stay silent before the client takes the backend's 300 s
     * connection lease to have lapsed and reconnects — the zombie socket. `null` disables the guard.
     */
    public val idleReconnectThreshold: Duration? = null,
    /**
     * How long a connected socket with context posted may stay silent before
     * [LiveSyncException.ContactMayBeStale] is reported: a stale `conversationId` gives a socket that
     * connects and delivers nothing, for ever. `null` disables it.
     */
    public val staleContactThreshold: Duration? = null,
    private val idleSleeper: LiveSyncSleeper = LiveSyncSleeper { delay(it) },
) : AutoCloseable,
    LiveSyncControl {
    /**
     * Creates a client and, unless [autoConnect] is `false`, immediately opens the action socket.
     *
     * @param credentials where the deployment and API keys come from — normally the `CredentialBroker`.
     *   Consulted on every connection attempt, so a rotated key reaches the next socket.
     * @param consent the customer's decision. Defaults to held: nothing is posted until granted.
     * @param autoConnect pass `false` to defer to [connect], for instance to attach [onAction] first.
     * @param callbackDispatcher where [onAction] and [onError] run. The main thread by default.
     * @param idleReconnectThreshold the zombie-socket guard; 240 s, under the backend's 300 s lease.
     *   `null`, zero or negative disables it: a zero guard would reconnect without pause, for ever.
     * @param staleContactThreshold the stale-contact diagnostic; 45 s, past an assistant's greeting.
     *   `null`, zero or negative disables it.
     */
    public constructor(
        configuration: LiveSyncConfiguration,
        credentials: LiveSyncCredentialSource,
        consent: ConsentDecision = ConsentDecision.DEFAULT,
        reconnection: ReconnectionPolicy = ReconnectionPolicy.DEFAULT,
        autoConnect: Boolean = true,
        callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        idleReconnectThreshold: Duration? = DEFAULT_IDLE_RECONNECT,
        staleContactThreshold: Duration? = DEFAULT_STALE_CONTACT,
    ) : this(
        configuration, credentials, consent, reconnection, autoConnect, callbackDispatcher,
        LiveSyncHttp.poster, LiveSyncHttp.transports, LiveSyncSleeper { delay(it) },
        idleReconnectThreshold, staleContactThreshold, LiveSyncSleeper { delay(it) },
    )

    private val confined = Dispatchers.Default.limitedParallelism(1)

    // A failure that escapes everything below is reported, never left to crash the host.
    private val scope =
        CoroutineScope(SupervisorJob() + confined + CoroutineExceptionHandler { _, e -> if (e !is CancellationException) postError(LiveSyncException.SocketFailure(LiveSyncException.describe(e))) })

    private val mutableState = MutableStateFlow<LiveSyncConnectionState>(LiveSyncConnectionState.Idle)

    /** Current state of the action socket. */
    public val connectionState: StateFlow<LiveSyncConnectionState> = mutableState.asStateFlow()

    /**
     * Called on [callbackDispatcher] for every action the conversation engine invokes — the one place
     * the app decides what an action does. Let an `else` branch absorb names the app does not know.
     * An exception thrown here is reported to [onError] as [LiveSyncException.ActionHandlerFailed] and
     * never interrupts the socket.
     *
     * Only actions in the context last advertised reach it, each payload checked first on the device
     * against the schema advertised with it, as the Live Sync engine does not check. One that fails is
     * refused, counted ([LiveSyncDiagnostics.rejectedInvocations]), reported as
     * [LiveSyncException.InvalidPayload] and raised as a security event. The check is a JSON
     * Schema subset, fail-closed (see the security guide), which covers every schema the SDK
     * generates; a schema outside it is reported as [LiveSyncException.InvalidConfiguration] when it
     * is advertised. Keep an `else` branch anyway, and check business rules (an amount's ceiling, an
     * id that must exist) in the handler: the schema says what may come, not that it is true.
     */
    @Volatile public var onAction: ((LiveSyncActionInvocation) -> Unit)? = null

    /**
     * Called on [callbackDispatcher] for socket failures, unparseable frames, and exceptions thrown by
     * [onAction] — recoverable failures that happen outside any call the app made.
     */
    @Volatile public var onError: ((LiveSyncException) -> Unit)? = null

    /**
     * Called on [callbackDispatcher] when the client needs the app's context again: consent re-granted
     * after a denial cleared it (the grant may come from the SDK's own consent sheet). Until context is
     * sent, every invocation is refused as not advertised. A [TouchpointActionAggregator] built on this
     * client sets it to its [TouchpointActionAggregator.readvertise]; a host advertising by hand sets it
     * to send its current context again.
     */
    @Volatile public var onContextNeeded: (() -> Unit)? = null

    // Unbounded: an invocation is never dropped for a slow observer.
    // `null` is the end: teardown sends it, and every collector completes.
    private val invocationFlow = MutableSharedFlow<LiveSyncActionInvocation?>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * Every action invoked by the conversation engine, for observers that want the raw feed. [onAction]
     * is called whether or not anyone collects this. Hot: a collector sees what arrives after it starts,
     * in the collector's own context — unlike [onAction], not on [callbackDispatcher].
     */
    public val invocations: Flow<LiveSyncActionInvocation> =
        // Completes on teardown: a collector must not wait for ever on a client that is done.
        // Checked once subscribed, so a teardown can't fall between the check and the subscription:
        // before, it is seen here; after, its end marker reaches this collector.
        invocationFlow
            .onSubscription { if (tornDown) emit(null) }
            .transformWhile { invocation -> invocation?.let { emit(it) } != null }

    /**
     * Called for every `navigation` frame. They arrive whether or not the app advertised
     * destinations, so an app that does not navigate for the assistant can leave this `null`: the
     * frame is then counted in [diagnostics]. Gated as [onAction] is.
     */
    @Volatile public var onNavigation: ((LiveSyncNavigation) -> Unit)? = null

    /** Called whenever [dispatchState] changes, with why: an agent joining or leaving, or a call. Never silent. */
    @Volatile public var onDispatchStateChange: ((DispatchState, DispatchChangeReason?) -> Unit)? = null

    private val mutableDispatch = MutableStateFlow(DispatchState.ENABLED)

    /**
     * Whether invocations reach [onAction] right now: suspended while a human agent is on the
     * contact, and from a cold resume until the transcript is reconciled. One that arrives while
     * suspended is dropped and counted; one already inside [onAction] completes.
     */
    public val dispatchState: StateFlow<DispatchState> = mutableDispatch.asStateFlow()

    /**
     * Actions the customer must confirm before [onAction] hears them, by name: an invocation of
     * one is held as [pendingConfirmation] until [confirmPendingAction] or [cancelPendingAction]. A
     * [TouchpointActionAggregator] keeps this to what the screen declares `confirmationRequired`; a host
     * routing [onAction] by hand sets it. Replacing it drops a held invocation whose action is no longer
     * in it — its screen has gone.
     */
    public var confirmationRequirements: Map<String, ActionConfirmation>
        get() = requirements
        set(value) {
            requirements = value
            scope.launch(confined) { mutablePending.value?.let { if (it.action !in value) decline(it) } }
        }

    @Volatile private var requirements: Map<String, ActionConfirmation> = emptyMap()
    private val mutablePending = MutableStateFlow<PendingConfirmation?>(null)

    /**
     * The invocation held for the customer's answer, or `null`. Show it — [TouchpointActionConfirmation]
     * does — and call [confirmPendingAction] or [cancelPendingAction]. It goes, declined, if another
     * held action arrives meanwhile (the first stands), a human agent joins, consent is withdrawn or
     * the contact ends: nothing the customer allowed then runs later.
     */
    public val pendingConfirmation: StateFlow<PendingConfirmation?> = mutablePending.asStateFlow()

    /** The customer allowed the held invocation: [onAction] hears it now. */
    public suspend fun confirmPendingAction(): Unit =
        withContext(confined) {
            val held = mutablePending.value ?: return@withContext
            // Suspended meanwhile (an agent joined, a reconnect reconciling): declined, as on suspension.
            if (mutableDispatch.value != DispatchState.ENABLED) return@withContext decline(held)
            mutablePending.value = null
            // Checked again against what is advertised now: the screen may have moved on, or tightened
            // the schema, while the prompt was up. Refused, not declined: the customer allowed it.
            rejection(held.invocation)?.let { problem -> return@withContext reject(held.invocation, problem) }
            deliver(held.invocation)
        }

    /** The customer refused the held invocation: dropped, counted, and reported as declined. */
    public suspend fun cancelPendingAction(): Unit =
        withContext(confined) { mutablePending.value?.let { decline(it) } }

    /** Confined. Declined is declined however it came about: counted, told to [onError], and a security event. */
    private suspend fun decline(held: PendingConfirmation) {
        if (mutablePending.value?.id == held.id) mutablePending.value = null
        decline(held.action)
    }

    private suspend fun decline(action: String) {
        count { it.copy(declinedConfirmations = it.declinedConfirmations + 1) }
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncActionDeclined(action))
        report(LiveSyncException.ActionHandlerFailed(action, "declined by user"))
    }

    private val mutableDiagnostics = MutableStateFlow(LiveSyncDiagnostics())

    /** What the client saw and did not act on. Diagnostic, not a contract. */
    public val diagnostics: StateFlow<LiveSyncDiagnostics> = mutableDiagnostics.asStateFlow()

    /** `true` once the stale-contact diagnostic has fired on this connection, until a frame arrives. */
    public val contactMayBeStale: Boolean get() = staleFired

    @Volatile private var staleFired = false
    private var hasReceivedFrame = false
    private var idleGuard: Job? = null
    private var staleWatch: Job? = null
    @Volatile private var idleReconnectPending = false
    @Volatile private var tornDown = false
    @Volatile private var resumeOnForeground = false

    /** The customer's consent decision. Every context POST is gated on [ConsentDecision.GRANTED]. */
    @Volatile public var consent: ConsentDecision = consent
        private set

    // Confined to [confined].
    private var reportedConsentDenied = false

    /**
     * The last context advertised, re-posted on every (re)connection: not because an unsubscribed POST
     * is discarded — the backend keeps context ~300 s regardless of any socket — but because the re-post
     * refreshes that lease, and because a command issued in reply needs a registered socket to land on.
     */
    private var advertisedContext = LiveSyncContext()
    private var hasContextToSend = false

    /** Whether a context is held and not yet posted. For tests of the consent gate. */
    internal suspend fun hasPendingContext(): Boolean = withContext(confined) { hasContextToSend }

    // The loop and the live transport, guarded by [lock]: connect/disconnect/close are synchronous.
    private val lock = Any()
    private var loop: Job? = null

    /**
     * Which loop is current. A loop clears [loop] on its way out only if it is still its own — the
     * proof of concept's defect was a loop that ended on its own and left the slot occupied, making
     * every later connect a silent no-op — and writes state only while it is current, so a loop that
     * [disconnect] superseded cannot announce itself connected afterwards.
     */
    private var generation = 0
    private var transport: LiveSyncTransport? = null
    private var closed = false

    @Volatile private var isConnected = false

    /**
     * Consecutive 401/403s on the context POST. The socket can open with a key the context API refuses
     * (the wrong kind of key, say); each refusal re-vends and reconnects, and without this the cycle
     * would repeat at network speed. It sets a floor on the backoff until a POST succeeds.
     */
    @Volatile private var authFailures = 0

    // Context POSTs one at a time, each carrying the latest context: a slower, older snapshot must
    // not land after a newer one and replace it on the backend.
    private val posting = Mutex()

    init {
        if (autoConnect) connect()
    }

    // region Connection

    /** Opens the action socket. Does nothing when already connecting or connected, or after [close]. */
    public fun connect() {
        synchronized(lock) {
            if (closed || tornDown || loop != null) return
            val gen = ++generation
            loop = scope.launch { runLoop(gen) }
        }
    }

    /**
     * Closes the action socket and stops reconnecting. The advertised context and [onAction] are kept,
     * so a later [connect] resumes where this left off.
     */
    public fun disconnect() {
        val dropped =
            synchronized(lock) {
                generation++
                loop?.cancel()
                loop = null
                isConnected = false
                idleReconnectPending = false
                mutableState.value = LiveSyncConnectionState.Disconnected
                transport.also { transport = null }
            }
        dropped?.close()
    }

    /** Disconnects for good: the socket is closed and no later [connect] opens another. */
    override fun close() {
        synchronized(lock) { closed = true }
        disconnect()
        scope.cancel()
    }

    /**
     * Registers with [session] as its Live Sync control, so a human agent joining
     * suspends dispatch, and adopts the session's gate: a client made after a cold resume starts
     * suspended, not enabled.
     */
    public fun attach(session: TouchpointSession) {
        session.register(this)
        // Now, not when the session's lane gets to it: a frame arriving meanwhile must not slip through.
        // One atomic swap, as applyDispatch's: the session's own call landing meanwhile is not a second change.
        val reason = session.dispatchChangeReason
        if (session.dispatchState == DispatchState.SUSPENDED && mutableDispatch.getAndUpdate { DispatchState.SUSPENDED } != DispatchState.SUSPENDED) {
            onDispatchStateChange?.let { handler -> scope.launch { callback { handler(DispatchState.SUSPENDED, reason) } } }
        }
    }

    /**
     * Background: a resumable disconnect. Context and handlers are kept; the socket closes, as the
     * backend's lease lapses in the background anyway. [enterForeground] reconnects and replays.
     * Call both from the app's own lifecycle (`ProcessLifecycleOwner`'s stop and start):
     * the SDK does not observe it itself, as that would declare a component in the host's manifest.
     */
    public fun enterBackground() {
        if (tornDown) return
        resumeOnForeground = synchronized(lock) { loop != null }
        disconnect()
    }

    /** Foreground: reconnect if the client was running when it went to the background. */
    public fun enterForeground() {
        if (tornDown || !resumeOnForeground) return
        resumeOnForeground = false
        connect()
    }

    /**
     * Full teardown: the contact has ended. Closes the socket, forgets the context and the
     * handlers, and makes every later [connect] a no-op. A new contact gets a new client. The session
     * calls this when the contact ends.
     */
    override suspend fun teardown() {
        tornDown = true
        resumeOnForeground = false
        disconnect()
        withContext(confined) {
            advertisedContext = LiveSyncContext()
            hasContextToSend = false
            // Allowed after the contact ended, it would act for no one.
            mutablePending.value?.let { decline(it) }
        }
        onAction = null
        onNavigation = null
        onDispatchStateChange = null
        onError = null
        invocationFlow.tryEmit(null)
    }

    /**
     * Stops invocations reaching [onAction]: a human agent joined, or a cold resume has yet to
     * reconcile. The socket stays open — context keeps its lease — but nothing the assistant says
     * moves the app while a person is handling the customer.
     */
    override suspend fun suspendDispatch(reason: DispatchChangeReason) {
        // What the assistant asked for is not the person's to allow on its behalf.
        withContext(confined) { mutablePending.value?.let { decline(it) } }
        applyDispatch(DispatchState.SUSPENDED, reason)
    }

    /** Lets invocations through again: the last human agent left, or reconciliation found none. */
    override suspend fun resumeDispatch() {
        applyDispatch(DispatchState.ENABLED, null)
    }

    private suspend fun applyDispatch(
        state: DispatchState,
        reason: DispatchChangeReason?,
    ) {
        if (mutableDispatch.getAndUpdate { state } == state) return
        val handler = onDispatchStateChange ?: return
        callback { handler(state, reason) }
    }

    /**
     * Forces a fresh key: invalidates the source's cache and reconnects, because the key lives in the
     * socket URL and cannot reach a live socket any other way. Call this when the host backend has
     * rotated the Live Sync credentials.
     */
    public suspend fun rotateCredentials() {
        credentials.invalidateLiveSyncCredentials()
        restart()
    }

    private fun restart() {
        val wasRunning = synchronized(lock) { loop != null }
        disconnect()
        if (wasRunning) connect()
    }

    /**
     * Records the customer's decision. Granting flushes any held context; denying closes the socket and
     * clears what was advertised (revocation is immediate).
     */
    public suspend fun setConsent(decision: ConsentDecision): Unit =
        withContext(confined) {
            if (decision == consent) return@withContext
            consent = decision
            reportedConsentDenied = false
            when (decision) {
                ConsentDecision.GRANTED -> {
                    if (isConnected) sendPendingContext()
                    // A denial cleared what was advertised: ask for the screen's actions again. Until
                    // they come, every invocation is refused as not advertised.
                    if (!hasContextToSend) onContextNeeded?.let { needed -> callback { needed() } }
                }
                ConsentDecision.DENIED -> {
                    advertisedContext = LiveSyncContext()
                    hasContextToSend = false
                    mutablePending.value?.let { decline(it) }
                    disconnect()
                    // The host hears the decision's effect once, now — not on a later sendContext.
                    reportedConsentDenied = true
                    report(LiveSyncException.ConsentNotGranted())
                }
                ConsentDecision.HELD -> Unit
            }
        }

    private fun current(gen: Int): Boolean = synchronized(lock) { gen == generation }

    private fun setState(
        gen: Int,
        state: LiveSyncConnectionState,
    ) {
        synchronized(lock) { if (gen == generation) mutableState.value = state }
    }

    private fun setConnected(
        gen: Int,
        connected: Boolean,
    ) {
        synchronized(lock) {
            if (gen != generation) return
            isConnected = connected
            if (connected) mutableState.value = LiveSyncConnectionState.Connected
        }
    }

    private suspend fun runLoop(gen: Int) {
        var attempt = 0

        // Returns false when the loop should stop: cancelled, or the policy allows no retries at all.
        // Otherwise it keeps trying for ever at the capped delay: a dropped socket must not end the
        // assistant's ability to help while the call is still going.
        suspend fun backOff(): Boolean {
            if (reconnection.maxAttempts <= 0) return false
            attempt += 1
            setState(gen, LiveSyncConnectionState.Reconnecting(attempt))
            sleeper.sleep(reconnection.delay(minOf(maxOf(attempt, authFailures), reconnection.maxAttempts)))
            return currentCoroutineContext().isActive && current(gen)
        }

        try {
            while (currentCoroutineContext().isActive && current(gen)) {
                val request =
                    try {
                        makeSocketRequest()
                    } catch (e: LiveSyncException) {
                        report(e)
                        // A configuration error cannot heal by waiting; a vend failure can.
                        if (e is LiveSyncException.InvalidConfiguration) {
                            setState(gen, LiveSyncConnectionState.Disconnected)
                            return
                        }
                        if (!backOff()) break
                        continue
                    }
                setState(gen, if (attempt == 0) LiveSyncConnectionState.Connecting else LiveSyncConnectionState.Reconnecting(attempt))
                val socket = transports.makeTransport()
                val previous =
                    synchronized(lock) {
                        if (gen != generation) null.also { socket.close() } else transport.also { transport = socket }
                    }
                previous?.close()
                if (!current(gen)) return
                try {
                    val events =
                        try {
                            socket.open(request)
                        } catch (e: Exception) {
                            // An HTTP client can refuse a request outright (a header it will not send):
                            // a failed attempt, never the end of the loop.
                            report(LiveSyncException.SocketFailure(LiveSyncException.describe(e)))
                            null
                        }
                    events?.collect { event ->
                        if (!current(gen)) {
                            socket.close()
                            return@collect
                        }
                        when (event) {
                            LiveSyncTransportEvent.Opened -> {
                                hasReceivedFrame = false
                                setConnected(gen, true)
                                restartIdleGuard(socket)
                                sendPendingContext()
                            }
                            is LiveSyncTransportEvent.Text -> {
                                // The backoff resets on a frame, not the handshake: a server that
                                // accepts and drops at once must not be retried at the first delay for ever.
                                attempt = 0
                                hasReceivedFrame = true
                                staleFired = false
                                stopStaleWatch()
                                restartIdleGuard(socket)
                                ingest(event.text)
                            }
                        }
                    }
                } finally {
                    // However the collection ended — even by something escaping it — the socket goes
                    // with it: an abandoned one would keep receiving with nobody to close it.
                    socket.close()
                    synchronized(lock) { if (transport === socket) transport = null }
                    setConnected(gen, false)
                    stopIdleGuard()
                    stopStaleWatch()
                }
                if (!currentCoroutineContext().isActive || !current(gen)) return
                // The idle guard closed a quiet socket on purpose: reconnect at once, no backoff. Not if
                // the socket failed for a reason of its own meanwhile: that is reported, and backed off.
                // A policy that allows no reconnects allows this one neither.
                val idleClose = idleReconnectPending && socket.terminationError == null && reconnection.maxAttempts > 0
                idleReconnectPending = false
                if (idleClose) {
                    attempt = 0
                    continue
                }
                socket.terminationError?.let { error ->
                    report(error)
                    // A refused handshake means the key is dead: drop it, so the retry vends afresh.
                    if (error is LiveSyncException.HttpFailure && (error.statusCode == UNAUTHORIZED || error.statusCode == FORBIDDEN)) {
                        credentials.invalidateLiveSyncCredentials()
                    }
                }
                if (!backOff()) break
            }
            setState(gen, LiveSyncConnectionState.Disconnected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Nothing above should throw; if something does, the loop ends visibly, not silently.
            report(LiveSyncException.SocketFailure(LiveSyncException.describe(e)))
            setState(gen, LiveSyncConnectionState.Disconnected)
        } finally {
            synchronized(lock) { if (gen == generation) loop = null }
        }
    }

    internal suspend fun makeSocketRequest(): HttpRequestSpec {
        val creds = vendCredentials()
        configuration.validate(creds)
        // No Origin: the proof of concept sent a hardcoded one the backend never required (defect 4).
        val headers = buildMap {
            put("Cache-Control", "no-cache")
            put("Pragma", "no-cache")
            configuration.userAgent?.let { put("User-Agent", it) }
        }
        return HttpRequestSpec(configuration.socketUrl(creds), headers)
    }

    private suspend fun vendCredentials(): LiveSyncCredentials =
        try {
            credentials.liveSyncCredentials()
        } catch (e: CancellationException) {
            // Ours propagates; a source that raises one while we are active failed.
            if (!currentCoroutineContext().isActive) throw e
            throw LiveSyncException.CredentialVendingFailed(LiveSyncException.describe(e))
        } catch (e: Exception) {
            throw LiveSyncException.CredentialVendingFailed(LiveSyncException.describe(e))
        }

    // endregion

    // region Context

    /**
     * Advertises what the app can currently do. Replaces whatever was advertised before rather than
     * merging into it. While the socket is not open, or consent is not granted, the context is stored
     * and posted once both hold — so this returns without a request in that case.
     *
     * @throws LiveSyncException when the request fails or is rejected.
     */
    public suspend fun sendContext(context: LiveSyncContext): Unit =
        withContext(confined) {
            reportUnusableSchemas(context)
            advertisedContext = context
            hasContextToSend = true
            if (!consentAllowsPosting() || !isConnected) return@withContext
            try {
                postContext()
            } catch (e: LiveSyncException) {
                refused(e)
                throw e
            }
        }

    /** Advertises what the app can currently do, without building a [LiveSyncContext] first. */
    public suspend fun sendContext(
        scopes: List<String>? = null,
        actions: List<LiveSyncAction> = emptyList(),
    ): Unit = sendContext(LiveSyncContext(actions, scopes))

    /** The consent gate. [ConsentDecision.DENIED] is reported once per decision, not per call. */
    private suspend fun consentAllowsPosting(): Boolean =
        when (consent) {
            ConsentDecision.GRANTED -> true
            ConsentDecision.HELD -> false
            ConsentDecision.DENIED -> {
                if (!reportedConsentDenied) {
                    reportedConsentDenied = true
                    report(LiveSyncException.ConsentNotGranted())
                }
                false
            }
        }

    private suspend fun sendPendingContext() {
        if (!hasContextToSend || !consentAllowsPosting()) return
        try {
            postContext()
        } catch (e: LiveSyncException) {
            report(e)
            refused(e)
        }
    }

    /**
     * The key was refused (401/403): rotated or revoked. Drop the cached one and end the socket, which
     * hands it to the loop's backoff — growing with repeated refusals — and a fresh vend.
     */
    private suspend fun refused(e: LiveSyncException) {
        if (e is LiveSyncException.HttpFailure && (e.statusCode == UNAUTHORIZED || e.statusCode == FORBIDDEN)) {
            authFailures += 1
            credentials.invalidateLiveSyncCredentials()
            synchronized(lock) { transport }?.close()
        }
    }

    /** `POST /nlu/c/<deploymentKey>/connect-<language>/context`, authenticated with `nlx-api-key`. */
    internal suspend fun makeContextRequest(): HttpRequestSpec {
        val creds = vendCredentials()
        configuration.validate(creds)
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("nlx-api-key", creds.apiKey)
            configuration.userAgent?.let { put("User-Agent", it) }
        }
        return HttpRequestSpec(configuration.contextUrl(creds), headers, contextRequestBody())
    }

    internal suspend fun postContext(): Unit =
        posting.withLock {
            // A post that consent stopped is not a success: nothing was sent to go stale.
            if (!postContextNow()) return@withLock
            authFailures = 0
            armStaleWatch()
        }

    /** Whether the context was posted: `false` when consent changed during the vend. */
    private suspend fun postContextNow(): Boolean {
        val request = makeContextRequest()
        // The vend can take a while: a denial that landed meanwhile still stops this post.
        if (consent != ConsentDecision.GRANTED) return false
        val (status, body) =
            try {
                poster.post(request)
            } catch (e: LiveSyncException) {
                throw e
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                throw LiveSyncException.RequestFailed(LiveSyncException.describe(e))
            } catch (e: Exception) {
                throw LiveSyncException.RequestFailed(LiveSyncException.describe(e))
            }
        if (status !in 200..299) throw LiveSyncException.HttpFailure(status, body)
        return true
    }

    /** `{ conversationId, context: { "nlx:vpContext": … } }`. */
    internal fun contextRequestBody(): String =
        buildJsonObject {
            put("conversationId", configuration.conversationId)
            put("context", JsonObject(mapOf("nlx:vpContext" to advertisedContext.voicePlusContext())))
        }.toString()

    // endregion

    // region Invocations

    /** Parses one socket frame and dispatches it. For tests and for apps with their own transport. */
    internal suspend fun ingest(text: String) {
        val frame =
            try {
                LiveSyncInboundFrame.decode(text)
            } catch (e: LiveSyncException) {
                report(e)
                return
            }
        when (frame) {
            is LiveSyncInboundFrame.Custom -> dispatch(frame.invocation)
            is LiveSyncInboundFrame.Navigation -> {
                val handler = onNavigation
                if (handler != null && mutableDispatch.value == DispatchState.ENABLED) {
                    callback {
                        // Again after the hop, as for an action: a suspension landing meanwhile holds it back.
                        if (mutableDispatch.value == DispatchState.ENABLED) {
                            handler(frame.navigation)
                        } else {
                            count { it.copy(unhandledNavigationFrames = it.unhandledNavigationFrames + 1) }
                        }
                    }
                } else {
                    count { it.copy(unhandledNavigationFrames = it.unhandledNavigationFrames + 1) }
                }
            }
            LiveSyncInboundFrame.Input -> count { it.copy(droppedInputFrames = it.droppedInputFrames + 1) }
            LiveSyncInboundFrame.Note -> count { it.copy(noteFrames = it.noteFrames + 1) }
            LiveSyncInboundFrame.Unknown -> Unit
        }
    }

    private suspend fun dispatch(invocation: LiveSyncActionInvocation) {
        // While a human agent is on the contact nothing the assistant says moves the app — the
        // flow included, which is a dispatch path of another shape.
        if (mutableDispatch.value != DispatchState.ENABLED) {
            count { it.copy(suppressedInvocations = it.suppressedInvocations + 1) }
            return
        }
        // The service does not hold the engine to the schema the app declared, so the device does,
        // before the customer is asked and before any handler, raw-JSON ones included.
        rejection(invocation)?.let { problem ->
            reject(invocation, problem)
            return
        }
        // Held for the customer, and kept from the flow too until they allow it. One at a time:
        // a second ask while the first waits is declined, and the first stands.
        val confirmation = requirements[invocation.action]
        if (confirmation != null) {
            if (mutablePending.value != null) {
                decline(invocation.action)
            } else {
                mutablePending.value = PendingConfirmation(UUID.randomUUID(), invocation.action, confirmation.actionDescription, confirmation.message, invocation)
            }
            return
        }
        deliver(invocation)
    }

    /** An engine-supplied name, reported only if it looks like an action's. */
    private fun reportableName(name: String): String =
        name.takeIf { n -> n.length in 1..MAX_ACTION_NAME && n.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } } ?: "<unnamed>"

    /** Confined. Why [invocation] may not run, or `null`: a keyword and a pointer, never a value. */
    private fun rejection(invocation: LiveSyncActionInvocation): String? {
        val declared = advertisedContext.actions.firstOrNull { it.name == invocation.action } ?: return "not in the advertised context"
        val schema =
            declared.schema
                // Advertised without parameters: the engine has nothing to send.
                ?: return if ((invocation.payload as? JsonObject)?.isEmpty() == true) null else "parameters for an action advertised without a schema"
        return PayloadSchema.validate(invocation.payload, schema)?.toString()
    }

    /** Confined. Refused, never declined: counted, told to [onError], and a security event. */
    private suspend fun reject(
        invocation: LiveSyncActionInvocation,
        problem: String,
    ) {
        val name = reportableName(invocation.action)
        count { it.copy(rejectedInvocations = it.rejectedInvocations + 1) }
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncPayloadRejected(name))
        report(LiveSyncException.InvalidPayload(name, problem))
    }

    /** Schema problems already told to the host, so a re-advertise does not repeat them. */
    private val reportedSchemaProblems = HashSet<String>()

    /**
     * Confined. An action whose schema is outside the subset, or that the engine rewrites, can never
     * run: say so when it is advertised, once per action and problem.
     */
    private suspend fun reportUnusableSchemas(context: LiveSyncContext) {
        for (action in context.actions) {
            val schema = action.schema ?: continue
            val problems = mutableListOf<String>()
            PayloadSchema.unsupportedKeyword(schema)?.let { problems += "schema for ${action.name} uses $it; its invocations will be rejected" }
            PayloadSchema.backendUnwrappedProperty(schema)?.let {
                problems += "schema for ${action.name} has a top-level property named $it, which the Live Sync engine unwraps; its invocations may be rejected"
            }
            PayloadSchema.engineRewrite(schema)?.let {
                problems += "schema for ${action.name} has ${it.description}, which the Live Sync engine always sends as a string" +
                    if (it.admitsString) "" else "; its invocations will be rejected"
            }
            for (problem in problems) if (reportedSchemaProblems.add(problem)) report(LiveSyncException.InvalidConfiguration(problem))
        }
    }

    /** Confined. To [onAction] and the flow: an invocation that needs nothing more, or one the customer allowed. */
    private suspend fun deliver(invocation: LiveSyncActionInvocation) {
        val handler = onAction
        if (handler == null) {
            invocationFlow.tryEmit(invocation)
            return
        }
        val failure =
            withContext(callbackDispatcher) {
                // Again here: a suspension landing during the hop must hold this one back too — from
                // the flow as well, so the flow, the handler and the count agree.
                if (mutableDispatch.value != DispatchState.ENABLED) {
                    count { it.copy(suppressedInvocations = it.suppressedInvocations + 1) }
                    return@withContext null
                }
                invocationFlow.tryEmit(invocation)
                try {
                    handler(invocation)
                    null
                } catch (e: Throwable) {
                    // Only our own cancellation stays cancellation: one the handler raised while
                    // we are active — a cancelled future, a timeout of its own — is the handler failing,
                    // as is an Error such as TODO(). Neither may end the socket.
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    e
                }
            }
        failure?.let { report(LiveSyncException.ActionHandlerFailed(invocation.action, LiveSyncException.describe(it))) }
    }

    private fun count(update: (LiveSyncDiagnostics) -> LiveSyncDiagnostics) {
        mutableDiagnostics.update(update)
    }

    /** A host callback on [callbackDispatcher]; what it throws is dropped, never the socket's end. */
    private suspend fun callback(block: () -> Unit) {
        withContext(callbackDispatcher) {
            try {
                block()
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            }
        }
    }

    // region Guards

    /**
     * (Re)arms the idle guard. The backend renews its connection lease only when it delivers a
     * command, so a socket quiet past the lease is healthy to TCP and useless; there is no ping, and
     * the one remedy is to reconnect before the lease lapses.
     */
    private fun restartIdleGuard(socket: LiveSyncTransport) {
        stopIdleGuard()
        val threshold = idleReconnectThreshold?.takeIf { it.isPositive() } ?: return
        idleGuard =
            scope.launch {
                idleSleeper.sleep(threshold)
                if (!isConnected) return@launch
                count { it.copy(idleReconnects = it.idleReconnects + 1) }
                idleReconnectPending = true
                socket.close() // ends the stream; the loop reconnects at once
            }
    }

    private fun stopIdleGuard() {
        idleGuard?.cancel()
        idleGuard = null
    }

    /**
     * Armed once context is posted on a live socket with nothing received yet. Reported once until a
     * frame arrives, not once per connection: a quiet contact that reconnects is the same quiet contact.
     */
    private fun armStaleWatch() {
        val threshold = staleContactThreshold?.takeIf { it.isPositive() } ?: return
        if (!isConnected || hasReceivedFrame || staleFired || staleWatch != null) return
        staleWatch =
            scope.launch {
                idleSleeper.sleep(threshold)
                if (!isConnected || hasReceivedFrame) return@launch
                staleFired = true
                count { it.copy(staleContactWarnings = it.staleContactWarnings + 1) }
                report(LiveSyncException.ContactMayBeStale(threshold))
            }
    }

    private fun stopStaleWatch() {
        staleWatch?.cancel()
        staleWatch = null
    }

    // endregion

    private suspend fun report(error: LiveSyncException) {
        val handler = onError ?: return
        withContext(callbackDispatcher) {
            try {
                handler(error)
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                // An error handler that throws has nowhere left to report to.
            }
        }
    }

    /**
     * [report] for a caller that cannot suspend: through the client's own lane, so it keeps the
     * delivery order and stops with [close] — never a detached scope.
     */
    private fun postError(error: LiveSyncException) {
        scope.launch { report(error) }
    }

    // endregion

    public companion object {
        /** Under the backend's 300 s connection lease. */
        public val DEFAULT_IDLE_RECONNECT: Duration = 240.seconds

        /** Long enough for the assistant to finish its greeting, short enough to matter on a call. */
        public val DEFAULT_STALE_CONTACT: Duration = 45.seconds

        private const val MAX_ACTION_NAME = 64
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
    }
}

/** What a [LiveSyncClient] saw and did not act on. Diagnostic, not a contract. */
public data class LiveSyncDiagnostics(
    /** `input` frames, which nothing can act on while nothing advertises fields. */
    val droppedInputFrames: Int = 0,
    /** `navigation` frames that arrived with no [LiveSyncClient.onNavigation], or while suspended. */
    val unhandledNavigationFrames: Int = 0,
    /** Invocations that arrived while dispatch was suspended. */
    val suppressedInvocations: Int = 0,
    /** Invocations of a `confirmationRequired` action that never ran: refused, a second ask, or dropped by an end. */
    val declinedConfirmations: Int = 0,
    /** Invocations refused on the device before any handler: not advertised, or not what the declared schema allows. */
    val rejectedInvocations: Int = 0,
    /** Lines the assistant spoke, delivered on the action socket. */
    val noteFrames: Int = 0,
    /** Times the idle guard closed a socket gone quiet past the lease. */
    val idleReconnects: Int = 0,
    /** Times the stale-contact diagnostic fired. */
    val staleContactWarnings: Int = 0,
)

/** What the customer is shown for an action they must confirm: what it does, and the app's own words for it, if any. */
public data class ActionConfirmation(
    val actionDescription: String,
    val message: String? = null,
)

/**
 * An invocation held for the customer's answer: the [action], what it does ([actionDescription]),
 * and the app's [message] for it, if any. The payload stays inside the SDK.
 */
public class PendingConfirmation internal constructor(
    public val id: UUID,
    public val action: String,
    public val actionDescription: String,
    public val message: String?,
    internal val invocation: LiveSyncActionInvocation,
) {
    override fun toString(): String = "PendingConfirmation(action=$action)"
}
