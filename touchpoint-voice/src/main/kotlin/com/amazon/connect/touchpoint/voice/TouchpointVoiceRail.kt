@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.amazon.connect.touchpoint.chat.ui.IconButton
import com.amazon.connect.touchpoint.chat.ui.IconKind
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.Loader
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.TouchpointIcons
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.ui.R

/**
 * A call's rail: bound to a [call] the host holds — it never
 * starts one, and draws nothing while there is none (idle, ended, failed). Its controls, as the
 * guide's voice mini: the microphone and Speakers, screen sharing once the agent offers it, and a red
 * close that ends the call; each wears its side's volume ring while it speaks.
 *
 * Two additions, drawn first, so the rail can stand in for the host's own launcher during a call
 * — one pill rather than two side by side: [onOpenChat] (the host's chat) and [onMenu] (more: the
 * full-screen [TouchpointVoiceView] over the same call, say). Omit either and it is not drawn.
 *
 * @param vertical a column, as on a phone; a row (`false`) where the host lays it along an edge.
 * @param draggable web's voice mini: the rail leads with web's drag
 *   handle and moves from it within the space it is given, 8 inside every edge, starting at [anchor].
 *   Give it a layer over the whole screen (it fills what it is given and takes no touches but its
 *   own), and keep that layer composed while the chat is open if the position should survive.
 */
@Composable
public fun TouchpointVoiceRail(
    call: TouchpointVoiceCall,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    vertical: Boolean = true,
    onOpenChat: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    draggable: Boolean = false,
    anchor: Alignment = Alignment.BottomEnd,
    /** Draggable: `false` hides it where it is (the chat open over it, say), so it comes back there. */
    visible: Boolean = true,
    /** For its [TouchpointChatOptions.icons]: the rest of the chat's options do not apply to a rail. */
    options: com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions = com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions.Default,
) {
    val phase by call.phase.collectAsState()
    if (phase != TouchpointVoiceCall.Phase.CONNECTING && phase != TouchpointVoiceCall.Phase.CONNECTED) return
    val state by call.session.state.collectAsState()
    val voice = call.voice
    val now by voice.call.collectAsState()
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val live = (state == ContactState.Connected || state is ContactState.Reconnecting) && call.session.modality == Modality.VOICE
    CompositionLocalProvider(LocalTouchpointTheme provides theme, com.amazon.connect.touchpoint.chat.ui.LocalChatOptions provides options) {
        val rail: @Composable (@Composable () -> Unit) -> Unit = { handle ->
            Controls(wide = !vertical, handle = handle) {
                if (onOpenChat != null) IconButton(TouchpointIcons.Chat, stringResource(R.string.touchpoint_call_open_chat), onOpenChat)
                if (onMenu != null) IconButton(TouchpointIcons.Menu, stringResource(R.string.touchpoint_call_menu), onMenu)
                CallButtons(live, now, voice, vertical = vertical, onShare = { confirmShare = true }, onClose = call::end, closeLabel = stringResource(R.string.touchpoint_mini_close))
            }
        }
        Box {
            // Draggable, the rail is web's voice mini: its handle first, moved within the layer it fills.
            if (draggable) DragLayer(anchor, modifier, visible) { handle -> rail(handle) } else Box(modifier) { rail {} }
            // Hidden, another view of the call (the chat's tray) is the one that speaks.
            if (visible) CallAnnouncer(state, now, failed = false, modality = call.session.modality)
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
