package com.amazon.connect.touchpoint.core

import java.util.UUID

// Connect customer authentication: the flow's "Authenticate Customer" block. Web's
// `Authentication.tsx` card and `conversation.ts` state machine; the transport owns the protocol,
// Core only names the states and forwards.

/** Where one sign-in stands, web's `AuthenticationStatus`. */
public enum class AuthenticationStatus {
    /** The flow asked the customer to sign in: Authenticate, or continue without. */
    PROMPT,

    /** The sign-in page is open, or its result is on its way to Connect. */
    IN_PROGRESS,
    SUCCESS,
    FAILED,

    /** Connect's window lapsed: its `timeout` or `expired` event, or 60 s without a start. */
    EXPIRED,

    /** The customer continued without signing in, or closed the page. */
    CANCELLED,
    ;

    /** Prompt and in progress hold the composer: web locks the input until the card resolves. */
    public val isPending: Boolean get() = this == PROMPT || this == IN_PROGRESS
}

/**
 * The transcript's sign-in card: one per `authentication.initiated`, updated in place.
 *
 * @property afterMessageId The newest message when the flow asked; the card is drawn after it, as a notice is.
 */
public data class AuthenticationCard(
    val id: UUID,
    val status: AuthenticationStatus,
    val afterMessageId: UUID?,
)

/**
 * What the sign-in page opens, and the redirect it comes back on. [toString] omits the URL, which
 * carries Connect's `state` and `nonce`.
 */
public data class AuthenticationRequest(
    val url: String,
    val redirectUri: String,
) {
    override fun toString(): String = "AuthenticationRequest(url=<redacted>, redirectUri=$redirectUri)"
}

/**
 * What the sign-in redirect carried, for the host's backend to hand to Connect with
 * `UpdateParticipantAuthentication`, an IAM API the device cannot call. Web posts the same fields to
 * its `authenticationEndpoint`; the instance id stays on the backend. [toString] omits the code,
 * a one-time credential, and the state.
 */
public data class AuthenticationCompletion(
    val code: String?,
    val state: String,
    val error: String? = null,
    val errorDescription: String? = null,
) {
    override fun toString(): String = "AuthenticationCompletion(code=${if (code == null) "null" else "<redacted>"}, error=$error)"
}

/**
 * The host's relay of a sign-in to its backend. Its own interface rather than a broker requirement:
 * a default that throws would let a backend never wired for sign-in look configured.
 */
public fun interface AuthenticationRelaying {
    public suspend fun completeAuthentication(completion: AuthenticationCompletion)
}
