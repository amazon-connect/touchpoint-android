package com.amazon.connect.touchpoint.voice

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.buffer.VideoFrameRGBABuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** The app's own window as Chime frames: copied, scaled to 1280 at most, and nothing while the window is marked secure. */
@RunWith(AndroidJUnit4::class)
class AppWindowCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theWindowIsSentAsFramesAndASecureWindowIsNot() {
        rule.setContent { BasicText("Balance", Modifier.fillMaxSize().background(Color.White)) }
        rule.waitForIdle()
        val frames = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val capture = AppWindowCapture(rule.activity.window, interval = 50)
        capture.addVideoSink(
            object : VideoSink {
                override fun onVideoFrameReceived(frame: VideoFrame) {
                    val buffer = frame.buffer as VideoFrameRGBABuffer
                    assertEquals(buffer.width * 4, buffer.stride)
                    frames += frame.width to frame.height
                }
            },
        )
        capture.start()
        try {
            rule.waitUntil(5_000) { frames.size >= 3 }
            val (w, h) = frames.first()
            assertTrue("scaled to 1280 at most: ${w}x$h", maxOf(w, h) <= AppWindowCapture.MAX_SIDE && w % 2 == 0 && h % 2 == 0)
            // Marked secure — a card number on screen — and nothing more is sent.
            rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            Thread.sleep(300)
            val atSecure = frames.size
            Thread.sleep(500)
            assertEquals(atSecure, frames.size)
        } finally {
            capture.stop()
            rule.runOnUiThread { rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    @Test
    fun whatTheHostMarksIsBlackInEveryFrame() {
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.White)) {
                BasicText("Card 4111 1111 1111 1111", Modifier.fillMaxSize().background(Color.White).redactedFromScreenShare())
            }
        }
        rule.waitForIdle()
        val pixels = Collections.synchronizedList(mutableListOf<Int>())
        val capture = AppWindowCapture(rule.activity.window, interval = 50)
        capture.addVideoSink(
            object : VideoSink {
                override fun onVideoFrameReceived(frame: VideoFrame) {
                    val buffer = frame.buffer as VideoFrameRGBABuffer
                    // The frame's centre, inside the marked element: R, G, B of one pixel.
                    val at = (buffer.height / 2) * buffer.stride + (buffer.width / 2) * 4
                    val data = buffer.data
                    pixels += (data.get(at).toInt() and 0xFF) + (data.get(at + 1).toInt() and 0xFF) + (data.get(at + 2).toInt() and 0xFF)
                }
            },
        )
        capture.start()
        try {
            rule.waitUntil(5_000) { pixels.size >= 3 }
            assertTrue("the marked element is black, not the white it draws: $pixels", pixels.all { it == 0 })
        } finally {
            capture.stop()
        }
    }

    @Test
    fun aScreenMarkedSecureGoesOutBlackThroughout() {
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.White)) {
                BasicText("Statement", Modifier.background(Color.White).secureFromScreenShare())
            }
        }
        rule.waitForIdle()
        val corners = Collections.synchronizedList(mutableListOf<Int>())
        val capture = AppWindowCapture(rule.activity.window, interval = 50)
        capture.addVideoSink(
            object : VideoSink {
                override fun onVideoFrameReceived(frame: VideoFrame) {
                    val buffer = frame.buffer as VideoFrameRGBABuffer
                    // A corner far from the marked element: white on screen, black in the frame.
                    val at = (buffer.height - 2) * buffer.stride + (buffer.width - 2) * 4
                    val data = buffer.data
                    corners += (data.get(at).toInt() and 0xFF) + (data.get(at + 1).toInt() and 0xFF) + (data.get(at + 2).toInt() and 0xFF)
                }
            },
        )
        capture.start()
        try {
            rule.waitUntil(5_000) { corners.size >= 3 }
            assertTrue("the whole frame is black: $corners", corners.all { it == 0 })
        } finally {
            capture.stop()
        }
    }
}
