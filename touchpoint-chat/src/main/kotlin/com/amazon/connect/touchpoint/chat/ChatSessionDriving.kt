package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.ResolvedView
import com.amazon.connect.touchpoint.core.TouchpointException

// The seam between ConnectChatTransport and the upstream amazon-connect-chat-android SDK.
//
// The adapter's logic — normalisation, receipts, error mapping, buffering — lives behind this small
// interface and is tested on the JVM against a fake. The one real implementation,
// ConnectChatSessionDriver, is the only code that touches the SDK.

/** What the transport needs from a chat session. Deliberately smaller than the SDK's `ChatSession`. */
internal interface ChatSessionDriving {
    fun setHandlers(handlers: ChatDriverHandlers)

    suspend fun connect(
        contactId: String,
        participantId: String,
        /** `null` with a custom chat client: the host's backend holds the token. */
        participantToken: String?,
    )

    /** End the participant's connection on the server, then stop the SDK. */
    suspend fun disconnect()

    /** Stop the SDK's connection and its own reconnecting, but leave the participant on the contact. */
    suspend fun release()

    /**
     * Must return the **server** id of the accepted message. Never implemented via the SDK's
     * `resendFailedMessage`, which deletes and re-issues under a new id.
     */
    suspend fun sendMessage(
        body: String,
        interactiveResponse: Boolean = false,
    ): SentMessageAck

    suspend fun sendReceipt(
        kind: ReceiptKind,
        messageId: String,
    )

    /** A customer typing event. Default: nothing, for a driver without it. */
    suspend fun sendTyping() {}

    /** Older items first within the page; `nextToken` reaches the page before this one. */
    suspend fun fetchTranscript(
        nextToken: String?,
        maxResults: Int,
    ): RawTranscriptPage

    /** Start, send and complete an upload; Connect's attachment id. Default: not supported. */
    suspend fun uploadAttachment(file: AttachmentUpload): String = throw TouchpointException.InvalidConfiguration("this driver cannot send files")

    /** A short-lived address for an attachment. Default: not supported. */
    suspend fun attachmentUrl(attachmentId: String): String = throw TouchpointException.InvalidConfiguration("this driver cannot fetch files")

    /**
     * Start hearing Connect's sign-in events, which the chat SDK drops. Called once, before
     * [connect], only when the host configured sign-in. Default: nothing to hear them with.
     */
    fun enableAuthenticationEvents() {}

    /** `GetAuthenticationUrl` for the prompt's session. Default: not supported. */
    suspend fun authenticationUrl(
        sessionId: String,
        redirectUri: String,
    ): String = throw TouchpointException.InvalidConfiguration("this driver cannot sign in")

    /** `CancelParticipantAuthentication`: the flow takes its opted-out branch. Default: not supported. */
    suspend fun cancelAuthentication(sessionId: String): Unit = throw TouchpointException.InvalidConfiguration("this driver cannot sign in")

    /** `DescribeView`: a Connect View, by its message's token. Default: not supported. */
    suspend fun describeView(viewToken: String): ResolvedView = throw TouchpointException.InvalidConfiguration("this driver cannot open views")
}

/** Callbacks a driver raises, from any thread. Each must return at once. */
internal class ChatDriverHandlers(
    val onConnected: () -> Unit,
    /** The SDK reconnected by itself; what happened while it was away is unknown. */
    val onReEstablished: () -> Unit,
    val onConnectionBroken: () -> Unit,
    val onChatEnded: () -> Unit,
    val onItem: (RawTranscriptItem) -> Unit,
    val onReceipt: (ReceiptKind, String) -> Unit,
    /** Someone typing, by their Connect role; never the customer's own echo. */
    val onTyping: (role: String) -> Unit = {},
    /**
     * A sign-in event: its content type, its `Content` (the `SessionId` JSON for `initiated`), and its
     * `AbsoluteTime` when it came from the catch-up read rather than live.
     */
    val onAuthenticationEvent: (contentType: String, content: String?, absoluteTime: String?) -> Unit = { _, _, _ -> },
)

/** @property absoluteTime Server `AbsoluteTime`, ISO-8601 with fractional seconds: the ordering authority. */
internal data class SentMessageAck(val serverMessageId: String, val absoluteTime: String)

/**
 * One websocket frame or transcript row, before any SDK type is involved.
 *
 * @property participantRole Connect wire role: `CUSTOMER`, `AGENT`, `SYSTEM`, `SUPERVISOR`, `CUSTOM_BOT`.
 * @property participantId Connect `ParticipantId`, recovered from the raw frame; `null` when the frame
 *   did not carry one (system messages, and the SDK's own history fetch, which drops it).
 * @property isOutgoing The server's copy of a message *this* participant sent.
 * @property isFromPastSession A row of the SDK's own history replay rather than a live frame: never
 *   receipted again, and never trusted for participant ids, which the replay drops.
 * @property attachmentId Connect's id for the file this item is; then [text] is its name and
 *   [contentType] its MIME type.
 */
internal data class RawTranscriptItem(
    val kind: Kind,
    val id: String,
    val absoluteTime: String,
    val contentType: String,
    val participantRole: String,
    val participantId: String?,
    val displayName: String,
    val text: String?,
    val isOutgoing: Boolean,
    val isFromPastSession: Boolean = false,
    val attachmentId: String? = null,
) {
    enum class Kind { MESSAGE, EVENT }

    override fun toString(): String = "RawTranscriptItem(kind=$kind, id=$id, contentType=$contentType, participantRole=$participantRole, text=<redacted>)"
}

/** @property nextToken An empty string from the service means "no more"; the transport normalises it to `null`. */
internal data class RawTranscriptPage(val items: List<RawTranscriptItem>, val nextToken: String?)

/**
 * Connect content types the adapter understands. Anything else is carried as plain text, so an
 * unknown type degrades to "a message we cannot render" rather than a dropped frame.
 */
internal object ConnectContentType {
    const val PLAIN_TEXT = "text/plain"
    const val MARKDOWN = "text/markdown"
    const val INTERACTIVE = "application/vnd.amazonaws.connect.message.interactive"
    const val INTERACTIVE_RESPONSE = "application/vnd.amazonaws.connect.message.interactive.response"
    const val JSON = "application/json"
    const val JOINED = "application/vnd.amazonaws.connect.event.participant.joined"
    const val LEFT = "application/vnd.amazonaws.connect.event.participant.left"
    const val ENDED = "application/vnd.amazonaws.connect.event.chat.ended"
    const val DELIVERED = "application/vnd.amazonaws.connect.event.message.delivered"
    const val READ = "application/vnd.amazonaws.connect.event.message.read"
}

/** The template a JSON message is kept under beside its words, so its choices can be read. */
internal const val JSON_MESSAGE = "JsonMessage"
