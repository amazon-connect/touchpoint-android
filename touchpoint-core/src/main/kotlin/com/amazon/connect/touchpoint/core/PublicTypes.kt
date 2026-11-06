package com.amazon.connect.touchpoint.core

/**
 * The conversation channel a contact uses. There is no umbrella enum of UI modes — each product
 * exposes its own entry point — but the coordinator still needs to name which
 * transport a contact runs on.
 *
 * [rawValue] is the persisted form, deliberately independent of the constant's name so renaming a
 * constant can never orphan stored conversations.
 */
public enum class Modality(public val rawValue: String) {
    CHAT("chat"),
    VOICE("voice"),
    ;

    public companion object {
        public fun fromRawValue(rawValue: String): Modality? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Why a contact ended. Terminal; a new contact after any of these is a new session. */
public enum class EndReason {
    /** The customer ended it through the SDK (`end()`). */
    CUSTOMER_ENDED,

    /** The agent or the contact flow disconnected the participant. */
    AGENT_ENDED,

    /** Bounded reconnection reached its attempt or time ceiling. */
    CONNECTION_LOST,

    /** A mid-session credential refresh failed and the modality cannot continue (voice). */
    CREDENTIAL_VENDING_FAILED,

    /** The SDK or host ended it for another reason (resume failure, teardown). */
    SYSTEM_ENDED,
}

/**
 * A permission the host must obtain before a feature can work. Denial is a product state, never an
 * error; the SDK never requests one without host opt-in.
 */
public enum class Permission {
    MICROPHONE,
}

/** Lifecycle of the single active contact in a [TouchpointSession]. */
public sealed interface ContactState {
    public data object Idle : ContactState

    public data object Connecting : ContactState

    public data object Connected : ContactState

    public data class Reconnecting(val attempt: Int) : ContactState

    /** Backgrounded or resumed from the store; for chat, push delivery takes over. */
    public data object Suspended : ContactState

    public data class Ended(val reason: EndReason) : ContactState
}

/**
 * The error taxonomy. Thrown from calls the host made; delivered through
 * [ContactEvent.Failed] for failures with no caller to throw to.
 *
 * Kotlin-specific rule: a coroutine's `CancellationException` is never mapped into this hierarchy.
 * Cancellation is control flow, and converting it into a failure would break structured concurrency
 * for the host.
 *
 * Messages carry identifiers, states and case names only — never a response body, token or message
 * content — because an exception message ends up in crash reports and `Log` calls.
 */
public sealed class TouchpointException(message: String) : Exception(message) {
    public data class InvalidConfiguration(val detail: String) : TouchpointException("invalid configuration: $detail")

    public data class CredentialVendingFailed(val underlying: String) : TouchpointException("credential vending failed: $underlying")

    /** Most often a stale contact ID. */
    public class ContactNotFound : TouchpointException("contact not found") {
        override fun equals(other: Any?): Boolean = other is ContactNotFound

        override fun hashCode(): Int = ContactNotFound::class.hashCode()

        override fun toString(): String = "ContactNotFound"
    }

    public data class TransportFailure(val detail: String) : TouchpointException("transport failure: $detail")

    /**
     * The response body is carried because it is frequently the only authoritative explanation of a
     * rejection. It is kept out of the exception message and out of [toString], so it reaches a log
     * only if the host reads the property deliberately.
     */
    public data class HttpFailure(val statusCode: Int, val responseBody: String?) : TouchpointException("http failure: status $statusCode") {
        override fun toString(): String = "HttpFailure(statusCode=$statusCode, responseBody=${if (responseBody == null) "null" else "<${responseBody.length} chars>"})"
    }

    public data class MalformedPayload(val detail: String) : TouchpointException("malformed payload: $detail")

    public data class ActionHandlerFailed(val action: String, val description: String) : TouchpointException("action handler failed: $action")

    public data class PermissionDenied(val permission: Permission) : TouchpointException("permission denied: $permission")

    public class ConsentNotGranted : TouchpointException("consent not granted") {
        override fun equals(other: Any?): Boolean = other is ConsentNotGranted

        override fun hashCode(): Int = ConsentNotGranted::class.hashCode()

        override fun toString(): String = "ConsentNotGranted"
    }

    public data class PersistenceFailure(val detail: String) : TouchpointException("persistence failure: $detail")
}
