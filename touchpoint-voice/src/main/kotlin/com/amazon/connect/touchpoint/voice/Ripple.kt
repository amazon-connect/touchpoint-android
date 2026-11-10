@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.chat.ui.reduceMotion

/**
 * The guide's volume feedback around a round control while someone speaks, in the Accent Effects
 * colour: an inner ring, 2 wide at 50 %, just outside the control; and an outer ring at 15 %, reaching
 * further out the louder they are ([level], 0 to 1) — quick to grow, slow to shrink, so it never
 * blinks between words. Only ever outside the control. Still at its outer ring's middle reach when
 * animations are off. Decorative.
 *
 * It takes no room: drawn centred on its control, whatever size [modifier] gives it (twice the
 * control's), so a rail or a row never grows while someone speaks.
 */
@Composable
internal fun VolumeRing(
    level: Float,
    modifier: Modifier,
) {
    val theme = LocalTouchpointTheme.current
    val still = reduceMotion()
    val reach = remember { Animatable(0f) }
    LaunchedEffect(level, still) {
        val target = if (still) STILL_LEVEL else level.coerceIn(0f, 1f)
        reach.animateTo(target, tween(if (target >= reach.value) GROW_MS else SHRINK_MS, easing = FastOutSlowInEasing))
    }
    val border = with(androidx.compose.ui.platform.LocalDensity.current) { TouchpointMetrics.rippleBorder.toPx() }
    Canvas(Modifier.overflowing().then(modifier).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val r0 = size.minDimension / 4 // the control: the ring's box is twice its size
        val outer = r0 + border + r0 * REACH * reach.value
        drawCircle(theme.accentEffect.copy(alpha = OUTER_ALPHA * theme.accentEffect.alpha), radius = outer)
        drawCircle(Color.Black, radius = r0, blendMode = BlendMode.Clear) // never over the control
        drawCircle(theme.accentEffect.copy(alpha = INNER_ALPHA * theme.accentEffect.alpha), radius = r0 + border / 2, style = Stroke(border))
    }
}

private const val INNER_ALPHA = 0.5f
private const val OUTER_ALPHA = 0.15f

/** The outer ring's widest, past the control's edge, as a share of the control's radius. */
private const val REACH = 0.6f
private const val STILL_LEVEL = 0.5f
private const val GROW_MS = 120
private const val SHRINK_MS = 450

/** Measured at its own size, laid out as nothing: centred on where its parent puts a point. */
private fun Modifier.overflowing(): Modifier =
    layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        layout(0, 0) { placeable.place(-placeable.width / 2, -placeable.height / 2) }
    }
