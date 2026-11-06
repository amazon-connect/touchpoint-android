package com.amazon.connect.touchpoint.livesync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.amazon.connect.touchpoint.chat.ui.LiveSyncConsentSheet
import kotlinx.coroutines.launch

/**
 * The customer's Live Sync consent in one line: reads the
 * earlier decision, and asks with the SDK's consent sheet only when there is none. Allow grants,
 * Decline denies, and the decision is kept per deployment ([LiveSyncConsentController]).
 *
 * Place it once, inside [TouchpointLiveSync]. For your own sheet, collect
 * [LiveSyncConsentController.needsPrompt] and call [LiveSyncConsentController.grant] or
 * [LiveSyncConsentController.deny] instead.
 */
@Composable
public fun LiveSyncConsent(consent: LiveSyncConsentController) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(consent) { consent.load() }
    val asking by consent.needsPrompt.collectAsState()
    if (asking) {
        LiveSyncConsentSheet(
            consent.prompt,
            onAllow = { scope.launch { consent.grant() } },
            onDecline = { scope.launch { consent.deny() } },
        )
    }
}
