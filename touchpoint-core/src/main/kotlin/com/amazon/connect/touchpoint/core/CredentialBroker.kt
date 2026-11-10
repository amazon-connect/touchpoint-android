package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * What Live Sync needs from a credential provider: a current key pair, and a way to say the one it was
 * given no longer works. [CredentialBroker] is the production implementation; a test fake is the
 * other. Declared in Core so `touchpoint-livesync` depends on the seam, not on the broker's surface.
 */
public interface LiveSyncCredentialSource {
    public suspend fun liveSyncCredentials(): LiveSyncCredentials

    public suspend fun invalidateLiveSyncCredentials()
}

/**
 * Sits between the SDK and the host's [TouchpointBackend].
 *
 * - Live Sync credentials are **cached**: coalesced across concurrent callers, refreshed when less
 *   than `refreshFraction` of their lifetime remains, and persisted only to the [SecureStore] with
 *   [SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY].
 * - Contact credentials (chat, voice) are **per contact** and are never cached for *starting*: each
 *   start mints a new contact. Chat credentials are **persisted under their contact id** so
 *   `resume(contactId)` can reconnect after process death; they are forgotten when the contact ends
 *   (a dead token has no business staying in the secure store).
 *
 * A Live Sync vend runs in the broker's own scope rather than the caller's coroutine, because one vend
 * serves every coalesced caller; the caller's context elements do not reach the backend call. Contact
 * vends run in the caller's coroutine.
 *
 * @param refreshFraction Refresh when *less than* this fraction of the credential's lifetime remains.
 *   `0.2` means "refresh in the last 20%". `0` disables early refresh; an expired credential is never
 *   returned from the cache (a backend that vends one already expired is believed).
 */
