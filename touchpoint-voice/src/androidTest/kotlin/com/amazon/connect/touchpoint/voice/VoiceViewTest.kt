package com.amazon.connect.touchpoint.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/** The voice view over the real session, with only the call faked: what web shows, and what it does. */
@RunWith(AndroidJUnit4::class)
class VoiceViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "tp-voice-${UUID.randomUUID()}")
    private lateinit var store: ConversationStore
    private lateinit var session: TouchpointSession
    private val driver = FakeDriver()
    @Volatile private var microphone = true
    private val transport = ConnectVoiceTransport(driver, { microphone }, Dispatchers.Main.immediate, allowsScreenShare = true)
    private val starts = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<VoiceContactRequest>())
    @Volatile private var failStart = false
    @Volatile private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private class FakeDriver : VoiceDriving {
        @Volatile var events: ((VoiceDriverEvent) -> Unit)? = null
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun start(meetingJson: String, attendeeJson: String, events: (VoiceDriverEvent) -> Unit) {
            calls += "start"
            this.events = events
            events(VoiceDriverEvent.Started(false))
        }

        override fun stop() {
            calls += "stop"
            events?.invoke(VoiceDriverEvent.Stopped(byUs = true, status = "Left"))
        }

        override fun setMuted(muted: Boolean) {
            calls += "muted=$muted"
        }

        override fun setPlaybackMuted(muted: Boolean) {
            calls += "playback=$muted"
        }

        override fun chooseOutput(id: String) {
            calls += "output=$id"
        }

        override fun listenForScreenShare(listen: Boolean) = Unit

        override fun startScreenShare(window: android.view.Window) {
            calls += "share"
        }

        override fun stopScreenShare() {
            calls += "unshare"
        }
    }

    private inner class Backend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest) = throw TouchpointException.ContactNotFound()

        override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials {
            requests += request
            gate?.await()
            if (failStart) throw TouchpointException.HttpFailure(502, null)
            return VoiceContactCredentials("v-${starts.size}", "p", "t", "{}", "{}", null)
        }

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        runBlocking {
            val keys = InMemorySecureStore()
            store = ConversationStore.open(dir, keys, AndroidSQLiteDriver())
            session = TouchpointSession(CredentialBroker(Backend(), keys), store, mapOf(Modality.VOICE to transport))
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            session.close()
            store.close()
        }
        dir.deleteRecursively()
    }

    private fun show() =
        rule.setContent {
            TouchpointVoiceView(session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) }, onClose = {})
        }

    private fun waitFor(label: String) = rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun withTheMicrophoneOffAndNoLeaveToAskItSaysSoAndCallsOnceItIsOn() {
        microphone = false
        var chat = 0
        rule.setContent {
            TouchpointVoiceView(session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) }, onClose = {}, onContinueWithChat = { chat++ })
        }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Microphone is off")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, starts.size) // nothing asked, nothing called: the default policy is the host's to change
        rule.onNodeWithText("Open Settings").assertIsDisplayed()
        rule.onNodeWithText("Continue with chat").performClick()
        assertEquals(1, chat)
        // On again — from Settings, say — and Restart calls.
        microphone = true
        rule.onNodeWithContentDescription("Restart conversation").performClick()
        waitFor("Voice")
        assertEquals(1, starts.size)
    }

    @Test
    fun aDropSaysItIsReconnectingAndNoOutputPickerIsShown() {
        show()
        waitFor("Voice")
        val speaker = VoiceOutput("s", VoiceOutput.Kind.SPEAKER)
        val earpiece = VoiceOutput("e", VoiceOutput.Kind.EARPIECE)
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.Outputs(listOf(speaker, earpiece), speaker)) }
        rule.waitForIdle()
        // The designer's answer: the full-screen call does not show where it is heard.
        assertEquals(0, rule.onAllNodes(hasContentDescription("Audio output", substring = true)).fetchSemanticsNodes().size)
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.Dropped) }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Reconnecting the call…")).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.Started(reconnecting = true)) }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Reconnecting the call…")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aHeldCallsRailStartsNothingOpensTheChatAndItsCloseEndsTheCall() {
        val call = TouchpointVoiceCall(session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) })
        var chat = 0
        var menu = 0
        rule.setContent { TouchpointVoiceRail(call, onOpenChat = { chat++ }, onMenu = { menu++ }) }
        // No call, no rail: it never starts one.
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasContentDescription("Open chat")).fetchSemanticsNodes().size)
        assertEquals(0, starts.size)
        rule.runOnUiThread { call.start() }
        waitFor("Open chat")
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.CONNECTED }
        rule.onNodeWithContentDescription("Open chat").performClick()
        rule.onNodeWithContentDescription("More options").performClick()
        assertEquals(1 to 1, chat to menu)
        rule.onNodeWithContentDescription("Microphone").performClick()
        rule.waitUntil(WAIT_MS) { "muted=true" in driver.calls }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.ENDED }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasContentDescription("Open chat")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aCallMadeWithItsNameBuildsItsOwnRequestAndOffersTheShareItAllows() {
        // The call made with its name: the name and the share in one place, both of the share's switches set.
        transport.allowsScreenShare = false
        val call = TouchpointVoiceCall(session, transport, displayName = "Acme Bank", allowsScreenShare = true, attributes = mapOf("screen" to "accounts"))
        rule.runOnUiThread { call.start() }
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.CONNECTED }
        val request = requests.single()
        assertEquals("Acme Bank", request.displayName)
        assertTrue(request.screenShare)
        assertEquals(mapOf("screen" to "accounts"), request.attributes)
        assertTrue(transport.allowsScreenShare)
        // The agent asks: the call says so, and its own start shares the window.
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.ScreenShareSignal(canSend = true)) }
        rule.waitUntil(WAIT_MS) { call.screenShare.value == ScreenShareState.AVAILABLE }
        rule.setContent { val window = checkNotNull(androidx.activity.compose.LocalActivity.current).window; androidx.compose.runtime.LaunchedEffect(Unit) { call.startScreenShare(window) } }
        rule.waitUntil(WAIT_MS) { "share" in driver.calls }
        rule.runOnUiThread { call.end() }
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.ENDED }
    }

    @Test
    fun theFullScreenOverAHeldCallStartsNoneOfItsOwnAndLeavingItKeepsTheCall() {
        val call = TouchpointVoiceCall(session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) })
        rule.runOnUiThread { call.start() }
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.CONNECTED }
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { if (shown) TouchpointVoiceView(call, onClose = {}) }
        waitFor("Voice")
        assertEquals(1, starts.size) // adopted, not called again
        shown = false
        rule.waitForIdle()
        Thread.sleep(300)
        assertEquals(TouchpointVoiceCall.Phase.CONNECTED, call.phase.value)
        assertTrue("stop" !in driver.calls)
        rule.runOnUiThread { call.end() }
        rule.waitUntil(WAIT_MS) { call.phase.value == TouchpointVoiceCall.Phase.ENDED }
    }

    @Test
    fun minimiseHandsTheCallToTheHostAndTheViewGoingLeavesItRunning() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        var ends = 0
        rule.setContent {
            if (shown) {
                TouchpointVoiceView(
                    session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) }, onClose = {},
                    onContactEnded = { ends++ }, onMinimise = { shown = false },
                )
            }
        }
        waitFor("Minimise")
        rule.onNodeWithContentDescription("Minimise").performClick()
        rule.waitForIdle()
        Thread.sleep(300)
        assertTrue("the view hung up the call it handed over", "stop" !in driver.calls)
        assertTrue(session.isActive)
        runBlocking { session.end() }
        rule.waitForIdle()
        assertEquals("a handed-over call is the host's to hear end", 0, ends)
    }

    @Test
    fun theMiniCallsMutesAndItsCloseHangsUp() {
        var closed = 0
        rule.setContent {
            TouchpointVoiceMini(session, transport, onStartCall = { starts += "start"; session.startVoice(VoiceContactRequest("Me")) }, onClose = { closed++ })
        }
        waitFor("Microphone")
        rule.waitUntil(WAIT_MS) { session.state.value == com.amazon.connect.touchpoint.core.ContactState.Connected }
        assertEquals(1, starts.size)
        rule.onNodeWithContentDescription("Microphone").performClick()
        rule.waitUntil(WAIT_MS) { "muted=true" in driver.calls }
        rule.onNodeWithContentDescription("Speakers").performClick()
        rule.waitUntil(WAIT_MS) { "playback=true" in driver.calls }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitUntil(WAIT_MS) { "stop" in driver.calls }
        assertEquals(1, closed)
    }

    @Test
    fun whenTheAgentOpensASessionTheCustomerIsAskedAndThenShares() {
        show()
        waitFor("Voice")
        assertEquals(0, rule.onAllNodes(hasContentDescription("Share screen")).fetchSemanticsNodes().size) // not before the agent asks
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.ScreenShareSignal(canSend = true)) }
        waitFor("Share screen")
        rule.onNodeWithContentDescription("Share screen").performClick()
        rule.onNodeWithText("Share your screen?").assertIsDisplayed()
        assertEquals(0, driver.calls.count { it == "share" }) // asked first
        rule.onNodeWithText("Share").performClick()
        rule.waitUntil(WAIT_MS) { "share" in driver.calls }
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.ScreenShareStarted) }
        waitFor("Stop sharing screen")
        rule.onNodeWithContentDescription("Stop sharing screen").performClick()
        rule.waitUntil(WAIT_MS) { "unshare" in driver.calls }
    }

    @Test
    fun shownItCallsAndTheMicrophoneMutes() {
        show()
        waitFor("Voice")
        assertEquals(1, starts.size)
        rule.onNodeWithContentDescription("Speakers").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voice").performClick()
        rule.waitUntil(WAIT_MS) { "muted=true" in driver.calls }
        assertTrue(transport.call.value.muted)
    }

    @Test
    fun whenTheOtherSideHangsUpItIsWebsEndedScreen() {
        show()
        waitFor("Voice")
        rule.runOnUiThread { driver.events?.invoke(VoiceDriverEvent.Stopped(byUs = false, status = "AudioCallEnded")) }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("The conversation has ended")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCallThatCannotStartIsWebsErrorAndRetryCallsAgain() {
        failStart = true
        show()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Oops!")).fetchSemanticsNodes().isNotEmpty() }
        failStart = false
        rule.onNodeWithText("Retry").performClick()
        waitFor("Voice")
        assertEquals(2, starts.size)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }

    @Test
    fun closingWhileItConnectsHangsUpTheCallThatLands() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>().also { gate = it }
        show()
        rule.waitUntil(WAIT_MS) { starts.isNotEmpty() }
        rule.onNodeWithContentDescription("Close").performClick() // web's voice label; on the loader, before the backend answers
        held.complete(Unit)
        rule.waitUntil(WAIT_MS) { "stop" in driver.calls }
        rule.waitUntil(WAIT_MS) { !session.isActive }
    }
}
