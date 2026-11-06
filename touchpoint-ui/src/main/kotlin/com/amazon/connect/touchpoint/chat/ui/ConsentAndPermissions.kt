@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amazon.connect.touchpoint.core.ConsentPrompt
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.PermissionDenial
import com.amazon.connect.touchpoint.ui.R

/**
 * The SDK's consent prompt for Live Sync: what is shared, with whom, a link to the policy,
 * and two choices. It cannot be dismissed without one — Back and a tap beside it do nothing — as a
 * dismissal is neither consent nor a refusal. Drawn as web's dialog card.
 *
 * Stateless: show it while a `LiveSyncConsentController`'s `needsPrompt` is `true`, and pass its
 * `grant` and `deny`. A host whose consent copy is its legal team's draws its own instead.
 */
@Composable
public fun LiveSyncConsentSheet(
    prompt: ConsentPrompt,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    theme: TouchpointTheme = TouchpointTheme.system(),
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalTouchpointTheme provides theme, LocalDensity provides density) {
            val title = stringResource(R.string.touchpoint_consent_title)
            val metrics = TouchpointMetrics.current()
            Card(title) {
                BasicText(title, Modifier.semantics { heading() }, style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight))
                Body(stringResource(R.string.touchpoint_consent_body, prompt.dataDescription, prompt.recipient))
                // Only a web page: the policy opens in the browser, never another scheme.
                prompt.privacyPolicyUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    val uri = LocalUriHandler.current
                    TextControl(stringResource(R.string.touchpoint_consent_privacy_policy)) {
                        try {
                            uri.openUri(url)
                        } catch (_: IllegalArgumentException) {
                            // no browser: the link does nothing, as web's would
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                }
                Choices {
                    RowButton(stringResource(R.string.touchpoint_consent_allow), TouchpointIcons.Check, onAllow)
                    RowButton(stringResource(R.string.touchpoint_consent_decline), TouchpointIcons.Close, onDecline)
                }
            }
        }
    }
}

/**
 * The SDK's confirmation for a Live Sync action declared `confirmationRequired`: the assistant asked
 * for it, and nothing happens until the customer allows it. [message] is the app's words for
 * it, or the action's description. Allow or Cancel, and Back or a tap beside it is Cancel.
 */
@Composable
public fun LiveSyncActionConfirmationSheet(
    message: String,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
    theme: TouchpointTheme = TouchpointTheme.system(),
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalTouchpointTheme provides theme, LocalDensity provides density) {
            val title = stringResource(R.string.touchpoint_action_confirm_title)
            val metrics = TouchpointMetrics.current()
            Card(title) {
                BasicText(title, Modifier.semantics { heading() }, style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight))
                Body(message)
                Choices {
                    RowButton(stringResource(R.string.touchpoint_consent_allow), TouchpointIcons.Check, onAllow)
                    RowButton(stringResource(R.string.touchpoint_confirm_cancel), TouchpointIcons.Close, onCancel)
                }
            }
        }
    }
}

/**
 * Stop sharing, for a settings screen or beside the chat: shown while [granted], so the customer can
 * stop mid-conversation. Once they have, it says so — "Sharing stopped" — and TalkBack
 * reads it.
 */
@Composable
public fun LiveSyncConsentRevoke(
    granted: Boolean,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
) {
    var stopped by remember { mutableStateOf(false) }
    LaunchedEffect(granted) { if (granted) stopped = false }
    CompositionLocalProvider(LocalTouchpointTheme provides theme) {
        when {
            granted ->
                RowButton(stringResource(R.string.touchpoint_consent_revoke), TouchpointIcons.LogOut, {
                    stopped = true
                    onRevoke()
                }, modifier, error = true)
            stopped -> {
                val metrics = TouchpointMetrics.current()
                BasicText(
                    stringResource(R.string.touchpoint_consent_revoked),
                    modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight),
                )
            }
        }
    }
}

/**
 * A denied permission as a product state, not an error: what is off, what still works,
 * and the way to Settings. Voice shows it for the microphone, with Continue with chat when
 * [onContinueWithChat] is given.
 */
@Composable
public fun PermissionDenialView(
    denial: PermissionDenial,
    modifier: Modifier = Modifier,
    onContinueWithChat: (() -> Unit)? = null,
    theme: TouchpointTheme = TouchpointTheme.system(),
) {
    val context = LocalContext.current
    CompositionLocalProvider(LocalTouchpointTheme provides theme) {
        val metrics = TouchpointMetrics.current()
        val (title, body) =
            when (denial.permission) {
                Permission.MICROPHONE -> R.string.touchpoint_permission_microphone_title to R.string.touchpoint_permission_microphone_body
            }
        Column(modifier.padding(metrics.dialogPadding), verticalArrangement = Arrangement.spacedBy(metrics.sectionGap)) {
            BasicText(stringResource(title), Modifier.semantics { heading() }, style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight))
            Body(stringResource(body))
            Choices {
                if (denial.consequence == PermissionDenial.Consequence.VOICE_UNAVAILABLE_CHAT_REMAINS && onContinueWithChat != null) {
                    RowButton(stringResource(R.string.touchpoint_permission_continue_with_chat), TouchpointIcons.Send, onContinueWithChat)
                }
                RowButton(stringResource(R.string.touchpoint_permission_open_settings), TouchpointIcons.Settings, {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        // a device without the settings screen: nothing to open
                    }
                })
            }
        }
    }
}

/** Web's dialog card: max-w-sm, inset 16, radius outer, a hairline border, the panel's fill twice over. */
@Composable
private fun Card(
    title: String,
    content: @Composable () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.outerRadius)
    Column(
        Modifier
            .padding(metrics.dialogPadding)
            .widthIn(max = metrics.dialogMaxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(theme.background)
            .background(theme.background)
            .border(metrics.hairline, theme.primary10, shape)
            .padding(metrics.dialogPadding)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
    ) { content() }
}

@Composable
private fun Body(text: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    BasicText(text, style = theme.text(theme.primary, metrics.smallText, metrics.smallLineHeight))
}

@Composable
private fun Choices(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TouchpointMetrics.current().smallGap)) { content() }
}
