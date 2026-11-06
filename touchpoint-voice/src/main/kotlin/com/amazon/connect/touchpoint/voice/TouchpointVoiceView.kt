@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.amazon.connect.touchpoint.chat.ui.Header
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LocalChatOptions
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.LocalWideWindow
import com.amazon.connect.touchpoint.chat.ui.Loader
import com.amazon.connect.touchpoint.chat.ui.SettingsMenu
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.WebBackground
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.PermissionDenial
import com.amazon.connect.touchpoint.core.PermissionPolicy
import com.amazon.connect.touchpoint.chat.ui.PermissionDenialView
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.TouchpointSession

/**
 * Touchpoint web's full-screen voice, for a call on [voice]: shown, it starts one — as web's does on
 * opening — asking for the microphone first, through [onStartCall] (normally
 * `session.startVoice(request)` with the host's own request). Then web's call: the orb, rippling while
 * the other side speaks; the microphone; Speakers and a red close in the header, which hangs up; and
 * its connecting, ended and failed screens, with Retry. Restart hangs up and calls again.
 *
 * The settings hold Restart alone, as the guide's: the header's red close is how a call ends.
 *
 * @param onClose The header's close: the call is hung up first, as web's collapse does. `null` hides it.
 * @param onContactEnded Once when a call this view is showing ends — not for Restart's.
 * @param permissionPolicy Whether the view may ask for the microphone. By default it does not, as
 *   is intended: the host asks, at the moment it chooses. Without the microphone the view shows
 *   that it is off, with the way to Settings, and calls once it is on.
 * @param onContinueWithChat Offered beside Settings when the microphone is off: chat still works.
 * @param onMinimise Minimise, an addition web has no need of: a ghost control beside the red
 *   close while the call connects or is live. It hands the call to the host, which takes the view
 *   away; the call goes on (the host's rail shows it) and this view tells no end of it. `null` hides it.
 */
@Composable
public fun TouchpointVoiceView(
    session: TouchpointSession,
    voice: ConnectVoiceTransport,
    onStartCall: suspend () -> Unit,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    onClose: (() -> Unit)? = null,
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    permissionPolicy: PermissionPolicy = PermissionPolicy.NEVER,
    onContinueWithChat: (() -> Unit)? = null,
    onMinimise: (() -> Unit)? = null,
): Unit = VoiceView(session, voice, onStartCall, modifier, theme, onClose, options, onContactEnded, permissionPolicy, onContinueWithChat, adopted = false, onMinimise)

/**
 * The full-screen voice, over a [call] the host holds — the rail's More, say — adopting a
 * running call: it starts none of its own, and leaving it (Back, or the host taking it away) leaves
 * the call running, for the rail to show again. Its red close still hangs up first, as web's and
 * the rail's do. Restart and Retry call again on [call]. The other parameters are [TouchpointVoiceView]'s.
 */
@Composable
public fun TouchpointVoiceView(
    call: TouchpointVoiceCall,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    onClose: (() -> Unit)? = null,
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    onMinimise: (() -> Unit)? = null,
): Unit = VoiceView(call.session, call.voice, call::startContact, modifier, theme, onClose, options, onContactEnded, call.permissionPolicy, null, adopted = true, onMinimise)

@Composable
private fun VoiceView(
    session: TouchpointSession,
    voice: ConnectVoiceTransport,
    onStartCall: suspend () -> Unit,
    modifier: Modifier,
    theme: TouchpointTheme,
    onClose: (() -> Unit)?,
    options: TouchpointChatOptions,
    onContactEnded: ((EndReason) -> Unit)?,
    permissionPolicy: PermissionPolicy,
    onContinueWithChat: (() -> Unit)?,
    adopted: Boolean,
    onMinimise: (() -> Unit)?,
) {
    val state by session.state.collectAsState()
    val call by voice.call.collectAsState()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val control = rememberCallControl(session, voice, onStartCall, onContactEnded, permissionPolicy, adopted = adopted)
    fun call(hangUpFirst: Boolean) {
        menuOpen = false
        control.call(hangUpFirst)
    }
    fun hangUp() = control.hangUp()
    val failed = control.failed
    val microphoneOff = control.microphoneOff
    BackHandler(enabled = menuOpen) { menuOpen = false }

    val window = LocalConfiguration.current.screenWidthDp.dp
    CompositionLocalProvider(
        LocalTouchpointTheme provides theme,
        LocalChatOptions provides options,
        LocalWideWindow provides (window >= TouchpointMetrics.MEDIUM_WINDOW),
    ) {
        Box(modifier.fillMaxSize()) {
            WebBackground(Modifier.fillMaxSize(), depth = options.backgroundDepthLayer)
            // Edge to edge, the background runs under the system bars and the controls keep clear of
            // them; insets a host's layout has already taken are not taken twice.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Header(
                    brand = options.brandIcon,
                    menuOpen = menuOpen,
                    onRestart = { call(hangUpFirst = true) },
                    onToggleMenu = { menuOpen = !menuOpen },
                    onClose = onClose?.let { close -> { hangUp(); close() } },
                    // While it connects or is live: the call is handed to the host, which takes the view away.
                    onMinimise =
                        onMinimise?.takeIf { state == ContactState.Connecting || state == ContactState.Connected || state is ContactState.Reconnecting }?.let { minimise ->
                            {
                                control.handOver()
                                minimise()
                            }
                        },
                    // The guide's call header: Speakers sits by the microphone, not here.
                    closeIsError = true,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val live = (state == ContactState.Connected || state is ContactState.Reconnecting) && session.modality == Modality.VOICE
                    when {
                        // Web keeps the call mounted under its settings; here it simply goes on.
                        menuOpen ->
                            SettingsMenu(
                                onRestart = { call(hangUpFirst = true) },
                                onDownload = null, // web hides Download in a call
                            )
                        microphoneOff -> PermissionDenialView(PermissionDenial(Permission.MICROPHONE), onContinueWithChat = onContinueWithChat, theme = theme)
                        failed -> CallFailed(onRetry = { call(hangUpFirst = true) })
                        state is ContactState.Ended -> CallEnded()
                        live ->
                            LiveCall(
                                call, options.brandIcon, onMicrophone = { voice.setMuted(!call.muted) }, onSpeakers = { voice.setSpeakersOn(!call.speakersOn) },
                                onShareScreen = { confirmShare = true }, onStopSharing = voice::stopScreenShare,
                            )
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Loader() }
                    }
                }
            }
            CallAnnouncer(state, call, failed = failed, modality = session.modality)
            if (confirmShare) {
                ShareConfirmation(
                    onShare = {
                        confirmShare = false
                        activity?.window?.let(voice::startScreenShare)
                    },
                    onCancel = { confirmShare = false },
                )
            }
        }
    }
}
