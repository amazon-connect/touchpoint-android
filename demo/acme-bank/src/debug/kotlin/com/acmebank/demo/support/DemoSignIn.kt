package com.acmebank.demo.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

/** Temporary AWS credentials for the demo backend. Redacted when printed. */
class DemoAwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String,
    val expiresAt: Instant?,
) {
    override fun toString(): String = "DemoAwsCredentials(<redacted>)"
}

/**
 * The demo's sign-in, so nobody vends AWS credentials for the app by hand.
 *
 * A demo user's username and password go to the Cognito user pool (`USER_PASSWORD_AUTH` on the
 * pool's app sign-in client). The demo backend's identity pool then trades the ID token for temporary
 * credentials whose role may only call the demo backend. Accounts exist only because an administrator
 * created them: the pool has no self sign-up.
 *
 * Tokens and credentials live in memory for the run, never on disk. The Cognito calls are unsigned
 * JSON APIs, so this needs no AWS SDK. A real bank's app signs its customers in to its own backend
 * and holds no AWS credentials at all; this exists so the demo can.
 */
class DemoSignIn(
    val configuration: Configuration,
    private val http: CognitoHttp = CognitoHttp.Platform,
    private val clock: () -> Instant = Instant::now,
) {
    data class Configuration(val region: String, val userPoolId: String, val clientId: String, val identityPoolId: String) {
        /** The identity pool's name for the user pool, as the `Logins` map keys it. */
        val provider: String get() = "cognito-idp.$region.amazonaws.com/$userPoolId"

        companion object {
            /** Null unless every value is set. */
            fun of(region: String, userPoolId: String, clientId: String, identityPoolId: String): Configuration? =
                if (listOf(region, userPoolId, clientId, identityPoolId).any { it.isBlank() }) null else Configuration(region, userPoolId, clientId, identityPoolId)
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        /** No signed-in user, or the session can no longer be refreshed. */
        class SignedOut : Failure("Your session has ended. Sign out and sign in again.")

        /** Cognito refused: a wrong password, a disabled account, a pending password change. */
        class Rejected(why: String) : Failure(why)

        /** Anything else: the network, an unexpected reply, a setup fault. */
        class Unavailable(why: String) : Failure("Sign-in is unavailable right now ($why).")
    }

    /** One Cognito JSON call: what the tests replace. Returns the HTTP status and the reply body. */
    fun interface CognitoHttp {
        suspend fun post(url: String, target: String, body: String): Pair<Int, String>

        object Platform : CognitoHttp {
            override suspend fun post(url: String, target: String, body: String): Pair<Int, String> =
                withContext(Dispatchers.IO) {
                    val connection = URI(url).toURL().openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000
                        connection.doOutput = true
                        connection.setRequestProperty("content-type", "application/x-amz-json-1.1")
                        connection.setRequestProperty("x-amz-target", target)
                        connection.outputStream.use { it.write(body.encodeToByteArray()) }
                        val status = connection.responseCode
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } catch (e: IOException) {
                        throw Failure.Unavailable("network")
                    } finally {
                        connection.disconnect()
                    }
                }
        }
    }

    private val lock = Mutex()
    private var idToken: String? = null
    private var idTokenExpiry: Instant? = null
    private var refreshToken: String? = null
    private var identityId: String? = null
    private var cached: DemoAwsCredentials? = null

    /**
     * Bumped by every sign-out. Work that started under an older generation writes nothing when it
     * resumes, so a reply that lands after a sign-out can't restore the old user's session.
     */
    @Volatile private var generation = 0

    val isSignedIn: Boolean get() = refreshToken != null

    /**
     * Signs in and proves the whole chain (pool, identity pool, credentials) before returning, so a
     * wrong setup fails on the sign-in screen rather than at the first chat.
     */
    suspend fun signIn(username: String, password: String) {
        signOut()
        val started = generation
        val reply =
            userPool(
                "InitiateAuth",
                buildJsonObject {
                    put("AuthFlow", "USER_PASSWORD_AUTH")
                    put("ClientId", configuration.clientId)
                    putJsonObject("AuthParameters") {
                        put("USERNAME", username)
                        put("PASSWORD", password)
                    }
                },
            )
        when (reply.string("ChallengeName")) {
            null -> Unit
            "NEW_PASSWORD_REQUIRED" -> throw Failure.Rejected("This account needs a new password. Ask an administrator to set one.")
            else -> throw Failure.Rejected("This account uses multi-factor sign-in, which the demo doesn't support.")
        }
        lock.withLock {
            if (generation != started) throw Failure.SignedOut()
            keep(reply)
        }
        if (refreshToken == null) throw Failure.Unavailable("no refresh token")
        try {
            credentials()
        } catch (e: Failure) {
            // Half a sign-in is no sign-in: drop the tokens the pool gave us.
            if (generation == started) signOut()
            throw e
        }
    }

    fun signOut() {
        generation++
        idToken = null
        idTokenExpiry = null
        refreshToken = null
        identityId = null
        cached = null
    }

    /**
     * Credentials for the demo backend, refreshed five minutes before they expire. One refresh at a
     * time: calls made together at launch share it rather than each exchanging.
     */
    suspend fun credentials(): DemoAwsCredentials =
        lock.withLock {
            cached?.takeIf { c -> c.expiresAt?.isAfter(clock().plusSeconds(300)) == true }?.let { return@withLock it }
            val started = generation
            val fresh = exchange(started)
            if (generation != started) throw Failure.SignedOut()
            cached = fresh
            fresh
        }

    // Called with the lock held.
    private suspend fun exchange(started: Int): DemoAwsCredentials {
        val token = currentIdToken(started)
        val logins = buildJsonObject { put(configuration.provider, token) }
        val id =
            identityId ?: identityPool(
                "GetId",
                buildJsonObject {
                    put("IdentityPoolId", configuration.identityPoolId)
                    put("Logins", logins)
                },
            ).string("IdentityId")?.also {
                if (generation != started) throw Failure.SignedOut()
                identityId = it
            } ?: throw Failure.Unavailable("no identity id")
        val reply =
            identityPool(
                "GetCredentialsForIdentity",
                buildJsonObject {
                    put("IdentityId", id)
                    put("Logins", logins)
                },
            )
        val c = reply["Credentials"] as? JsonObject ?: throw Failure.Unavailable("no credentials")
        return DemoAwsCredentials(
            c.string("AccessKeyId") ?: throw Failure.Unavailable("no credentials"),
            c.string("SecretKey") ?: throw Failure.Unavailable("no credentials"),
            c.string("SessionToken") ?: throw Failure.Unavailable("no credentials"),
            // A reply without an expiry is cached for a short while rather than not at all: otherwise
            // every call would go back to Cognito.
            c["Expiration"]?.jsonPrimitive?.doubleOrNull?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
                ?: clock().plusSeconds(UNDATED_CREDENTIALS_SECONDS),
        )
    }

    /** The ID token, refreshed with the refresh token when it has two minutes or less left. */
    private suspend fun currentIdToken(started: Int): String {
        val token = idToken
        if (token != null && idTokenExpiry?.isAfter(clock().plusSeconds(120)) == true) return token
        val refresh = refreshToken ?: throw Failure.SignedOut()
        val reply =
            try {
                userPool(
                    "InitiateAuth",
                    buildJsonObject {
                        put("AuthFlow", "REFRESH_TOKEN_AUTH")
                        put("ClientId", configuration.clientId)
                        putJsonObject("AuthParameters") { put("REFRESH_TOKEN", refresh) }
                    },
                )
            } catch (e: Failure.Rejected) {
                if (generation == started) signOut()
                throw Failure.SignedOut()
            }
        if (generation != started) throw Failure.SignedOut()
        keep(reply)
        return idToken ?: throw Failure.SignedOut()
    }

    private fun keep(reply: JsonObject) {
        val result = reply["AuthenticationResult"] as? JsonObject ?: throw Failure.Unavailable("no ID token")
        idToken = result.string("IdToken") ?: throw Failure.Unavailable("no ID token")
        idTokenExpiry = clock().plusSeconds(result["ExpiresIn"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 3600)
        // A refresh reply carries no new refresh token: keep the one from sign-in.
        result.string("RefreshToken")?.let { refreshToken = it }
    }

    // Wire

    private suspend fun userPool(action: String, body: JsonObject): JsonObject = call("cognito-idp", "AWSCognitoIdentityProviderService.$action", body)

    private suspend fun identityPool(action: String, body: JsonObject): JsonObject = call("cognito-identity", "AWSCognitoIdentityService.$action", body)

    private suspend fun call(host: String, target: String, body: JsonObject): JsonObject {
        val (status, text) =
            try {
                http.post("https://$host.${configuration.region}.amazonaws.com/", target, body.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Failure) {
                throw e
            } catch (e: Exception) {
                throw Failure.Unavailable("network")
            }
        val reply = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        if (status == 200) return reply
        // `__type` is the exception name, sometimes prefixed with a namespace and `#`.
        val type = reply.string("__type")?.substringAfterLast('#') ?: "HTTP $status"
        // The identity pool's refusals (a token it won't take, a role it can't assume) are setup
        // faults, not a wrong password: name them.
        if (host != "cognito-idp") throw Failure.Unavailable(type)
        val message = reply.string("message").orEmpty()
        throw when (type) {
            "NotAuthorizedException", "UserNotFoundException" ->
                when {
                    // The same exception carries "Password attempts exceeded" and "User is disabled".
                    message.contains("attempts exceeded", ignoreCase = true) -> Failure.Rejected("Too many attempts. Wait a few minutes, then try again.")
                    message.contains("disabled", ignoreCase = true) -> Failure.Rejected("This account is disabled. Ask an administrator.")
                    else -> Failure.Rejected("Incorrect username or password.")
                }
            "PasswordResetRequiredException" -> Failure.Rejected("This account needs a new password. Ask an administrator to set one.")
            "UserNotConfirmedException" -> Failure.Rejected("This account isn't confirmed yet.")
            else -> Failure.Unavailable(type)
        }
    }

    private companion object {
        /** How long credentials whose reply named no expiry are reused: under the refresh margin plus a little. */
        const val UNDATED_CREDENTIALS_SECONDS = 600L
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
