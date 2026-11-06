package com.amazon.connect.touchpoint.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoContentHint
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoRotation
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.buffer.VideoFrameRGBABuffer
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * This app's window, as Chime content: an in-app capture, without Android's MediaProjection,
 * which records the whole device and needs a foreground service the host would declare. Frames are
 * copied from [window] with PixelCopy, so other apps, notifications and the system's bars are never
 * sent; a window marked `FLAG_SECURE` — a card number's screen, say — sends nothing while it is;
 * and what the host marks with [TouchpointScreenShareRedaction] is painted black in every frame.
 * A few frames a second, for a screen read as text, scaled to at most [MAX_SIDE].
 */
internal class AppWindowCapture(
    private val window: Window,
    private val interval: Long = FRAME_INTERVAL_MS,
) : VideoSource {
    private val sinks = CopyOnWriteArraySet<VideoSink>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false

    override val contentHint: VideoContentHint = VideoContentHint.Text

    override fun addVideoSink(sink: VideoSink) {
        sinks += sink
    }

    override fun removeVideoSink(sink: VideoSink) {
        sinks -= sink
    }

    fun start() {
        if (running) return
        running = true
        val t = HandlerThread("touchpoint-screen-share").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(::capture) }
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun capture() {
        val h = handler ?: return
        if (!running) return
        val view = window.peekDecorView()
        val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (view == null || view.width == 0 || view.height == 0 || secure || sinks.isEmpty()) {
            h.postDelayed(::capture, interval)
            return
        }
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(view.width, view.height))
        // Even sizes: the encoder's planes halve them.
        val width = (view.width * scale).roundToInt() and 1.inv()
        val height = (view.height * scale).roundToInt() and 1.inv()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Where the marked elements are is read on the main thread, beside the copy it masks.
        main.post {
            if (!running) {
                bitmap.recycle()
                return@post
            }
            val marks = TouchpointScreenShareRedaction.masks(view)
            val now = TouchpointScreenShareRedaction.scaled(marks.rects, width.toFloat() / view.width)
            // This frame's places and the last one's: an element moving under its mask is covered both ways.
            val masks = now + previous
            previous = now
            val blank = marks.secure
            try {
                PixelCopy.request(window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && running) deliver(bitmap, masks, blank)
                    bitmap.recycle()
                    if (running) h.postDelayed(::capture, interval)
                }, h)
            } catch (_: IllegalArgumentException) {
                // The window has no surface yet, or no longer: try again shortly.
                bitmap.recycle()
                h.postDelayed(::capture, interval)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Main thread: the last frame's masks. */
    private var previous: List<Rect> = emptyList()
    private val black = Paint().apply { color = Color.BLACK }

    /**
     * Direct buffers of the current frame size, handed back by Chime's release once a frame is done
     * with: a few megabytes of native memory per frame, five times a second, are not allocated afresh.
     */
    private val pool = ArrayDeque<ByteBuffer>()

    private fun buffer(size: Int): ByteBuffer =
        synchronized(pool) {
            while (pool.isNotEmpty() && pool.first().capacity() != size) pool.removeFirst()
            pool.removeFirstOrNull()
        }?.apply { clear() } ?: ByteBuffer.allocateDirect(size)

    private fun recycle(data: ByteBuffer) {
        synchronized(pool) { if (pool.size < POOL_SIZE) pool.addLast(data) }
    }

    private fun deliver(
        bitmap: Bitmap,
        masks: List<Rect>,
        blank: Boolean,
    ) {
        // Painted before a byte leaves the bitmap: a marked element is never in a frame, and a frame
        // with a secure one on show is black throughout.
        if (blank) bitmap.eraseColor(Color.BLACK) else if (masks.isNotEmpty()) Canvas(bitmap).apply { masks.forEach { drawRect(it, black) } }
        // ARGB_8888 is laid out R, G, B, A in memory: the RGBA Chime takes.
        val data = buffer(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(data)
        data.rewind()
        val frame = VideoFrame(SystemClock.elapsedRealtimeNanos(), VideoFrameRGBABuffer(bitmap.width, bitmap.height, data, bitmap.width * BYTES_PER_PIXEL, Runnable { recycle(data) }), VideoRotation.Rotation0)
        sinks.forEach { it.onVideoFrameReceived(frame) }
        frame.release()
    }

    companion object {
        /** Five frames a second: a screen read as text, not motion. */
        const val FRAME_INTERVAL_MS = 200L

        /** The longer side, in pixels: legible to the agent, light on the customer's data. */
        const val MAX_SIDE = 1280
        private const val BYTES_PER_PIXEL = 4
        private const val POOL_SIZE = 3
    }
}
