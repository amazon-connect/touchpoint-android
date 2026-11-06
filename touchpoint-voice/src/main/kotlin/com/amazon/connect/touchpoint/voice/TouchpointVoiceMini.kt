@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.amazon.connect.touchpoint.chat.ui.BrandImage
import com.amazon.connect.touchpoint.chat.ui.Glyph
import com.amazon.connect.touchpoint.chat.ui.IconButton
import com.amazon.connect.touchpoint.chat.ui.IconKind
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LocalChatOptions
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.LocalWideWindow
import com.amazon.connect.touchpoint.chat.ui.Loader
import com.amazon.connect.touchpoint.chat.ui.PermissionDenialView
import com.amazon.connect.touchpoint.chat.ui.TextControl
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions
import com.amazon.connect.touchpoint.chat.ui.TouchpointIcons
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.text
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.Permission
import com.amazon.connect.touchpoint.core.PermissionDenial
import com.amazon.connect.touchpoint.core.PermissionPolicy
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.ui.R
import kotlin.math.roundToInt

/**
 * The guide's voice mini (web's `voiceMini`): the call as a small widget over the host's screen, in
 * the chat's place rather than inside it, its controls in a column on a phone and a row from web's
 * `md:` — the handle, the brand, the microphone and Speakers, and a red Close, each wearing its
 * side's volume ring while it speaks. Shown, it calls, as [TouchpointVoiceView] does;
 * Close hangs up and calls [onClose]. While it connects, web's loader; a call that never started,
 * "I couldn't connect" with Retry; an ended one, "The conversation has ended."
 *
 * Draggable (the default), it is a layer over whatever space it is given — put it over the whole
 * screen — anchored where web anchors it, the bottom end, 8 in; it moves from its handle and stays 8
 * inside every edge, as web's does, and the layer takes no touches but the widget's. [draggable]
 * `false` for one embedded in the host's own layout.
 *
 * @param permissionPolicy and [onContinueWithChat]: as [TouchpointVoiceView]'s.
 */
@Composable
public fun TouchpointVoiceMini(
    session: TouchpointSession,
    voice: ConnectVoiceTransport,
    onStartCall: suspend () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    permissionPolicy: PermissionPolicy = PermissionPolicy.NEVER,
    onContinueWithChat: (() -> Unit)? = null,
    draggable: Boolean = true,
): Unit = VoiceMini(session, voice, onStartCall, onClose, modifier, theme, options, onContactEnded, permissionPolicy, onContinueWithChat, draggable, adopted = false)

/**
 * The mini voice over a [call] the host holds, as the full-screen [TouchpointVoiceView] over one: it
 * starts none of its own, and going away leaves the call running. Its red close still hangs up
 * first. The other parameters are [TouchpointVoiceMini]'s.
 */
@Composable
public fun TouchpointVoiceMini(
    call: TouchpointVoiceCall,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    draggable: Boolean = true,
): Unit = VoiceMini(call.session, call.voice, call::startContact, onClose, modifier, theme, options, onContactEnded, PermissionPolicy.NEVER, null, draggable, adopted = true)

