package com.amazon.connect.touchpoint.livesync

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * An action the assistant may invoke on the app: a pure declaration — a name, a description and a
 * JSON Schema for its parameters — uploaded by [LiveSyncClient.sendContext]. What the app *does* when
 * the action arrives is decided in one place, [LiveSyncClient.onAction].
 *
 * ```kotlin
 * LiveSyncAction(
 *     "set_contact_info",
 *     description = "Set the customer's name and email",
 *     schema = buildJsonObject {
 *         put("type", "object")
 *         putJsonObject("properties") {
 *             putJsonObject("fullName") { put("type", "string") }
 *             putJsonObject("email") { put("type", "string") }
 *         }
 *     },
 * )
 * ```
 *
 * Schemas and payloads are kotlinx.serialization [JsonElement]s.
 *
 * @property name Wire name, unique within a context; what [LiveSyncActionInvocation.action] carries.
 * @property schema JSON Schema for the payload; `null` for no parameters. The Live Sync service does
 *   not enforce it: the client checks each payload against it on the device before any handler runs,
 *   and refuses one that does not match (a subset of JSON Schema: see `LiveSyncClient.onAction`).
 * @property input Extra data for the model, e.g. the current value of a field.
 */
public data class LiveSyncAction(
    val name: String,
    val description: String,
    val schema: JsonElement? = null,
    val input: JsonElement? = null,
) {
    /** The JSON the conversation engine is told about. */
    internal fun advertisedDefinition(): JsonObject =
        buildJsonObject {
            put("action", name)
            put("description", description)
            schema?.let { put("schema", it) }
            input?.let { put("input", it) }
        }
}

/**
 * The app state advertised to the conversation engine. A complete snapshot, not a patch: each
 * [LiveSyncClient.sendContext] replaces what was advertised before. Scopes travel with the actions
 * they describe; an empty [actions] list advertises nothing.
 */
public data class LiveSyncContext(
    val actions: List<LiveSyncAction> = emptyList(),
    val scopes: List<String>? = null,
) {
    /** The `nlx:vpContext` object as sent to the context API. */
    internal fun voicePlusContext(): JsonObject =
        buildJsonObject {
            put("actions", kotlinx.serialization.json.JsonArray(actions.map { it.advertisedDefinition() }))
            scopes?.let { put("scopes", kotlinx.serialization.json.JsonArray(it.map(::JsonPrimitive))) }
        }
}

/**
 * An action the conversation engine invoked, as pushed over the action socket. Handed to
 * [LiveSyncClient.onAction] and published on [LiveSyncClient.invocations].
 *
 * @property payload Arguments the engine produced, normally an object, checked on the device against
 *   the action's declared schema before it reached a handler. An action declared without a schema
 *   arrives with an empty object, and with nothing else.
 */
