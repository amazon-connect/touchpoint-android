@file:OptIn(com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.AuthenticationCompletion
import com.amazon.connect.touchpoint.core.AuthenticationRelaying
import com.amazon.connect.touchpoint.core.AuthenticationRequest
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TransportEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

// Connect customer authentication on chat: web's state machine (`conversation.ts`
// `AUTH_EVENT_STATUS`, `setAuthState`, `startAuthentication`, `completeAuthentication`,
// `cancelAuthentication`) on the transport, where the fake driver can test it.

/**
 * Sign-in for a chat: where Connect sends the customer back, and who relays what came back to the
 * host's backend (`UpdateParticipantAuthentication` is an IAM API the device cannot call).
 *
 * @property redirectUri Where the identity provider sends the customer back, registered as its
 *   callback. Either:
 *   - a custom scheme the app owns, e.g. `acmebank-touchpoint://auth`; or
 *   - an `https` URL on a domain the app owns, with a path, e.g. `https://bank.example/touchpoint/auth`,
 *     whose domain's Digital Asset Links must name the app: the sign-in tab checks them
 *     before it hands the redirect back, and a browser too old for that tab opens the link in the
 *     app only through a verified App Link.
 *
 *   The sign-in tab hands the redirect straight back to the SDK where the browser can (Chrome's Auth
 *   Tab). Otherwise it reaches the host's activity, which declares the redirect (the SDK declares
 *   nothing) and hands it to `TouchpointSession.completeAuthentication`.
 * @throws TouchpointException.InvalidConfiguration for an `http` redirect, or an `https` one with no
 *   host or no path.
 */
public class ChatAuthentication(
    public val redirectUri: String,
    public val relay: AuthenticationRelaying,
) {
    internal val redirect: AuthenticationRedirect =
        AuthenticationRedirect.of(redirectUri) ?: throw TouchpointException.InvalidConfiguration(
            "ChatAuthentication needs a custom scheme the app owns, or an https URL with a host and a path",
        )

    override fun toString(): String = "ChatAuthentication(redirectUri=$redirectUri)"
}

/** Where a sign-in comes back: the app's own scheme, or an https link on its own domain. */
internal sealed interface AuthenticationRedirect {
    data class Scheme(val scheme: String) : AuthenticationRedirect

    data class Https(val host: String, val path: String) : AuthenticationRedirect

    companion object {
        fun of(uri: String): AuthenticationRedirect? {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when (scheme) {
                // Cleartext would hand the code to anyone on the network.
                "http" -> null
                "https" -> {
                    val host = parsed.host?.takeIf { it.isNotEmpty() } ?: return null
                    val path = parsed.path?.takeIf { it.isNotEmpty() && it != "/" } ?: return null
                    Https(host, path)
                }
                else -> Scheme(scheme)
            }
        }
    }
}

/**
 * The transport's view of the sign-in on one contact. Touched only on the transport's confined
 * thread. [now] is a monotonic clock in milliseconds, [wallClock] the time Connect's are read against.
 */
internal class SignIn(
    private val config: ChatAuthentication,
    private val driver: ChatSessionDriving,
    private val yieldEvent: (TransportEvent) -> Unit,
    private val now: () -> Long,
    private val wallClock: () -> Instant,
) {
    private var cardId: UUID? = null
    private var status: AuthenticationStatus? = null
    private var sessionId: String? = null
    private var initiatedAt: Long? = null
    private var expectedState: String? = null

    /** Sessions already carded, so the catch-up read's copy of an event is not a second card. */
    private val seenSessions = HashSet<String>()

    fun onEvent(
        contentType: String,
        content: String?,
        absoluteTime: String?,
    ) {
        val next = statusOf(contentType) ?: return
        if (next == AuthenticationStatus.PROMPT) {
            val session = content?.let { runCatching { json.parseToJsonElement(it).jsonObject.string("SessionId") }.getOrNull() }
            if (session.isNullOrEmpty() || !seenSessions.add(session)) return // the catch-up's copy
            cardId = UUID.randomUUID()
            status = null // a new card: its prompt is news even while the last one prompted
            sessionId = session
            expectedState = null
            // From Connect's clock when the event was read back late, so the window is not reset.
            val sent = absoluteTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val late = sent?.let { (wallClock().toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) } ?: 0
            initiatedAt = now() - late
            set(AuthenticationStatus.PROMPT)
            return
        }
        // Any other event ends this session; it updates the card even after a local terminal state,
        // as web's does, so Connect's own outcome has the last word.
        if (cardId == null) return
        sessionId = null
        set(next)
    }

    suspend fun begin(): AuthenticationRequest {
        val session = sessionId
        val started = initiatedAt
        if (status != AuthenticationStatus.PROMPT || session == null || started == null) throw TouchpointException.InvalidConfiguration("no sign-in waiting")
        if (now() - started > START_WINDOW_MS) {
            set(AuthenticationStatus.EXPIRED)
            throw TouchpointException.InvalidConfiguration("the sign-in window has passed")
        }
        set(AuthenticationStatus.IN_PROGRESS)
        val url =
            try {
                driver.authenticationUrl(session, config.redirectUri)
            } catch (e: Exception) {
                set(AuthenticationStatus.FAILED)
                throw e
            }
        // The state Connect put in the URL is what the redirect must echo (web's CSRF check). A URL
        // without one, or not over https, is not opened at all.
        val parsed = uri(url)
        val state = parsed?.let { query(it)["state"] }
        if (parsed?.scheme?.lowercase() != "https" || state.isNullOrEmpty()) {
            set(AuthenticationStatus.FAILED)
            throw TouchpointException.InvalidConfiguration("sign-in URL is not usable")
        }
        expectedState = state
        return AuthenticationRequest(url, config.redirectUri)
    }

    suspend fun complete(redirect: String) {
        if (status != AuthenticationStatus.IN_PROGRESS) throw TouchpointException.InvalidConfiguration("no sign-in in progress")
        val expected = expectedState
        expectedState = null
        val back = uri(redirect)
        val ours = uri(config.redirectUri)
        val items = back?.let(::query).orEmpty()
        val state = items["state"]
        // The whole redirect must be ours, and the state Connect's. **Stricter than web**, which skips
        // the check when it has no expected state.
        if (back == null || ours == null || !back.scheme.equals(ours.scheme, ignoreCase = true) || back.host != ours.host ||
            back.path.orEmpty() != ours.path.orEmpty() || expected == null || state != expected
        ) {
            set(AuthenticationStatus.FAILED)
            TouchpointSecurityEvents.report(TouchpointSecurityEvent.SignInRedirectRejected)
            // The card only shows it failed: the host's events hear why (I6).
            yieldEvent(TransportEvent.SecurityRejection(TouchpointException.InvalidConfiguration("sign-in redirect did not match")))
            throw TouchpointException.InvalidConfiguration("sign-in redirect did not match")
        }
        try {
            config.relay.completeAuthentication(AuthenticationCompletion(items["code"], state, items["error"], items["error_description"]))
        } catch (e: Exception) {
            set(AuthenticationStatus.FAILED)
            throw e
        }
        // Success is Connect's to say: the card stays in progress until its event.
    }

    /** See `ContactTransport.cancelAuthentication`. */
    suspend fun cancel(unlessRedirected: Boolean) {
        val session = sessionId ?: return
        if (status?.isPending != true) return
        // The page's redirect consumed the expected state: it came back, so the page was not closed.
        if (unlessRedirected && (status != AuthenticationStatus.IN_PROGRESS || expectedState == null)) return
        try {
            driver.cancelAuthentication(session)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // cancelled either way, as web's
        }
        sessionId = null
        set(AuthenticationStatus.CANCELLED)
    }

    private fun set(next: AuthenticationStatus) {
        val id = cardId ?: return
        if (status == next) return
        status = next
        yieldEvent(TransportEvent.AuthenticationChanged(id, next))
    }

    companion object {
        /** Connect's window to ask for the sign-in URL: web's `Date.now() - authInitiatedAt > 60_000`. */
        const val START_WINDOW_MS = 60_000L

        private const val PREFIX = "application/vnd.amazonaws.connect.event.authentication."
        private val json = Json { ignoreUnknownKeys = true }

        /** Web's `AUTH_EVENT_STATUS`, aliases included. */
        fun statusOf(contentType: String): AuthenticationStatus? =
            if (!contentType.startsWith(PREFIX)) {
                null
            } else {
                when (contentType.removePrefix(PREFIX)) {
                    "initiated" -> AuthenticationStatus.PROMPT
                    "succeeded", "success" -> AuthenticationStatus.SUCCESS
                    "failed" -> AuthenticationStatus.FAILED
                    "timeout", "expired" -> AuthenticationStatus.EXPIRED
                    "cancelled" -> AuthenticationStatus.CANCELLED
                    else -> null
                }
            }

        private fun uri(s: String): URI? =
            try {
                URI(s)
            } catch (_: URISyntaxException) {
                null
            }

        /** The first value of each query parameter, decoded. */
        private fun query(uri: URI): Map<String, String> =
            uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
            }.distinctBy { it.first }.toMap()

        private fun JsonObject.string(key: String): String? = runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}

/** The sign-in frames on a participant websocket, which the chat SDK drops. */
internal object AuthenticationFrames {
    data class Event(val contentType: String, val content: String?, val absoluteTime: String?)

    private val json = Json { ignoreUnknownKeys = true }

    /** `{"topic":"aws/chat","content":"<frame JSON>"}` → the event, when it is a sign-in one. */
    fun event(frame: String): Event? =
        runCatching {
            val outer = json.parseToJsonElement(frame).jsonObject
            if (outer["topic"]?.jsonPrimitive?.contentOrNull != "aws/chat") return null
            val inner = json.parseToJsonElement(outer["content"]?.jsonPrimitive?.contentOrNull ?: return null).jsonObject
            val type = inner["ContentType"]?.jsonPrimitive?.contentOrNull ?: return null
            if (SignIn.statusOf(type) == null) return null
            Event(type, inner["Content"]?.jsonPrimitive?.contentOrNull, inner["AbsoluteTime"]?.jsonPrimitive?.contentOrNull)
        }.getOrNull()
}
