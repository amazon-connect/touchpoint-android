@file:OptIn(com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.voice

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The voice view beside web's FullscreenVoice, state by state, for the parity check. A tool, not a
 * check: it captures only when asked (`-Pandroid.testInstrumentationRunnerArguments.parityCapture=true`).
 */
@RunWith(AndroidJUnit4::class)
class VoiceParityCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val enabled = InstrumentationRegistry.getArguments().getString("parityCapture") == "true"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Driver(private val then: VoiceDriverEvent?) : VoiceDriving {
        override fun start(meetingJson: String, attendeeJson: String, events: (VoiceDriverEvent) -> Unit) {
            events(VoiceDriverEvent.Started(false))
            then?.let(events)
        }

        override fun stop() = Unit

        override fun setMuted(muted: Boolean) = Unit

        override fun setPlaybackMuted(muted: Boolean) = Unit

        override fun chooseOutput(id: String) = Unit

        override fun listenForScreenShare(listen: Boolean) = Unit

        override fun startScreenShare(window: android.view.Window) = Unit

        override fun stopScreenShare() = Unit
    }

    private fun capture(name: String, then: VoiceDriverEvent? = null, fail: Boolean = false) {
        if (!enabled) return
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val dir = File(context.cacheDir, "tp-vcap-${UUID.randomUUID()}")
        val keys = InMemorySecureStore()
        val store = runBlocking { ConversationStore.open(dir, keys, AndroidSQLiteDriver()) }
        val transport = ConnectVoiceTransport(Driver(then), { true }, Dispatchers.Main.immediate)
        val backend =
            object : TouchpointBackend {
                override suspend fun startChatContact(request: ChatContactRequest) = throw TouchpointException.ContactNotFound()

                override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials =
                    if (fail) throw TouchpointException.HttpFailure(502, null) else VoiceContactCredentials("v", "p", "t", "{}", "{}", null)

                override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
            }
        val session = TouchpointSession(CredentialBroker(backend, keys), store, mapOf(Modality.VOICE to transport))
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                Box(Modifier.requiredSize(WIDTH.dp, HEIGHT.dp).testTag("parity")) {
                    TouchpointVoiceView(session, transport, onStartCall = { session.startVoice(VoiceContactRequest("Me")) }, theme = TouchpointTheme.Dark, onClose = {})
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        rule.waitForIdle()
        val image = rule.onNodeWithTag("parity").captureToImage().asAndroidBitmap()
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: File(context.getExternalFilesDir(null), "parity")
        out.mkdirs()
        File(out, "android-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        runBlocking {
            session.close()
            store.close()
        }
        dir.deleteRecursively()
    }

    @Test fun live() = capture("voice-live-dark")

    @Test fun ended() = capture("voice-ended-dark", VoiceDriverEvent.Stopped(byUs = false, status = "AudioCallEnded"))

    @Test fun error() = capture("voice-error-dark", fail = true)

    private companion object {
        const val WIDTH = 412
        const val HEIGHT = 915
        const val SETTLE_MS = 800L
    }
}
