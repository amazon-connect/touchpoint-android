@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import com.amazon.connect.touchpoint.chat.ui.reduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.amazon.connect.touchpoint.chat.ui.Glyph
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LocalTouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.TouchpointIcons
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import kotlin.math.roundToInt

/** Web's `useDraggable`: the offset rule, pure for the JVM tests. */
internal object Draggable {
    /** Web's `bottom-2 right-2`: the widget stays this far inside every edge. */
    val margin = TouchpointMetrics.floatingMargin

    /**
     * [asked] held to where the content stays wholly inside [container] (already inset by [margin]),
     * from its [anchor]'s place there. Bigger than the room, it stays at the anchor.
     */
    fun clamp(
        asked: IntOffset,
        content: IntSize,
        container: IntSize,
        anchor: Alignment,
        direction: LayoutDirection,
    ): IntOffset {
        if (content.width <= 0 || container.width <= 0) return IntOffset.Zero
        val base = anchor.align(content, container, direction)
        fun axis(
            value: Int,
            base: Int,
            size: Int,
            room: Int,
        ): Int {
            val low = -base
            val high = room - size - base
            return if (high < low) 0 else value.coerceIn(low, high)
        }
        return IntOffset(axis(asked.x, base.x, content.width, container.width), axis(asked.y, base.y, content.height, container.height))
    }

    /**
     * Where a drop at [at] settles across: against the start or the end edge, whichever the widget's
     * middle is nearer, as the guide's mini snaps; its height stays where it was dropped.
     */
    fun snapX(
        at: IntOffset,
        content: IntSize,
        container: IntSize,
        anchor: Alignment,
        direction: LayoutDirection,
    ): Int {
        if (content.width <= 0 || container.width <= 0) return at.x
        val base = anchor.align(content, container, direction).x
        val start = -base
        val end = container.width - content.width - base
        if (end < start) return 0
        val middle = base + at.x + content.width / 2
        return if (middle * 2 < container.width) start else end
    }
}

/**
 * A layer as big as it is given, with [content] at [anchor] inset 8 and moved by the customer's drag
 * from the handle [content] places: web's voice mini, a draggable rail. Let go, it snaps to the
 * start or end edge nearer its middle, at the height it was dropped. The position is kept as asked
 * and clamped only where it is drawn, so a layer that shrinks for a while (a keyboard, a half sheet
 * insetting the screen) and grows back returns the widget where it was. Nothing is remembered across
 * launches, as web. The layer takes no touches of its own: only the widget does.
 */
@Composable
internal fun DragLayer(
    anchor: Alignment,
    modifier: Modifier = Modifier,
    /** Hidden, it keeps its place (still composed), but draws nothing and takes no touch or focus. */
    visible: Boolean = true,
    content: @Composable (handle: @Composable () -> Unit) -> Unit,
) {
    // Floats, so a slow drag's fractions of a pixel add up rather than being dropped.
    var askedX by rememberSaveable { mutableStateOf(0f) }
    var askedY by rememberSaveable { mutableStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val direction = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val still = reduceMotion()
    var settling by remember { mutableStateOf<Job?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().padding(Draggable.margin)) {
        val room = IntSize(constraints.maxWidth, constraints.maxHeight)
        fun clamped(
            x: Float,
            y: Float,
        ) = Draggable.clamp(IntOffset(x.roundToInt(), y.roundToInt()), size, room, anchor, direction)

        fun drawn() = clamped(askedX, askedY)

        fun settle() {
            val target = Draggable.snapX(drawn(), size, room, anchor, direction).toFloat()
            if (still) {
                askedX = target
            } else {
                settling = scope.launch { animate(askedX, target, animationSpec = tween(SNAP_MS)) { x, _ -> askedX = x } }
            }
        }
        val handle: @Composable () -> Unit = {
            val theme = LocalTouchpointTheme.current
            val metrics = TouchpointMetrics.current()
            // A pointer's affordance: TalkBack moves through the controls instead, wherever it sits.
            Box(
                Modifier
                    .size(metrics.minimumTouchTarget)
                    // Snapped to an edge, the handle is in the system's back-gesture zone: a drag that
                    // starts on it moves the widget, not back.
                    .systemGestureExclusion()
                    .clearAndSetSemantics {}
                    .pointerInput(room, size) {
                        detectDragGestures(
                            // From where it is drawn, so a drag never has to undo an overshoot first.
                            onDragStart = {
                                settling?.cancel()
                                drawn().let {
                                    askedX = it.x.toFloat()
                                    askedY = it.y.toFloat()
                                }
                            },
                            // Let go — or the drag cancelled — it settles against the nearer side.
                            onDragEnd = { settle() },
                            onDragCancel = { settle() },
                        ) { change, amount ->
                            change.consume()
                            // Held inside as it goes, so the widget follows the finger back at once.
                            val next = clamped(askedX + amount.x, askedY + amount.y)
                            askedX = if (next.x == (askedX + amount.x).roundToInt()) askedX + amount.x else next.x.toFloat()
                            askedY = if (next.y == (askedY + amount.y).roundToInt()) askedY + amount.y else next.y.toFloat()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Glyph(TouchpointIcons.Reorder, theme.primary60, metrics.iconSize) }
        }
        val hidden =
            if (visible) {
                Modifier
            } else {
                Modifier.alpha(0f).clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        Box(Modifier.align(anchor).onSizeChanged { size = it }.offset { drawn() }.then(hidden)) { content(handle) }
    }
}

private const val SNAP_MS = 250
