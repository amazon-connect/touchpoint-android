package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.ConsentPrompt
import com.amazon.connect.touchpoint.core.ConsentStore
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The consent controller: the stored decision applied, the customer asked once, and a stop that is immediate. */
class LiveSyncConsentControllerTest {
    private val prompt = ConsentPrompt("which screen you are on", "Acme Bank's assistant")

    @Test
    fun `undecided, it asks and nothing is posted - granted, it is kept and the client posts`() =
        runBlocking {
            val secure = InMemorySecureStore()
            val client = makeTestClient(consent = ConsentDecision.HELD)
            val consent = LiveSyncConsentController(client, ConsentStore(secure, "acme"), prompt)
            consent.load()
            assertTrue(consent.needsPrompt.value)
            assertEquals(ConsentDecision.HELD, client.consent)
            consent.grant()
            assertFalse(consent.needsPrompt.value)
            assertEquals(ConsentDecision.GRANTED, client.consent)
            // Next time, from the store: not asked again.
            val next = makeTestClient(consent = ConsentDecision.HELD)
            val again = LiveSyncConsentController(next, ConsentStore(secure, "acme"), prompt)
            again.load()
            assertFalse(again.needsPrompt.value)
            assertEquals(ConsentDecision.GRANTED, next.consent)
            client.close()
            next.close()
        }

    @Test
    fun `stopping is kept, says it stopped sharing that was on, and Ask me again asks`() =
        runBlocking {
            val client = makeTestClient(consent = ConsentDecision.HELD)
            val consent = LiveSyncConsentController(client, ConsentStore(InMemorySecureStore(), "acme"), prompt)
            consent.load()
            assertFalse(consent.deny()) // Not now: nothing was on
            consent.grant()
            assertTrue(consent.deny())
            assertEquals(ConsentDecision.DENIED, client.consent)
            assertEquals(ConsentDecision.DENIED, consent.decision.value)
            consent.reset()
            assertTrue(consent.needsPrompt.value)
            assertEquals(ConsentDecision.HELD, client.consent)
            client.close()
        }

    @Test
    fun `a client made with a decision keeps it - the store is for a held one`() =
        runBlocking {
            val secure = InMemorySecureStore()
            ConsentStore(secure, "acme").record(ConsentStore.Decision.DENIED)
            val client = makeTestClient(consent = ConsentDecision.GRANTED)
            val consent = LiveSyncConsentController(client, ConsentStore(secure, "acme"), prompt)
            consent.load()
            assertFalse(consent.needsPrompt.value)
            assertEquals(ConsentDecision.GRANTED, client.consent)
            client.close()
        }
}
