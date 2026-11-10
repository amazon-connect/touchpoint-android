package com.amazon.connect.touchpoint.chat

import android.content.Context
import com.amazon.connect.chat.sdk.ChatSession
import com.amazon.connect.chat.sdk.model.ChatDetails
import com.amazon.connect.chat.sdk.model.ContentType
import com.amazon.connect.chat.sdk.model.Event
import com.amazon.connect.chat.sdk.model.GlobalConfig
import com.amazon.connect.chat.sdk.model.Message
import com.amazon.connect.chat.sdk.model.TranscriptItem
import com.amazon.connect.chat.sdk.network.AWSClient
import com.amazon.connect.chat.sdk.provider.ChatSessionProvider
import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.ReceiptKind
import com.amazon.connect.touchpoint.core.ResolvedView
import com.amazon.connect.touchpoint.core.TouchpointException
import aws.sdk.kotlin.services.connectparticipant.model.CompleteAttachmentUploadRequest
import aws.sdk.kotlin.services.connectparticipant.model.GetTranscriptRequest
import aws.sdk.kotlin.services.connectparticipant.model.Item
import aws.sdk.kotlin.services.connectparticipant.model.ScanDirection
import aws.sdk.kotlin.services.connectparticipant.model.SortKey
import aws.sdk.kotlin.services.connectparticipant.model.StartAttachmentUploadRequest
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import com.amazon.connect.chat.sdk.model.Region
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The one real [ChatSessionDriving]: wraps the process-wide `ChatSession` from
 * amazon-connect-chat-android 2.0.11. Everything that can be unit-tested lives in
 * [ConnectChatTransport]; this is the only code that touches the SDK.
 *
 * Upstream constraints it works around, each read from the SDK's source:
 * - `ChatSession.sendMessage` returns no server id. Sends go through the SDK's own [AWSClient] with the
 *   connection token the session already holds, so the id and server time come back in the response
 *   and the client-id ↔ server-id correlation is exact.
 * - The SDK's history fetch rebuilds each row without `ParticipantId`, which the participant set
 *   is keyed on. Transcript pages are fetched through [AWSClient] too, from the service's
 *   own items.
 * - On live frames the typed model drops `ParticipantId`; it is recovered from the raw frame the SDK
 *   keeps in `serializedContent`.
 * - The session is a process singleton: one chat per process, as the coordinator assumes.
 */
