package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.core.ReconnectionPolicy
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private fun errorsOf(client: LiveSyncClient): MutableList<LiveSyncException> = Collections.synchronizedList(mutableListOf<LiveSyncException>()).also { list -> client.onError = { list += it } }

/** Four defects fixed when the Live Sync client came into this SDK, and D1b/D3b, found by test. */
class LiveSyncDefectTest {
    @Test
    fun `D1 - after the socket loop ends on its own, connect() works again (it used to be a silent no-op)`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, reconnection = ReconnectionPolicy.DEFAULT.copy(maxAttempts = 0))
            client.connect()
            val first = assertNotNull(transports.waitFor(1))
            first.opened()
            first.end(LiveSyncException.SocketFailure("server went away"))
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Disconnected })
            client.connect()
            assertNotNull(transports.waitFor(2), "connect() after a self-terminated loop did nothing")
            assertNotEquals(LiveSyncConnectionState.Disconnected, client.connectionState.value)
            client.close()
        }

    @Test
    fun `D1b - a configuration error ends the loop, and once fixed connect() runs again`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val credentials = FakeCredentialSource(deploymentKey = "")
            val client = makeTestClient(credentials = credentials, transports = transports)
            val errors = errorsOf(client)
            client.connect()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Disconnected })
            assertEquals(1, errors.size)
            assertIs<LiveSyncException.InvalidConfiguration>(errors.single())
            credentials.setDeploymentKey("deployment-key")
            client.connect()
            assertNotNull(transports.waitFor(1))
            client.close()
        }

    @Test
    fun `D2 - a context POST that never got an HTTP response is a failure, not success`() =
        runBlocking {
            val poster = FakePoster().apply { failure = IOException("connection reset") }
            val client = makeTestClient(poster = poster)
            client.sendContext(scopes = listOf("booking"))
            val e = runCatching { client.postContext() }.exceptionOrNull()
            assertEquals(LiveSyncException.RequestFailed("IOException"), e)
        }

    @Test
    fun `D3 - backoff never collapses toward zero - jitter is one-sided, and the floor is the base delay`() {
        val policy = ReconnectionPolicy.DEFAULT
        for (attempt in 1..20) {
            val low = policy.delay(attempt, random = 0.0)
            val high = policy.delay(attempt, random = 1.0)
            assertTrue(low >= policy.initialDelay, "attempt $attempt went below the initial delay")
            assertTrue(high >= low)
            assertTrue(high <= policy.maxDelay * (1 + policy.jitterFactor) + 1.milliseconds)
        }
        assertTrue(policy.delay(2, random = 0.0) > policy.delay(1, random = 0.0))
    }

    @Test
    fun `D3b - Live Sync does not give up - attempts past maxAttempts keep the capped delay and the loop keeps running`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val sleeper = ManualSleeper()
            val policy = ReconnectionPolicy.DEFAULT.copy(maxAttempts = 2)
            val client = makeTestClient(transports = transports, reconnection = policy, sleeper = sleeper)
            client.connect()
            for (n in 1..4) assertNotNull(transports.waitFor(n)).end(LiveSyncException.SocketFailure("drop $n"))
            assertNotNull(transports.waitFor(5), "the loop stopped after maxAttempts; Live Sync must outlive the policy's cap")
            assertTrue(sleeper.slept.size >= 4)
            assertTrue(sleeper.slept.last() <= policy.maxDelay * (1 + policy.jitterFactor))
            client.close()
        }

    @Test
    fun `D4 - no Origin header on the socket handshake`() =
        runBlocking {
            assertFalse("Origin" in makeTestClient().makeSocketRequest().headers)
        }
}

/** Credentials come from the source, every time. */
class LiveSyncCredentialTest {
    @Test
    fun `C1 - the socket URL carries the key the source vended, and the deployment key too`() =
        runBlocking {
            val q = query(makeTestClient(credentials = FakeCredentialSource("dep-1", "key-one")).makeSocketRequest().url)
            assertEquals("key-one", q["apiKey"])
            assertEquals("dep-1", q["deploymentKey"])
        }

    @Test
    fun `C2 - every reconnect asks the source again, so a rotated key reaches the next socket`() =
        runBlocking {
            val credentials = FakeCredentialSource(apiKey = "key-A")
            val transports = FakeTransportFactory()
            val client = makeTestClient(credentials = credentials, transports = transports)
            client.connect()
            val first = assertNotNull(transports.waitFor(1))
            assertEquals("key-A", first.query()["apiKey"])
            credentials.rotate("key-B")
            first.end(LiveSyncException.SocketFailure("drop"))
            assertEquals("key-B", assertNotNull(transports.waitFor(2)).query()["apiKey"])
            assertTrue(credentials.vends >= 2)
            client.close()
        }

