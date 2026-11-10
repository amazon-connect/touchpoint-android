package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.livesync.internal.HttpRequestSpec
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncHttp
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncTransportEvent
import com.amazon.connect.touchpoint.livesync.internal.OkHttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The production transport and context POST over OkHttp, against a local server: no fakes. */
class OkHttpTransportTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun socketUrl() = server.url("/").toString().replaceFirst("http", "ws")

    private fun serverSocket(script: (WebSocket) -> Unit) =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = script(webSocket)
            },
        )

    @Test
    fun `opened, text and UTF-8 binary frames arrive in order, and a normal close ends cleanly`() =
        runBlocking {
            server.enqueue(
                serverSocket { ws ->
                    ws.send("""{"classification":"custom","action":"a"}""")
                    ws.send("""{"classification":"custom","action":"b"}""".encodeUtf8())
                    ws.close(1000, null)
                },
            )
            val transport = OkHttpTransport(LiveSyncHttp.client)
            val events = withTimeout(5_000) { transport.open(HttpRequestSpec(socketUrl(), mapOf("Cache-Control" to "no-cache"))).toList() }
            assertEquals(LiveSyncTransportEvent.Opened, events.first())
            assertEquals(listOf("a", "b"), events.drop(1).map { (it as LiveSyncTransportEvent.Text).text.substringAfter("\"action\":\"").substringBefore('"') })
            assertNull(transport.terminationError)
            val handshake = server.takeRequest()
            assertEquals("no-cache", handshake.getHeader("Cache-Control"))
            assertNull(handshake.getHeader("Origin")) // defect 4, on the wire
        }

    @Test
    fun `an abnormal close is a socket failure with its code`() =
        runBlocking {
            server.enqueue(serverSocket { ws -> ws.close(4003, "lease expired") })
            val transport = OkHttpTransport(LiveSyncHttp.client)
            withTimeout(5_000) { transport.open(HttpRequestSpec(socketUrl(), emptyMap())).toList() }
            assertEquals(LiveSyncException.SocketFailure("closed with code 4003: lease expired"), transport.terminationError)
        }

    @Test
    fun `a refused handshake is an HTTP failure with its status, so the key is dropped, and never names the URL`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(403))
            val transport = OkHttpTransport(LiveSyncHttp.client)
            withTimeout(5_000) { transport.open(HttpRequestSpec(socketUrl() + "?apiKey=SECRET-KEY", emptyMap())).toList() }
            val error = assertIs<LiveSyncException.HttpFailure>(transport.terminationError)
            assertEquals(403, error.statusCode)
            assertTrue("SECRET-KEY" !in error.toString())
        }

    @Test
    fun `any refused handshake is an HTTP failure with its status`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            val transport = OkHttpTransport(LiveSyncHttp.client)
            withTimeout(5_000) { transport.open(HttpRequestSpec(socketUrl() + "?apiKey=SECRET-KEY", emptyMap())).toList() }
            assertEquals(500, assertIs<LiveSyncException.HttpFailure>(transport.terminationError).statusCode)
        }

    @Test
    fun `close() ends the flow and reports nothing`() =
        runBlocking {
            server.enqueue(serverSocket { })
            val transport = OkHttpTransport(LiveSyncHttp.client)
            val flow = transport.open(HttpRequestSpec(socketUrl(), emptyMap()))
            transport.close()
            transport.close() // idempotent
            withTimeout(5_000) { flow.toList() }
            assertNull(transport.terminationError)
        }

    @Test
    fun `the context POST sends the body and headers, and hands back status and body`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
            val (status, body) = LiveSyncHttp.poster.post(HttpRequestSpec(server.url("/nlu/c/dk/connect-en-US/context").toString(), mapOf("nlx-api-key" to "k", "Content-Type" to "application/json"), """{"x":1}"""))
            assertEquals(403, status)
            assertEquals("""{"message":"Forbidden"}""", body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("k", request.getHeader("nlx-api-key"))
            assertEquals("""{"x":1}""", request.body.readUtf8())
        }
}
