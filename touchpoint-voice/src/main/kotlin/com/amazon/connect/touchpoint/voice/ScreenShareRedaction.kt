package com.amazon.connect.touchpoint.voice

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What screen sharing never sends: parts of the app's window the host marks — a card number, an
 * account's balance, a one-time code. Each frame is painted black over them before it leaves the
 * device, as the agent would otherwise see whatever the customer sees. On top of the
 * window-wide rules: only the app's own window is shared, and a `FLAG_SECURE` window sends nothing.
 *
 * Mark a composable with [redactedFromScreenShare], or [secureFromScreenShare] for one whose screen
 * must not be shared at all — every frame goes out black while it is on show. A classic `View`:
 * [redact] or [secure], and [stop] when it goes (a view that is garbage collected stops by itself).
 * Screen sharing:
 * - A mark follows its element, padded a little; the previous frame's places are masked too, so a
 *   balance scrolling under a mask never shows between two frames.
 * - A `View` that is hidden, or fully transparent, is not masked.
 */
public object TouchpointScreenShareRedaction {
    private class Marked(val root: View, val bounds: Rect, val secure: Boolean)

    private val marked = ConcurrentHashMap<Any, Marked>()
    private val views: MutableMap<View, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** Masks [view] in every frame shared while it is on show. */
    public fun redact(view: View) {
        views[view] = false
    }

    /** Sends every frame black while [view] is on show: for a screen that must not be shared at all. */
    public fun secure(view: View) {
        views[view] = true
    }

    /** Stops masking [view], however it was marked. */
    public fun stop(view: View) {
        views.remove(view)
    }

    internal fun mark(
        key: Any,
        root: View,
        bounds: Rect,
        secure: Boolean,
    ) {
        marked[key] = Marked(root, bounds, secure)
    }

    internal fun unmark(key: Any) {
        marked.remove(key)
    }

    /** What to mask in [root]'s window, in its pixels, and whether the whole frame goes black. Main thread: a `View`'s place is read there. */
    internal fun masks(root: View): Masks {
        val compose = marked.values.filter { it.root === root }
        val shown = synchronized(views) { views.toList() }.filter { (v, _) -> v.isAttachedToWindow && v.rootView === root && v.isShown && v.alpha > MIN_ALPHA }
        val rects =
            compose.map { Rect(it.bounds) } +
                shown.map { (v, _) ->
                    val at = IntArray(2).also(v::getLocationInWindow)
                    Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
                }
        return Masks(rects, secure = compose.any { it.secure } || shown.any { it.second })
    }

    internal class Masks(val rects: List<Rect>, val secure: Boolean)

    /**
     * [masks] in a frame [scale] times the window's size: rounded outward and padded by [PAD]
     * frame pixels, so a mask never shrinks off its element.
     */
    internal fun scaled(
        masks: List<Rect>,
        scale: Float,
    ): List<Rect> =
        masks.map { r ->
            Rect(floor(r.left * scale).toInt() - PAD, floor(r.top * scale).toInt() - PAD, ceil(r.right * scale).toInt() + PAD, ceil(r.bottom * scale).toInt() + PAD)
        }

    /** The padding round a mask, in frame pixels. */
    private const val PAD = 2

    /** Below this, a `View` draws nothing to hide. */
    private const val MIN_ALPHA = 0.01f
}

/**
 * Masks this element in every frame of a screen share, wherever it is laid out: for what the agent
 * must not see even when the customer shares the app. See [TouchpointScreenShareRedaction].
 */
public fun Modifier.redactedFromScreenShare(): Modifier = marked(secure = false)

/**
 * While this element is on show, every frame of a screen share goes out black: for a screen that must
 * not be shared at all. See [TouchpointScreenShareRedaction].
 */
public fun Modifier.secureFromScreenShare(): Modifier = marked(secure = true)

private fun Modifier.marked(secure: Boolean): Modifier =
    composed {
        val root = LocalView.current.rootView
        val key = remember { Any() }
        DisposableEffect(key) { onDispose { TouchpointScreenShareRedaction.unmark(key) } }
        onGloballyPositioned { coordinates ->
            val b = coordinates.boundsInWindow()
            TouchpointScreenShareRedaction.mark(key, root, Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt()), secure)
        }
    }
