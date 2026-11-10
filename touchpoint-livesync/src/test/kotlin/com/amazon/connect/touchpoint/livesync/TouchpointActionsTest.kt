package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.DispatchState
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Declarative actions: what a screen declares, advertised once it settles, and each invocation routed home. */
@OptIn(ExperimentalCoroutinesApi::class)
class TouchpointActionsTest {
    @Serializable
    enum class Reason { unrecognized, duplicate }

    @Serializable
    data class DisputeDetails(val reason: Reason? = null, val authorized: Boolean? = null, val amount: Double? = null, val notes: List<String>? = null)

    @Serializable
    data class Transfer(val to: String, val cents: Long)

    private fun declaration(name: String, scope: String? = null, description: String = name, handler: (kotlinx.serialization.json.JsonElement) -> Unit = {}) =
        TouchpointActionDeclaration(name, description, TouchpointActionSchema.NONE, scope, handler = handler)

    @Test
    fun `the schema comes from the serializer - every optional field optional, enums by name, the rest required`() {
        val schema = TouchpointActionSchema.of(DisputeDetails.serializer())
        val expected =
            Json.parseToJsonElement(
                """{"type":"object","properties":{"reason":{"type":"string","enum":["unrecognized","duplicate"]},"authorized":{"type":"boolean"},"amount":{"type":"number"},"notes":{"type":"array","items":{"type":"string"}}}}""",
            )
        assertEquals(expected, schema)
        val transfer = TouchpointActionSchema.of(Transfer.serializer())
        assertEquals(Json.parseToJsonElement("""["to","cents"]"""), transfer["required"])
        assertEquals(JsonPrimitive("integer"), (transfer["properties"] as JsonObject)["cents"]!!.let { (it as JsonObject)["type"] })
    }

    @Serializable
    data class Node(val name: String? = null, val children: List<Node>? = null)

    @Test
    fun `a type that contains itself is described once, not for ever`() {
        val schema = TouchpointActionSchema.of(Node.serializer())
        val items = ((schema["properties"] as JsonObject)["children"] as JsonObject)["items"]
        assertEquals(TouchpointActionSchema.UNDESCRIBED, items)
    }

    @Test
    fun `a context that fails to post is posted again when the same set is reported`() =
        runTest {
            var fail = true
            val posts = mutableListOf<LiveSyncContext>()
            val errors = mutableListOf<LiveSyncException>()
            val aggregator =
                TouchpointActionAggregator(report = { errors += it }, scope = backgroundScope, timeSource = testScheduler.timeSource) {
                    if (fail) throw LiveSyncException.RequestFailed("down")
                    posts += it
                }
            val set = TouchpointActionSet(listOf(declaration("a")))
            aggregator.update(set)
            aggregator.flushNow()
            runCurrent()
            assertEquals(1, errors.size)
            fail = false
            aggregator.update(set)
            aggregator.flushNow()
            runCurrent()
            assertEquals(1, posts.size)
        }

    @Test
    fun `a set is by name, the last of a name wins, and its scopes are sent only when there are some`() {
        val set = TouchpointActionSet(listOf(declaration("b"), declaration("a", description = "first"), declaration("a", description = "second")))
        assertEquals(listOf("a", "b"), set.names)
        assertEquals("second", set["a"]!!.description)
        assertNull(set.scopes)
        assertEquals(listOf("accounts", "dispute"), TouchpointActionSet(listOf(declaration("x", "dispute"), declaration("y", "accounts"), declaration("z", "dispute"))).scopes)
        // A new lambda is not a new context.
        assertEquals(TouchpointActionSet(listOf(declaration("a") { })), TouchpointActionSet(listOf(declaration("a") { error("other") })))
    }

