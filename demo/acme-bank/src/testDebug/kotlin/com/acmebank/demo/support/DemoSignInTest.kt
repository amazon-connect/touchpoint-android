package com.acmebank.demo.support

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoSignInTest {
    private val config = DemoSignIn.Configuration("ca-central-1", "ca-central-1_pool", "client", "ca-central-1:identities")
    private var now = Instant.parse("2026-01-01T00:00:00Z")

    private data class Call(val url: String, val target: String, val body: String) {
        val json get() = Json.parseToJsonElement(body).jsonObject
    }

    /** Cognito, scripted: the targets it is asked for, and what each answers. */
    private class FakeCognito(var answer: (Call) -> Pair<Int, String>) : DemoSignIn.CognitoHttp {
        val calls = mutableListOf<Call>()

        override suspend fun post(url: String, target: String, body: String): Pair<Int, String> {
            val call = Call(url, target, body)
            calls += call
            return answer(call)
        }
    }

    private fun credentials(expiresIn: Long = 3600) =
        """{"IdentityId":"id-1","Credentials":{"AccessKeyId":"AKIA","SecretKey":"secret","SessionToken":"session","Expiration":${now.epochSecond + expiresIn}}}"""

    private fun happy(call: Call): Pair<Int, String> =
        when (call.target) {
            "AWSCognitoIdentityProviderService.InitiateAuth" ->
                200 to """{"AuthenticationResult":{"IdToken":"id-token","RefreshToken":"refresh","ExpiresIn":3600}}"""
            "AWSCognitoIdentityService.GetId" -> 200 to """{"IdentityId":"id-1"}"""
            "AWSCognitoIdentityService.GetCredentialsForIdentity" -> 200 to credentials()
            else -> 400 to "{}"
        }

    private fun signIn(cognito: FakeCognito) = DemoSignIn(config, cognito) { now }

    @Test fun `signing in proves the chain, from the password to the pool to the ID token to the identity pool`() =
        runTest {
            val cognito = FakeCognito(::happy)
            val signIn = signIn(cognito)
            signIn.signIn("jordan", "pw")
            assertTrue(signIn.isSignedIn)
            assertEquals(
                listOf("AWSCognitoIdentityProviderService.InitiateAuth", "AWSCognitoIdentityService.GetId", "AWSCognitoIdentityService.GetCredentialsForIdentity"),
                cognito.calls.map { it.target },
            )
            val auth = cognito.calls[0].json
            assertEquals("USER_PASSWORD_AUTH", auth["AuthFlow"]?.jsonPrimitive?.content)
            assertEquals("https://cognito-idp.ca-central-1.amazonaws.com/", cognito.calls[0].url)
            val logins = cognito.calls[1].json["Logins"]!!.jsonObject
            assertEquals("id-token", logins["cognito-idp.ca-central-1.amazonaws.com/ca-central-1_pool"]?.jsonPrimitive?.content)
            assertEquals("AKIA", signIn.credentials().accessKeyId)
        }

    @Test fun `credentials are reused until five minutes before they expire`() =
        runTest {
            val cognito = FakeCognito(::happy)
            val signIn = signIn(cognito)
            signIn.signIn("jordan", "pw")
            val before = cognito.calls.size
            signIn.credentials()
            assertEquals(before, cognito.calls.size)
            now = now.plusSeconds(3500)
            signIn.credentials()
            assertTrue(cognito.calls.size > before)
            // With 100 s left, under two minutes, the ID token is refreshed too.
            assertTrue(cognito.calls.any { it.json["AuthFlow"]?.jsonPrimitive?.content == "REFRESH_TOKEN_AUTH" })
        }

    @Test fun `credentials whose reply names no expiry are still reused for a while`() =
        runTest {
            val cognito =
                FakeCognito {
                    if (it.target.endsWith("GetCredentialsForIdentity")) {
                        200 to """{"IdentityId":"id-1","Credentials":{"AccessKeyId":"AKIA","SecretKey":"secret","SessionToken":"session"}}"""
                    } else {
                        happy(it)
                    }
                }
            val signIn = signIn(cognito)
            signIn.signIn("jordan", "pw")
            val before = cognito.calls.size
            signIn.credentials()
            signIn.credentials()
            assertEquals(before, cognito.calls.size)
        }

        @Test fun `a wrong password says so`() =
        runTest {
            val cognito = FakeCognito { 400 to """{"__type":"NotAuthorizedException","message":"Incorrect username or password."}""" }
            val e = assertFailsWith<DemoSignIn.Failure.Rejected> { signIn(cognito).signIn("jordan", "wrong") }
            assertEquals("Incorrect username or password.", e.message)
        }

    @Test fun `too many attempts and a disabled account are told apart`() =
        runTest {
            val attempts = FakeCognito { 400 to """{"__type":"NotAuthorizedException","message":"Password attempts exceeded"}""" }
            assertTrue(assertFailsWith<DemoSignIn.Failure.Rejected> { signIn(attempts).signIn("j", "p") }.message!!.startsWith("Too many attempts"))
            val disabled = FakeCognito { 400 to """{"__type":"NotAuthorizedException","message":"User is disabled."}""" }
            assertTrue(assertFailsWith<DemoSignIn.Failure.Rejected> { signIn(disabled).signIn("j", "p") }.message!!.contains("disabled"))
        }

    @Test fun `a new-password challenge is refused with what to do`() =
        runTest {
            val cognito = FakeCognito { 200 to """{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"s"}""" }
            val e = assertFailsWith<DemoSignIn.Failure.Rejected> { signIn(cognito).signIn("j", "p") }
            assertTrue(e.message!!.contains("new password"))
        }

    @Test fun `an identity pool that refuses is a setup fault, and leaves nobody signed in`() =
        runTest {
            val cognito = FakeCognito { if (it.target.endsWith("GetId")) 400 to """{"__type":"com.amazon#NotAuthorizedException"}""" else happy(it) }
            val signIn = signIn(cognito)
            val e = assertFailsWith<DemoSignIn.Failure.Unavailable> { signIn.signIn("j", "p") }
            assertTrue(e.message!!.contains("NotAuthorizedException"))
            assertFalse(signIn.isSignedIn)
        }

    @Test fun `a refused refresh signs the user out`() =
        runTest {
            val cognito = FakeCognito(::happy)
            val signIn = signIn(cognito)
            signIn.signIn("jordan", "pw")
            cognito.answer = { if (it.target.endsWith("InitiateAuth")) 400 to """{"__type":"NotAuthorizedException","message":"Refresh Token has expired"}""" else happy(it) }
            now = now.plusSeconds(7200)
            assertFailsWith<DemoSignIn.Failure.SignedOut> { signIn.credentials() }
            assertFalse(signIn.isSignedIn)
        }

    @Test fun `signing out forgets everything`() =
        runTest {
            val signIn = signIn(FakeCognito(::happy))
            signIn.signIn("jordan", "pw")
            signIn.signOut()
            assertFalse(signIn.isSignedIn)
            assertFailsWith<DemoSignIn.Failure.SignedOut> { signIn.credentials() }
        }

    @Test fun `the configuration needs all four values`() {
        assertNull(DemoSignIn.Configuration.of("ca-central-1", "pool", "", "identities"))
        assertEquals("cognito-idp.ca-central-1.amazonaws.com/pool", DemoSignIn.Configuration.of("ca-central-1", "pool", "c", "i")!!.provider)
    }

    @Test fun `credentials print redacted`() = assertEquals("DemoAwsCredentials(<redacted>)", DemoAwsCredentials("a", "b", "c", null).toString())
}
