package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.LiveSyncChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Inherited from the proof of concept's suite: endpoints, context and
// dispatch. Consent is granted here so the behaviour under test is unchanged; the gate has its own suite.
private fun configuration(
    locale: Locale = Locale.US,
    stage: String? = null,
) = LiveSyncConfiguration("us-west-2", "4411ca47-96d8-4bc8-88d8-1b7a6a1dcd98", LiveSyncChannel.from(locale), stage)

private val contactInfoSchema =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("fullName") { put("type", "string") }
            putJsonObject("email") { put("type", "string") }
        }
    }

private fun body(client: LiveSyncClient) = Json.parseToJsonElement(client.contextRequestBody()).jsonObject

private fun vpContext(client: LiveSyncClient) = body(client)["context"]!!.jsonObject["nlx:vpContext"]!!.jsonObject

class LiveSyncEndpointTest {
    @Test
    fun `socket URL matches the documented handshake`() =
        runBlocking {
            val request = makeTestClient().makeSocketRequest()
            val uri = URI(request.url)
            val q = query(request.url)
            assertEquals("wss", uri.scheme)
            // Derived, not spelled out: the rule is "ws." + the API host; production has no stage suffix.
            assertEquals("ws." + configuration().apiHost, uri.host)
            assertTrue(configuration().apiHost.endsWith(".us-west-2.amazonaws.com"))
            assertTrue(configuration().stageSuffix.isEmpty())
            assertEquals("/", uri.path)
            assertEquals("deployment-key", q["deploymentKey"])
            assertEquals("connect-en-US", q["channelKey"])
            assertEquals("test-api-key-placeholder", q["apiKey"])
            assertEquals("4411ca47-96d8-4bc8-88d8-1b7a6a1dcd98", q["conversationId"])
            assertEquals("voice-plus", q["type"])
            assertNull(request.headers["Origin"]) // defect 4: no hardcoded Origin
        }

    @Test
    fun `a stage suffixes both hosts, and the locale drives the channel`() =
        runBlocking {
            val staged = configuration(Locale.GERMANY, "example")
            val client = makeTestClient(configuration = staged)
            assertEquals(staged.socketHost, URI(client.makeSocketRequest().url).host)
            assertTrue("-example.us-west-2." in staged.socketHost)
            assertEquals("ws." + staged.apiHost, staged.socketHost)
            val context = URI(client.makeContextRequest().url)
            assertEquals(staged.apiHost, context.host)
            assertEquals("/nlu/c/deployment-key/connect-de-DE/context", context.path)
        }

    @Test
    fun `an empty required value is rejected before any connection is attempted`() =
        runBlocking {
            val e = assertFailsWith<LiveSyncException.InvalidConfiguration> { makeTestClient(configuration = configuration().copy(conversationId = "")).makeSocketRequest() }
            assertEquals("`conversationId` must not be empty", e.detail)
        }
}

class LiveSyncSendContextTest {
    @Test
    fun `posts the vpContext envelope with the API key header`() =
        runBlocking {
            val poster = FakePoster()
            val client = makeTestClient(poster = poster)
            client.sendContext(
                scopes = listOf("booking"),
                actions = listOf(LiveSyncAction("set_contact_info", "Set the customer's name and email", contactInfoSchema), LiveSyncAction("search_flights", "Run the flight search")),
            )
            client.postContext()
            val request = poster.requests.last()
            assertEquals(configuration().apiHost, URI(request.url).host)
            assertEquals("/nlu/c/deployment-key/connect-en-US/context", URI(request.url).path)
            assertEquals("test-api-key-placeholder", request.headers["nlx-api-key"])
            assertEquals("application/json", request.headers["Content-Type"])
            val body = Json.parseToJsonElement(request.body!!).jsonObject
            assertEquals("4411ca47-96d8-4bc8-88d8-1b7a6a1dcd98", body["conversationId"]!!.jsonPrimitive.content)
            val vp = body["context"]!!.jsonObject["nlx:vpContext"]!!.jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive("booking"))), vp["scopes"])
            assertEquals(
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("action", "set_contact_info")
                            put("description", "Set the customer's name and email")
                            put("schema", contactInfoSchema)
                        },
                        buildJsonObject {
                            put("action", "search_flights")
                            put("description", "Run the flight search")
                        },
                    ),
                ),
                vp["actions"],
            )
        }

    @Test
    fun `each call replaces the previous context rather than merging into it`() =
        runBlocking {
            val client = makeTestClient()
            client.sendContext(scopes = listOf("booking"), actions = listOf(LiveSyncAction("search_flights", "Run the search")))
            client.sendContext(scopes = listOf("checkout"))
            val vp = vpContext(client)
            assertEquals(JsonArray(listOf(JsonPrimitive("checkout"))), vp["scopes"])
            assertEquals(JsonArray(emptyList()), vp["actions"])
        }

    @Test
    fun `omitted scopes are left out of the request`() =
        runBlocking {
            val client = makeTestClient()
            client.sendContext(actions = listOf(LiveSyncAction("search_flights", "Run the search")))
            val vp = vpContext(client)
            assertNull(vp["scopes"])
            assertEquals("search_flights", vp["actions"]!!.jsonArray[0].jsonObject["action"]!!.jsonPrimitive.content)
        }

    @Test
    fun `context sent before the socket opens is held rather than posted`() =
        runBlocking {
            val poster = FakePoster()
            val client = makeTestClient(poster = poster)
            client.sendContext(scopes = listOf("booking"))
            assertTrue(poster.requests.isEmpty())
            assertEquals(LiveSyncConnectionState.Idle, client.connectionState.value)
        }

    @Test
    fun `a rejected request throws with the status and body`() =
        runBlocking {
            val client = makeTestClient(poster = FakePoster(403, """{"message":"Forbidden"}"""))
            client.sendContext(scopes = listOf("booking"))
            val e = assertFailsWith<LiveSyncException.HttpFailure> { client.postContext() }
            assertEquals(LiveSyncException.HttpFailure(403, """{"message":"Forbidden"}"""), e)
            // The body is the API's answer, kept out of the message and out of toString.
            assertTrue("Forbidden" !in e.toString() && "Forbidden" !in e.message.orEmpty())
        }
}

