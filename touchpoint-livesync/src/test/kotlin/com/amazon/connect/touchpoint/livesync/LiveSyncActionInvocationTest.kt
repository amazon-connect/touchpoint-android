package com.amazon.connect.touchpoint.livesync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSyncActionInvocationTest {
    @Test
    fun `action frame with a payload`() {
        val invocation =
            LiveSyncActionInvocation.decode(
                """{"conversationId":"ea03d952-de13-4107-b461-3ace4465e563","classification":"custom","action":"set_contact_info","payload":{"fullName":"chirag","email":"chirag@example.com"}}""",
            )
        assertEquals(
            LiveSyncActionInvocation("set_contact_info", JsonObject(mapOf("fullName" to JsonPrimitive("chirag"), "email" to JsonPrimitive("chirag@example.com")))),
            invocation,
        )
    }

    @Test
    fun `action frame without a payload`() {
        assertEquals(LiveSyncActionInvocation("search_flights", JsonObject(emptyMap())), LiveSyncActionInvocation.decode("""{"classification":"custom","action":"search_flights"}"""))
    }

    @Test
    fun `an action frame without a name is rejected`() {
        assertFailsWith<LiveSyncException.MalformedInvocation> { LiveSyncActionInvocation.decode("""{"classification":"custom","payload":{}}""") }
    }

    @Test
    fun `a null payload arrives as the empty object`() {
        assertEquals(JsonObject(emptyMap()), LiveSyncActionInvocation.decode("""{"classification":"custom","action":"a","payload":null}""")?.payload)
    }

    @Test
    fun `frames that are not action invocations are ignored`() {
        assertNull(LiveSyncActionInvocation.decode("""{"classification":"something-else"}"""))
        assertNull(LiveSyncActionInvocation.decode("""{"conversationId":"abc"}"""))
    }

    @Test
    fun `malformed JSON surfaces as a Live Sync exception, without quoting the frame`() {
        val e = assertFailsWith<LiveSyncException.MalformedInvocation> { LiveSyncActionInvocation.decode("not json secret-words") }
        assertTrue("secret-words" !in e.message.orEmpty())
    }

    @Test
    fun `an invocation wrapped one level down in payload is read there, as NLX's client reads it`() {
        val wrapped = """{"conversationId":"c1","payload":{"classification":"custom","action":"open_account","payload":{"accountType":"credit_card"}}}"""
        assertEquals(LiveSyncActionInvocation("open_account", JsonObject(mapOf("accountType" to JsonPrimitive("credit_card")))), LiveSyncActionInvocation.decode(wrapped))
        val halfWrapped = """{"classification":"custom","payload":{"action":"open_account","payload":{"accountType":"credit_card"}}}"""
        assertEquals("open_account", LiveSyncActionInvocation.decode(halfWrapped)?.action)
    }

    @Test
    fun `a custom frame with no action anywhere names its keys, never its values`() {
        val e = assertFailsWith<LiveSyncException.MalformedInvocation> { LiveSyncActionInvocation.decode("""{"classification":"custom","tool":"secret-words","payload":{"x":"secret-words"}}""") }
        assertTrue("tool" in e.detail && "x" in e.detail, e.detail)
        assertTrue("secret-words" !in e.detail)
    }

    @Test
    fun `the assistant's speech between invocations is not an invocation, nor a malformed one`() {
        assertNull(LiveSyncActionInvocation.decode("""{"classification":"custom","note":"One moment.","metadata":{"interim":true}}"""))
        assertNull(LiveSyncActionInvocation.decode("""{"payload":{"classification":"custom","note":"Let me look."}}"""))
        // An invocation beside a note is still the invocation.
        kotlin.test.assertEquals("open_account", LiveSyncActionInvocation.decode("""{"classification":"custom","action":"open_account","note":"Opening it.","payload":{}}""")?.action)
    }
}
