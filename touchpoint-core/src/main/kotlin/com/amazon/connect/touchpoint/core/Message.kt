package com.amazon.connect.touchpoint.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * The public message type. Its **shape is locked in v1**, while the rendering of the
 * richer fields lands in increment 2: adding a field later would be a breaking change; adding a
 * renderer for a field that already exists is additive.
 *
 * A value **projected from the store**, never the stored entity, which is what makes resume and
 * reconciliation observable rather than magical.
 *
 * Caveats recorded while they are cheap:
 * - **Choice identifiers are display titles, not stable opaque ids.** Nothing may assume choice ids
 *   are stable across renders or safe to persist as keys.
 * - **Markdown support is an inclusion list**, decided by the UI layer.
 *
 * [toString] omits the body and the sender's participant id.
 */
@ConsistentCopyVisibility
public data class Message private constructor(
    /** Our idempotency key, stable for the row's whole life — a list keyed on it does not re-insert the bubble on acknowledgement. */
    val clientMessageId: UUID,
    /** The server's id once assigned; `null` for a local echo the server has not acknowledged. */
    val serverMessageId: MessageId?,
    val direction: Direction,
    val participantRole: ParticipantRole,
    /** Connect `ParticipantId` of the sender, when the wire carried one. */
    val participantId: String?,
    /** Raw text as received. Markdown is rendered by the UI layer, never pre-processed here. */
    val body: String,
    /**
     * Server ordering authority: the server's `AbsoluteTime` in milliseconds since 1970 — device
     * clocks are never trusted for ordering. `null` until reconciled; local echoes pin to the tail.
     */
    val sequence: Long?,
    /** Display formatting only, never ordering. */
    val createdAt: Instant,
    val deliveryState: DeliveryState,
    // Locked in v1, rendered in increment 2.
    /** Choices, cards and other interactive content. */
    val interactive: InteractiveContent?,
    /** Knowledge-base sources and citations, consolidated. */
    val citations: List<Citation>,
    /** A streamed bot turn: true until the final chunk arrives. */
    val isStreaming: Boolean,
    /** The file this message is, sent or received; `null` for text. Its name is also the [body]. */
    val attachment: Attachment?,
) {
    /** The list key: [clientMessageId]. */
    val id: UUID get() = clientMessageId

    /**
     * Projection from the stored row. Increment-2 blobs that fail to decode project as empty rather
     * than failing the message: the text is what the user needs, and the server copy is authoritative.
     */
    public constructor(stored: StoredMessage) : this(
        clientMessageId = stored.clientMessageId,
        serverMessageId = stored.serverMessageId?.let(::MessageId),
        direction = stored.direction,
        participantRole = stored.participantRole,
        participantId = stored.participantId,
        body = stored.body,
        sequence = stored.sequence,
        createdAt = stored.createdAt,
        deliveryState = stored.deliveryState,
        interactive = stored.interactiveJson?.let(InteractiveContent::decodeOrNull),
        citations = stored.citationsJson?.let(Citation::decodeListOrEmpty).orEmpty(),
        isStreaming = stored.isStreaming,
        attachment = stored.attachmentId?.let { Attachment(it, stored.body, stored.attachmentContentType?.takeIf(String::isNotBlank) ?: Attachment.UNKNOWN_TYPE, stored.attachmentSize) },
    )

    override fun toString(): String =
        "Message(clientMessageId=$clientMessageId, serverMessageId=${serverMessageId?.rawValue}, direction=$direction, " +
            "participantRole=$participantRole, sequence=$sequence, deliveryState=$deliveryState, body=<redacted>)"
}

/**
 * Interactive message content: choice sets, list and time pickers. v1 carries the template
 * type and the raw Connect payload; increment 2 adds typed decoding on top without changing this shape.
 *
 * @property templateType Connect template type, e.g. `ListPicker`, `QuickReply`, `TimePicker`; or
 *   [CHOICE_TEMPLATE] for the customer's own answer to one.
 * @property rawJson An inbound `application/vnd.amazonaws.connect.message.interactive` payload,
 *   verbatim. For the customer's answer, empty — the answer's words are the message's encrypted body,
 *   and repeating them here would store them in the clear — or, where the choice has an opaque
 *   id of its own, `{"choiceId": …}`.
 */
@Serializable
public data class InteractiveContent(val templateType: String, val rawJson: String) {
    override fun toString(): String = "InteractiveContent(templateType=$templateType, rawJson=<${rawJson.length} chars>)"

