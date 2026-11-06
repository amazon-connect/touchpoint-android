package com.amazon.connect.touchpoint.chat

import com.amazon.connect.touchpoint.core.InteractiveContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The customer's answer to a choice, as Touchpoint web 1.0.4 sends it (`sendModalityRequest`): an
 * interactive response in web's `TouchpointModality` template, whose `action` and `data.content.title`
 * carry the choice's words — what Connect requires, and what the agent's workspace shows — and whose
 * `modalityPayload` carries the choice itself, with web's empty context.
 */
internal object InteractiveResponse {
    /** Connect's limit on an interactive response addressed to a conversational AI bot. */
    private const val MAX_BYTES = 16_384

    /** Web's envelope for an answer, or `null` where Connect would refuse it: then it goes as text. */
    fun envelope(
        text: String,
        choiceId: String = text,
    ): String? {
        // Normalised once already, by the session; again here changes nothing.
        val title = InteractiveContent.choiceText(text)
        val json =
            buildJsonObject {
                put("templateType", InteractiveContent.CHOICE_TEMPLATE)
                put("version", "1.0")
                put("action", title)
                putJsonObject("data") {
                    // A sibling of modalityPayload, never inside it: the flow rejects any key there
                    // but `request` and `context`.
                    putJsonObject("content") { put("title", title) }
                    putJsonObject("modalityPayload") {
                        putJsonObject("request") { putJsonObject("structured") { put("choiceId", choiceId) } }
                        putJsonObject("context") {}
                    }
                }
            }.toString()
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    /**
     * Web's slot response (`sendSlots`): a modality's pick as `structured.slots`, with an `action` that
     * says what was picked ("Charge: harbour"), capped as an answer's words are.
     */
    /**
     * Web's `submitView`: a Connect View's [action] and what the customer entered ([data], JSON), with
     * the view's name, as a `ViewResource` interactive response — what moves the flow past its
     * `ShowView`. [meta] is the stored `{"action", "viewName"}`.
     */
    fun viewEnvelope(
        meta: String,
        data: String,
    ): String? {
        val m = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(meta) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        val action = (m["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val name = (m["viewName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val input = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val json =
            buildJsonObject {
                put("templateType", "ViewResource")
                put("version", "1.0")
                name?.let { put("viewName", it) }
                put("action", action)
                put("data", input)
            }.toString()
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    fun slotEnvelope(
        slotId: String,
        value: String,
    ): String? {
        val action = InteractiveContent.choiceText("$slotId: $value")
        val json =
            buildJsonObject {
                put("templateType", InteractiveContent.CHOICE_TEMPLATE)
                put("version", "1.0")
                put("action", action)
                putJsonObject("data") {
                    putJsonObject("content") { put("title", action) }
                    putJsonObject("modalityPayload") {
                        putJsonObject("request") {
                            putJsonObject("structured") {
                                put("slots", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("slotId", slotId); put("value", value) })))
                            }
                        }
                    }
                }
            }.toString()
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }
}