@Composable
private fun VoiceMini(
    session: TouchpointSession,
    voice: ConnectVoiceTransport,
    onStartCall: suspend () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    theme: TouchpointTheme,
    options: TouchpointChatOptions,
    onContactEnded: ((EndReason) -> Unit)?,
    permissionPolicy: PermissionPolicy,
    onContinueWithChat: (() -> Unit)?,
    draggable: Boolean,
    adopted: Boolean,
) {
    val state by session.state.collectAsState()
    val call by voice.call.collectAsState()
    val control = rememberCallControl(session, voice, onStartCall, onContactEnded, permissionPolicy, adopted)
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val close = {
        control.hangUp()
        onClose()
    }
    val window = LocalConfiguration.current.screenWidthDp.dp
    CompositionLocalProvider(
        LocalTouchpointTheme provides theme,
        LocalChatOptions provides options,
        LocalWideWindow provides (window >= TouchpointMetrics.MEDIUM_WINDOW),
    ) {
        val wide = LocalWideWindow.current
        val live = (state == ContactState.Connected || state is ContactState.Reconnecting) && session.modality == Modality.VOICE
        val mini: @Composable (@Composable () -> Unit) -> Unit = { handle ->
            when {
                control.microphoneOff ->
                    Panel(onClose = close) {
                        PermissionDenialView(PermissionDenial(Permission.MICROPHONE), onContinueWithChat = onContinueWithChat, theme = theme)
                    }
                control.failed ->
                    Panel(onClose = close) {
                        MiniError(stringResource(R.string.touchpoint_voice_mini_error))
                        TextControl(stringResource(R.string.touchpoint_voice_retry)) { control.call(true) }
                    }
                state is ContactState.Ended -> Panel(onClose = close) { MiniText(stringResource(R.string.touchpoint_voice_mini_ended)) }
                else ->
                    Controls(wide, handle) {
                        options.brandIcon?.let { BrandImage(it, TouchpointMetrics.brandIcon) }
                        CallButtons(live, call, voice, vertical = !wide, onShare = { confirmShare = true }, onClose = close, closeLabel = stringResource(R.string.touchpoint_mini_close))
                    }
            }
        }
        Box {
            if (draggable) DragLayer(Alignment.BottomEnd, modifier) { handle -> mini(handle) } else Box(modifier) { mini {} }
            CallAnnouncer(state, call, failed = control.failed, modality = session.modality)
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

/**
 * The guide's voice mini: the panel's fill and border, radius outer, under its Float shadow; 8 around
 * and 12 between its 40 controls (each in its 48 target); a column on a phone, a row from `md:`.
 */
@Composable
internal fun Controls(
    wide: Boolean,
    handle: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.outerRadius)
    val surface =
        Modifier
            .dropShadow(shape, Shadow(TouchpointMetrics.floatingShadow, Color.Black.copy(alpha = TouchpointMetrics.FLOATING_SHADOW_ALPHA)))
            .clip(shape)
            .background(theme.background)
            .background(theme.background)
            .border(metrics.hairline, theme.primary10, shape)
            .padding(metrics.aroundTarget)
    if (wide) {
        Row(surface, horizontalArrangement = Arrangement.spacedBy(metrics.tinyGap), verticalAlignment = Alignment.CenterVertically) {
            handle()
            content()
        }
    } else {
        Column(surface, verticalArrangement = Arrangement.spacedBy(metrics.tinyGap), horizontalAlignment = Alignment.CenterHorizontally) {
            handle()
            content()
        }
    }
}

/**
 * The guide's call controls, the mini's and the rail's alike: the microphone and Speakers side by
 * side, subtle buttons each wearing its side's volume ring while it speaks; screen sharing once the
 * agent offers it; then the red close. While the call connects, the guide's loading dot where the
 * microphone and Speakers will be, and no label. **Kept beyond the guide:** the close while it
 * connects, so a call that is slow to connect can still be hung up.
 */
@Composable
internal fun CallButtons(
    live: Boolean,
    call: VoiceCallState,
    voice: ConnectVoiceTransport,
    vertical: Boolean,
    onShare: () -> Unit,
    onClose: () -> Unit,
    closeLabel: String,
) {
    val metrics = TouchpointMetrics.current()
    val ring = Modifier.requiredSize(metrics.buttonSize * 2)
    if (live) {
        Box(contentAlignment = Alignment.Center) {
            if (call.userSpeaking) VolumeRing(call.userVolume, ring)
            IconButton(
                if (call.muted) TouchpointIcons.MicOff else TouchpointIcons.Mic,
                stringResource(R.string.touchpoint_voice_mini_microphone),
                { voice.setMuted(!call.muted) },
                kind = IconKind.SUBTLE,
                state = stringResource(if (call.muted) R.string.touchpoint_voice_off else R.string.touchpoint_voice_on),
            )
        }
        Box(contentAlignment = Alignment.Center) {
            if (call.agentSpeaking) VolumeRing(call.agentVolume, ring)
            IconButton(
                if (call.speakersOn) TouchpointIcons.Volume else TouchpointIcons.VolumeOff,
                stringResource(R.string.touchpoint_voice_speakers),
                { voice.setSpeakersOn(!call.speakersOn) },
                kind = IconKind.SUBTLE,
                state = stringResource(if (call.speakersOn) R.string.touchpoint_voice_on else R.string.touchpoint_voice_off),
            )
        }
        ShareScreenButton(call.screenShare, onShare, voice::stopScreenShare)
    } else {
        // The two controls' room, the dot in its middle, so the widget keeps its size as it connects.
        val two = metrics.minimumTouchTarget * 2 + metrics.tinyGap
        Box(
            Modifier.size(if (vertical) metrics.minimumTouchTarget else two, if (vertical) two else metrics.minimumTouchTarget).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) { Loader() }
    }
    IconButton(TouchpointIcons.Close, closeLabel, onClose, kind = IconKind.ERROR, clickLabel = stringResource(R.string.touchpoint_call_end_action))
}

/** Web's VoiceMiniPanel: up to 360 wide, its close on the right, then what it has to say. */
@Composable
private fun Panel(
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.outerRadius)
    Column(
        Modifier
            .widthIn(max = TouchpointMetrics.voiceMiniPanelMaxWidth)
            .clip(shape)
            .background(theme.background)
            .background(theme.background)
            .border(metrics.hairline, theme.primary10, shape)
            .padding(metrics.smallGap),
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(TouchpointIcons.Close, stringResource(R.string.touchpoint_mini_close), onClose)
        }
        content()
    }
}

/** Web's error Banner (1.0.5-6), as its voice mini shows "I couldn't connect". */
@Composable
private fun MiniError(text: String) = com.amazon.connect.touchpoint.chat.ui.TouchpointBanner(com.amazon.connect.touchpoint.chat.ui.TouchpointBannerType.ERROR, text)

@Composable
private fun MiniText(text: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    BasicText(text, Modifier.padding(horizontal = metrics.smallGap), style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight))
}