    public companion object {
        /** The template of the customer's answer to a choice, as Touchpoint web sends it. */
        public const val CHOICE_TEMPLATE: String = "TouchpointModality"

        /**
         * The stored mark of a slot the customer filled from a modality — a card picked, a date chosen —
         * as web's `sendSlots`. Never a Connect template: the transport sends web's slot response for
         * it. Its raw JSON is `{"slotId": …}`; the value is the message's encrypted body.
         */
        public const val SLOT_TEMPLATE: String = "TouchpointSlot"

        /**
         * The stored mark of a Connect View the customer answered, as web's `submitView`: the
         * transport sends web's `ViewResource` response for it. Its raw JSON is `{"action": …,
         * "viewName": …}`; what the customer entered is the message's encrypted body.
         */
        public const val VIEW_RESPONSE_TEMPLATE: String = "TouchpointViewResponse"

        /** Connect's limit on an interactive response's `action`: an answer's words are capped to it. */
        public const val CHOICE_MAX_LENGTH: Int = 200

        /**
         * The words an answer is shown and sent as — trimmed and capped, as web sends them, and again
         * trimmed after the cap, so normalising twice changes nothing: the server's echo of an answer
         * must match the stored row exactly to be adopted rather than shown twice.
         */
        public fun choiceText(text: String): String = text.trim().take(CHOICE_MAX_LENGTH).trimEnd()

        /** The stored form, as the transport writes it into `StoredMessage.interactiveJson`. */
        public fun encode(content: InteractiveContent): String = blobJson.encodeToString(serializer(), content)

        internal fun decodeOrNull(text: String): InteractiveContent? = ignoringFailure { blobJson.decodeFromString(serializer(), text) }
    }
}

/**
 * The modalities this message carries, in the order the flow wrote them, as web draws each
 * `[name, data]` of `modalityPayload.modalities` in turn: a `TouchpointModality` message's, or an
 * `application/json` message's top-level `modalities`. Empty for anything else, and for a payload
 * that cannot be read.
 */
public val InteractiveContent.modalities: List<TouchpointModality>
    get() {
        if (templateType != InteractiveContent.CHOICE_TEMPLATE && templateType != JSON_TEMPLATE) return emptyList()
        val root = ignoringFailure { blobJson.parseToJsonElement(rawJson) as? kotlinx.serialization.json.JsonObject } ?: return emptyList()
        val raw =
            if (templateType == JSON_TEMPLATE) {
                root["modalities"]
            } else {
                ((root["data"] as? kotlinx.serialization.json.JsonObject)?.get("content") as? kotlinx.serialization.json.JsonObject)
                    ?.get("modalityPayload")?.let { it as? kotlinx.serialization.json.JsonObject }?.get("modalities")
            }
        // A JsonObject keeps the order it was written in: web's order.
        return (raw as? kotlinx.serialization.json.JsonObject)?.map { (name, value) -> TouchpointModality(name, value) }.orEmpty()
    }

