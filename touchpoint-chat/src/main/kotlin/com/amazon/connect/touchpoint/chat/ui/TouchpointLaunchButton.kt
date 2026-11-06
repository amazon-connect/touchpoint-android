package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.amazon.connect.touchpoint.ui.R

/**
 * The launch button: what the customer taps to open the chat, as web's `LaunchButton`, here
 * `TouchpointLaunchButton`. The design guide's form: the assistant's mark, then [label], on the chat's
 * own surface under the guide's Float shadow, a pill 56 tall. Without a [label] it is the mark alone,
 * a disc, spoken as the assistant.
 *
 * Opening is the host's: [onClick] typically shows [TouchpointChatSheet]. Place it yourself, 8 in
 * from the screen's corner as the guide does, and hide it while a call's rail is up.
 *
 * @param label the words beside the mark, such as "Talk to AI Assistant". `null` draws the mark alone.
 * @param icon the brand's launch icon in place of the assistant's sparkle (web's `iconUrl`), drawn as is.
 * @param onClickLabel what TalkBack says a tap does, such as "Opens a chat with Example Bank support".
 */
@OptIn(InternalTouchpointUi::class)
@Composable
public fun TouchpointLaunchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: Painter? = null,
    theme: TouchpointTheme = TouchpointTheme.system(),
    onClickLabel: String? = null,
) {
    CompositionLocalProvider(LocalTouchpointTheme provides theme) {
        val metrics = TouchpointMetrics.current()
        val shape = RoundedCornerShape(percent = 50)
        val spoken = label ?: stringResource(R.string.touchpoint_header_title)
        Row(
            modifier
                .dropShadow(shape, Shadow(TouchpointMetrics.floatingShadow, Color.Black.copy(alpha = TouchpointMetrics.FLOATING_SHADOW_ALPHA)))
                .clip(shape)
                .background(theme.background)
                .border(metrics.hairline, theme.primary10, shape)
                .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                .then(if (label == null) Modifier.semantics { contentDescription = spoken } else Modifier)
                .heightIn(min = metrics.rowMinHeight)
                .widthIn(min = metrics.rowMinHeight)
                .padding(start = metrics.launchPaddingStart, end = if (label == null) metrics.launchPaddingStart else metrics.launchPaddingEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.smallGap, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(metrics.launchMark))
            } else {
                Image(
                    rememberVectorPainter(TouchpointIcons.Sparkle),
                    contentDescription = null,
                    modifier = Modifier.size(metrics.launchMark),
                    colorFilter = ColorFilter.tint(theme.primary),
                )
            }
            if (label != null) BasicText(label, style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight))
        }
    }
}
