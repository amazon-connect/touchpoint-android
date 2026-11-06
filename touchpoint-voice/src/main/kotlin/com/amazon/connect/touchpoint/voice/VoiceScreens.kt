@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.Modality
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.amazon.connect.touchpoint.chat.ui.Glyph
import com.amazon.connect.touchpoint.chat.ui.IconButton
import com.amazon.connect.touchpoint.chat.ui.IconKind
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.RowButton
import com.amazon.connect.touchpoint.chat.ui.TouchpointIcons
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.chat.ui.text
import com.amazon.connect.touchpoint.ui.R

/**
 * The guide's live call: the orb in the middle, wearing the other side's volume ring while it speaks;
 * the microphone and Speakers side by side under it, subtle buttons, the microphone wearing the
 * customer's ring. One mobile need the guide has no place for: a line under the orb while the call
 * reconnects or is on hold for another. Where the call is heard is left to the system (its output
 * switcher, a headset) or the host ([ConnectVoiceTransport.setOutput]), as the guide has no control
 * for it.
 */
@Composable
internal fun LiveCall(
    call: VoiceCallState,
    brand: Painter?,
    onMicrophone: () -> Unit,
    onSpeakers: () -> Unit = {},
    onShareScreen: (() -> Unit)? = null,
    onStopSharing: () -> Unit = {},
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (call.agentSpeaking) VolumeRing(call.agentVolume, Modifier.requiredSize(TouchpointMetrics.voiceOrb * 2))
            Orb(brand)
            val status =
                when {
                    call.reconnecting -> stringResource(R.string.touchpoint_call_reconnecting)
                    call.interrupted -> stringResource(R.string.touchpoint_call_interrupted)
                    else -> null
                }
            // Spoken when it changes by the view's announcer; here for the eye, and for TalkBack to find.
            if (status != null) {
                BasicText(
                    status,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = metrics.sectionGap),
                    style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = metrics.voiceBarPadding), horizontalArrangement = Arrangement.spacedBy(metrics.smallGap, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                if (call.userSpeaking) VolumeRing(call.userVolume, Modifier.requiredSize(metrics.buttonSize * 2))
                // The guide's subtle button, its glyph saying whether it is on; one label, "Voice".
                IconButton(
                    if (call.muted) TouchpointIcons.MicOff else TouchpointIcons.Mic,
                    stringResource(R.string.touchpoint_voice_microphone),
                    onMicrophone,
                    kind = IconKind.SUBTLE,
                    state = stringResource(if (call.muted) R.string.touchpoint_voice_off else R.string.touchpoint_voice_on),
                )
            }
            IconButton(
                if (call.speakersOn) TouchpointIcons.Volume else TouchpointIcons.VolumeOff,
                stringResource(R.string.touchpoint_voice_speakers),
                onSpeakers,
                kind = IconKind.SUBTLE,
                state = stringResource(if (call.speakersOn) R.string.touchpoint_voice_on else R.string.touchpoint_voice_off),
            )
            ShareScreenButton(call.screenShare, onShareScreen, onStopSharing)
        }
    }
}

/** Where a call is heard, named for the customer: a headset by its own name, the phone's by what it is. */
@Composable
internal fun outputName(output: VoiceOutput): String =
    output.name ?: stringResource(
        when (output.kind) {
            VoiceOutput.Kind.SPEAKER -> R.string.touchpoint_call_output_speaker
            VoiceOutput.Kind.EARPIECE -> R.string.touchpoint_call_output_earpiece
            VoiceOutput.Kind.WIRED_HEADSET -> R.string.touchpoint_call_output_wired
            VoiceOutput.Kind.USB_HEADSET -> R.string.touchpoint_call_output_usb
            VoiceOutput.Kind.BLUETOOTH -> R.string.touchpoint_call_output_bluetooth
        },
    )

