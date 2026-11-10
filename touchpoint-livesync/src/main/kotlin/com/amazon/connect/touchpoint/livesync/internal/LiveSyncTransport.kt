package com.amazon.connect.touchpoint.livesync.internal

import com.amazon.connect.touchpoint.livesync.LiveSyncException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** What the socket tells the client. Receive-only: Live Sync never sends on the socket. */
internal sealed interface LiveSyncTransportEvent {
    /** The handshake completed: the backend has a subscriber, so pending context may be posted. */
    data object Opened : LiveSyncTransportEvent

    /** A text frame, or a UTF-8 binary frame. */
    data class Text(val text: String) : LiveSyncTransportEvent
}

/** A request with no HTTP library's types in it, so the client and its tests never see OkHttp. */
internal class HttpRequestSpec(val url: String, val headers: Map<String, String>, val body: String? = null) {
    // The socket URL carries the API key in its query, and the context POST carries it in a header.
    override fun toString(): String = "HttpRequestSpec(<redacted>)"
}

/**
 * One socket connection. Production is [OkHttpTransport]; tests inject a scripted one so frames can be
 * driven and the reconnect loop observed without a network.
 */
internal interface LiveSyncTransport {
    /** Opens the socket; the flow completes when the socket ends, for whatever reason. Called once. */
    fun open(request: HttpRequestSpec): Flow<LiveSyncTransportEvent>

    /** Closes the socket and completes the flow. Safe to call more than once. */
    fun close()

    /** The failure that ended the connection, or `null` when it closed cleanly or was closed by us. */
    val terminationError: LiveSyncException?
}

/** Makes one transport per connection attempt. */
internal fun interface LiveSyncTransportFactory {
    fun makeTransport(): LiveSyncTransport
}

/** The context POST. Returns the status and body; throws only when no HTTP response was received. */
internal fun interface ContextPoster {
    suspend fun post(request: HttpRequestSpec): Pair<Int, String?>
}

/** The backoff's sleep, injectable so reconnect tests do not wait real time. */
internal fun interface LiveSyncSleeper {
    suspend fun sleep(duration: Duration)
}

/** One client for the process, as OkHttp recommends: its connection pool and threads are shared. */
internal object LiveSyncHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A socket is held open for the life of the call: no read timeout on it, and no pings, as
            // none is needed. The context POST has its own deadline.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    val transports: LiveSyncTransportFactory = LiveSyncTransportFactory { OkHttpTransport(client) }

    val poster: ContextPoster = ContextPoster { request -> post(client, request) }

    private val JSON = "application/json".toMediaType()

    private suspend fun post(client: OkHttpClient, spec: HttpRequestSpec): Pair<Int, String?> {
        val builder = Request.Builder().url(spec.url).post((spec.body ?: "").toRequestBody(JSON))
        spec.headers.forEach { (k, v) -> builder.header(k, v) }
        val call = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(LiveSyncException.RequestFailed(LiveSyncException.describe(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use { r -> r.code to runCatching { r.body?.string() }.getOrNull() }
                        cont.resume(result)
                    }
                },
            )
        }
    }
}

/**
 * Bridges an OkHttp [WebSocket] to a flow of events: opens the connection, forwards text frames, and
 * completes when the socket closes or fails. Every listener callback lands on OkHttp's own thread; the
 * channel is the hand-off, and [finish] runs once whichever way the socket ends.
 */
internal class OkHttpTransport(private val client: OkHttpClient) : LiveSyncTransport {
    private val events = Channel<LiveSyncTransportEvent>(Channel.UNLIMITED)
    private val finished = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null

    @Volatile override var terminationError: LiveSyncException? = null
        private set

    override fun open(request: HttpRequestSpec): Flow<LiveSyncTransportEvent> {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        socket = client.newWebSocket(builder.build(), Listener())
        // A close() that raced ahead of the socket's creation must still close it.
        if (finished.get()) socket?.close(GOING_AWAY, null)
        return events.receiveAsFlow()
    }

    /** Ours: a close frame with 1001 (going away); the server sees an orderly end. */
    override fun close() = finish(null, closeCode = GOING_AWAY)

    /**
     * Ends the flow once. [closeCode] sends a close frame and lets OkHttp finish the handshake; without
     * one the connection is already gone (a failure, or a close the server began and we answered).
     */
    private fun finish(
        error: LiveSyncException?,
        closeCode: Int? = null,
    ) {
        if (!finished.compareAndSet(false, true)) return
        terminationError = error
        socket?.let { ws -> if (closeCode == null || !ws.close(closeCode, null)) ws.cancel() }
        events.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.trySend(LiveSyncTransportEvent.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(LiveSyncTransportEvent.Text(text))
        }

        // A binary frame that is not valid UTF-8 is dropped — never decoded with
        // replacement characters into something that might parse.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull() ?: return
            events.trySend(LiveSyncTransportEvent.Text(text))
        }

        // The server began closing: answer it once, through finish, and let OkHttp complete the
        // handshake. A second close would return false and turn into a cancel.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            finish(closeError(code, reason), closeCode = NORMAL)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(closeError(code, reason))
        }

        // Handshake rejections (a 403 for a revoked key) land here with the response; so does a drop.
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            // A refused handshake is an HTTP failure with its status; on 401/403 the
            // loop drops the key.
            if (code != null && code >= HTTP_ERROR) return finish(LiveSyncException.HttpFailure(code, null))
            val status = code?.let { " (handshake $it)" } ?: ""
            finish(LiveSyncException.SocketFailure(LiveSyncException.describe(t) + status))
        }
    }

    private companion object {
        const val NORMAL = 1000
        const val HTTP_ERROR = 400
        const val GOING_AWAY = 1001

        fun closeError(code: Int, reason: String): LiveSyncException? =
            if (code == NORMAL || code == GOING_AWAY) null else LiveSyncException.SocketFailure("closed with code $code" + if (reason.isEmpty()) "" else ": ${reason.take(120)}")
    }
}
