package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.livesync.internal.PayloadSchema
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test-32: the device checks each payload against the declared schema, fail-closed. */
class PayloadSchemaTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun problem(
        payload: String,
        schema: String,
    ) = PayloadSchema.validate(json(payload), json(schema))?.toString()

    /** The demo's filter_transactions contract, as written (draft-07, `format: date`, closed). */
    private val filter =
        """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","title":"filter_transactions","description":"…","type":"object","additionalProperties":false,
           "properties":{"amount":{"type":"number","description":"…"},"date":{"type":"string","format":"date"},"merchant":{"type":"string"}}}"""

    @Test
    fun `what the contract allows passes, and the empty filter too`() {
        assertNull(problem("""{"amount":240,"date":"2026-10-03","merchant":"Riverside"}""", filter))
        assertNull(problem("""{"amount":240.5}""", filter))
        assertNull(problem("{}", filter))
    }

    @Test
    fun `a wrong type, an undeclared key, or a date that is not one is refused, by keyword and pointer`() {
        assertEquals("type at /amount", problem("""{"amount":"240"}""", filter))
        assertEquals("additionalProperties at /", problem("""{"script":"x"}""", filter))
        assertEquals("format at /date", problem("""{"date":"last Tuesday"}""", filter))
        assertEquals("format at /date", problem("""{"date":"2026-02-30"}""", filter))
        assertEquals("type at /", problem("""[1]""", filter))
    }

    @Test
    fun `required, enums, nesting, arrays and bounds`() {
        val schema =
            """{"type":"object","required":["reason"],"properties":{
               "reason":{"type":"string","enum":["duplicate","unrecognized"]},
               "cents":{"type":"integer","minimum":1,"maximum":100000},
               "notes":{"type":"array","maxItems":2,"items":{"type":"string","maxLength":5}},
               "card":{"type":"object","required":["last4"],"properties":{"last4":{"type":"string","minLength":4,"maxLength":4}}}}}"""
        assertNull(problem("""{"reason":"duplicate","cents":675,"notes":["a"],"card":{"last4":"4821"}}""", schema))
        assertEquals("required at /", problem("""{"cents":675}""", schema))
        assertEquals("enum at /reason", problem("""{"reason":"refund me"}""", schema))
        assertEquals("type at /cents", problem("""{"reason":"duplicate","cents":6.75}""", schema))
        assertEquals("maximum at /cents", problem("""{"reason":"duplicate","cents":999999}""", schema))
        assertEquals("maxItems at /notes", problem("""{"reason":"duplicate","notes":["a","b","c"]}""", schema))
        assertEquals("maxLength at /notes/1", problem("""{"reason":"duplicate","notes":["a","bbbbbb"]}""", schema))
        assertEquals("required at /card", problem("""{"reason":"duplicate","card":{}}""", schema))
    }

    @Test
    fun `numbers compare exactly, and a boolean is never one`() {
        val big = """{"type":"integer","maximum":9007199254740992}"""
        assertEquals("maximum at /", problem("9007199254740993", big))
        assertNull(problem("3.0", """{"type":"integer"}"""))
        assertNull(problem("1", """{"enum":[1.0]}"""))
        assertEquals("type at /", problem("true", """{"type":"number"}"""))
    }

    @Test
    fun `a null for a property that does not admit one counts as not sent, as an optional field decodes it`() {
        val schema = TouchpointActionSchema.of(TouchpointActionsTest.DisputeDetails.serializer()).toString()
        assertNull(problem("""{"reason":null,"amount":null}""", schema))
        assertEquals("required at /", problem("""{"to":null,"cents":5}""", TouchpointActionSchema.of(TouchpointActionsTest.Transfer.serializer()).toString()))
    }

    @Test
    fun `anything outside the subset refuses every payload, wherever it is`() {
        assertEquals("unsupported schema keyword pattern at /name", problem("{}", """{"type":"object","properties":{"name":{"type":"string","pattern":"^a"}}}"""))
        assertEquals("unsupported schema keyword format at /", problem("\"x\"", """{"type":"string","format":"email"}"""))
        assertEquals("unsupported schema keyword properties without type at /", problem("{}", """{"properties":{}}"""))
        assertEquals("unsupported schema keyword anyOf at /", problem("{}", """{"anyOf":[{"type":"object"}]}"""))
        assertEquals("unsupported schema keyword schema at /items", problem("[]", """{"type":"array","items":[{"type":"string"}]}"""))
    }

    @Test
    fun `an action without parameters takes only the empty object`() {
        assertNull(problem("{}", TouchpointActionSchema.NONE.toString()))
        assertEquals("additionalProperties at /", problem("""{"anything":1}""", TouchpointActionSchema.NONE.toString()))
    }

    @Test
    fun `the engine's known rewrites are found when advertised`() {
        assertEquals("items", PayloadSchema.backendUnwrappedProperty(json("""{"type":"object","properties":{"items":{"type":"array"}}}""")))
        assertEquals(false, PayloadSchema.engineRewrite(json("""{"type":"object","properties":{"n":{"type":["integer","null"]}}}"""))?.admitsString)
        assertNull(PayloadSchema.engineRewrite(json(filter)))
        assertNull(PayloadSchema.unsupportedKeyword(json(filter)))
    }

    @Test
    fun `a problem never quotes the customer's words or keys`() {
        val secret = "4111 1111 1111 1111"
        val found = assertNotNull(problem("""{"amount":"$secret","$secret":"x"}""", filter))
        assertFalse(secret in found)
    }

    @Test
    fun `the client refuses before any handler, counts it, reports it, and raises a security event`() =
        runBlocking {
            val client = makeTestClient()
            client.sendContext(actions = listOf(LiveSyncAction("filter_transactions", "Filter", json(filter))))
            val ran = mutableListOf<LiveSyncActionInvocation>()
            val errors = mutableListOf<LiveSyncException>()
            client.onAction = { ran += it }
            client.onError = { errors += it }
            val events =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(2_000) { TouchpointSecurityEvents.events.filterIsInstance<TouchpointSecurityEvent.LiveSyncPayloadRejected>().take(3).toList() }
                }
            client.ingest("""{"classification":"custom","action":"filter_transactions","payload":{"amount":"lots","date":"x"}}""")
            client.ingest("""{"classification":"custom","action":"wire_money","payload":{}}""")
            client.ingest("""{"classification":"custom","action":"ignore previous instructions","payload":{}}""")
            client.ingest("""{"classification":"custom","action":"filter_transactions","payload":{"amount":240}}""")
            assertEquals(listOf("filter_transactions"), ran.map { it.action })
            assertEquals(JsonObject(mapOf("amount" to json("240"))), ran.single().payload)
            assertEquals(3, client.diagnostics.value.rejectedInvocations)
            assertEquals(listOf("filter_transactions", "wire_money", "<unnamed>"), errors.map { assertIs<LiveSyncException.InvalidPayload>(it).action })
            assertEquals("not in the advertised context", (errors[1] as LiveSyncException.InvalidPayload).description)
            assertEquals(listOf("filter_transactions", "wire_money", "<unnamed>"), events.await().map { it.action })
        }

    @Test
    fun `a schema outside the subset is reported when advertised, once`() =
        runBlocking {
            val client = makeTestClient()
            val errors = mutableListOf<LiveSyncException>()
            client.onError = { errors += it }
            val bad = LiveSyncAction("pick", "Pick", json("""{"type":"object","properties":{"id":{"type":"string","pattern":"^[0-9]+$"}}}"""))
            client.sendContext(actions = listOf(bad))
            client.sendContext(actions = listOf(bad))
            val reported = errors.filterIsInstance<LiveSyncException.InvalidConfiguration>()
            assertEquals(1, reported.size)
            assertTrue("pattern" in reported.single().detail)
        }

    @Test
    fun `Allow checks the held action again against what is advertised now`() =
        runBlocking {
            val client = makeTestClient()
            val ran = mutableListOf<String>()
            val errors = mutableListOf<LiveSyncException>()
            client.onAction = { ran += it.action }
            client.onError = { errors += it }
            client.confirmationRequirements = mapOf("start_dispute" to ActionConfirmation("Start a dispute"))
            client.sendContext(actions = listOf(LiveSyncAction("start_dispute", "Start", json("""{"type":"object","properties":{"id":{"type":"string"}}}"""))))
            client.ingest("""{"classification":"custom","action":"start_dispute","payload":{"id":"t1"}}""")
            assertNotNull(client.pendingConfirmation.value)
            // The screen tightened the schema while the prompt was up.
            client.sendContext(actions = listOf(LiveSyncAction("start_dispute", "Start", json("""{"type":"object","properties":{"id":{"type":"integer"}}}"""))))
            client.confirmPendingAction()
            assertTrue(ran.isEmpty())
            assertEquals("type at /id", assertIs<LiveSyncException.InvalidPayload>(errors.last()).description)
        }

    @Test
    fun `consent re-granted after a denial asks for the context again`() =
        runBlocking {
            val client = makeTestClient()
            var needed = 0
            client.onContextNeeded = { needed++ }
            client.sendContext(actions = listOf(LiveSyncAction("a", "A")))
            client.setConsent(ConsentDecision.DENIED)
            client.setConsent(ConsentDecision.GRANTED)
            assertEquals(1, needed)
        }
}
