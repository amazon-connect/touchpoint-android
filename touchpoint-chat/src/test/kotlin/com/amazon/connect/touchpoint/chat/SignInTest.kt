package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.AuthenticationCompletion
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Connect's sign-in on chat — web's state machine, against a fake driver. */
class SignInTest {
    private class Driver : ChatSessionDriving {
        @Volatile var installed: ChatDriverHandlers? = null

        @Volatile var enabled = 0
        val urlRequests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val cancels: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var url = "https://idp.example/authorize?state=abc%3D&nonce=n"

        @Volatile var failUrl: Exception? = null

        override fun setHandlers(handlers: ChatDriverHandlers) {
            installed = handlers
        }

        override fun enableAuthenticationEvents() {
            enabled++
        }

        override suspend fun authenticationUrl(
            sessionId: String,
            redirectUri: String,
        ): String {
            urlRequests += sessionId to redirectUri
            failUrl?.let { throw it }
            return url
        }

        override suspend fun cancelAuthentication(sessionId: String) {
            cancels += sessionId
        }

        override suspend fun connect(
            contactId: String,
            participantId: String,
            participantToken: String?,
        ) {
            installed?.onConnected?.invoke()
        }

        override suspend fun disconnect() {}

        override suspend fun release() {}

        override suspend fun sendMessage(
            body: String,
            interactiveResponse: Boolean,
        ): SentMessageAck = SentMessageAck("s", "2026-10-21T00:00:00Z")

        override suspend fun sendReceipt(
            kind: ReceiptKind,
            messageId: String,
        ) {}

        override suspend fun fetchTranscript(
            nextToken: String?,
            maxResults: Int,
        ): RawTranscriptPage = RawTranscriptPage(emptyList(), null)

        fun event(
            type: String,
            content: String? = null,
            time: String? = null,
        ) = installed?.onAuthenticationEvent?.invoke("application/vnd.amazonaws.connect.event.authentication.$type", content, time)
    }

    private val relayed = Collections.synchronizedList(mutableListOf<AuthenticationCompletion>())

    @Volatile private var relayFails = false

    @Volatile private var clock = 1_000_000L
    private var wall = Instant.parse("2026-10-21T12:00:00Z")

    private val auth =
        ChatAuthentication("acmebank-touchpoint://auth") { c ->
            if (relayFails) throw TouchpointException.HttpFailure(500, null)
            relayed += c
        }

    private fun test(
        authentication: ChatAuthentication? = auth,
        body: suspend CoroutineScope.(Driver, ConnectChatTransport, () -> List<AuthenticationStatus>) -> Unit,
    ) = runBlocking {
        val d = Driver()
        val t = ConnectChatTransport(d, authentication = authentication, now = { clock }, wallClock = { wall })
        val seen = Collections.synchronizedList(mutableListOf<TransportEvent>())
        val sink = launch(Dispatchers.Default) { t.events.collect { seen += it } }
        withTimeout(10.seconds) {
            t.connect(ChatContactCredentials("c1", "p", "tok", null))
            body(d, t) { synchronized(seen) { seen.filterIsInstance<TransportEvent.AuthenticationChanged>().map { it.status } } }
        }
        sink.cancel()
    }

