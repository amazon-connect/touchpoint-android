package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The participant service's calls the chat SDK does not make: a listen-only socket's URL
 * (`CreateParticipantConnection` with type `WEBSOCKET` only, so no connection token is minted and the
 * SDK's stays valid), `GetAuthenticationUrl` and `CancelParticipantAuthentication`. Direct HTTPS, as
 * web makes them; the AWS Android SDK binds neither sign-in call. Replies are read for
 * their one field and never logged: they carry signed URLs.
 *
 * The endpoint is the AWS SDK's for the region, `participant.connect.<region>.amazonaws.com`; web uses
 * the dual-stack `api.aws` host, a named deviation.
 */
internal class ParticipantHttp(
    private val endpoint: String,
    private val client: OkHttpClient = AuthenticationHttp.client,
    private val requireHttps: Boolean = true,
    private val requireWss: Boolean = requireHttps,
) {
    /** A fresh socket URL for [participantToken]. */
    suspend fun websocketUrl(participantToken: String): String {
        val reply = post("/participant/connection", participantToken, buildJsonObject { put("Type", JsonArray(listOf(JsonPrimitive("WEBSOCKET")))) }.toString())
        val url = field(reply, "Websocket", "Url") ?: throw TouchpointException.MalformedPayload("connection reply without a socket")
        // The socket carries the customer's sign-in events: only an encrypted one is opened.
        if (requireWss && !url.startsWith("wss://")) throw TouchpointException.MalformedPayload("connection reply with a socket that is not wss")
        return url
    }

    suspend fun authenticationUrl(
        connectionToken: String,
        sessionId: String,
        redirectUri: String,
    ): String {
        val reply = post("/participant/authentication-url", connectionToken, buildJsonObject { put("SessionId", sessionId); put("RedirectUri", redirectUri) }.toString())
        return field(reply, "AuthenticationUrl") ?: throw TouchpointException.MalformedPayload("authentication-url reply")
    }

    suspend fun cancelAuthentication(
        connectionToken: String,
        sessionId: String,
    ) {
        post("/participant/cancel-authentication", connectionToken, buildJsonObject { put("SessionId", sessionId) }.toString())
    }

    private suspend fun post(
        path: String,
        bearer: String,
        body: String,
    ): String {
        if (requireHttps && !endpoint.startsWith("https://")) throw TouchpointException.InvalidConfiguration("participant endpoint must be https")
        val request =
            Request.Builder().url(endpoint.trimEnd('/') + path)
                .header("X-Amz-Bearer", bearer)
                .post(body.toRequestBody(JSON))
                .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        cont.resumeWithException(TouchpointException.TransportFailure(e.javaClass.simpleName))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use { r ->
                            // Status only: a reply body can echo request details.
                            if (!r.isSuccessful) {
                                cont.resumeWithException(TouchpointException.HttpFailure(r.code, null))
                            } else {
                                // A body that breaks off is the transport's failure, not a malformed reply.
                                runCatching { r.body?.string().orEmpty() }.fold(
                                    onSuccess = { cont.resume(it) },
                                    onFailure = { cont.resumeWithException(TouchpointException.TransportFailure(it.javaClass.simpleName)) },
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun forRegion(region: String): ParticipantHttp = ParticipantHttp("https://participant.connect.$region.amazonaws.com")

        private fun field(
            reply: String,
            vararg path: String,
        ): String? =
            runCatching {
                var element = json.parseToJsonElement(reply)
                path.dropLast(1).forEach { element = element.jsonObject.getValue(it) }
                element.jsonObject[path.last()]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            }.getOrNull()
    }
}

/**
 * One client for the process, as OkHttp recommends; the listener's socket has no read deadline.
 *
 * Redirects are never followed: OkHttp drops only `Authorization` on a cross-host redirect,
 * so a followed 307 would carry `X-Amz-Bearer` (a participant or connection token) to the host it names.
 */
internal object AuthenticationHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }

    val sockets: OkHttpClient by lazy { client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
}

/**
 * A listen-only second participant socket that hears Connect's sign-in events.
 *
 * **Why it exists:** amazon-connect-chat-android 2.0.11 names the six authentication content types
 * but drops them, live and in its transcript parsing (`WebSocketManagerImpl.parseTranscriptItemFromJson`
 * logs and returns `null` for any type its `when` does not list), and exposes no raw-frame hook.
 * Rejected instead: polling `GetTranscript` (latency against Connect's 60 s window), the
 * SDK's logger (content through a logger), reusing the SDK's socket URL, forking the SDK. Delete this
 * when the SDK surfaces the events.
 *
 * Connect allows five sockets per participant; this is the second. The driver opens it when the SDK's
 * connection is established or re-established and stops it when the chat ends; between those it
 * reopens itself after a close, a few times in a row at most, since the SDK's own reconnect restarts it.
 */
internal class AuthenticationEventListener(
    private val client: OkHttpClient = AuthenticationHttp.sockets,
    private val retryDelayMs: Long = 2_000,
    private val heartbeatMs: Long = 10_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("touchpoint-sign-in-events") + CoroutineExceptionHandler { _, _ -> })
    private val lock = Any()
    private var job: Job? = null

    /**
     * @param websocketUrl a fresh socket URL, asked for on every open.
     * @param onOpen after each subscribe: the driver reads the transcript back for anything missed.
     * @param onEvent each sign-in frame.
     */
    fun start(
        websocketUrl: suspend () -> String,
        onOpen: suspend () -> Unit,
        onEvent: (AuthenticationFrames.Event) -> Unit,
    ) {
        val run =
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var failures = 0
                while (isActive && failures < MAX_CONSECUTIVE_FAILURES) {
                    val frames = Channel<String>(Channel.UNLIMITED)
                    // Opened is healthy: only sockets that fail to open count toward giving up.
                    val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
                    var socket: WebSocket? = null
                    try {
                        socket =
                            client.newWebSocket(
                                Request.Builder().url(websocketUrl()).build(),
                                object : WebSocketListener() {
                                    override fun onOpen(
                                        webSocket: WebSocket,
                                        response: Response,
                                    ) {
                                        opened.complete(Unit)
                                    }

                                    override fun onMessage(
                                        webSocket: WebSocket,
                                        text: String,
                                    ) {
                                        frames.trySend(text)
                                    }

                                    override fun onClosed(
                                        webSocket: WebSocket,
                                        code: Int,
                                        reason: String,
                                    ) {
                                        frames.close()
                                    }

                                    override fun onFailure(
                                        webSocket: WebSocket,
                                        t: Throwable,
                                        response: Response?,
                                    ) {
                                        frames.close()
                                    }
                                },
                            )
                        socket.send(SUBSCRIBE) // queued until the socket opens
                        onOpen()
                        val open = socket
                        val beat =
                            launch {
                                while (isActive) {
                                    delay(heartbeatMs)
                                    open.send(HEARTBEAT)
                                }
                            }
                        try {
                            for (text in frames) {
                                failures = 0
                                AuthenticationFrames.event(text)?.let(onEvent)
                            }
                        } finally {
                            beat.cancel()
                        }
                        if (opened.isCompleted) failures = 0 else failures++ // closed under us
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures++
                    } finally {
                        socket?.cancel()
                    }
                    delay(retryDelayMs)
                }
            }
        synchronized(lock) {
            job?.cancel()
            job = run
        }
        run.start()
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel() // its finally cancels the socket
            job = null
        }
    }

    companion object {
        const val SUBSCRIBE = """{"topic":"aws/subscribe","content":{"topics":["aws/chat"]}}"""
        const val HEARTBEAT = """{"topic":"aws/heartbeat"}"""
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
