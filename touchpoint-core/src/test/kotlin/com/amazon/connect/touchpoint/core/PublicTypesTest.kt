package com.amazon.connect.touchpoint.core

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicTypesTest {
    @Test
    fun `Modality has exactly chat and voice, with stable persisted values`() {
        assertEquals(listOf(Modality.CHAT, Modality.VOICE), Modality.entries)
        assertEquals(listOf("chat", "voice"), Modality.entries.map { it.rawValue })
        assertEquals(Modality.VOICE, Modality.fromRawValue("voice"))
        assertNull(Modality.fromRawValue("video"))
    }

    @Test
    fun `EndReason covers the design's terminal reasons`() {
        assertEquals(5, EndReason.entries.toSet().size)
    }

    @Test
    fun `Permission covers microphone and notifications`() {
        assertEquals(listOf(Permission.MICROPHONE), Permission.entries)
    }

    @Test
    fun `ContactState equality distinguishes reconnect attempts and end reasons`() {
        assertNotEquals<ContactState>(ContactState.Reconnecting(1), ContactState.Reconnecting(2))
        assertNotEquals<ContactState>(ContactState.Ended(EndReason.CUSTOMER_ENDED), ContactState.Ended(EndReason.CONNECTION_LOST))
        assertEquals<ContactState>(ContactState.Idle, ContactState.Idle)
    }

    @Test
    fun `TouchpointException cases compare by value and carry their diagnostics`() {
        val a = TouchpointException.HttpFailure(403, "denied")
        assertEquals(a, TouchpointException.HttpFailure(403, "denied"))
        assertNotEquals(a, TouchpointException.HttpFailure(403, null))
        assertEquals(TouchpointException.ContactNotFound(), TouchpointException.ContactNotFound())
        assertEquals(TouchpointException.ConsentNotGranted(), TouchpointException.ConsentNotGranted())
        assertNotEquals<TouchpointException>(TouchpointException.CredentialVendingFailed("x"), TouchpointException.ContactNotFound())
    }

    @Test
    fun `an HTTP failure's body reaches neither its message nor its toString`() {
        val e = TouchpointException.HttpFailure(400, "token=SECRET-IN-BODY")
        assertFalse(e.message.orEmpty().contains("SECRET"))
        assertFalse(e.toString().contains("SECRET"))
        assertEquals("token=SECRET-IN-BODY", e.responseBody) // still there for a host that asks
        val thrown = assertFailsWith<TouchpointException.HttpFailure> { throw e }
        assertEquals(400, thrown.statusCode)
    }

    @Test
    fun `request DTOs are value types with stable fields`() {
        val chat = ChatContactRequest("Alex", mapOf("tier" to "gold"))
        assertEquals("Alex", chat.displayName)
        assertEquals("gold", chat.attributes["tier"])
        assertEquals(emptyMap(), VoiceContactRequest("Alex").attributes)
        val out = OutboundMessage(UUID.randomUUID(), "hi")
        assertEquals("hi", out.body)
        assertNotEquals(OutboundMessage(body = "x").clientMessageId, OutboundMessage(body = "x").clientMessageId)
    }

    @Test
    fun `credentials expose an expiry the broker can reason about`() {
        val now = Instant.now()
        assertTrue(ChatContactCredentials("c", "p", "t", now.plusSeconds(600)).expiresAt!!.isAfter(now))
        assertNull(LiveSyncCredentials("d", "k", null).expiresAt)
    }

    @Test
    fun `no credential, token or message body appears in a toString`() {
        val strings =
            listOf(
                ChatContactCredentials("c1", "p1", "SECRET-TOKEN", null).toString(),
                VoiceContactCredentials("c1", "p1", "SECRET-TOKEN", "{\"SECRET-MEETING\":1}", "{\"SECRET-ATTENDEE\":1}", null).toString(),
                LiveSyncCredentials("dep", "SECRET-KEY", null).toString(),
                OutboundMessage(body = "SECRET-BODY").toString(),
                ChatContactRequest("SECRET-NAME", mapOf("account" to "SECRET-VALUE")).toString(),
                VoiceContactRequest("SECRET-NAME", mapOf("account" to "SECRET-VALUE")).toString(),
                "${ChatContactCredentials("c1", "p1", "SECRET-TOKEN", null)}",
            )
        strings.forEach { assertFalse(it.contains("SECRET"), it) }
        assertTrue(strings[0].contains("c1"), "identifiers stay visible for diagnostics")
    }

    @Test
    fun `a voice request carries one idempotency token for its life, and its copies keep it`() {
        val request = VoiceContactRequest("Alex")
        assertTrue(request.clientToken.length >= 8)
        assertEquals(request.clientToken, request.copy(screenShare = true).clientToken)
        assertTrue(request.clientToken != VoiceContactRequest("Alex").clientToken)
        assertFalse(request.clientToken in request.toString())
    }

    @Test
    fun `a citation and a guide's summary print none of the conversation's words`() {
        val printed = listOf(Citation("SECRET-TITLE", "https://secret.example", "SECRET-SNIPPET"), ViewSummary("SECRET-HEADING", "SECRET-SUB")).joinToString()
        assertFalse("SECRET" in printed, printed)
        assertFalse("secret.example" in printed, printed)
    }
}