    @Test
    fun `C3 - rotateCredentials() invalidates the cache and reconnects with the new key`() =
        runBlocking {
            val credentials = FakeCredentialSource(apiKey = "key-A")
            val transports = FakeTransportFactory()
            val client = makeTestClient(credentials = credentials, transports = transports)
            client.connect()
            val first = assertNotNull(transports.waitFor(1))
            first.opened()
            credentials.rotate("key-B")
            client.rotateCredentials()
            assertEquals(1, credentials.invalidations)
            assertTrue(first.closed)
            assertEquals("key-B", assertNotNull(transports.waitFor(2)).query()["apiKey"])
            client.close()
        }

    @Test
    fun `C4 - a 401 or 403 on the context POST invalidates the credentials and reconnects`() =
        runBlocking {
            val credentials = FakeCredentialSource()
            val transports = FakeTransportFactory()
            val client = makeTestClient(credentials = credentials, transports = transports, poster = FakePoster(403, """{"message":"Forbidden"}"""))
            client.sendContext(scopes = listOf("booking"))
            client.connect()
            assertNotNull(transports.waitFor(1)).opened() // replays the context → 403
            assertTrue(eventually { credentials.invalidations == 1 })
            assertNotNull(transports.waitFor(2), "no reconnect after the auth failure")
            client.close()
        }

    @Test
    fun `C5 - a vend failure is reported and retried with backoff, not terminal`() =
        runBlocking {
            val credentials = FakeCredentialSource().apply { failure = TouchpointException.CredentialVendingFailed("IOException") }
            val transports = FakeTransportFactory()
            val sleeper = ManualSleeper()
            val client = makeTestClient(credentials = credentials, transports = transports, sleeper = sleeper)
            val errors = errorsOf(client)
            client.connect()
            assertTrue(eventually { sleeper.slept.size >= 2 })
            assertTrue(synchronized(errors) { errors.any { it is LiveSyncException.CredentialVendingFailed } })
            assertEquals(0, transports.count) // never opened a socket without a key
            credentials.failure = null
            assertNotNull(transports.waitFor(1)) // recovers once the vend works
            client.close()
        }

    @Test
    fun `C6 - the channel comes from Core's LiveSyncChannel - canonical BCP 47 in the socket query and the context path`() =
        runBlocking {
            val client = makeTestClient(configuration = LiveSyncConfiguration("us-west-2", "c1", LiveSyncChannel.from(Locale.GERMANY)))
            assertEquals("connect-de-DE", query(client.makeSocketRequest().url)["channelKey"])
            assertEquals("/nlu/c/deployment-key/connect-de-DE/context", URI(client.makeContextRequest().url).path)
        }
}

/** Consent gates every context POST. */
class LiveSyncConsentTest {
    @Test
    fun `S1 - the default decision is held - sendContext while connected posts nothing and keeps the context`() =
        runBlocking {
            val poster = FakePoster()
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, consent = ConsentDecision.HELD, poster = poster)
            client.connect()
            assertNotNull(transports.waitFor(1)).opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            client.sendContext(scopes = listOf("accounts"))
            delay(50)
            assertTrue(poster.requests.isEmpty())
            assertTrue(client.hasPendingContext())
            client.close()
        }

    @Test
    fun `S2 - the replay on socket open is gated too`() =
        runBlocking {
            val poster = FakePoster()
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, consent = ConsentDecision.HELD, poster = poster)
            client.sendContext(scopes = listOf("accounts"))
            client.connect()
            assertNotNull(transports.waitFor(1)).opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            delay(50)
            assertTrue(poster.requests.isEmpty())
            client.close()
        }

    @Test
    fun `S3 - granting consent flushes the held context exactly once`() =
        runBlocking {
            val poster = FakePoster()
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, consent = ConsentDecision.HELD, poster = poster)
            client.sendContext(scopes = listOf("accounts"))
            client.connect()
            assertNotNull(transports.waitFor(1)).opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            client.setConsent(ConsentDecision.GRANTED)
            assertTrue(eventually { poster.requests.size == 1 })
            delay(50)
            assertEquals(1, poster.requests.size)
            client.close()
        }

    @Test
    fun `S4 - denied posts nothing and reports ConsentNotGranted once`() =
        runBlocking {
            val poster = FakePoster()
            val client = makeTestClient(consent = ConsentDecision.DENIED, poster = poster)
            val errors = errorsOf(client)
            client.sendContext(scopes = listOf("accounts"))
            client.sendContext(scopes = listOf("transactions"))
            assertTrue(poster.requests.isEmpty())
            assertEquals(listOf<LiveSyncException>(LiveSyncException.ConsentNotGranted()), errors.toList())
        }

    @Test
    fun `S5 - revoking consent closes the socket, clears the advertised context, and a later open posts nothing`() =
        runBlocking {
            val poster = FakePoster()
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports, consent = ConsentDecision.GRANTED, poster = poster)
            client.sendContext(scopes = listOf("accounts"))
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            assertTrue(eventually { poster.requests.size == 1 })
            client.setConsent(ConsentDecision.DENIED)
            assertTrue(t.closed)
            assertFalse(client.hasPendingContext())
            assertEquals(LiveSyncConnectionState.Disconnected, client.connectionState.value)
            client.connect() // the host reconnects for some reason
            assertNotNull(transports.waitFor(2)).opened()
            delay(50)
            assertEquals(1, poster.requests.size)
            client.close()
        }

    @Test
    fun `S6 - the default is held, and there is no development default to reach for`() {
        assertEquals(ConsentDecision.HELD, ConsentDecision.DEFAULT)
        // There is no debug-only `development` = granted. A library cannot see the host's build type,
        // so Android has none: the only way to post is a real grant.
        assertEquals(listOf("HELD", "GRANTED", "DENIED"), ConsentDecision.entries.map { it.name })
    }
}

