package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Consent: the consent decision kept per deployment, and permissions the host asks for. */
class ConsentTest {
    @Test
    fun `a decision is kept per deployment, in the secure store, until reset`() =
        runBlocking {
            val secure = InMemorySecureStore()
            val acme = ConsentStore(secure, "acme", now = { 42L })
            assertNull(acme.decision())
            acme.record(ConsentStore.Decision.GRANTED)
            assertEquals(ConsentStore.Decision.GRANTED, acme.decision())
            assertEquals(42L, acme.decidedAt())
            assertEquals(listOf(SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY), secure.allAccessibility()) // the Keystore: never in a backup
            // Another assistant asks again.
            assertNull(ConsentStore(secure, "other").decision())
            acme.record(ConsentStore.Decision.DENIED)
            assertEquals(ConsentStore.Decision.DENIED, ConsentStore(secure, "acme").decision()) // survives a relaunch
            acme.reset()
            assertNull(acme.decision())
        }

    @Test
    fun `an unreadable record is no decision, and a store that fails is never a crash`() =
        runBlocking {
            val secure = InMemorySecureStore()
            secure.set("livesync.consent.v1.acme", "not json".encodeToByteArray(), SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
            assertNull(ConsentStore(secure, "acme").decision())
            val broken =
                object : SecureStore {
                    override suspend fun get(key: String): ByteArray? = throw IllegalStateException("keystore")

                    override suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility) = throw IllegalStateException("keystore")

                    override suspend fun delete(key: String) = throw IllegalStateException("keystore")
                }
            val store = ConsentStore(broken, "acme")
            store.record(ConsentStore.Decision.GRANTED)
            store.reset()
            assertNull(store.decision())
        }

    @Test
    fun `the SDK asks for no permission unless the host lists it, and a denial says what still works`() {
        assertFalse(PermissionPolicy.NEVER.allows(Permission.MICROPHONE))
        assertTrue(PermissionPolicy(setOf(Permission.MICROPHONE)).allows(Permission.MICROPHONE))
        assertEquals(PermissionDenial.Consequence.VOICE_UNAVAILABLE_CHAT_REMAINS, PermissionDenial(Permission.MICROPHONE).consequence)
    }
}
