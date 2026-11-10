package com.amazon.connect.touchpoint.livesync

/**
 * Failures surfaced by [LiveSyncClient]: thrown by the calls the host makes, and reported through
 * [LiveSyncClient.onError] for what happens outside them. Messages carry case names and safe detail
 * only: an underlying exception is reduced to its type, because an I/O exception's message can
 * carry the socket URL, and the socket URL carries the API key.
 */
public sealed class LiveSyncException(message: String) : Exception(message) {
    /** A required configuration value was missing or malformed. */
    public data class InvalidConfiguration(val detail: String) : LiveSyncException("invalid Live Sync configuration: $detail")

    /**
     * The context request completed with a non-2xx status. The body is kept out of the message and
     * out of [toString]; it reaches a log only if the host reads it deliberately.
     */
    public data class HttpFailure(val statusCode: Int, val responseBody: String?) : LiveSyncException("Live Sync context request failed with status $statusCode") {
        override fun toString(): String = "HttpFailure(statusCode=$statusCode, responseBody=${if (responseBody == null) "null" else "<${responseBody.length} chars>"})"
    }

    /** The context request could not be sent. */
    public data class RequestFailed(val detail: String) : LiveSyncException("Live Sync context request failed: $detail")

    /** The action socket failed or closed unexpectedly. */
    public data class SocketFailure(val detail: String) : LiveSyncException("Live Sync action socket failed: $detail")

    /** A frame arrived that could not be parsed as an action invocation. */
    public data class MalformedInvocation(val detail: String) : LiveSyncException("malformed Live Sync action invocation: $detail")

    /**
     * An invocation refused on the device before any handler ran: the action
     * is not in the context the app advertised, its payload does not match the schema the app
     * declared, or that schema uses a keyword outside the supported subset (see the security guide).
     * [description] names the keyword and a JSON Pointer into the payload, never a value; [action] is
     * `<unnamed>` unless the engine's name looks like an action's.
     */
    public data class InvalidPayload(val action: String, val description: String) : LiveSyncException("rejected the `$action` action before it ran: $description")

    /**
     * An action handler threw — a payload the model could not decode, or a check of the handler's
     * own — or a held confirmation was declined.
     * [detail] is the exception's type, never its message, which may quote the payload.
     */
    public data class ActionHandlerFailed(val action: String, val detail: String) : LiveSyncException("handler for the `$action` action failed: $detail")

    /** The credential source could not vend Live Sync credentials. Type only. */
    public data class CredentialVendingFailed(val detail: String) : LiveSyncException("Live Sync credentials could not be vended: $detail")

    /** Context was not posted because consent has not been granted. Reported once per decision. */
    /**
     * A context was posted on a live socket and nothing has come back for [quietFor]: the
     * `conversationId` may name a contact the backend no longer serves. Reported once per connection;
     * the next frame clears it. A diagnostic, not a failure.
     */
    public data class ContactMayBeStale(val quietFor: kotlin.time.Duration) : LiveSyncException("no Live Sync frame for $quietFor after context was posted; the contact may be stale")

    public class ConsentNotGranted : LiveSyncException("Live Sync context was not sent because consent has not been granted") {
        override fun equals(other: Any?): Boolean = other is ConsentNotGranted

        override fun hashCode(): Int = javaClass.hashCode()

        override fun toString(): String = "ConsentNotGranted"
    }

    internal companion object {
        /** An arbitrary throwable reduced to something safe to surface: its type, never its message. */
        fun describe(error: Throwable): String = error.javaClass.simpleName.ifEmpty { "Throwable" }
    }
}
