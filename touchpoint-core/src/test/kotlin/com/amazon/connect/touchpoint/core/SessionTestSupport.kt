package com.amazon.connect.touchpoint.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Scriptable transport. Tests push [TransportEvent]s and control whether connect succeeds. [events]
 * is bound to the *current* connection, like a real adapter: a coordinator that cached one flow
 * across end()/start() would never see the second contact. Events pushed while nothing is collecting
 * are buffered until a collector attaches — the ordering contract.
 */
class StubTransport : ContactTransport {
    private val lock = Any()
    private var current: SendChannel<TransportEvent>? = null
    private val buffered = mutableListOf<TransportEvent>()

    @Volatile var connectCalls = 0

    @Volatile var disconnectCalls = 0

    @Volatile var releaseCalls = 0
    val sent: MutableList<OutboundMessage> = java.util.Collections.synchronizedList(mutableListOf())

    /** Number of upcoming connects that should throw. */
    @Volatile var failConnects = 0

    /** Emit `Connected` on a successful connect. */
    @Volatile var autoConnectEvent = true

    @Volatile var transcriptPage = TranscriptPage.EMPTY

    /** Older pages, keyed by the token that reaches them. */
    val pagesByToken: MutableMap<String, TranscriptPage> = java.util.concurrent.ConcurrentHashMap()
    val fetchCalls: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
    val readMarks: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var failFetches = 0

    @Volatile var fetchDelay: Duration = Duration.ZERO

    /** Upcoming sends that should throw. */
    @Volatile var failSends = 0

    /** Emit `MessageDelivered` for each successful send. */
    @Volatile var autoAck = false

    /** Emit the server echo of our own message before the ack (Connect can). */
    @Volatile var echoBeforeAck = false

    @Volatile var sendDelay: Duration = Duration.ZERO
    private val ackSeq = java.util.concurrent.atomic.AtomicLong(1000)

    /** What a connect throws when it fails: lets a test throw a foreign or cancellation exception. */
    @Volatile var connectFailure: () -> Exception = { TouchpointException.TransportFailure("stub refused") }

    /** Suspends each connect until released, so a test can act while one is in flight. */
    @Volatile var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override val events: Flow<TransportEvent>
        get() =
            callbackFlow {
                synchronized(lock) {
                    current?.close()
                    current = channel
                    buffered.forEach { trySend(it) }
                    buffered.clear()
                }
                awaitClose { synchronized(lock) { if (current === channel) current = null } }
            }.buffer(Channel.UNLIMITED)

    private fun yield(e: TransportEvent) {
        synchronized(lock) {
            val c = current
            if (c == null || c.trySend(e).isFailure) buffered += e
        }
    }

    fun push(e: TransportEvent) = yield(e)

    override suspend fun connect(credentials: ExpiringCredentials) {
        connectCalls++
        connectGate?.await()
        if (failConnects > 0) {
            failConnects--
            throw connectFailure()
        }
        if (autoConnectEvent) yield(TransportEvent.Connected)
    }

    override suspend fun send(message: OutboundMessage) {
        // A send on the wire completes whether or not its caller is still waiting, as a real one does.
        if (sendDelay > Duration.ZERO) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(sendDelay) }
        if (failSends > 0) {
            failSends--
            throw TouchpointException.TransportFailure("send refused")
        }
        sent += message
        val seq = ackSeq.incrementAndGet()
        val sid = "srv-${message.clientMessageId.toString().take(8)}"
        if (echoBeforeAck) {
            yield(TransportEvent.MessageReceived(StoredMessage(UUID.randomUUID(), sid, message.body, Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = seq, deliveryState = DeliveryState.SENT)))
        }
        if (autoAck) yield(TransportEvent.MessageDelivered(message.clientMessageId, sid, seq))
    }

    @Volatile var entries: List<TranscriptEntry> = emptyList()

    @Volatile var entryLimits: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var failEntries = false

    override suspend fun fetchTranscriptEntries(limit: Int): List<TranscriptEntry> {
        entryLimits += limit
        if (failEntries) throw IllegalStateException("token=SECRET")
        return entries
    }

    @Volatile var typingSent = 0

    @Volatile var failTyping = false

    override suspend fun sendTyping() {
        typingSent++
        if (failTyping) throw TouchpointException.TransportFailure("typing refused")
    }

    override suspend fun disconnect() {
        disconnectCalls++
        synchronized(lock) {
            current?.close()
            current = null
        }
    }

    override suspend fun release() {
        releaseCalls++
        synchronized(lock) {
            current?.close()
            current = null
        }
    }

    override suspend fun fetchTranscript(before: String?, limit: Int): TranscriptPage {
        fetchCalls += before
        if (fetchDelay > Duration.ZERO) delay(fetchDelay)
        if (failFetches > 0) {
            failFetches--
            throw TouchpointException.TransportFailure("transcript unavailable")
        }
        return if (before != null) pagesByToken[before] ?: TranscriptPage.EMPTY else transcriptPage
    }

    override suspend fun markRead(upToServerMessageId: String) {
        readMarks += upToServerMessageId
    }

    /** Sign-in calls that reached the transport, in order. */
    val signInCalls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun beginAuthentication(): AuthenticationRequest {
        signInCalls += "begin"
        return AuthenticationRequest("https://idp.example/authorize?state=s", "app://auth")
    }

    override suspend fun completeAuthentication(redirect: String) {
        signInCalls += "complete:$redirect"
    }

    override suspend fun cancelAuthentication(unlessRedirected: Boolean) {
        signInCalls += "cancel:$unlessRedirected"
    }
}

