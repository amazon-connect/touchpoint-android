package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.ReconnectionPolicy
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.livesync.internal.ContextPoster
import com.amazon.connect.touchpoint.livesync.internal.HttpRequestSpec
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncSleeper
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransport
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransportEvent
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import kotlin.time.Duration

/** Scripted socket. One per open; the factory records them so a test can drive the n-th connection. */
internal class FakeTransport : LiveSyncTransport {
    private val events = Channel<LiveSyncTransportEvent>(Channel.UNLIMITED)

    @Volatile var request: HttpRequestSpec? = null

    @Volatile var closed = false

    @Volatile override var terminationError: LiveSyncException? = null

    override fun open(request: HttpRequestSpec): Flow<LiveSyncTransportEvent> {
        this.request = request
        return events.receiveAsFlow()
    }

    override fun close() {
        closed = true
        events.close()
    }

    fun opened() = events.trySend(LiveSyncTransportEvent.Opened)

    fun frame(text: String) = events.trySend(LiveSyncTransportEvent.Text(text))

    /** The socket ends on its own — the server closed it, or it failed. */
    fun end(error: LiveSyncException? = null) {
        terminationError = error
        events.close()
    }

    fun query(): Map<String, String> = query(request?.url)
}

internal fun query(url: String?): Map<String, String> {
    if (url == null) return emptyMap()
    return URI(url).rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val (k, v) = it.split("=", limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
    }
}

internal class FakeTransportFactory : LiveSyncTransportFactory {
    val made: MutableList<FakeTransport> = Collections.synchronizedList(mutableListOf())
    val count: Int get() = made.size

    override fun makeTransport(): LiveSyncTransport = FakeTransport().also { made += it }

    /** Waits for the n-th connection attempt (1-based) to have been opened. */
    suspend fun waitFor(
        n: Int,
        timeoutMs: Long = 2_000,
    ): FakeTransport? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(made) { made.getOrNull(n - 1) }?.takeIf { it.request != null }?.let { return it }
            delay(5)
        }
        return null
    }
}

/** Vends whatever it is told, counts invalidations, and can fail. */
internal class FakeCredentialSource(
    deploymentKey: String = "deployment-key",
    apiKey: String = "test-api-key-placeholder",
) : LiveSyncCredentialSource {
    @Volatile var current = LiveSyncCredentials(deploymentKey, apiKey, null)
    private val queue = Collections.synchronizedList(mutableListOf<LiveSyncCredentials>())

    @Volatile var vends = 0

    @Volatile var invalidations = 0

    @Volatile var failure: TouchpointException? = null

    /** Set, a vend waits for it: a test can change things while one is under way. */
    @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun liveSyncCredentials(): LiveSyncCredentials {
        vends++
        hold?.await()
        failure?.let { throw it }
        synchronized(queue) { if (queue.isNotEmpty()) current = queue.removeAt(0) }
        return current
    }

    override suspend fun invalidateLiveSyncCredentials() {
        invalidations++
    }

    fun rotate(apiKey: String) {
        queue += LiveSyncCredentials(current.deploymentKey, apiKey, null)
    }

    fun setDeploymentKey(key: String) {
        current = LiveSyncCredentials(key, current.apiKey, null)
        synchronized(queue) { queue.replaceAll { LiveSyncCredentials(key, it.apiKey, null) } }
    }
}

/** The context API, recorded; answers with [status] and [body], or throws [failure]. */
internal class FakePoster(
    @Volatile var status: Int = 200,
    @Volatile var body: String? = null,
) : ContextPoster {
    val requests: MutableList<HttpRequestSpec> = Collections.synchronizedList(mutableListOf())

    @Volatile var failure: Exception? = null

    override suspend fun post(request: HttpRequestSpec): Pair<Int, String?> {
        requests += request
        failure?.let { throw it }
        return status to body
    }
}

/** A clock the backoff sleeps on, so tests never wait real time. */
internal class ManualSleeper : LiveSyncSleeper {
    val slept: MutableList<Duration> = Collections.synchronizedList(mutableListOf())

    override suspend fun sleep(duration: Duration) {
        slept += duration
        yield()
    }
}

internal val testConfiguration =
    LiveSyncConfiguration(region = "us-west-2", conversationId = "4411ca47-96d8-4bc8-88d8-1b7a6a1dcd98", channel = LiveSyncChannel.from(Locale.US))

internal fun makeTestClient(
    credentials: FakeCredentialSource = FakeCredentialSource(),
    transports: FakeTransportFactory = FakeTransportFactory(),
    consent: ConsentDecision = ConsentDecision.GRANTED,
    reconnection: ReconnectionPolicy = ReconnectionPolicy.DEFAULT,
    poster: FakePoster = FakePoster(),
    sleeper: LiveSyncSleeper = ManualSleeper(),
    configuration: LiveSyncConfiguration = testConfiguration,
    idleReconnect: Duration? = null,
    staleContact: Duration? = null,
    idleSleeper: LiveSyncSleeper = GateSleeper(),
): LiveSyncClient =
    LiveSyncClient(configuration, credentials, consent, reconnection, autoConnect = false, Dispatchers.Unconfined, poster, transports, sleeper, idleReconnect, staleContact, idleSleeper)

/** A sleeper that waits until a test releases it: the guards' timers, fired on demand. */
internal class GateSleeper : LiveSyncSleeper {
    private val waiting = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>())
    val pending: Int get() = synchronized(waiting) { waiting.count { !it.isCompleted } }

    override suspend fun sleep(duration: Duration) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        waiting += gate
        gate.await()
    }

    /** Lets every timer armed so far fire. */
    fun fire() = synchronized(waiting) { waiting.forEach { it.complete(Unit) } }
}

internal suspend fun eventually(
    timeoutMs: Long = 2_000,
    cond: suspend () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return true
        delay(5)
    }
    return cond()
}

/**
 * Advertises [names], each taking any object: the client refuses an invocation of anything it has
 * not advertised, so a test that invokes an action declares it first, as an app does.
 */
internal suspend fun LiveSyncClient.advertise(vararg names: String) =
    sendContext(actions = names.map { LiveSyncAction(it, it, kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }) })