    @Test
    fun `a burst posts once, after the composition is quiet - and nothing new posts nothing`() =
        runTest {
            val posts = mutableListOf<LiveSyncContext>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource) { posts += it }
            aggregator.update(TouchpointActionSet(listOf(declaration("a"))))
            advanceTimeBy(30)
            aggregator.update(TouchpointActionSet(listOf(declaration("a"), declaration("b"))))
            advanceTimeBy(30)
            assertTrue(posts.isEmpty()) // restarted: not quiet for 50 yet
            advanceTimeBy(30)
            runCurrent()
            assertEquals(listOf(listOf("a", "b")), posts.map { c -> c.actions.map { it.name } })
            aggregator.update(TouchpointActionSet(listOf(declaration("b"), declaration("a") { })))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, posts.size)
            assertEquals(1, aggregator.postCount.value)
        }

    @Test
    fun `a composition that never settles still advertises within the ceiling, and a reverted change posts nothing`() =
        runTest {
            val posts = mutableListOf<LiveSyncContext>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource) { posts += it }
            repeat(10) { i ->
                aggregator.update(TouchpointActionSet(listOf(declaration("a$i"))))
                advanceTimeBy(40)
            }
            runCurrent()
            assertEquals(1, posts.size) // by 300, though it never went quiet for 50
            aggregator.flushNow()
            runCurrent()
            val settled = posts.size
            val shown = aggregator.advertised.value
            aggregator.update(TouchpointActionSet(listOf(declaration("other"))))
            aggregator.update(shown)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(settled, posts.size)
        }

    @Test
    fun `an invocation goes to what declared it - by what was advertised - and an unknown name is counted and reported`() =
        runTest {
            val seen = mutableListOf<String>()
            val errors = mutableListOf<LiveSyncException>()
            val aggregator = TouchpointActionAggregator(report = { errors += it }, scope = backgroundScope, timeSource = testScheduler.timeSource) { }
            aggregator.update(TouchpointActionSet(listOf(declaration("open") { seen += "open:$it" })))
            aggregator.flushNow()
            aggregator.update(TouchpointActionSet()) // the screen moved on; the engine has not heard yet
            aggregator.route(LiveSyncActionInvocation("open", buildJsonObject { put("k", 1) }))
            assertEquals(listOf("open:{\"k\":1}"), seen)
            aggregator.route(LiveSyncActionInvocation("nothing"))
            assertEquals(1, aggregator.unroutedInvocations.value)
            assertTrue(errors.single() is LiveSyncException.ActionHandlerFailed)
            // A handler's own failure propagates, for the client to report.
            aggregator.update(TouchpointActionSet(listOf(declaration("fails") { error("no") })))
            aggregator.flushNow()
            assertFailsWith<IllegalStateException> { aggregator.route(LiveSyncActionInvocation("fails")) }
        }

    @Test
    fun `what the screen marks for confirmation reaches the client - never the engine - and both sides of a navigation hold`() =
        runTest {
            val posts = mutableListOf<LiveSyncContext>()
            val required = mutableListOf<Map<String, ActionConfirmation>>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource, confirmations = { required += it }) { posts += it }
            val submit = TouchpointActionDeclaration("submit", "Submit the dispute", TouchpointActionSchema.NONE, confirmationRequired = true, confirmationMessage = "Submit your dispute?") {}
            aggregator.update(TouchpointActionSet(listOf(submit)))
            assertEquals(mapOf("submit" to ActionConfirmation("Submit the dispute", "Submit your dispute?")), required.last(), "at once, not after the debounce")
            aggregator.flushNow()
            runCurrent()
            assertEquals(listOf("submit"), posts.single().actions.map { it.name })
            // The screen moves on; until the engine hears, what it was offered still needs confirming.
            aggregator.update(TouchpointActionSet())
            assertEquals(setOf("submit"), required.last().keys)
            aggregator.flushNow()
            runCurrent()
            assertEquals(emptyMap(), required.last())
        }

    @Test
    fun `a screen that turns confirmation on is held to it at once, though nothing new is advertised`() =
        runTest {
            val posts = mutableListOf<LiveSyncContext>()
            val required = mutableListOf<Map<String, ActionConfirmation>>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource, confirmations = { required += it }) { posts += it }
            aggregator.update(TouchpointActionSet(listOf(declaration("pay"))))
            aggregator.flushNow()
            val held = TouchpointActionSet(listOf(TouchpointActionDeclaration("pay", "pay", TouchpointActionSchema.NONE, confirmationRequired = true) {}))
            // Equal for the engine, different for the customer: the composition's change still lands.
            assertTrue(held != TouchpointActionSet(listOf(declaration("pay"))))
            aggregator.update(held)
            runCurrent()
            assertEquals(1, posts.size, "nothing new for the engine: no post")
            assertEquals(setOf("pay"), required.last().keys)
        }

    @Test
    fun `a new client gets the screen already on show`() =
        runTest {
            val posts = mutableListOf<LiveSyncContext>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource) { posts += it }
            aggregator.update(TouchpointActionSet(listOf(declaration("a"))))
            aggregator.flushNow()
            aggregator.readvertise()
            runCurrent()
            assertEquals(2, posts.size)
            assertEquals(posts[0], posts[1])
        }

    @Test
    fun `the overlay says where things stand - and an invocation's payload by its keys only`() {
        assertEquals("Live Sync: connected · dispatch SUSPENDED · 1 post", LiveSyncOverlayText.status(LiveSyncConnectionState.Connected, DispatchState.SUSPENDED, 1))
        assertEquals("Live Sync: no client · 0 posts", LiveSyncOverlayText.status(null, null, 0))
        assertEquals("2 notes · 1 UNROUTED · last: open", LiveSyncOverlayText.traffic(2, 1, "open"))
        val current = TouchpointActionSet(listOf(declaration("b")))
        val advertised = TouchpointActionSet(listOf(declaration("a", "accounts")))
        val lines = LiveSyncOverlayText.detail(current, advertised, LiveSyncActionInvocation("a", buildJsonObject { put("secret", "1234") }))
        assertEquals(listOf("scopes: accounts · 1 action", "• a (withdrawn)", "+ b (pending)"), lines.take(3))
        assertTrue("1234" !in lines.last())
    }
}