    private suspend fun eventually(condition: () -> Boolean): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + 2.seconds
        while (deadline.hasNotPassedNow()) {
            if (condition()) return true
            delay(10.milliseconds)
        }
        return condition()
    }

    private val initiated = """{"SessionId":"sess-1"}"""

    @Test
    fun `web's aliases - succeeded and success, timeout and expired, and nothing for the rest`() {
        assertEquals(AuthenticationStatus.PROMPT, SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.initiated"))
        assertEquals(AuthenticationStatus.SUCCESS, SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.success"))
        assertEquals(AuthenticationStatus.SUCCESS, SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.succeeded"))
        assertEquals(AuthenticationStatus.EXPIRED, SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.timeout"))
        assertEquals(AuthenticationStatus.EXPIRED, SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.expired"))
        assertNull(SignIn.statusOf("application/vnd.amazonaws.connect.event.authentication.other"))
        assertNull(SignIn.statusOf("application/vnd.amazonaws.connect.event.typing"))
    }

    @Test
    fun `a socket frame is read for sign-in events only`() {
        val inner = """{"ContentType":"application/vnd.amazonaws.connect.event.authentication.initiated","Content":"{\"SessionId\":\"s\"}","AbsoluteTime":"2026-10-21T12:00:00.000Z"}"""
        val frame = """{"topic":"aws/chat","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))}}"""
        assertEquals(AuthenticationFrames.Event("application/vnd.amazonaws.connect.event.authentication.initiated", """{"SessionId":"s"}""", "2026-10-21T12:00:00.000Z"), AuthenticationFrames.event(frame))
        assertNull(AuthenticationFrames.event(frame.replace("authentication.initiated", "typing")))
        assertNull(AuthenticationFrames.event("""{"topic":"aws/heartbeat"}"""))
        assertNull(AuthenticationFrames.event("not json"))
    }

    @Test
    fun `initiated shows the card once per session, and the events are heard only when the host configured sign-in`() =
        test { d, _, statuses ->
            assertEquals(1, d.enabled)
            d.event("initiated", initiated)
            d.event("initiated", initiated) // the catch-up read's copy
            assertTrue(eventually { statuses() == listOf(AuthenticationStatus.PROMPT) })
            d.event("initiated", """{"SessionId":"sess-2"}""")
            assertTrue(eventually { statuses().size == 2 })
        }

    @Test
    fun `without sign-in configured, nothing is enabled or carded`() =
        test(authentication = null) { d, t, statuses ->
            assertEquals(0, d.enabled)
            d.event("initiated", initiated)
            delay(100)
            assertTrue(statuses().isEmpty())
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.beginAuthentication() }
        }

    @Test
    fun `begin asks for the page with the redirect, and the redirect is checked against Connect's state before it is relayed`() =
        test { d, t, statuses ->
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            val request = t.beginAuthentication()
            assertEquals(listOf("sess-1" to "acmebank-touchpoint://auth"), d.urlRequests.toList())
            assertEquals(d.url, request.url)
            assertTrue("state" !in request.toString()) // no customer data in toString
            t.completeAuthentication("acmebank-touchpoint://auth?code=c%2F1&state=abc%3D")
            assertEquals(listOf(AuthenticationCompletion("c/1", "abc=")), relayed.toList())
            // Success is Connect's to say.
            assertTrue(eventually { statuses() == listOf(AuthenticationStatus.PROMPT, AuthenticationStatus.IN_PROGRESS) })
            d.event("succeeded")
            assertTrue(eventually { statuses().last() == AuthenticationStatus.SUCCESS })
            // The page came back: a return to the app does not cancel.
            t.cancelAuthentication(unlessRedirected = true)
            assertTrue(d.cancels.isEmpty())
        }

    @Test
    fun `an https redirect on the app's own domain needs a host and a path, and plain http is refused`() {
        assertEquals(AuthenticationRedirect.Scheme("acmebank-touchpoint"), AuthenticationRedirect.of("acmebank-touchpoint://auth"))
        assertEquals(AuthenticationRedirect.Https("bank.example", "/touchpoint/auth"), AuthenticationRedirect.of("https://bank.example/touchpoint/auth"))
        assertEquals(null, AuthenticationRedirect.of("https://bank.example"))
        assertEquals(null, AuthenticationRedirect.of("https://bank.example/"))
        assertEquals(null, AuthenticationRedirect.of("http://bank.example/touchpoint/auth"))
        assertEquals(null, AuthenticationRedirect.of("not a uri"))
        assertFailsWith<TouchpointException.InvalidConfiguration> { ChatAuthentication("http://bank.example/auth") {} }
        assertFailsWith<TouchpointException.InvalidConfiguration> { ChatAuthentication("https://bank.example") {} }
    }

    @Test
    fun `an https redirect is held to its host, path and Connect's state, as a scheme is`() =
        test(authentication = ChatAuthentication("https://bank.example/touchpoint/auth") { c -> relayed += c }) { d, t, statuses ->
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            t.beginAuthentication()
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.completeAuthentication("https://evil.example/touchpoint/auth?code=c&state=abc%3D") }
            assertTrue(relayed.isEmpty())
            d.event("initiated", initiated.replace("sess-1", "sess-2"))
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.PROMPT })
            t.beginAuthentication()
            t.completeAuthentication("https://bank.example/touchpoint/auth?code=c&state=abc%3D")
            assertEquals(listOf(AuthenticationCompletion("c", "abc=")), relayed.toList())
        }

    @Test
    fun `a redirect that is not ours, or carries another state, fails the card and relays nothing`() =
        test { d, t, statuses ->
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            t.beginAuthentication()
            val events = Collections.synchronizedList(mutableListOf<TouchpointSecurityEvent>())
            val watching = CoroutineScope(Dispatchers.Default).launch { TouchpointSecurityEvents.events.collect { events += it } }
            delay(50)
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.completeAuthentication("acmebank-touchpoint://auth?code=c&state=other") }
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.FAILED })
            assertTrue(relayed.isEmpty())
            // The host hears it, for its own monitoring (A12).
            assertTrue(eventually { TouchpointSecurityEvent.SignInRedirectRejected in events })
            watching.cancel()
        }

    @Test
    fun `a page with no state, or not over https, is never opened`() =
        test { d, t, statuses ->
            d.url = "http://idp.example/authorize?state=abc"
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.beginAuthentication() }
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.FAILED })
        }

    @Test
    fun `Connect's 60 second window counts from its own clock when the event was read back late`() =
        test { d, t, statuses ->
            d.event("initiated", initiated, time = "2026-10-21T11:59:30Z") // 30 s ago
            assertTrue(eventually { statuses().isNotEmpty() })
            clock += 31_000
            assertFailsWith<TouchpointException.InvalidConfiguration> { t.beginAuthentication() }
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.EXPIRED })
            assertTrue(d.urlRequests.isEmpty())
        }

    @Test
    fun `a failed relay fails the card`() =
        test { d, t, statuses ->
            relayFails = true
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            t.beginAuthentication()
            assertFailsWith<TouchpointException.HttpFailure> { t.completeAuthentication("acmebank-touchpoint://auth?code=c&state=abc%3D") }
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.FAILED })
        }

    @Test
    fun `continuing without cancels with Connect, and a page closed with no redirect cancels too`() =
        test { d, t, statuses ->
            d.event("initiated", initiated)
            assertTrue(eventually { statuses().isNotEmpty() })
            // Only while the page is open: at the prompt, a return to the app is not a closed page.
            t.cancelAuthentication(unlessRedirected = true)
            assertTrue(d.cancels.isEmpty())
            t.beginAuthentication()
            t.cancelAuthentication(unlessRedirected = true)
            assertEquals(listOf("sess-1"), d.cancels.toList())
            assertTrue(eventually { statuses().lastOrNull() == AuthenticationStatus.CANCELLED })
            // Connect's own outcome still has the last word.
            d.event("failed")
            assertTrue(eventually { statuses().last() == AuthenticationStatus.FAILED })
        }

    @Test
    fun `the participant calls - the socket's type only, the bearer, the bodies, and a status without its body`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody("""{"Websocket":{"Url":"wss://socket.example/x","ConnectionExpiry":"2026-10-21T13:00:00Z"}}"""))
            server.enqueue(MockResponse().setBody("""{"AuthenticationUrl":"https://idp.example/a?state=s"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret detail"))
            server.start()
            try {
                val http = ParticipantHttp(server.url("/").toString(), requireHttps = false)
                assertEquals("wss://socket.example/x", http.websocketUrl("participant-token"))
                assertEquals("https://idp.example/a?state=s", http.authenticationUrl("connection-token", "sess", "acmebank-touchpoint://auth"))
                val error = assertFailsWith<TouchpointException.HttpFailure> { http.cancelAuthentication("connection-token", "sess") }
                assertEquals(403, error.statusCode)
                assertNull(error.responseBody)

                val connection = server.takeRequest()
                assertEquals("/participant/connection", connection.path)
                assertEquals("participant-token", connection.getHeader("X-Amz-Bearer"))
                assertEquals(listOf("WEBSOCKET"), Json.parseToJsonElement(connection.body.readUtf8()).jsonObject["Type"]!!.jsonArray.map { it.jsonPrimitive.content })
                val url = server.takeRequest()
                assertEquals("/participant/authentication-url", url.path)
                assertEquals("connection-token", url.getHeader("X-Amz-Bearer"))
                val body = Json.parseToJsonElement(url.body.readUtf8()).jsonObject
                assertEquals("sess", body["SessionId"]!!.jsonPrimitive.content)
                assertEquals("acmebank-touchpoint://auth", body["RedirectUri"]!!.jsonPrimitive.content)
                assertEquals("/participant/cancel-authentication", server.takeRequest().path)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a redirect is never followed, so the bearer never reaches the host it names`() =
        runBlocking {
            val elsewhere = MockWebServer()
            val server = MockWebServer()
            elsewhere.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/stolen").toString()))
            server.start()
            try {
                val http = ParticipantHttp(server.url("/").toString(), requireHttps = false)
                val error = assertFailsWith<TouchpointException.HttpFailure> { http.websocketUrl("participant-token") }
                assertEquals(307, error.statusCode)
                assertEquals(0, elsewhere.requestCount)
            } finally {
                server.shutdown()
                elsewhere.shutdown()
            }
        }

    @Test
    fun `a socket that is not wss is never opened`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody("""{"Websocket":{"Url":"ws://socket.example/x"}}"""))
            server.start()
            try {
                val http = ParticipantHttp(server.url("/").toString(), requireHttps = false, requireWss = true)
                assertFailsWith<TouchpointException.MalformedPayload> { http.websocketUrl("participant-token") }
                Unit
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a participant endpoint that is not https is refused`() =
        runBlocking {
            assertFailsWith<TouchpointException.InvalidConfiguration> { ParticipantHttp("http://participant.example").websocketUrl("t") }
            Unit
        }

    @Test
    fun `a completion prints neither its code nor its state`() {
        val c = AuthenticationCompletion("the-code", "the-state")
        assertTrue("the-code" !in c.toString() && "the-state" !in c.toString())
    }
}