/** Web's VoiceIcon: 128 round, the brand as its picture, or the Touchpoint glyph on a solid grey. Decorative. */
@Composable
private fun Orb(brand: Painter?) {
    val theme = LocalTouchpointTheme.current
    val shape = CircleShape
    val base = Modifier.size(TouchpointMetrics.voiceOrb).clip(shape).clearAndSetSemantics {}
    if (brand != null) {
        Image(brand, contentDescription = null, modifier = base, contentScale = ContentScale.Crop)
    } else {
        // Web's stand-in for primary5 over the background, solid so the ripple cannot show through.
        Box(base.background(if (theme.isDark) ORB_DARK else ORB_LIGHT).padding(TouchpointMetrics.voiceOrbPadding)) {
            // The guide's orb: the assistant's sparkle.
            Glyph(TouchpointIcons.Sparkle, theme.primary40, TouchpointMetrics.voiceOrb - TouchpointMetrics.voiceOrbPadding * 2)
        }
    }
}

/** Web's end of a call: the glyph in primary20, "The conversation has ended", and what to do now. */
@Composable
internal fun CallEnded() {
    StateScreen(stringResource(R.string.touchpoint_voice_ended_title), stringResource(R.string.touchpoint_voice_ended_body))
}

/** Web's FullscreenError, and its Retry bar under it. */
@Composable
internal fun CallFailed(onRetry: (() -> Unit)?) {
    val metrics = TouchpointMetrics.current()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StateScreen(
                stringResource(R.string.touchpoint_voice_error_title),
                stringResource(R.string.touchpoint_voice_error_body),
                stringResource(R.string.touchpoint_voice_error_hint),
            )
        }
        Box(
            Modifier.fillMaxWidth().height(metrics.voiceRetryBarHeight).padding(horizontal = metrics.voiceRetryPadding),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.widthIn(max = TouchpointMetrics.contentMaxWidth)) {
                RowButton(stringResource(R.string.touchpoint_voice_retry), TouchpointIcons.Restart, onRetry)
            }
        }
    }
}

@Composable
private fun StateScreen(
    title: String,
    vararg lines: String,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    Column(
        Modifier.fillMaxSize().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(TouchpointIcons.Touchpoint, theme.primary20, TouchpointMetrics.voiceStateGlyph)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(title, Modifier.padding(bottom = metrics.titleGap), style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight).copy(textAlign = TextAlign.Center))
            lines.forEach { BasicText(it, style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center)) }
        }
    }
}

private val ORB_DARK = Color(0xFF28292F)
private val ORB_LIGHT = Color(0xFFAFAFAF)


/**
 * A call's changes, spoken by TalkBack: calling, connected, reconnecting,
 * on hold and resumed, the microphone, where it is heard, and the end. One polite live region, one
 * line at a time, so none talks over another.
 */
@Composable
internal fun CallAnnouncer(
    state: ContactState,
    call: VoiceCallState,
    failed: Boolean,
    modality: Modality?,
) {
    val metrics = TouchpointMetrics.current()
    val queue = remember { mutableStateListOf<String>() }
    var spoken by remember { mutableStateOf("") }
    val calling = stringResource(R.string.touchpoint_call_connecting)
    val connected = stringResource(R.string.touchpoint_call_connected)
    val reconnecting = stringResource(R.string.touchpoint_call_reconnecting)
    val onHold = stringResource(R.string.touchpoint_call_interrupted)
    val resumed = stringResource(R.string.touchpoint_call_resumed)
    val ended = stringResource(R.string.touchpoint_call_ended)
    val failedText = stringResource(R.string.touchpoint_call_failed)
    val muted = stringResource(R.string.touchpoint_call_muted)
    val unmuted = stringResource(R.string.touchpoint_call_unmuted)
    val route = call.output?.let { stringResource(R.string.touchpoint_call_route, outputName(it)) }
    val voice = modality == Modality.VOICE
    // Each a change from what was, never the first value: opening on a live call announces nothing old.
    Changed(voice && state == ContactState.Connecting) { _, now -> if (now) queue += calling }
    Changed(voice && state == ContactState.Connected && !call.reconnecting) { _, now -> if (now) queue += connected }
    Changed(call.reconnecting) { _, now -> if (now) queue += reconnecting }
    Changed(call.interrupted) { _, now -> queue += if (now) onHold else resumed }
    // The mute an interruption makes, and the one it gives back, are said by "on hold" and "resumed":
    // only the customer's own is said here.
    Changed(call.muted to call.interrupted) { (_, wasHeld), (m, held) -> if (!held && !wasHeld) queue += if (m) muted else unmuted }
    Changed(route) { _, r -> if (r != null) queue += r }
    Changed(failed || state is ContactState.Ended) { _, now -> if (now) queue += if (failed) failedText else ended }
    val shareAvailable = stringResource(R.string.touchpoint_call_share_available)
    val shareStarted = stringResource(R.string.touchpoint_call_share_started)
    val shareStopped = stringResource(R.string.touchpoint_call_share_stopped)
    Changed(call.screenShare) { old, now ->
        val wasSharing = old == ScreenShareState.SHARING
        when {
            now == ScreenShareState.SHARING -> queue += shareStarted
            wasSharing -> queue += shareStopped
            old == ScreenShareState.UNAVAILABLE && now == ScreenShareState.AVAILABLE -> queue += shareAvailable
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { queue.isNotEmpty() }.first { it }
            spoken = queue.removeAt(0)
            delay(ANNOUNCEMENT_PACING)
            spoken = ""
            delay(ANNOUNCEMENT_GAP)
        }
    }
    // Described only while it speaks: an empty description made an invisible stop for TalkBack's swipe.
    Box(
        Modifier.size(metrics.hairline).semantics {
            liveRegion = LiveRegionMode.Polite
            if (spoken.isNotEmpty()) contentDescription = spoken
        },
    )
}

