package com.amazon.connect.touchpoint.core

import java.time.Instant
import java.util.UUID

// The integration contract

/**
 * The one genuinely permanent decision in the SDK: how credentials reach it.
 *
 * The host implements this against a backend it operates. Every method suspends and may throw, and
 * there is no overload accepting a literal API key for production use. `DevelopmentBackend`
 * lives in the separate `touchpoint-development` artifact, which a host links only into debug builds.
 */
public interface TouchpointBackend {
    /** Host backend calls `StartChatContact` and returns the participant token. */
    public suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials

    /** Host backend calls `StartWebRTCContact` and returns the Chime meeting configuration. */
    public suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials

    /** Host backend returns Live Sync deployment credentials, ideally short-lived. */
    public suspend fun liveSyncCredentials(): LiveSyncCredentials
}

// Request DTOs

/**
 * What the SDK tells the host backend when starting a chat. The backend adds the instance and flow
 * identifiers — the SDK never holds them.
 *
 * [toString] prints the attribute keys only: the display name identifies a person and the values
 * are the customer's data.
 *
 * @property displayName Shown to the agent in the CCP.
 * @property attributes Contact attributes forwarded to `StartChatContact`. Must not carry secrets.
 */
public data class ChatContactRequest(
    val displayName: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ChatContactRequest(displayName=<redacted>, attributeKeys=${attributes.keys.sorted()})"
}

/** What the SDK tells the host backend when starting an outbound voice contact. [toString] redacts as [ChatContactRequest]'s does. */
public data class VoiceContactRequest(
    val displayName: String,
    val attributes: Map<String, String> = emptyMap(),
    /**
     * The customer may share this app's screen with the agent: the backend asks Connect for it
     * (`StartWebRTCContact`'s `AllowedCapabilities.Customer.ScreenShare = SEND`). Off by default, and a
     * backend sends nothing different until it is on.
     */
    val screenShare: Boolean = false,
    /**
     * The idempotency key, as [OutboundMessage.clientMessageId] is the outbox's: a host whose request
     * times out at its gateway and is retried must not start a second contact. Forward it to
     * `StartWebRTCContact` as `ClientToken`. Minted once per request, so a retry of the same request
     * carries the same token.
     */
    val clientToken: String = UUID.randomUUID().toString(),
) {
    override fun toString(): String = "VoiceContactRequest(displayName=<redacted>, attributeKeys=${attributes.keys.sorted()}, screenShare=$screenShare)"
}

/**
 * A message the customer composed. [clientMessageId] is the idempotency key for the outbox.
 *
 * [interactiveTemplate]: the message is the customer's answer to an interactive message — a quick
 * reply's or list picker's choice — in that template ([InteractiveContent.CHOICE_TEMPLATE]); the
 * transport builds the response from [body], and [choiceId] where the choice has an id other than its
 * words. `null` for text.
 *
 * [toString] omits the body and the choice: a Kotlin data class otherwise prints every field, and
 * message content must never reach a log through a string template.
 */
public data class OutboundMessage(
    val clientMessageId: UUID = UUID.randomUUID(),
    val body: String,
    val interactiveTemplate: String? = null,
    val choiceId: String? = null,
) {
    override fun toString(): String = "OutboundMessage(clientMessageId=$clientMessageId, body=<redacted>, interactiveTemplate=$interactiveTemplate, choiceId=<redacted>)"
}

// Credentials

/** Anything the broker can decide to refresh. */
public interface ExpiringCredentials {
    /** `null` means the credential does not expire on a schedule the SDK knows about. */
    public val expiresAt: Instant?
}

/**
 * Result of `StartChatContact` as the host backend relays it.
 *
 * Deliberately has no serializer: one would write the token in clear text. [toString] redacts the
 * token, because a data class would otherwise print it into any string template.
 */
public data class ChatContactCredentials(
    val contactId: String,
    val participantId: String,
    /**
     * `null` when the host routes every Participant Service call through its own backend with a
     * custom chat client (web's `customChatClient`), so the token never reaches the device.
     */
    val participantToken: String?,
    override val expiresAt: Instant?,
) : ExpiringCredentials {
    override fun toString(): String = "ChatContactCredentials(contactId=$contactId, participantToken=<redacted>)"
}

/**
 * Result of `StartWebRTCContact` as the host backend relays it. Meeting and attendee payloads are
 * carried as opaque JSON text so Core never references Chime types. No serializer, for the
 * same reason as [ChatContactCredentials].
 */
public data class VoiceContactCredentials(
    val contactId: String,
    val participantId: String,
    val participantToken: String,
    val meetingJson: String,
    val attendeeJson: String,
    override val expiresAt: Instant?,
) : ExpiringCredentials {
    override fun toString(): String =
        "VoiceContactCredentials(contactId=$contactId, participantToken=<redacted>, meeting=<redacted>)"
}

/**
 * Live Sync deployment credentials. The API key travels in the socket URL, so rotation takes effect
 * on the next connect. Persisted by [CredentialBroker] through an internal surrogate,
 * so the public type carries no serializer that could write the key somewhere else.
 */
public data class LiveSyncCredentials(
    val deploymentKey: String,
    val apiKey: String,
    override val expiresAt: Instant?,
) : ExpiringCredentials {
    override fun toString(): String = "LiveSyncCredentials(deploymentKey=$deploymentKey, apiKey=<redacted>)"
}
