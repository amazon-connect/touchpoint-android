package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.ReconnectionPolicy
import com.amazon.connect.touchpoint.livesync.internal.HttpRequestSpec
import com.amazon.connect.touchpoint.livesync.internal.LiveSyncHttp
import com.amazon.connect.touchpoint.livesync.internal.OkHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A review's findings on the Live Sync client, each held by a test. R-numbers follow its ranking. */
class LiveSyncRegressionTest {
    private fun errorsOf(client: LiveSyncClient) = Collections.synchronizedList(mutableListOf<LiveSyncException>()).also { list -> client.onError = { list += it } }

    @Test
    fun `R1 - a handler throwing CancellationException or an Error is a handler failure, and the socket keeps dispatching`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("after", "cancels", "todo")
            val errors = errorsOf(client)
            val seen = Collections.synchronizedList(mutableListOf<String>())
            client.onAction = {
                seen += it.action
                when (it.action) {
                    "cancels" -> throw CancellationException("a cancelled future the host waited on")
                    "todo" -> TODO("not written yet")
                }
            }
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            t.frame("""{"classification":"custom","action":"cancels"}""")
            t.frame("""{"classification":"custom","action":"todo"}""")
            t.frame("""{"classification":"custom","action":"after"}""")
            assertTrue(eventually { seen.toList() == listOf("cancels", "todo", "after") }, "dispatch stopped: $seen")
            assertEquals(listOf("cancels", "todo"), errors.filterIsInstance<LiveSyncException.ActionHandlerFailed>().map { it.action })
            assertEquals(LiveSyncConnectionState.Connected, client.connectionState.value)
            assertTrue(!t.closed)
            client.close()
            assertTrue(t.closed)
        }

    @Test
    fun `R1b - a credential source raising CancellationException while we are active is a vend failure, retried`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val calls = AtomicInteger()
            val source =
                object : com.amazon.connect.touchpoint.core.LiveSyncCredentialSource {
                    override suspend fun liveSyncCredentials(): LiveSyncCredentials {
                        if (calls.incrementAndGet() == 1) throw CancellationException("the host's own scope")
                        return LiveSyncCredentials("deployment-key", "test-api-key-placeholder", null)
                    }

                    override suspend fun invalidateLiveSyncCredentials() = Unit
                }
            val client = LiveSyncClient(testConfiguration, source, ConsentDecision.GRANTED, ReconnectionPolicy.DEFAULT, false, kotlinx.coroutines.Dispatchers.Unconfined, FakePoster(), transports, ManualSleeper())
            val errors = errorsOf(client)
            client.connect()
            assertNotNull(transports.waitFor(1), "the loop died on a foreign cancellation")
            assertIs<LiveSyncException.CredentialVendingFailed>(errors.first())
            client.close()
        }

    @Test
    fun `R1c - a poster raising CancellationException while we are active is a request failure`() =
        runBlocking {
            val client = makeTestClient(poster = FakePoster().apply { failure = CancellationException("the HTTP client's own") })
            client.sendContext(scopes = listOf("x"))
            assertEquals(LiveSyncException.RequestFailed("CancellationException"), runCatching { client.postContext() }.exceptionOrNull())
        }

    @Test
    fun `R2 - a User-Agent the HTTP client would refuse is a configuration error, and a valid one is sent`() =
        runBlocking {
            val bad = makeTestClient(configuration = testConfiguration.copy(userAgent = "Acmé/1.0"))
            assertIs<LiveSyncException.InvalidConfiguration>(runCatching { bad.makeSocketRequest() }.exceptionOrNull())
            val good = makeTestClient(configuration = testConfiguration.copy(userAgent = "AcmeBank/1.0"))
            assertEquals("AcmeBank/1.0", good.makeSocketRequest().headers["User-Agent"])
            assertEquals("AcmeBank/1.0", good.makeContextRequest().headers["User-Agent"])
            // Case in a region is not significant in a host name.
            makeTestClient(configuration = testConfiguration.copy(region = "US-WEST-2")).makeSocketRequest()
            Unit
        }

    @Test
    fun `R5 - repeated 401s back off, closing the refused socket each time, instead of looping at network speed`() =
        runBlocking {
            val credentials = FakeCredentialSource()
            val transports = FakeTransportFactory()
            val sleeper = ManualSleeper()
            val policy = ReconnectionPolicy.DEFAULT
            val client = makeTestClient(credentials = credentials, transports = transports, sleeper = sleeper, poster = FakePoster(401), reconnection = policy)
            client.sendContext(scopes = listOf("booking"))
            client.connect()
            for (n in 1..4) {
                val t = assertNotNull(transports.waitFor(n))
                t.opened()
                assertTrue(eventually { t.closed }, "socket $n not closed after the refusal")
            }
            assertTrue(credentials.invalidations >= 3)
            // The delay grows with consecutive refusals although each socket opened.
            val delays = synchronized(sleeper.slept) { sleeper.slept.toList() }
            assertTrue(delays.size >= 3 && delays[2] > delays[0], "no growing backoff: $delays")
            client.close()
        }

    @Test
    fun `R8 - a disconnect from another thread wins over a loop mid-open - no stale Connected, no post while down`() =
        runBlocking {
            val poster = FakePoster()
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, poster = poster)
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            client.disconnect()
            // disconnect() closed the socket synchronously, so an Opened that races in is never
            // delivered, and nothing can mark the client connected afterwards.
            assertTrue(t.closed)
            assertTrue(t.opened().isFailure)
            assertEquals(LiveSyncConnectionState.Disconnected, client.connectionState.value)
            client.sendContext(scopes = listOf("x"))
            assertTrue(poster.requests.isEmpty())
        }

    @Test
    fun `R9 - context POSTs are serialised and each carries the latest context`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val inFlight = AtomicInteger()
            val maxInFlight = AtomicInteger()
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            val poster =
                com.amazon.connect.touchpoint.livesync.internal.ContextPoster { request ->
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    bodies += request.body!!
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(2, TimeUnit.SECONDS) }
                    inFlight.decrementAndGet()
                    200 to null
                }
            val transports = FakeTransportFactory()
            val client = LiveSyncClient(testConfiguration, FakeCredentialSource(), ConsentDecision.GRANTED, ReconnectionPolicy.DEFAULT, false, kotlinx.coroutines.Dispatchers.Unconfined, poster, transports, ManualSleeper())
            client.connect()
            assertNotNull(transports.waitFor(1)).opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            val other = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val a = other.launch { client.sendContext(scopes = listOf("first")) }
            kotlinx.coroutines.delay(20)
            val b = other.launch { client.sendContext(scopes = listOf("second")) }
            kotlinx.coroutines.delay(100)
            gate.countDown()
            a.join()
            b.join()
            assertEquals(1, maxInFlight.get(), "two context POSTs were in flight at once")
            assertTrue("second" in bodies.last(), "the last POST did not carry the latest context")
            client.close()
        }

    @Test
    fun `callbacks run on the callback dispatcher, one at a time, in arrival order`() =
        runBlocking {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "host-main") }
            try {
                val transports = FakeTransportFactory()
                val client = LiveSyncClient(testConfiguration, FakeCredentialSource(), ConsentDecision.GRANTED, ReconnectionPolicy.DEFAULT, false, executor.asCoroutineDispatcher(), FakePoster(), transports, ManualSleeper())
                client.advertise("a1", "a2", "a3", "a4", "a5")
                val seen = Collections.synchronizedList(mutableListOf<String>())
                // Coroutine debug mode suffixes thread names with " @coroutine#n".
                client.onAction = { seen += "${Thread.currentThread().name.substringBefore(" @")}:${it.action}" }
                client.connect()
                val t = assertNotNull(transports.waitFor(1))
                t.opened()
                (1..5).forEach { t.frame("""{"classification":"custom","action":"a$it"}""") }
                assertTrue(eventually { seen.size == 5 })
                assertEquals((1..5).map { "host-main:a$it" }, seen.toList())
                client.close()
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun `our close sends a going-away close frame`() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                val code = java.util.concurrent.CompletableFuture<Int>()
                val opened = java.util.concurrent.CompletableFuture<Unit>()
                server.enqueue(
                    MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onClosing(webSocket: WebSocket, c: Int, reason: String) {
                                code.complete(c)
                                webSocket.close(1000, null) // answer, so the server can shut down
                            }

                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                opened.complete(Unit)
                            }
                        },
                    ),
                )
                val transport = OkHttpTransport(LiveSyncHttp.client)
                val flow = transport.open(HttpRequestSpec(server.url("/").toString().replaceFirst("http", "ws"), emptyMap()))
                opened.get(5, TimeUnit.SECONDS) // the upgrade completed: a close now goes on an open socket
                transport.close()
                withTimeout(5_000) { flow.toList() }
                assertEquals(1001, code.get(5, TimeUnit.SECONDS))
            } finally {
                server.shutdown()
            }
        }
}