/** Calls [onChange] with the old value and the new when [value] changes after the first composition. */
@Composable
private fun <T> Changed(
    value: T,
    onChange: (old: T, new: T) -> Unit,
) {
    var last by remember { mutableStateOf(value) }
    val current by rememberUpdatedState(onChange)
    LaunchedEffect(value) {
        if (value != last) {
            val old = last
            last = value
            current(old, value)
        }
    }
}

private val ANNOUNCEMENT_PACING = 900.milliseconds
private val ANNOUNCEMENT_GAP = 50.milliseconds

/**
 * Share screen, once the agent has opened a session — an addition, as web has none: ghost to
 * offer it, subtle while sharing, and off while it starts. Nothing at all before the agent asks.
 */
@Composable
internal fun ShareScreenButton(
    state: ScreenShareState,
    onShare: (() -> Unit)?,
    onStop: () -> Unit,
) {
    when (state) {
        ScreenShareState.UNAVAILABLE -> Unit
        // The guide's subtle buttons beside the call's others; the main one while the agent sees the screen.
        ScreenShareState.AVAILABLE -> IconButton(TouchpointIcons.ScreenShare, stringResource(R.string.touchpoint_call_share_screen), onShare, kind = IconKind.SUBTLE)
        ScreenShareState.STARTING -> IconButton(TouchpointIcons.ScreenShare, stringResource(R.string.touchpoint_call_share_screen), null, kind = IconKind.SUBTLE)
        ScreenShareState.SHARING -> IconButton(TouchpointIcons.ScreenShare, stringResource(R.string.touchpoint_call_stop_sharing), onStop, kind = IconKind.MAIN)
    }
}

/**
 * Asked before the first frame leaves, as Android shows nothing of its own for an app's own screen
 * Web's dialog card; Back or a tap beside it is Not now.
 */
@Composable
internal fun ShareConfirmation(
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val title = stringResource(R.string.touchpoint_call_share_title)
    val density = LocalDensity.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalTouchpointTheme provides theme, LocalDensity provides density) {
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
            ) {
                BasicText(title, Modifier.semantics { heading() }, style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight))
                BasicText(stringResource(R.string.touchpoint_call_share_body), style = theme.text(theme.primary, metrics.smallText, metrics.smallLineHeight))
                Column(verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
                    RowButton(stringResource(R.string.touchpoint_call_share_confirm), TouchpointIcons.ScreenShare, onShare)
                    RowButton(stringResource(R.string.touchpoint_call_share_cancel), TouchpointIcons.Close, onCancel)
                }
            }
        }
    }
}