/** The injected transport drives dispatch. */
class LiveSyncTransportTest {
    @Test
    fun `T1 - frames from the transport reach onAction in order`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("filter_transactions", "open_account")
            val seen = Collections.synchronizedList(mutableListOf<String>())
            client.onAction = { seen += it.action }
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            t.frame("""{"classification":"custom","action":"open_account"}""")
            t.frame("""{"classification":"custom","action":"filter_transactions","payload":{"amount":240}}""")
            assertTrue(eventually { seen.toList() == listOf("open_account", "filter_transactions") })
            client.close()
        }

    @Test
    fun `T2 - a malformed frame between two good ones is reported and does not stop the stream`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.advertise("a", "b")
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val errors = errorsOf(client)
            client.onAction = { seen += it.action }
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            t.frame("""{"classification":"custom","action":"a"}""")
            t.frame("}{")
            t.frame("""{"classification":"custom","action":"b"}""")
            assertTrue(eventually { seen.toList() == listOf("a", "b") })
            assertEquals(1, errors.size)
            client.close()
        }

    @Test
    fun `T3 - a transport failure is reported and a reconnect follows after the backoff`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val sleeper = ManualSleeper()
            val client = makeTestClient(transports = transports, sleeper = sleeper)
            val errors = errorsOf(client)
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            t.end(LiveSyncException.SocketFailure("code 1006"))
            assertNotNull(transports.waitFor(2))
            assertTrue(LiveSyncException.SocketFailure("code 1006") in errors)
            assertEquals(1, sleeper.slept.size)
            assertEquals(LiveSyncConnectionState.Reconnecting(1), client.connectionState.value)
            client.close()
        }

    @Test
    fun `T4 - disconnect() closes the transport, settles on Disconnected, and does not reconnect`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            assertTrue(eventually { client.connectionState.value == LiveSyncConnectionState.Connected })
            client.disconnect()
            assertTrue(t.closed)
            assertEquals(LiveSyncConnectionState.Disconnected, client.connectionState.value)
            delay(100)
            assertEquals(1, transports.count)
            assertEquals(LiveSyncConnectionState.Disconnected, client.connectionState.value)
        }

    @Test
    fun `T5 - close() closes the socket, and a closed client never opens another`() =
        runBlocking {
            val transports = FakeTransportFactory()
            val client = makeTestClient(transports = transports)
            client.connect()
            val t = assertNotNull(transports.waitFor(1))
            t.opened()
            client.close()
            assertTrue(t.closed)
            client.connect()
            delay(50)
            assertEquals(1, transports.count)
        }

    @Test
    fun `T6 - an exception's description never carries its message - the socket URL, and its API key, live there`() {
        val described = LiveSyncException.describe(UnknownHostException("ws.example.invalid/?apiKey=SECRET-KEY"))
        assertFalse("SECRET-KEY" in described)
        assertEquals("UnknownHostException", described)
        assertEquals("HttpRequestSpec(<redacted>)", com.amazon.connect.touchpoint.livesync.internal.HttpRequestSpec("wss://h/?apiKey=SECRET-KEY", mapOf("nlx-api-key" to "SECRET-KEY")).toString())
    }
}