class LiveSyncDispatchTest {
    @Serializable
    private data class ContactInfo(val fullName: String, val email: String)

    @Test
    fun `every invocation reaches onAction with its name and payload`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("search_flights", "set_contact_info")
            val received = mutableListOf<LiveSyncActionInvocation>()
            client.onAction = { received += it }
            client.ingest("""{"conversationId":"ea03d952","classification":"custom","action":"set_contact_info","payload":{"fullName":"chirag","email":"chirag@example.com"}}""")
            client.ingest("""{"classification":"custom","action":"search_flights"}""")
            assertEquals(listOf("set_contact_info", "search_flights"), received.map { it.action })
            assertEquals("chirag@example.com", received.first().payload.jsonObject["email"]!!.jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), received.last().payload)
        }

    @Test
    fun `a payload decodes into a model inside the handler`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("set_contact_info")
            var received: ContactInfo? = null
            client.onAction = { if (it.action == "set_contact_info") received = it.decode<ContactInfo>() }
            client.ingest("""{"classification":"custom","action":"set_contact_info","payload":{"fullName":"chirag","email":"chirag@example.com"}}""")
            assertEquals(ContactInfo("chirag", "chirag@example.com"), received)
        }

    @Test
    fun `an exception thrown by the handler is reported, not crashed`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("set_contact_info")
            val errors = mutableListOf<LiveSyncException>()
            client.onError = { errors += it }
            client.onAction = { it.decode<ContactInfo>() }
            client.ingest("""{"classification":"custom","action":"set_contact_info","payload":{}}""")
            assertEquals(1, errors.size)
            assertEquals("set_contact_info", assertIs<LiveSyncException.ActionHandlerFailed>(errors.single()).action)
        }

    @Test
    fun `actions the handler does not recognise fall through to its else branch`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("set_extras")
            val ignored = mutableListOf<String>()
            client.onAction = {
                when (it.action) {
                    "search_flights" -> Unit
                    else -> ignored += it.action
                }
            }
            client.ingest("""{"classification":"custom","action":"set_extras","payload":{"extras":[]}}""")
            assertEquals(listOf("set_extras"), ignored)
        }

    @Test
    fun `the handler outlives the contexts it was registered before, and an action a newer context dropped is refused`() =
        runBlocking {
            val client = makeTestClient()
            val calls = mutableListOf<String>()
            client.onAction = { calls += it.action }
            client.sendContext(actions = listOf(LiveSyncAction("step_one", "First step")))
            client.ingest("""{"classification":"custom","action":"step_one"}""")
            client.sendContext(actions = listOf(LiveSyncAction("step_two", "Second step")))
            client.ingest("""{"classification":"custom","action":"step_one"}""")
            client.ingest("""{"classification":"custom","action":"step_two"}""")
            assertEquals(listOf("step_one", "step_two"), calls)
            assertEquals(1, client.diagnostics.value.rejectedInvocations)
        }

    @Test
    fun `invocations are also published on the observation flow`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("search_flights")
            val next = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { client.invocations.first() } }
            client.ingest("""{"classification":"custom","action":"search_flights"}""")
            assertEquals(LiveSyncActionInvocation("search_flights", JsonObject(emptyMap())), next.await())
        }

    @Test
    fun `a malformed frame is reported and does not stop later invocations`() =
        runBlocking {
            val client = makeTestClient()
            client.advertise("search_flights")
            var errors = 0
            var ran = false
            client.onError = { errors++ }
            client.onAction = { ran = true }
            client.ingest("}{")
            client.ingest("""{"classification":"custom","action":"search_flights"}""")
            assertEquals(1, errors)
            assertTrue(ran)
        }
}
