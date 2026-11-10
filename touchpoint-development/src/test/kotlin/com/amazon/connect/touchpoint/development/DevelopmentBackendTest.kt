package com.amazon.connect.touchpoint.development

import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DevelopmentBackendTest {
    private val literals = DevelopmentBackend.LiveSyncLiterals(deploymentKey = "dep", apiKey = "k")

    @Test
    fun `vends the literal Live Sync keys it was given, with no expiry`() = runBlocking {
        val c = DevelopmentBackend(literals).liveSyncCredentials()
        assertEquals("dep", c.deploymentKey)
        assertEquals("k", c.apiKey)
        assertNull(c.expiresAt)
    }

    @Test
    fun `without chat or voice vendors it throws InvalidConfiguration, not a crash`() = runBlocking {
        val backend = DevelopmentBackend(literals)
        assertFailsWith<TouchpointException.InvalidConfiguration> { backend.startChatContact(ChatContactRequest("a")) }
        assertFailsWith<TouchpointException.InvalidConfiguration> { backend.startVoiceContact(VoiceContactRequest("a")) }
        Unit
    }

    @Test
    fun `delegates chat to the injected vendor`() = runBlocking {
        val backend = DevelopmentBackend(literals, chat = { ChatContactCredentials("c", "p", "t", null) })
        assertEquals("c", backend.startChatContact(ChatContactRequest("a")).contactId)
    }

    @Test
    fun `the literals never print their API key`() {
        assertFalse(DevelopmentBackend.LiveSyncLiterals("dep", "SECRET").toString().contains("SECRET"))
    }
}
