package com.amazon.connect.touchpoint.development

import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest

/**
 * Debug-only backend that accepts literal Live Sync keys for a non-production deployment, so the SDK
 * can be exercised before a host backend exists. **Never in a release build**: it is not published
 * to Maven Central. A host copies this file into its app's `src/debug/` source set, so referencing
 * it from release code does not compile; in this repository the module is the e2e harness's and
 * the demo's. A production host implements [TouchpointBackend] against its own service.
 *
 * Chat and voice take vendor functions because a literal participant token cannot be hard-coded —
 * `StartChatContact` must be called per contact — so the development path injects a vendor (the e2e
 * harness, or a stub) rather than pretending otherwise.
 */
public class DevelopmentBackend(
    private val liveSync: LiveSyncLiterals,
    private val chat: (suspend (ChatContactRequest) -> ChatContactCredentials)? = null,
    private val voice: (suspend (VoiceContactRequest) -> VoiceContactCredentials)? = null,
) : TouchpointBackend {
    /** The literal Live Sync keys. [toString] redacts the API key. */
    public data class LiveSyncLiterals(val deploymentKey: String, val apiKey: String) {
        override fun toString(): String = "LiveSyncLiterals(deploymentKey=$deploymentKey, apiKey=<redacted>)"
    }

    override suspend fun startChatContact(request: ChatContactRequest): ChatContactCredentials {
        val vendor = chat ?: throw TouchpointException.InvalidConfiguration("DevelopmentBackend has no chat vendor; pass `chat` to start chat contacts")
        return vendor(request)
    }

    override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials {
        val vendor = voice ?: throw TouchpointException.InvalidConfiguration("DevelopmentBackend has no voice vendor; pass `voice` to start voice contacts")
        return vendor(request)
    }

    override suspend fun liveSyncCredentials(): LiveSyncCredentials = LiveSyncCredentials(liveSync.deploymentKey, liveSync.apiKey, expiresAt = null)
}