public data class LiveSyncActionInvocation(
    val action: String,
    val payload: JsonElement = JsonObject(emptyMap()),
) {
    /** Decodes [payload] into a model. Throwing out of [LiveSyncClient.onAction] is safe: it is reported. */
    public fun <T> decode(deserializer: DeserializationStrategy<T>, json: Json = PayloadJson): T = json.decodeFromJsonElement(deserializer, payload)

    /** Decodes [payload] into a `@Serializable` model. */
    public inline fun <reified T> decode(json: Json = PayloadJson): T = json.decodeFromJsonElement(payload)

    // A payload is the customer's words, structured: never in a log line by accident.
    override fun toString(): String =
        "LiveSyncActionInvocation(action=$action, payload=${(payload as? JsonObject)?.let { "keys ${it.keys.sorted()}" } ?: "<${payload::class.simpleName}>"})"

    public companion object {
        /** Lenient on fields the model does not declare, so an engine that adds one does not break a host. */
        public val PayloadJson: Json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a socket frame. `null` for frames that do not carry an action invocation, so frame
         * types this SDK does not model are ignored rather than treated as errors. The envelope:
         * `{"conversationId":…,"classification":"custom","action":…,"payload":{…}}`.
         */
        internal fun decode(frame: String): LiveSyncActionInvocation? = (LiveSyncInboundFrame.decode(frame) as? LiveSyncInboundFrame.Custom)?.invocation

        /**
         * The custom-classification reading of a frame, already parsed by [LiveSyncInboundFrame.decode]:
         * an invocation, or `null` for a note or another kind.
         */
        internal fun decodeCustom(envelope: JsonObject): LiveSyncActionInvocation? {
            fun string(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            // The server may wrap the invocation one level down, `{"payload": {"classification": …,
            // "action": …, "payload": {…}}}` — NLX's own Android client unwraps it so — and the voice
            // path does. Read whichever level carries the action; never deeper than one.
            val inner = envelope["payload"] as? JsonObject
            val level =
                when {
                    string(envelope, "classification") == "custom" && !string(envelope, "action").isNullOrEmpty() -> envelope
                    inner != null && string(inner, "classification") == "custom" && !string(inner, "action").isNullOrEmpty() -> inner
                    inner != null && string(envelope, "classification") == "custom" && !string(inner, "action").isNullOrEmpty() -> inner
                    else -> null
                }
            val action = level?.let { string(it, "action") }
            if (level == null || action == null) {
                if (string(envelope, "classification") != "custom" && (inner == null || string(inner, "classification") != "custom")) return null
                // The assistant's own speech, interleaved with invocations under the same classification —
                // `{"classification":"custom","note":"One moment.","metadata":{"interim":true}}` — is not an
                // invocation, and not a malformed one: reporting it made an error of every sentence it
                // said.
                if (envelope.containsKey("note") || inner?.containsKey("note") == true) return null
                // Key names only, never values: enough to see a shape this client does not know.
                throw LiveSyncException.MalformedInvocation("an action invocation arrived without an `action` name (keys ${envelope.keys.sorted()}${inner?.let { ", payload keys ${it.keys.sorted()}" } ?: ""})")
            }
            // A JSON null payload is no payload: the empty object the type promises.
            return LiveSyncActionInvocation(action, level["payload"]?.takeUnless { it is kotlinx.serialization.json.JsonNull } ?: JsonObject(emptyMap()))
        }
    }
}

/**
 * A navigation instruction from the conversation engine (classification `navigation`), as the backend sends
 * it. It arrives whether or not the app advertised destinations — the backend guards only
 * `page_custom` — so the SDK surfaces it rather than dropping it; what the app does is the app's.
 */
public class LiveSyncNavigation(
    public val kind: Kind,
    /** For [Kind.PageCustom], the destination's name. */
    public val destination: String?,
) {
    public sealed interface Kind {
        public data object PageNext : Kind

        public data object PagePrevious : Kind

        /** A named destination: [destination] carries the name. */
        public data object PageCustom : Kind

        /** The engine could not map the request to a page. */
        public data object PageUnknown : Kind

        /** A value this SDK does not model, kept verbatim. */
        public data class Other(val wire: String) : Kind

        public companion object {
            internal fun of(wire: String): Kind =
                when (wire) {
                    "page_next" -> PageNext
                    "page_previous" -> PagePrevious
                    "page_custom" -> PageCustom
                    "page_unknown" -> PageUnknown
                    else -> Other(wire)
                }
        }
    }

    override fun equals(other: Any?): Boolean = other is LiveSyncNavigation && other.kind == kind && other.destination == destination

    override fun hashCode(): Int = 31 * kind.hashCode() + destination.hashCode()

    override fun toString(): String = "LiveSyncNavigation(kind=$kind, destination=<redacted>)"
}

/**
 * Everything the socket delivers, from both envelope shapes the backend produces — interactive mode
 * puts the fields at the top level, agentic mode nests them under `payload`.
 */
internal sealed interface LiveSyncInboundFrame {
    data class Custom(val invocation: LiveSyncActionInvocation) : LiveSyncInboundFrame

    data class Navigation(val navigation: LiveSyncNavigation) : LiveSyncInboundFrame

    /** `input` frames cannot arrive while nothing advertises fields: counted, not modelled. */
    data object Input : LiveSyncInboundFrame

    /** A line the assistant spoke, on the same socket and classification as an action. Counted. */
    data object Note : LiveSyncInboundFrame

    /** A classification this SDK does not know. Ignored, never an error. */
    data object Unknown : LiveSyncInboundFrame

    companion object {
        fun decode(frame: String): LiveSyncInboundFrame {
            val envelope =
                try {
                    kotlinx.serialization.json.Json.parseToJsonElement(frame) as? JsonObject ?: throw LiveSyncException.MalformedInvocation("a frame was not a JSON object")
                } catch (e: LiveSyncException) {
                    throw e
                } catch (e: Exception) {
                    throw LiveSyncException.MalformedInvocation(LiveSyncException.describe(e))
                }
            fun string(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val inner = envelope["payload"] as? JsonObject
            val classification = string(envelope, "classification") ?: string(inner, "classification")
            return when (classification) {
                "custom" -> {
                    val hasAction = !string(envelope, "action").isNullOrEmpty() || !string(inner, "action").isNullOrEmpty()
                    if (!hasAction && (envelope.containsKey("note") || inner?.containsKey("note") == true)) Note else LiveSyncActionInvocation.decodeCustom(envelope)?.let(::Custom) ?: Unknown
                }
                "navigation" -> {
                    // Agentic mode nests `action` and `destination` under `payload`; interactive puts them at the top.
                    val action = string(inner, "action") ?: string(envelope, "action")
                    if (action.isNullOrEmpty()) throw LiveSyncException.MalformedInvocation("a navigation frame arrived without an `action`")
                    Navigation(LiveSyncNavigation(LiveSyncNavigation.Kind.of(action), string(inner, "destination") ?: string(envelope, "destination")))
                }
                "input" -> Input
                else -> Unknown
            }
        }
    }
}

