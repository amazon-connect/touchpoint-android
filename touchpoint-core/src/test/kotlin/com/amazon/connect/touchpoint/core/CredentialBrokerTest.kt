package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialBrokerTest {
    private val clock = FakeClock()
    private val backend = ScriptedBackend(clock)

    private fun broker(store: SecureStore = InMemorySecureStore(), refreshFraction: Double = 0.2) =
        CredentialBroker(backend, store, refreshFraction, clock.provider)

    @Test
    fun `vends Live Sync credentials from the backend and caches them`() = runBlocking {
        val broker = broker()
        assertEquals("key-1", broker.liveSyncCredentials().apiKey)
        assertEquals("key-1", broker.liveSyncCredentials().apiKey)
        assertEquals(1, backend.liveSyncCalls.get())
    }

    @Test
    fun `refreshes when less than the configured fraction of lifetime remains`() = runBlocking {
        backend.lifetime = Duration.ofSeconds(10)
        val broker = broker(refreshFraction = 0.9) // refresh when < 90% remains
        broker.liveSyncCredentials()
        clock.advance(Duration.ofMillis(1_100))
        assertEquals("key-2", broker.liveSyncCredentials().apiKey)
        assertEquals(2, backend.liveSyncCalls.get())
    }

    @Test
    fun `expired credentials are never returned from cache`() = runBlocking {
        backend.lifetime = Duration.ofMillis(50)
        val broker = broker(refreshFraction = 0.0)
        broker.liveSyncCredentials()
        clock.advance(Duration.ofMillis(100))
        assertEquals("key-2", broker.liveSyncCredentials().apiKey)
    }

    @Test
    fun `concurrent requests coalesce into one backend call`() = runBlocking {
        val broker = broker()
        val gate = backend.hold(1)
        val callers = (1..20).map { async { broker.liveSyncCredentials().apiKey } }
        backend.awaitVend(1)
        gate.complete(Unit)
        assertEquals(setOf("key-1"), callers.awaitAll().toSet())
        assertEquals(1, backend.liveSyncCalls.get())
    }

    @Test
    fun `a failed vend is not cached and the next call retries`() = runBlocking {
        val broker = broker()
        backend.failNext = true
        assertFailsWith<TouchpointException> { broker.liveSyncCredentials() }
        assertEquals("key-2", broker.liveSyncCredentials().apiKey)
    }

    @Test
    fun `vend failure is mapped to CredentialVendingFailed carrying only the type`() = runBlocking {
        val broker = broker()
        backend.failNext = true
        val e = assertFailsWith<TouchpointException.CredentialVendingFailed> { broker.liveSyncCredentials() }
        assertEquals("IOException", e.underlying) // the message, which could carry a URL, is dropped
    }

    @Test
    fun `coalesced waiters receive the same mapped error as the originator (C1)`() = runBlocking {
        val broker = broker()
        val gate = backend.hold(1)
        backend.failNext = true
        val callers = (1..5).map { async { runCatching { broker.liveSyncCredentials() }.exceptionOrNull() } }
        backend.awaitVend(1)
        gate.complete(Unit)
        val errors = callers.awaitAll()
        assertTrue(errors.all { it is TouchpointException.CredentialVendingFailed }, "got $errors")
    }

    @Test
    fun `invalidate during an in-flight vend wins - the stale result is not cached (m1)`() = runBlocking {
        val broker = broker()
        val gate = backend.hold(1)
        val first = async { broker.liveSyncCredentials() }
        backend.awaitVend(1)
        broker.invalidateLiveSyncCredentials()
        gate.complete(Unit)
        assertEquals("key-1", first.await().apiKey) // returned to its caller
        assertEquals("key-2", broker.liveSyncCredentials().apiKey) // but not cached past the invalidate
    }

    @Test
    fun `a caller arriving after invalidate does not join the stale in-flight vend (r1p8)`() = runBlocking {
        val broker = broker()
        val gate = backend.hold(1)
        val first = async { broker.liveSyncCredentials() }
        backend.awaitVend(1)
        broker.invalidateLiveSyncCredentials()
        val second = broker.liveSyncCredentials() // vend 2 is not held
        gate.complete(Unit)
        first.await()
        assertEquals("key-2", second.apiKey)
        assertEquals(2, backend.liveSyncCalls.get())
    }

    @Test
    fun `early-refresh failure returns the still-valid cached credential instead of throwing (r1p6)`() = runBlocking {
        backend.lifetime = Duration.ofSeconds(10)
        val broker = broker(refreshFraction = 0.99)
        val a = broker.liveSyncCredentials()
        clock.advance(Duration.ofMillis(200)) // inside the refresh window, not expired
        backend.failNext = true
        assertEquals(a.apiKey, broker.liveSyncCredentials().apiKey) // refresh failed → cached value
        assertEquals("key-3", broker.liveSyncCredentials().apiKey) // next call retries; call 2 was the failure
        assertEquals(3, backend.liveSyncCalls.get())
    }

    @Test
    fun `coalesced waiters also get the early-refresh fallback`() = runBlocking {
        backend.lifetime = Duration.ofSeconds(10)
        val broker = broker(refreshFraction = 0.99)
        val a = broker.liveSyncCredentials()
        clock.advance(Duration.ofMillis(150))
        val gate = backend.hold(2)
        backend.failNext = true
        val callers = (1..5).map { async { runCatching { broker.liveSyncCredentials().apiKey }.getOrDefault("THREW") } }
        backend.awaitVend(2)
        gate.complete(Unit)
        assertEquals(setOf(a.apiKey), callers.awaitAll().toSet())
    }

    @Test
    fun `the fallback never returns a credential that expired during the failed vend`() = runBlocking {
        backend.lifetime = Duration.ofMillis(250)
        val broker = broker(refreshFraction = 0.99)
        broker.liveSyncCredentials()
        clock.advance(Duration.ofMillis(50)) // in the refresh window, still valid
        val gate = backend.hold(2)
        backend.failNext = true
        val caller = async { runCatching { broker.liveSyncCredentials() } }
        backend.awaitVend(2)
        clock.advance(Duration.ofMillis(300)) // expires mid-vend
        gate.complete(Unit)
        assertIs<TouchpointException>(caller.await().exceptionOrNull())
    }

    @Test
    fun `a cancelled originator strands neither the coalesced waiters nor the cache`() = runBlocking {
        val broker = broker()
        val gate = backend.hold(1)
        val originator = async { broker.liveSyncCredentials() }
        backend.awaitVend(1)
        val waiter = async { broker.liveSyncCredentials() }
        yield() // the waiter joins the in-flight vend before its originator goes
        yield()
        originator.cancel()
        gate.complete(Unit)
        assertEquals("key-1", waiter.await().apiKey)
        assertEquals("key-1", broker.liveSyncCredentials().apiKey) // cached, though its originator left
        assertEquals(1, backend.liveSyncCalls.get())
    }

    @Test
    fun `a backend that throws its own CancellationException fails the vend without cancelling the caller`() = runBlocking {
        val throwing =
            object : TouchpointBackend by backend {
                override suspend fun liveSyncCredentials(): LiveSyncCredentials =
                    withTimeout(1) { awaitCancellation() } // a host's own timeout
            }
        val broker = CredentialBroker(throwing, InMemorySecureStore(), now = clock.provider)
        val e = assertFailsWith<TouchpointException.CredentialVendingFailed> { broker.liveSyncCredentials() }
        assertEquals("TimeoutCancellationException", e.underlying)
        assertTrue(isActive, "the caller's coroutine must not have been cancelled")
    }

    @Test
    fun `a contact vend's CancellationException from the backend is a vend failure while the caller is active`() = runBlocking {
        val throwing =
            object : TouchpointBackend by backend {
                override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials =
                    throw CancellationException("the backend's own, not the caller's")
            }
        val broker = CredentialBroker(throwing, InMemorySecureStore(), now = clock.provider)
        assertFailsWith<TouchpointException.CredentialVendingFailed> { broker.startChatContact(ChatContactRequest("a")) }
        assertTrue(isActive)
    }

    @Test
    fun `cancelling the caller of a contact vend propagates as cancellation`() = runBlocking {
        val hanging =
            object : TouchpointBackend by backend {
                override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials = awaitCancellation()
            }
        val broker = CredentialBroker(hanging, InMemorySecureStore(), now = clock.provider)
        val caller = async { broker.startChatContact(ChatContactRequest("a")) }
        yield()
        caller.cancel()
        assertFailsWith<CancellationException> { caller.await() }
        Unit
    }

    @Test
    fun `contact credentials are per contact, never cached across starts`() = runBlocking {
        val broker = broker()
        val a = broker.startChatContact(ChatContactRequest("a"))
        val b = broker.startChatContact(ChatContactRequest("a"))
        assertNotEquals(a.contactId, b.contactId)
        assertEquals(2, backend.chatCalls.get())
    }

    @Test
    fun `Live Sync credentials persist to the secure store and survive a new broker`() = runBlocking {
        val store = InMemorySecureStore()
        broker(store).liveSyncCredentials()
        assertEquals("key-1", broker(store).liveSyncCredentials().apiKey)
        assertEquals(1, backend.liveSyncCalls.get())
        assertTrue(store.allAccessibility().all { it == SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY })
    }

    @Test
    fun `invalidate forces the next call to re-vend (rotation)`() = runBlocking {
        val store = InMemorySecureStore()
        val broker = broker(store)
        broker.liveSyncCredentials()
        broker.invalidateLiveSyncCredentials()
        assertNull(store.get(CredentialBroker.LIVE_SYNC_KEY))
        assertEquals("key-2", broker.liveSyncCredentials().apiKey)
    }

    @Test
    fun `a failing secure store degrades to vending, never to a failed call`() = runBlocking {
        val broken =
            object : SecureStore {
                override suspend fun get(key: String): ByteArray? = throw TouchpointException.PersistenceFailure("locked")

                override suspend fun set(key: String, value: ByteArray, accessibility: SecureStoreAccessibility) =
                    throw TouchpointException.PersistenceFailure("locked")

                override suspend fun delete(key: String) = throw TouchpointException.PersistenceFailure("locked")
            }
        val broker = broker(broken)
        assertEquals("key-1", broker.liveSyncCredentials().apiKey)
        assertEquals("contact-1", broker.startChatContact(ChatContactRequest("a")).contactId)
        broker.invalidateLiveSyncCredentials()
    }

    @Test
    fun `refresh-failure policy - chat keeps going, voice ends the contact`() {
        assertEquals(CredentialBroker.RefreshFailurePolicy.RETAIN_AND_RECONNECT, CredentialBroker.refreshFailurePolicy(Modality.CHAT))
        assertEquals(CredentialBroker.RefreshFailurePolicy.END_CONTACT, CredentialBroker.refreshFailurePolicy(Modality.VOICE))
    }

    @Test
    fun `a chat started for a custom chat client keeps no token, and resumes without one`() = runBlocking {
        val store = InMemorySecureStore()
        val tokenless = object : TouchpointBackend {
            override suspend fun startChatContact(request: ChatContactRequest) = ChatContactCredentials("c-custom", "p", null, null)
            override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = throw TouchpointException.InvalidConfiguration("chat only")
            override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.InvalidConfiguration("none")
        }
        val broker = CredentialBroker(tokenless, store)
        val c = broker.startChatContact(ChatContactRequest("a"))
        assertNull(c.participantToken)
        assertEquals(c, CredentialBroker(tokenless, store).storedChatCredentials("c-custom"))
    }

    @Test
    fun `chat credentials persist under the contact id and come back for resume`() = runBlocking {
        val store = InMemorySecureStore()
        val broker = broker(store)
        val c = broker.startChatContact(ChatContactRequest("a"))
        assertEquals(c, broker.storedChatCredentials(c.contactId))
        val later = broker(store) // a fresh broker over the same store: a process restart
        assertEquals(c, later.storedChatCredentials(c.contactId))
        later.forgetContactCredentials(c.contactId)
        assertNull(later.storedChatCredentials(c.contactId))
        assertNull(broker.storedChatCredentials("never"))
    }

    @Test
    fun `persisted contact credentials are stored when-unlocked-this-device-only`() = runBlocking {
        val store = InMemorySecureStore()
        broker(store).startChatContact(ChatContactRequest("a"))
        val classes = store.allAccessibility()
        assertFalse(classes.isEmpty())
        assertTrue(classes.all { it == SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY })
    }

    @Test
    fun `the refresh fraction is clamped and NaN disables early refresh`() = runBlocking {
        backend.lifetime = Duration.ofSeconds(10)
        val nan = broker(refreshFraction = Double.NaN)
        nan.liveSyncCredentials()
        clock.advance(Duration.ofSeconds(9))
        assertEquals("key-1", nan.liveSyncCredentials().apiKey) // still valid, no early refresh

        val below = broker(refreshFraction = -5.0) // clamped to 0: no early refresh either
        assertEquals("key-2", below.liveSyncCredentials().apiKey)
        clock.advance(Duration.ofSeconds(9))
        assertEquals("key-2", below.liveSyncCredentials().apiKey)

        val above = broker(refreshFraction = 5.0) // clamped to 1: anything short of the full lifetime refreshes
        assertEquals("key-3", above.liveSyncCredentials().apiKey)
        clock.advance(Duration.ofSeconds(1))
        assertEquals("key-4", above.liveSyncCredentials().apiKey)
    }

    @Test
    fun `an invalidate whose delete failed never hands the rejected key back`() = runBlocking {
        val inner = InMemorySecureStore()
        val undeletable =
            object : SecureStore by inner {
                override suspend fun delete(key: String) = throw TouchpointException.PersistenceFailure("locked")
            }
        val broker = broker(undeletable)
        assertEquals("key-1", broker.liveSyncCredentials().apiKey)
        broker.invalidateLiveSyncCredentials()
        assertEquals("key-2", broker.liveSyncCredentials().apiKey) // not the stored key-1
        assertEquals("key-2", broker(undeletable).liveSyncCredentials().apiKey) // and the new one was persisted over it
    }

    @Test
    fun `the in-memory secure store round-trips, overwrites and deletes`() = runBlocking {
        val store = InMemorySecureStore()
        assertNull(store.get("a"))
        store.set("a", byteArrayOf(1), SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        store.set("a", byteArrayOf(2), SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        assertEquals(listOf<Byte>(2), store.get("a")?.toList())
        store.delete("a")
        assertNull(store.get("a"))
    }
}
