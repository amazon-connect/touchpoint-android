package com.acmebank.demo.support

import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debug live mode's signer against AWS's published test suite, and its settings' discipline. */
class LiveModeTest {
    // AWS's Signature Version 4 test-suite credentials (not a real key), assembled so no scanner
    // mistakes the literal for one.
    private val secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCY" + "EXAMPLEKEY"
    private val at = Instant.parse("2015-08-30T12:36:00Z")

    @Test
    fun `signs the test suite's post-vanilla request`() {
        val auth = SigV4.sign("POST", "example.amazonaws.com", "/", emptyMap(), "", "AKIDEXAMPLE", secret, null, "us-east-1", "service", at).getValue("authorization")
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, " +
                "Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b",
            auth,
        )
    }

    @Test
    fun `a session token is sent and signed`() {
        val headers = SigV4.sign("POST", "abc.execute-api.ca-central-1.amazonaws.com", "/prod/chat/start", emptyMap(), "{}", "AKIDEXAMPLE", secret, "token", "ca-central-1", "execute-api", at)
        assertEquals("token", headers["x-amz-security-token"])
        assertTrue("SignedHeaders=host;x-amz-date;x-amz-security-token," in headers.getValue("authorization"))
    }

    @Test
    fun `settings refuse a long-term key pair and never print credentials`() {
        val s = LiveMode.Settings()
        s.endpoint = "https://abc.example.com/prod"
        s.accessKeyId = "AKIDEXAMPLE"
        s.secretAccessKey = secret
        assertFalse(s.isComplete, "no session token: a long-term key must be refused")
        s.sessionToken = "token"
        assertTrue(s.isComplete)
        assertFalse(secret in s.toString() || "token" in s.toString() || "AKIDEXAMPLE" in s.toString(), s.toString())
        s.endpoint = "not a url"
        assertFalse(s.isComplete)
    }

    // ---- The Live Sync keys from the backend, fetched at sign-in.

    // Fake keys, assembled so no scanner mistakes them for real ones.
    private val deploymentKey = "dk-" + "test"
    private val apiKey = "ak-" + "test-secret"

    /** The backend's side of `/livesync/credentials`, behind the same broker the sign-in uses. */
    private class Backend(private val reply: () -> LiveSyncCredentials) : TouchpointBackend {
        var calls = 0

        override suspend fun liveSyncCredentials(): LiveSyncCredentials {
            calls += 1
            return reply()
        }

        override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials = throw UnsupportedOperationException()

        override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = throw UnsupportedOperationException()
    }

    @Test
    fun `the keys the backend vends at sign-in become the Live Sync source, and the log shows the status only`() =
        runTest {
            val backend = Backend { LiveSyncCredentials(deploymentKey, apiKey, expiresAt = null) }
            val logged = mutableListOf<String>()
            val source = LiveMode.fetchLiveSyncKeys(CredentialBroker(backend, InMemorySecureStore())) { logged += it }
            assertNotNull(source)
            assertEquals(LiveSyncCredentials(deploymentKey, apiKey, null), source.liveSyncCredentials())
            assertEquals(1, backend.calls, "the sign-in's fetch is cached for the session")
            assertEquals(1, logged.size)
            assertTrue("status=200" in logged.single(), logged.single())
            assertFalse(logged.any { deploymentKey in it || apiKey in it }, "a key was logged")
        }

    @Test
    fun `a 503 from the backend leaves Live Sync off without failing, and logs the status only`() =
        runTest {
            val body = """{"message":"no key configured $apiKey"}"""
            val logged = mutableListOf<String>()
            val source = LiveMode.fetchLiveSyncKeys(CredentialBroker(Backend { throw TouchpointException.HttpFailure(503, body) }, InMemorySecureStore())) { logged += it }
            assertNull(source)
            assertTrue("status=503" in logged.single(), logged.single())
            assertFalse(logged.any { apiKey in it || "message" in it }, "the reply body was logged")
        }

    @Test
    fun `an unreachable backend leaves Live Sync off without failing`() =
        runTest {
            val logged = mutableListOf<String>()
            val source = LiveMode.fetchLiveSyncKeys(CredentialBroker(Backend { throw TouchpointException.TransportFailure("UnknownHostException") }, InMemorySecureStore())) { logged += it }
            assertNull(source)
            assertTrue("transport failure" in logged.single(), logged.single())
        }

    @Test
    fun `the backend's reply must carry both keys, and they never expire on their own`() {
        assertEquals(LiveSyncCredentials(deploymentKey, apiKey, expiresAt = null), LiveDemoBackend.credentialsOf(" $deploymentKey ", apiKey))
        assertFailsWith<TouchpointException.MalformedPayload> { LiveDemoBackend.credentialsOf(deploymentKey, "") }
        assertFailsWith<TouchpointException.MalformedPayload> { LiveDemoBackend.credentialsOf(null, apiKey) }
    }
}
