@file:OptIn(com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.ConsentPrompt
import com.amazon.connect.touchpoint.core.ConsentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The customer's consent for one [LiveSyncClient]: reads the decision kept for the
 * deployment, says when the customer must be asked, and applies their answer to the client and the
 * store. The gate itself is the client's; this is what produces a real decision.
 *
 * Headless, as the module is. Draw [prompt] with the UI kit's `LiveSyncConsentSheet` while
 * [needsPrompt] is `true`, or with the host's own view, which calls [grant] or [deny]:
 *
 * ```
 * val asking by consent.needsPrompt.collectAsState()
 * LaunchedEffect(consent) { consent.load() }
 * if (asking) LiveSyncConsentSheet(consent.prompt, onAllow = { scope.launch { consent.grant() } }, onDecline = { scope.launch { consent.deny() } })
 * ```
 *
 * Stopping is the UI kit's `LiveSyncConsentRevoke`, which also says that sharing stopped.
 */
public class LiveSyncConsentController(
    private val client: LiveSyncClient,
    private val store: ConsentStore,
    public val prompt: ConsentPrompt,
) {
    private val asking = MutableStateFlow(false)
    private val current = MutableStateFlow(client.consent)

    /** `true` while the customer has not decided for this deployment and must be asked. */
    public val needsPrompt: StateFlow<Boolean> = asking.asStateFlow()

    /** The decision in effect, mirrored from the client. */
    public val decision: StateFlow<ConsentDecision> = current.asStateFlow()

    /**
     * Applies the stored decision. Call once after making the client, and before [LiveSyncClient.connect]
     * if the socket should wait for a grant. With nothing stored, [needsPrompt] becomes `true` and the
     * client stays held: nothing is posted.
     */
    public suspend fun load() {
        if (client.consent != ConsentDecision.HELD) {
            current.value = client.consent
            return
        }
        when (store.decision()) {
            ConsentStore.Decision.GRANTED -> apply(ConsentDecision.GRANTED)
            ConsentStore.Decision.DENIED -> apply(ConsentDecision.DENIED)
            null -> asking.value = true
        }
    }

    /** The customer agreed. Kept; the client posts what it was holding. */
    public suspend fun grant() {
        store.record(ConsentStore.Decision.GRANTED)
        apply(ConsentDecision.GRANTED)
        asking.value = false
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncConsentChanged(granted = true))
    }

    /**
     * The customer declined, or stopped sharing. Kept; the client closes its socket and forgets what
     * it advertised, at once. Chat and voice go on.
     *
     * @return whether this stopped sharing that was on: the moment to say "Sharing stopped".
     */
    public suspend fun deny(): Boolean {
        val wasGranted = current.value == ConsentDecision.GRANTED
        store.record(ConsentStore.Decision.DENIED)
        apply(ConsentDecision.DENIED)
        asking.value = false
        TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncConsentChanged(granted = false))
        return wasGranted
    }

    /** Forgets the decision and asks again: a host's "Ask me again". */
    public suspend fun reset() {
        store.reset()
        apply(ConsentDecision.HELD)
        asking.value = true
    }

    private suspend fun apply(decision: ConsentDecision) {
        client.setConsent(decision)
        current.value = decision
    }
}
