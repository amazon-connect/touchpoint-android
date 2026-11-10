package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A clock the test moves by hand, so expiry and refresh windows need no sleeping. */
class FakeClock(var now: Instant = Instant.parse("2026-10-13T12:00:00Z")) {
    fun advance(d: Duration) {
        now = now.plus(d)
    }

    val provider: () -> Instant = { now }
}

/**
 * A scripted backend: counts calls, fails on demand, vends credentials with a chosen lifetime, and can
 * hold the n-th Live Sync vend open until the test releases it — which is how coalescing and races
 * are made deterministic rather than timed.
 */
class ScriptedBackend(private val clock: FakeClock = FakeClock()) : TouchpointBackend {
    val chatCalls = AtomicInteger()
    val voiceCalls = AtomicInteger()
    val liveSyncCalls = AtomicInteger()

    @Volatile var failNext = false

    @Volatile var lifetime: Duration = Duration.ofSeconds(600)

    /** Vend number (1-based) → a gate that vend waits on. */
    val holds = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /** Receives each Live Sync vend's number as it starts. */
    val started = Channel<Int>(Channel.UNLIMITED)

    fun hold(vend: Int): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { holds[vend] = it }

    /** Suspends until Live Sync vend number [vend] has started, discarding the signals of earlier ones. */
    suspend fun awaitVend(vend: Int) {
        while (started.receive() < vend) Unit
    }

    private fun failIfAsked() {
        if (failNext) {
            failNext = false
            throw IOException("not connected to the internet")
        }
    }

    override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials {
        val n = chatCalls.incrementAndGet()
        failIfAsked()
        return ChatContactCredentials("contact-$n", "p", "tok-$n", clock.now.plus(lifetime))
    }

    override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials {
        val n = voiceCalls.incrementAndGet()
        failIfAsked()
        return VoiceContactCredentials("vcontact-$n", "p", "vtok-$n", "{}", "{}", clock.now.plus(lifetime))
    }

    override suspend fun liveSyncCredentials(): LiveSyncCredentials {
        val n = liveSyncCalls.incrementAndGet()
        started.trySend(n)
        holds[n]?.await()
        failIfAsked()
        return LiveSyncCredentials("dep", "key-$n", clock.now.plus(lifetime))
    }
}
