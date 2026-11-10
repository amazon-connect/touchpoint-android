package com.amazon.connect.touchpoint.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Consent

/**
 * What the consent disclosure tells the customer: what is shared, with whom, and where to read
 * more. The SDK draws a default presentation from it; a host whose copy is reviewed by its own legal
 * team draws its own and reads these same fields. A privacy policy alone is not disclosure: the
 * customer is told in the app, before anything is sent.
 */
public data class ConsentPrompt(
    /** What is shared, in plain language: "which screen you are on and what the app can do there". */
    val dataDescription: String,
    /** Who receives it: "Acme Bank's assistant, run by Amazon Connect". */
    val recipient: String,
    /** An `https` link to the policy, opened in the browser. `null` shows none. */
    val privacyPolicyUrl: String? = null,
)

/**
 * The customer's Live Sync consent decision, kept per deployment so the prompt is shown once and a
 * revocation survives a relaunch. In the [SecureStore] (the Keystore on Android) rather than shared
 * preferences: a decision about sharing data with a third party does not travel in a backup to
 * another device.
 *
 * @param deploymentId the deployment the decision is for: a different assistant asks again.
 */
public class ConsentStore(
    private val secure: SecureStore,
    private val deploymentId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public enum class Decision { GRANTED, DENIED }

    @Serializable
    private class Record(val decision: Decision, val decidedAt: Long)

    private val key: String get() = "livesync.consent.v1.$deploymentId"

    /** The stored decision, or `null` if the customer has not been asked for this deployment. */
    public suspend fun decision(): Decision? = record()?.decision

    /** When it was decided, in epoch milliseconds; `null` when it has not been. */
    public suspend fun decidedAt(): Long? = record()?.decidedAt

    public suspend fun record(decision: Decision) {
        val bytes = Json.encodeToString(Record.serializer(), Record(decision, now())).encodeToByteArray()
        try {
            secure.set(key, bytes, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
        } catch (e: Exception) {
            // Not kept, the customer is asked again next time: never a crash.
            rethrowOwnCancellation(e)
        }
    }

    /** Forgets the decision, so the prompt is shown again: a host's "Ask me again". */
    public suspend fun reset() {
        try {
            secure.delete(key)
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
        }
    }

    private suspend fun record(): Record? =
        try {
            secure.get(key)?.let { Json.decodeFromString(Record.serializer(), it.decodeToString()) }
        } catch (e: Exception) {
            rethrowOwnCancellation(e)
            null // unreadable: not decided
        }
}

// Permission denial as a product state

/** Where a permission stands, as the SDK sees it. */
public enum class PermissionStatus {
    /** Not granted, and the SDK may not ask: see [PermissionPolicy]. */
    NOT_DETERMINED,
    GRANTED,

    /** Refused. Settings is the way back. */
    DENIED,
}

/**
 * The product consequence of a denied permission, so a host shows a state rather than a failure:
 * voice gives way to chat.
 */
public data class PermissionDenial(val permission: Permission) {
    public enum class Consequence {
        /** Microphone: calling is unavailable; chat remains. */
        VOICE_UNAVAILABLE_CHAT_REMAINS,
    }

    val consequence: Consequence
        get() =
            when (permission) {
                Permission.MICROPHONE -> Consequence.VOICE_UNAVAILABLE_CHAT_REMAINS
            }
}

/**
 * Whether the SDK may ask the system for a permission. **By default, never:** when to ask decides
 * whether it is granted, so that moment is the host's. The SDK asks only for what the host
 * has listed here.
 */
public data class PermissionPolicy(val mayRequest: Set<Permission> = emptySet()) {
    public fun allows(permission: Permission): Boolean = permission in mayRequest

    public companion object {
        public val NEVER: PermissionPolicy = PermissionPolicy()
    }
}
