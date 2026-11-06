package com.amazon.connect.touchpoint.livesync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android uses kotlinx.serialization's JsonElement. These are a JSON value type's
 * guarantees, held for JsonElement on the wire.
 */
class JsonInteropTest {
    @Serializable
    private data class Filter(val amount: Double, val merchant: String? = null)

    @Test
    fun `the builder produces the expected tree`() {
        val schema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("amount") { put("type", "number") } }
                putJsonArray("required") { add(JsonPrimitive("amount")) }
            }
        assertEquals("""{"type":"object","properties":{"amount":{"type":"number"}},"required":["amount"]}""", schema.toString())
    }

    @Test
    fun `integers survive a round trip without becoming floats`() {
        val parsed = Json.parseToJsonElement("""{"n":240}""").jsonObject
        assertEquals("240", parsed["n"]!!.jsonPrimitive.content)
        assertEquals(240, parsed["n"]!!.jsonPrimitive.int)
        assertEquals("""{"n":240}""", parsed.toString())
    }

    @Test
    fun `booleans decode as booleans, not as numbers`() {
        val parsed = Json.parseToJsonElement("""{"b":true,"n":1}""").jsonObject
        assertEquals(true, parsed["b"]!!.jsonPrimitive.booleanOrNull)
        assertNull(parsed["n"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `a payload decodes into a model`() {
        val invocation = LiveSyncActionInvocation("filter_transactions", Json.parseToJsonElement("""{"amount":240,"merchant":"Riverside","unknown":1}"""))
        assertEquals(Filter(240.0, "Riverside"), invocation.decode<Filter>())
    }

    @Test
    fun `decoding into JsonElement is an identity`() {
        val payload = Json.parseToJsonElement("""{"a":[1,"x",null,{"b":false}]}""")
        assertEquals(payload, LiveSyncActionInvocation("x", payload).decode<JsonElement>())
    }

    @Test
    fun `a serializable model encodes as a schema input`() {
        val input = Json.encodeToJsonElement(Filter(12.5))
        val advertised = LiveSyncAction("filter_transactions", "Filter", input = input).advertisedDefinition()
        // Defaults are not encoded: an absent field, not a null one, reaches the model.
        assertEquals(JsonObject(mapOf("amount" to JsonPrimitive(12.5))), advertised["input"])
        assertTrue("schema" !in advertised)
    }
}