public class CredentialBroker(
    private val backend: TouchpointBackend,
    private val secureStore: SecureStore,
    refreshFraction: Double = 0.2,
    private val now: () -> Instant = { Instant.now() },
) : LiveSyncCredentialSource {
    /** What a modality does when a mid-session refresh fails. */
    public enum class RefreshFailurePolicy {
        /** Chat: emit `Failed(CredentialVendingFailed)`, go to `Reconnecting`, keep the outbox. */
        RETAIN_AND_RECONNECT,

        /** Voice: a dead Chime meeting cannot be rejoined on new credentials; end the contact. */
        END_CONTACT,
    }

    public companion object {
        public fun refreshFailurePolicy(modality: Modality): RefreshFailurePolicy =
            when (modality) {
                Modality.CHAT -> RefreshFailurePolicy.RETAIN_AND_RECONNECT
                Modality.VOICE -> RefreshFailurePolicy.END_CONTACT
            }

        internal const val LIVE_SYNC_KEY: String = "livesync.credentials.v1"

        internal fun contactKey(contactId: String): String = "contact.chat.v1.$contactId"

        private val json = Json { ignoreUnknownKeys = true }

        /** Only the type is kept: an underlying message could carry a URL with a key in it. */
        internal fun map(error: Throwable): TouchpointException =
            error as? TouchpointException ?: TouchpointException.CredentialVendingFailed(error.javaClass.simpleName.ifEmpty { error.javaClass.name })
    }

    private val refreshFraction: Double = if (refreshFraction.isNaN()) 0.0 else refreshFraction.coerceIn(0.0, 1.0)
    private val mutex = Mutex()

    // Vends run here rather than in the caller, so a caller cancelled mid-vend can neither strand the
    // coalesced waiters nor leave a successful result uncached. Never cancelled. The handler only
    // ever sees a JVM Error, after `startVend` has already failed every waiter; it exists so that
    // nothing escaping a vend can reach the host's uncaught-exception handler.
    private val vendScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("touchpoint-credential-broker") + CoroutineExceptionHandler { _, _ -> },
        )

    private var liveSync: Cached? = null

    /**
     * Set by an invalidate and cleared by the next vend that caches: while set, the stored copy is
     * never reloaded, so an item whose delete failed cannot hand the rejected key back.
     */
    private var storedCopyRejected = false
    private var inFlight: CompletableDeferred<LiveSyncCredentials>? = null
    private var generation = 0L

    // Contact credentials — pass-through, error-mapped

    public suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials {
        val credentials = vend { backend.startChatContact(request) }
        persistChat(credentials)
        return credentials
    }

    /** The persisted chat credentials for a contact started on this device, if still held. */
    public suspend fun storedChatCredentials(contactId: String): ChatContactCredentials? {
        val data = ignoringFailure { secureStore.get(contactKey(contactId)) } ?: return null
        return ignoringFailure {
            val p = json.decodeFromString(PersistedChat.serializer(), data.decodeToString())
            ChatContactCredentials(p.contactId, p.participantId, p.participantToken, p.expiresAt?.let(Instant::parse))
        }
    }

    public suspend fun forgetContactCredentials(contactId: String) {
        ignoringFailure { secureStore.delete(contactKey(contactId)) }
    }

    public suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = vend { backend.startVoiceContact(request) }

    // Live Sync credentials — cached and coalesced

    override suspend fun liveSyncCredentials(): LiveSyncCredentials {
        val pending =
            mutex.withLock {
                if (liveSync == null && !storedCopyRejected) liveSync = loadFromStore()
                liveSync?.let { if (isFresh(it)) return it.value }
                inFlight ?: startVend().also { inFlight = it }
            }
        // Every waiter — originator or coalesced — receives the same mapped error, and every waiter
        // gets the same early-refresh fallback: a refresh failure must not turn a credential that is
        // still valid into a failed call. The fallback is re-read at failure time, so a credential that
        // expired during the vend, or was invalidated during it, is never returned.
        return try {
            pending.await()
        } catch (e: TouchpointException) {
            mutex.withLock { stillValidCachedValue(e) }
        }
    }

    /**
     * Drop the cached Live Sync credentials so the next call re-vends. Used on deliberate rotation; the
     * transport reconnects to pick the new key up. A vend already in flight still returns
     * its result to its callers, but that result is never cached, and callers arriving after this
     * returns start a fresh vend rather than joining the stale one.
     */
    override suspend fun invalidateLiveSyncCredentials() {
        mutex.withLock {
            generation += 1
            liveSync = null
            inFlight = null
            storedCopyRejected = true
            // Inside the lock, so a concurrent caller cannot reload the item while it is being deleted.
            ignoringFailure { secureStore.delete(LIVE_SYNC_KEY) }
        }
    }

    // Internals

    private data class Cached(val value: LiveSyncCredentials, val issuedAt: Instant)

    /** Persisted form. A plain class, so its default toString cannot print the key. */
    @Serializable
    private class PersistedLiveSync(
        val deploymentKey: String,
        val apiKey: String,
        val expiresAt: String?,
        val issuedAt: String,
    )

    /**
     * Private mirror of [ChatContactCredentials], which deliberately has no serializer: the one
     * place that writes a participant token anywhere — the secure store, never a log — does so explicitly.
     */
    @Serializable
    private class PersistedChat(
        val contactId: String,
        val participantId: String,
        val participantToken: String? = null,
        val expiresAt: String?,
    )

    private suspend fun persistChat(c: ChatContactCredentials) {
        val p = PersistedChat(c.contactId, c.participantId, c.participantToken, c.expiresAt?.toString())
        val data = json.encodeToString(PersistedChat.serializer(), p).encodeToByteArray()
        ignoringFailure { secureStore.set(contactKey(c.contactId), data, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY) }
    }

    /** Called with [mutex] held. */
    private fun startVend(): CompletableDeferred<LiveSyncCredentials> {
        val result = CompletableDeferred<LiveSyncCredentials>()
        val vendGeneration = generation
        vendScope.launch {
            // Whatever happens below, every waiter is released: `finally` always completes `result`.
            var outcome: Result<LiveSyncCredentials> = Result.failure(TouchpointException.CredentialVendingFailed("vend did not complete"))
            try {
                // Any exception from the host's backend is a vend failure — including a
                // CancellationException it raised itself (its own withTimeout, say). This coroutine is
                // never cancelled, so such an exception cannot be ours to propagate.
                val fresh = backend.liveSyncCredentials()
                mutex.withLock {
                    // An invalidate that raced this vend bumps the generation; the stale result is
                    // returned to its callers but never cached.
                    if (vendGeneration == generation) {
                        val cached = Cached(fresh, now())
                        liveSync = cached
                        storedCopyRejected = false
                        persist(cached)
                    }
                }
                outcome = Result.success(fresh)
            } catch (e: Exception) {
                outcome = Result.failure(map(e))
            } finally {
                withContext(NonCancellable) { mutex.withLock { if (inFlight === result) inFlight = null } }
                outcome.fold(result::complete, result::completeExceptionally)
            }
        }
        return result
    }

    /** Early-refresh failure path: the cached credential if it is still unexpired *now*, else rethrow. */
    private fun stillValidCachedValue(error: TouchpointException): LiveSyncCredentials {
        val cached = liveSync
        if (cached != null && !isExpired(cached)) return cached.value
        throw error
    }

    private fun isExpired(cached: Cached): Boolean {
        val expiresAt = cached.value.expiresAt ?: return false
        return !now().isBefore(expiresAt)
    }

    private fun isFresh(cached: Cached): Boolean {
        val expiresAt = cached.value.expiresAt ?: return true
        val t = now()
        if (!t.isBefore(expiresAt)) return false
        val lifetime = Duration.between(cached.issuedAt, expiresAt).toSecondsDouble()
        if (lifetime <= 0) return false
        val remainingFraction = Duration.between(t, expiresAt).toSecondsDouble() / lifetime
        return remainingFraction >= refreshFraction
    }

    private suspend fun loadFromStore(): Cached? {
        val data = ignoringFailure { secureStore.get(LIVE_SYNC_KEY) } ?: return null
        return ignoringFailure {
            val p = json.decodeFromString(PersistedLiveSync.serializer(), data.decodeToString())
            Cached(
                LiveSyncCredentials(p.deploymentKey, p.apiKey, p.expiresAt?.let(Instant::parse)),
                Instant.parse(p.issuedAt),
            )
        }
    }

    private suspend fun persist(cached: Cached) {
        val p = PersistedLiveSync(cached.value.deploymentKey, cached.value.apiKey, cached.value.expiresAt?.toString(), cached.issuedAt.toString())
        val data = json.encodeToString(PersistedLiveSync.serializer(), p).encodeToByteArray()
        ignoringFailure { secureStore.set(LIVE_SYNC_KEY, data, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY) }
    }

    /**
     * A `CancellationException` is rethrown only when it is *ours* — the calling coroutine was
     * cancelled. One raised by the host's backend while the caller is still active (its own
     * `withTimeout`, say) is a vend failure: passing it through would tell the host its coroutine was
     * cancelled when it was not.
     */
    private suspend fun <T> vend(body: suspend () -> T): T =
        try {
            body()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            throw map(e)
        } catch (e: Exception) {
            throw map(e)
        }
}

/** Seconds as a Double: `toNanos()`/`toMillis()` overflow for far-future expiries. */
internal fun Duration.toSecondsDouble(): Double = seconds.toDouble() + nano / 1_000_000_000.0

/**
 * Runs [block], turning any failure into `null` — except cancellation, which is control flow and is
 * always rethrown.
 */
internal inline fun <T> ignoringFailure(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