/** A Connect View (`ShowView`, `ViewResource`): what web's guide card shows before it is opened — the view data's heading and subheading. `null` for any other template. */
public val InteractiveContent.viewSummary: ViewSummary?
    get() {
        if (templateType != "ViewResource") return null
        val root = ignoringFailure { blobJson.parseToJsonElement(rawJson) as? kotlinx.serialization.json.JsonObject } ?: return ViewSummary(null, null)
        val content = (root["data"] as? kotlinx.serialization.json.JsonObject)?.get("content") as? kotlinx.serialization.json.JsonObject
        val data =
            when (val v = content?.get("viewData")) {
                is kotlinx.serialization.json.JsonObject -> v
                is kotlinx.serialization.json.JsonPrimitive -> ignoringFailure { blobJson.parseToJsonElement(v.content) as? kotlinx.serialization.json.JsonObject }
                else -> null
            }
        fun text(key: String) = (data?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return ViewSummary(text("Heading"), text("SubHeading"))
    }

/** A Connect View's heading and subheading, as the flow wrote them. */
public data class ViewSummary(val heading: String?, val subheading: String?) {
    /** The heading and subheading are the conversation's words: never in a log. */
    override fun toString(): String = "ViewSummary(heading=${heading?.let { "<redacted>" }}, subheading=${subheading?.let { "<redacted>" }})"
}

/**
 * What opening a Connect View needs, as web's guide reference: its token, which `DescribeView`
 * resolves, and the flow's input data for it, as JSON. `null` for any other template, and for a view
 * without a token. [toString] omits both.
 */
public val InteractiveContent.viewReference: ViewReference?
    get() {
        if (templateType != "ViewResource") return null
        val root = ignoringFailure { blobJson.parseToJsonElement(rawJson) as? kotlinx.serialization.json.JsonObject } ?: return null
        val content = (root["data"] as? kotlinx.serialization.json.JsonObject)?.get("content") as? kotlinx.serialization.json.JsonObject ?: return null
        val token = (content["viewToken"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val data =
            when (val v = content["viewData"]) {
                is kotlinx.serialization.json.JsonObject -> v.toString()
                is kotlinx.serialization.json.JsonPrimitive -> v.content.takeIf { ignoringFailure { blobJson.parseToJsonElement(it) is kotlinx.serialization.json.JsonObject } == true }
                else -> null
            } ?: "{}"
        return ViewReference(token, data)
    }

/** See [viewReference]. */
public data class ViewReference(val viewToken: String, val inputData: String) {
    override fun toString(): String = "ViewReference(viewToken=<redacted>, inputData=<redacted>)"
}

/**
 * A Connect View as `DescribeView` returns it, for Connect's renderer: its template, input schema and
 * actions, each as Connect wrote them (the first two JSON). [toString] keeps only the name.
 */
public data class ResolvedView(
    val id: String,
    val name: String?,
    val version: Int?,
    val template: String,
    val inputSchema: String,
    val actions: List<String>,
) {
    override fun toString(): String = "ResolvedView(name=$name, version=$version)"
}

/**
 * One modality a message carries: its name — `DefaultCard`, `DefaultCarousel`, `DefaultDateInput`,
 * `DefaultVideo`, or a host's own — and its data as the flow resolved it. The SDK draws the four
 * defaults; a host draws its own, by name. [toString] omits the data: it is the conversation's.
 */
public data class TouchpointModality(val name: String, val data: kotlinx.serialization.json.JsonElement) {
    override fun toString(): String = "TouchpointModality(name=$name)"
}

/** The template of an `application/json` message, as the chat transport stores it. */
private const val JSON_TEMPLATE = "JsonMessage"

/** A knowledge-base source attached to an assistant message. */
@Serializable
public data class Citation(val title: String, val url: String? = null, val snippet: String? = null) {
    /** A source's title, link and excerpt come from the conversation: never in a log. */
    override fun toString(): String = "Citation(title=<redacted>, url=${url?.let { "<redacted>" }}, snippet=${snippet?.let { "<redacted>" }})"

    public companion object {
        /** The stored form, as the transport writes it into `StoredMessage.citationsJson`. */
        public fun encode(citations: List<Citation>): String = blobJson.encodeToString(ListSerializer(serializer()), citations)

        internal fun decodeListOrEmpty(text: String): List<Citation> =
            ignoringFailure { blobJson.decodeFromString(ListSerializer(serializer()), text) }.orEmpty()
    }
}

private val blobJson = Json { ignoreUnknownKeys = true }

/**
 * A file in the conversation: one the customer sent, or one an agent did. Fetch it with
 * `TouchpointSession.attachmentUrl`, as Touchpoint web does — afresh each time, so an address never
 * goes stale. [toString] omits the name: a file's name is content.
 */
public class Attachment internal constructor(
    /** Connect's attachment id. */
    public val id: String,
    public val name: String,
    /** Its MIME type, as Connect has it. */
    public val contentType: String,
    /** In bytes, where known. */
    public val sizeBytes: Long?,
) {
    override fun equals(other: Any?): Boolean =
        other is Attachment && other.id == id && other.name == name && other.contentType == contentType && other.sizeBytes == sizeBytes

    override fun hashCode(): Int = listOf(id, name, contentType, sizeBytes).hashCode()

    override fun toString(): String = "Attachment(id=$id, name=<redacted>, contentType=$contentType, sizeBytes=$sizeBytes)"

    public companion object {
        /** The type of a file whose provider will not say. */
        public const val UNKNOWN_TYPE_NAME: String = "application/octet-stream"

        internal const val UNKNOWN_TYPE: String = UNKNOWN_TYPE_NAME
    }
}

/**
 * A file for the customer to send ([TouchpointSession.sendAttachment]): its name, MIME type and size,
 * and a way to read it — opened once, for the upload, and closed after. The SDK keeps no copy.
 * [toString] omits the name.
 */
public class AttachmentUpload(
    public val name: String,
    public val contentType: String,
    public val sizeBytes: Long,
    public val open: () -> java.io.InputStream,
) {
    override fun toString(): String = "AttachmentUpload(name=<redacted>, contentType=$contentType, sizeBytes=$sizeBytes)"

    public companion object {
        /** Touchpoint web's limit: 8 MB. */
        public const val MAX_BYTES: Long = 8L * 1024 * 1024
    }
}