internal class ConnectChatSessionDriver(
    private val session: ChatSession,
    private val client: AWSClient,
    region: Region,
    /** The host's own Participant Service client (web's `customChatClient`): the SDK's, and every call this driver makes. */
    private val customChatClient: AWSClient? = null,
    private val participant: ParticipantHttp = ParticipantHttp.forRegion(region.getName()),
    private val listener: AuthenticationEventListener = AuthenticationEventListener(),
) : ChatSessionDriving {
    @Volatile private var participantId: String? = null

    // Sign-in, off until the host configures it.
    @Volatile private var authenticationEnabled = false

    @Volatile private var participantToken: String? = null

    /**
     * When the SDK last ended a chat. `ChatSessionImpl` tears down whatever callbacks exist 500 ms
     * after any chat end (ChatSession.kt:218-225), so a connect inside that window would lose its own.
     */
    @Volatile private var lastEnded: Long? = null

    init {
        // Client-side metrics off: the SDK would otherwise report usage on the host's behalf.
        session.configure(GlobalConfig(region = region, disableCsm = true, customAWSClient = customChatClient))
    }

    override fun setHandlers(handlers: ChatDriverHandlers) {
        // The sign-in listener follows the SDK's connection: (re)opened with it, stopped with it.
        session.onConnectionEstablished = {
            startListening(handlers)
            handlers.onConnected()
        }
        session.onConnectionReEstablished = {
            startListening(handlers)
            handlers.onReEstablished()
        }
        // Not onDeepHeartBeatFailure: one missed deep heartbeat raises it *and* onConnectionBroken
        // (WebSocketManager.kt:382,389), and counting both would spend two reconnect attempts on one drop.
        session.onConnectionBroken = { handlers.onConnectionBroken() }
        session.onChatEnded = {
            listener.stop()
            lastEnded = System.nanoTime()
            handlers.onChatEnded()
        }
        session.onMessageReceived = { item -> live(item)?.let(handlers.onItem) }
        session.onMessageDelivered = { event -> event?.id?.takeIf { it.isNotEmpty() }?.let { handlers.onReceipt(ReceiptKind.DELIVERED, it) } }
        session.onMessageRead = { event -> event?.id?.takeIf { it.isNotEmpty() }?.let { handlers.onReceipt(ReceiptKind.READ, it) } }
        // As web: everyone's typing but the customer's own, echoed back.
        session.onTyping = { event ->
            val role = event?.participant.orEmpty()
            if (role.isNotEmpty() && !role.equals("CUSTOMER", ignoreCase = true)) handlers.onTyping(role)
        }
    }

    override suspend fun connect(
        contactId: String,
        participantId: String,
        participantToken: String?,
    ) {
        lastEnded?.let { ended ->
            val wait = SETTLE_AFTER_END_MS - (System.nanoTime() - ended) / 1_000_000
            if (wait > 0) delay(wait)
        }
        this.participantId = participantId
        this.participantToken = participantToken
        listener.stop()
        // With a custom chat client there is no token: the SDK passes this one, empty, to the host's
        // createParticipantConnection, which knows the contact from its own session.
        session.connect(ChatDetails(contactId, participantId, participantToken.orEmpty())).orThrow()
    }

    override suspend fun disconnect() {
        listener.stop()
        lastEnded = System.nanoTime()
        // A failed DisconnectParticipant (no network — the usual case at the end of a dead
        // connection) skips the SDK's own socket close, and it would go on reconnecting for a contact
        // the session has ended. reset() stops it either way.
        session.disconnect().onFailure { error ->
            ignoringFailure { session.reset() }
            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
            throw map(error)
        }
    }

    override suspend fun release() {
        listener.stop()
        lastEnded = System.nanoTime()
        session.reset().orThrow()
    }

    override suspend fun sendMessage(
        body: String,
        interactiveResponse: Boolean,
    ): SentMessageAck {
        val type = if (interactiveResponse) ContentType.INTERACTIVE_RESPONSE else ContentType.PLAIN_TEXT
        val result = client.sendMessage(connectionToken(), type, body).orThrow()
        val id = result.id ?: throw TouchpointException.TransportFailure("send accepted without an id")
        return SentMessageAck(id, result.absoluteTime.orEmpty())
    }

    override suspend fun sendReceipt(
        kind: ReceiptKind,
        messageId: String,
    ) {
        val type = if (kind == ReceiptKind.READ) ContentType.MESSAGE_READ else ContentType.MESSAGE_DELIVERED
        client.sendEvent(connectionToken(), type, buildJsonObject { put("messageId", messageId) }.toString()).orThrow()
    }

    /** The SDK's own form for typing: the event type with an empty body. */
    override suspend fun sendTyping() {
        client.sendEvent(connectionToken(), ContentType.TYPING, "").orThrow()
    }

    override suspend fun fetchTranscript(
        nextToken: String?,
        maxResults: Int,
    ): RawTranscriptPage {
        val token = connectionToken()
        val request =
            GetTranscriptRequest {
                connectionToken = token
                scanDirection = ScanDirection.Backward
                sortOrder = SortKey.Ascending
                this.maxResults = maxResults
                if (!nextToken.isNullOrEmpty()) this.nextToken = nextToken
            }
        val result = client.getTranscript(request).orThrow()
        return RawTranscriptPage(result.transcript.orEmpty().mapNotNull(::fromService), result.nextToken)
    }

    /**
     * The participant API's three steps, as the SDK's own `sendAttachment` takes them, but streamed
     * from the host's file — the SDK's copies it, unencrypted, into the cache first — and with
     * the attachment id it does not return.
     */
    override suspend fun uploadAttachment(file: AttachmentUpload): String {
        val token = connectionToken()
        val clientToken = UUID.randomUUID().toString()
        val start =
            client.startAttachmentUpload(
                token,
                StartAttachmentUploadRequest {
                    attachmentName = file.name
                    contentType = file.contentType
                    attachmentSizeInBytes = file.sizeBytes
                    this.clientToken = clientToken
                    connectionToken = token
                },
            ).orThrow()
        val id = start.attachmentId ?: throw TouchpointException.TransportFailure("upload started without an id")
        val target = start.uploadMetadata?.url ?: throw TouchpointException.TransportFailure("upload started without an address")
        AttachmentHttp.put(target, start.uploadMetadata?.headersToInclude.orEmpty(), file)
        client.completeAttachmentUpload(
            token,
            CompleteAttachmentUploadRequest {
                attachmentIds = listOf(id)
                this.clientToken = clientToken
                connectionToken = token
            },
        ).orThrow()
        return id
    }

    override suspend fun attachmentUrl(attachmentId: String): String =
        client.getAttachment(connectionToken(), attachmentId).orThrow().url
            ?: throw TouchpointException.TransportFailure("attachment without an address")

    // Sign-in

    override fun enableAuthenticationEvents() {
        authenticationEnabled = true
    }

    private fun startListening(handlers: ChatDriverHandlers) {
        if (!authenticationEnabled) return
        val provider = customChatClient as? TouchpointSignInSocketProviding
        val token = participantToken
        val websocketUrl: suspend () -> String =
            when {
                // A custom chat client: its backend mints the listen-only socket, so the token stays off the device here too.
                provider != null -> ({ provider.signInEventsWebSocketUrl() })
                token != null -> ({ participant.websocketUrl(token) })
                else -> return // a custom client without the hook: `live` refuses that with sign-in on
            }
        listener.start(
            websocketUrl = websocketUrl,
            onOpen = { catchUpAuthentication(handlers) },
            onEvent = { handlers.onAuthenticationEvent(it.contentType, it.content, null) },
        )
    }

    /**
     * Anything the listener missed while it was down — the app in the background, a reconnect: the
     * newest sign-in item in the transcript, read through the SDK's own client, with its time so
     * Connect's 60 s window is not reset.
     */
    private suspend fun catchUpAuthentication(handlers: ChatDriverHandlers) {
        val token = session.getConnectionDetailsProvider().getConnectionDetails()?.connectionToken ?: return
        val request =
            GetTranscriptRequest {
                connectionToken = token
                scanDirection = ScanDirection.Backward
                sortOrder = SortKey.Descending
                maxResults = CATCH_UP_ITEMS
            }
        val items = client.getTranscript(request).getOrNull()?.transcript.orEmpty()
        val item = items.firstOrNull { SignIn.statusOf(it.contentType.orEmpty()) != null } ?: return
        handlers.onAuthenticationEvent(item.contentType.orEmpty(), item.content, item.absoluteTime)
    }

    override suspend fun authenticationUrl(
        sessionId: String,
        redirectUri: String,
    ): String = participant.authenticationUrl(connectionToken(), sessionId, redirectUri)

    override suspend fun cancelAuthentication(sessionId: String): Unit = participant.cancelAuthentication(connectionToken(), sessionId)

    /** Through the SDK's own client, as web reads it through ChatJS: participant-authorised, no IAM. */
    override suspend fun describeView(viewToken: String): ResolvedView {
        val view = client.describeView(connectionToken = connectionToken(), viewToken = viewToken).orThrow()
        val content = view.content ?: throw TouchpointException.MalformedPayload("view without content")
        return ResolvedView(
            id = view.id.orEmpty(),
            name = view.name,
            version = view.version,
            template = content.template ?: throw TouchpointException.MalformedPayload("view without a template"),
            inputSchema = content.inputSchema ?: "{}",
            actions = content.actions.orEmpty(),
        )
    }

    /**
     * The SDK wraps every call in `runCatching`, which also catches our own cancellation; that one is
     * rethrown as cancellation, anything else mapped.
     */
    private suspend fun <T> Result<T>.orThrow(): T =
        getOrElse { error ->
            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
            throw map(error)
        }

    private fun connectionToken(): String =
        session.getConnectionDetailsProvider().getConnectionDetails()?.connectionToken
            ?: throw TouchpointException.TransportFailure("not connected")

    /** A live websocket item; `ParticipantId` only exists in the raw frame. */
    private fun live(item: TranscriptItem): RawTranscriptItem? {
        val raw = item.serializedContent?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun field(name: String) = raw?.get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val pid = field("ParticipantId")?.takeIf { it.isNotEmpty() }
        val past = raw?.get("isFromPastSession")?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
        return when (item) {
            is Message ->
                RawTranscriptItem(
                    RawTranscriptItem.Kind.MESSAGE, item.id, item.timeStamp, item.contentType, item.participant, pid,
                    item.displayName.orEmpty(), item.text,
                    isOutgoing = item.participant.equals("CUSTOMER", ignoreCase = true) && (pid == null || pid == participantId),
                    isFromPastSession = past,
                    attachmentId = item.attachmentId?.takeIf { it.isNotEmpty() },
                )
            is Event ->
                RawTranscriptItem(
                    RawTranscriptItem.Kind.EVENT, item.id, item.timeStamp, item.contentType, item.participant ?: field("ParticipantRole").orEmpty(),
                    pid, item.displayName.orEmpty(), item.text, isOutgoing = false, isFromPastSession = past,
                )
            else -> null // metadata items are receipt state the SDK tracks for its own UI; we use the callbacks
        }
    }

    /** A row of the service's own transcript, `ParticipantId` included. */
    private fun fromService(item: Item): RawTranscriptItem? {
        val role = item.participantRole?.value.orEmpty()
        if (item.type?.value == "ATTACHMENT") {
            // One file per item, as Connect sends them; its name stands as the text.
            val file = item.attachments.orEmpty().firstOrNull { !it.attachmentId.isNullOrEmpty() } ?: return null
            return RawTranscriptItem(
                RawTranscriptItem.Kind.MESSAGE, item.id.orEmpty(), item.absoluteTime.orEmpty(), file.contentType.orEmpty(), role,
                item.participantId?.takeIf { it.isNotEmpty() }, item.displayName.orEmpty(), file.attachmentName.orEmpty(),
                isOutgoing = item.participantId != null && item.participantId == participantId,
                attachmentId = file.attachmentId,
            )
        }
        val kind =
            when (item.type?.value) {
                "MESSAGE" -> RawTranscriptItem.Kind.MESSAGE
                "EVENT" -> RawTranscriptItem.Kind.EVENT
                else -> return null // metadata: receipts the SDK tracks for its own UI
            }
        return RawTranscriptItem(
            kind, item.id.orEmpty(), item.absoluteTime.orEmpty(), item.contentType.orEmpty(), role, item.participantId?.takeIf { it.isNotEmpty() },
            item.displayName.orEmpty(), item.content,
            isOutgoing = kind == RawTranscriptItem.Kind.MESSAGE && item.participantId != null && item.participantId == participantId,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val SETTLE_AFTER_END_MS = 750L
        private const val CATCH_UP_ITEMS = 15
        // One word between the geography and the number: the isolated partitions' names (us-iso-east-1,
        // eu-isoe-west-1) have two, so they never match; China's (cn-north-1) match and are refused by name.
        private val REGION_NAME = Regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]{1,2}$")

        /** Whether [name] is a region whose participant service is at `participant.connect.<name>.amazonaws.com`. */
        internal fun isParticipantRegion(name: String): Boolean = REGION_NAME.matches(name) && !name.startsWith("cn-")

        private inline fun ignoringFailure(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
            }
        }

        fun create(
            context: Context,
            region: String,
            customChatClient: AWSClient? = null,
        ): ConnectChatSessionDriver {
            // The chat SDK 3 takes any string as a region, and the participant token goes to the host it
            // names: only a well-formed region name in a partition Connect's participant service is
            // reached at on amazonaws.com (no isolated or China partitions).
            if (!isParticipantRegion(region)) throw TouchpointException.InvalidConfiguration("unknown AWS region $region")
            val regions = Region.fromName(region)
            val session =
                try {
                    ChatSessionProvider.getChatSession(context)
                } catch (e: Exception) {
                    throw TouchpointException.TransportFailure("chat SDK initialisation failed: ${e.javaClass.simpleName}")
                }
            return ConnectChatSessionDriver(session, customChatClient ?: AWSClient.shared, regions, customChatClient = customChatClient)
        }

        /**
         * The SDK reports failures as untyped exceptions. A service error keeps its error code and
         * status; anything else only its type — a message can carry a URL with a token in it.
         */
        fun map(error: Throwable): TouchpointException =
            when (error) {
                is TouchpointException -> error
                is ServiceException -> {
                    val code = error.sdkErrorMetadata.errorCode ?: error.javaClass.simpleName
                    val status = (error.sdkErrorMetadata.protocolResponse as? HttpResponse)?.status?.value
                    TouchpointException.TransportFailure(if (status != null) "$code($status)" else code)
                }
                else -> TouchpointException.TransportFailure(error.javaClass.simpleName.ifEmpty { error.javaClass.name })
            }
    }
}