class RecordingLiveSync : LiveSyncControl {
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun suspendDispatch(reason: DispatchChangeReason) {
        calls += "suspend:$reason"
    }

    override suspend fun resumeDispatch() {
        calls += "resume"
    }

    override suspend fun teardown() {
        calls += "teardown"
    }

    fun snapshot(): List<String> = synchronized(calls) { calls.toList() }
}

/** Collects a session's events for assertions. */
class EventCollector(session: TouchpointSession, scope: CoroutineScope) {
    private val received = java.util.Collections.synchronizedList(mutableListOf<ContactEvent>())
    val job: Job = scope.launch(Dispatchers.Default) { session.events.collect { received += it } }

    val events: List<ContactEvent> get() = synchronized(received) { received.toList() }

    fun states(): List<ContactState> = events.filterIsInstance<ContactEvent.StateChanged>().map { it.state }

    fun stop() = job.cancel()
}

/** Polls [condition] until it holds or [timeout] passes; returns the final verdict. */
suspend fun eventually(
    timeout: Duration = 2.seconds,
    condition: suspend () -> Boolean,
): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (deadline.hasNotPassedNow()) {
        if (condition()) return true
        delay(10.milliseconds)
    }
    return condition()
}

/** A store, broker and session over one directory and one secure store, so a test can "restart the process". */
class SessionFixture(
    val dir: File = createTempDirectory("tp-sess-").toFile(),
    val secureStore: SecureStore = InMemorySecureStore(),
) {
    private val stores = mutableListOf<ConversationStore>()
    private val sessions = mutableListOf<TouchpointSession>()

    suspend fun store(): ConversationStore = ConversationStore.open(dir, secureStore, BundledSQLiteDriver()).also { stores += it }

    fun broker(): CredentialBroker = CredentialBroker(ScriptedBackend(), secureStore)

    suspend fun session(
        transports: Map<Modality, ContactTransport>,
        policy: ReconnectionPolicy = FAST_POLICY,
        liveSync: LiveSyncControl? = null,
        store: ConversationStore? = null,
        broker: CredentialBroker = broker(),
        ackTimeout: Duration = 30.seconds,
    ): TouchpointSession =
        TouchpointSession(broker, store ?: store(), transports, policy, ackTimeout).also {
            if (liveSync != null) it.register(liveSync)
            sessions += it
        }

    suspend fun close() {
        sessions.forEach { it.close() }
        stores.forEach { it.close() }
        dir.deleteRecursively()
    }

    companion object {
        val FAST_POLICY = ReconnectionPolicy(maxAttempts = 2, totalBudget = 5.seconds, initialDelay = 10.milliseconds, maxDelay = 20.milliseconds, jitterFactor = 0.0)
    }
}

fun inbound(
    body: String,
    id: String,
    seq: Long,
    role: ParticipantRole = ParticipantRole.AGENT,
): StoredMessage = StoredMessage(UUID.randomUUID(), id, body, Direction.INBOUND, role, sequence = seq, deliveryState = DeliveryState.DELIVERED)
