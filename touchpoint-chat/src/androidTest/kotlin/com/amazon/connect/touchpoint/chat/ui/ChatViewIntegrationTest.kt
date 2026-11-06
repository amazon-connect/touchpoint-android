package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.ChatContactCredentials
import com.amazon.connect.touchpoint.core.ChatContactRequest
import com.amazon.connect.touchpoint.core.ContactTransport
import com.amazon.connect.touchpoint.core.ConversationStore
import com.amazon.connect.touchpoint.core.CredentialBroker
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.ExpiringCredentials
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.Modality
import com.amazon.connect.touchpoint.core.OutboundMessage
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import com.amazon.connect.touchpoint.core.TouchpointBackend
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import com.amazon.connect.touchpoint.core.TransportEvent
import com.amazon.connect.touchpoint.core.VoiceContactCredentials
import com.amazon.connect.touchpoint.core.VoiceContactRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The real session behind the real view, with only the transport scripted: the wiring the surface
 * tests cannot see — a sent message appearing, a reply arriving, a second surface still hearing
 * the session, the status line following the contact. `make connected`.
 */
@RunWith(AndroidJUnit4::class)
class ChatViewIntegrationTest {
    @get:Rule val rule = createComposeRule()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "tp-view-${UUID.randomUUID()}")
    private val transport = Scripted()
    private lateinit var store: ConversationStore
    private lateinit var session: TouchpointSession

    private class Scripted : ContactTransport {
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        private val seq = AtomicLong(1000)
        override val events: Flow<TransportEvent> = channel.receiveAsFlow()

        override suspend fun connect(credentials: ExpiringCredentials) {
            channel.trySend(TransportEvent.Connected)
        }

        override suspend fun send(message: OutboundMessage) {
            channel.trySend(TransportEvent.MessageDelivered(message.clientMessageId, "srv-${message.clientMessageId}", seq.incrementAndGet()))
        }

        override suspend fun disconnect() {}

        fun reply(text: String) =
            channel.trySend(
                TransportEvent.MessageReceived(
                    StoredMessage(UUID.randomUUID(), "srv-${UUID.randomUUID()}", text, Direction.INBOUND, ParticipantRole.AGENT, sequence = seq.incrementAndGet(), deliveryState = DeliveryState.DELIVERED),
                ),
            )
    }

    private class Backend : TouchpointBackend {
        override suspend fun startChatContact(request: ChatContactRequest) = ChatContactCredentials("c-ui", "p-me", "tok", null)

        override suspend fun startVoiceContact(request: VoiceContactRequest): VoiceContactCredentials = throw TouchpointException.ContactNotFound()

        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()
    }

    private fun start() =
        runBlocking {
            val keys = InMemorySecureStore()
            store = ConversationStore.open(dir, keys, AndroidSQLiteDriver())
            session = TouchpointSession(CredentialBroker(Backend(), keys), store, mapOf(Modality.CHAT to transport))
            session.startChat(ChatContactRequest("a"))
        }

    @After
    fun cleanUp() {
        // A test skipped before it started (a phone, for the wide-window one) has nothing to close.
        if (::session.isInitialized) {
            runBlocking {
                session.close()
                store.close()
            }
        }
        dir.deleteRecursively()
    }

    private fun waitFor(label: String) = rule.waitUntil(WAIT_MS) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aSentMessageAppearsAtOnceAndIsAcknowledged() {
        start()
        rule.setContent { TouchpointChatView(session) }
        // As web, the input waits for the greeting.
        transport.reply("Hi! How can I help?")
        // The reply is a person's: named as the guide names an agent.
        waitFor("Agent: Hi! How can I help?")
        rule.onNodeWithContentDescription("Type something").performTextInput("I need help with a charge")
        rule.onNodeWithContentDescription("Send message").performClick()
        waitFor("You: I need help with a charge")
        rule.waitUntil(WAIT_MS) {
            rule.onAllNodes(androidx.compose.ui.test.hasStateDescription("Sent")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anAgentReplyAppears() {
        start()
        rule.setContent { TouchpointChatView(session) }
        transport.reply("Happy to help.")
        waitFor("Agent: Happy to help.")
    }

    @Test
    fun aSurfaceShownAfterAnotherWentAwayStillHearsTheSession() {
        start()
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) TouchpointChatView(session) }
        transport.reply("First.")
        waitFor("Agent: First.")
        shown = false // the sheet is dismissed
        rule.waitForIdle()
        shown = true
        transport.reply("Still here.")
        waitFor("Agent: Still here.")
        rule.onNodeWithContentDescription("Agent: First.").assertIsDisplayed() // and the history came back with it
    }

    @Test
    fun theEndedNoticeFollowsTheContact() {
        start()
        rule.setContent { TouchpointChatView(session) }
        transport.channel.trySend(TransportEvent.ContactEnded(com.amazon.connect.touchpoint.core.EndReason.AGENT_ENDED))
        rule.waitUntil(WAIT_MS) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("Conversation has ended")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun inTheSheetEachMessageIsStillItsOwnElement() {
        start()
        rule.setContent { TouchpointChatSheet(session, visible = true, onDismiss = {}) }
        transport.reply("First.")
        transport.reply("Second.")
        waitFor("Agent: First.")
        waitFor("Agent: Second.")
    }

    /** The chat, mounted while hidden: a draft left in the sheet is there when it opens again. */
    @Test
    fun aDraftLeftInTheSheetIsThereWhenItOpensAgain() {
        start()
        var visible by mutableStateOf(true)
        rule.setContent { TouchpointChatSheet(session, visible = visible, onDismiss = { visible = false }) }
        transport.reply("Hi! How can I help?") // as web, the input waits for the greeting
        waitFor("Type something")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Type something")).performTextInput("half a thought")
        visible = false
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Type something")).fetchSemanticsNodes().isEmpty() }
        visible = true
        waitFor("Type something")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Type something")).assertTextContains("half a thought")
    }

    @Test
    fun theHostHearsOfAnEndOnceEvenWithTheSheetClosedAndNotAgainWhenItReopens() {
        start()
        val ends = java.util.Collections.synchronizedList(mutableListOf<com.amazon.connect.touchpoint.core.EndReason>())
        var visible by mutableStateOf(false)
        rule.setContent { TouchpointChatSheet(session, visible = visible, onDismiss = {}, onContactEnded = { ends += it }) }
        rule.waitForIdle()
        transport.channel.trySend(TransportEvent.ContactEnded(com.amazon.connect.touchpoint.core.EndReason.AGENT_ENDED))
        rule.waitUntil(WAIT_MS) { ends.isNotEmpty() }
        visible = true // the reopened sheet's catch-up replays the end: not news
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(androidx.compose.ui.test.hasText("Conversation has ended")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        org.junit.Assert.assertEquals(listOf(com.amazon.connect.touchpoint.core.EndReason.AGENT_ENDED), ends.toList())
    }

    @Test
    fun aViewOpenedOnAnEndedContactDoesNotReportItsEnd() {
        start()
        transport.channel.trySend(TransportEvent.ContactEnded(com.amazon.connect.touchpoint.core.EndReason.AGENT_ENDED))
        rule.waitUntil(WAIT_MS) { session.state.value is com.amazon.connect.touchpoint.core.ContactState.Ended }
        var ends = 0
        rule.setContent { TouchpointChatView(session, onContactEnded = { ends++ }) }
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(androidx.compose.ui.test.hasText("Conversation has ended")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        org.junit.Assert.assertEquals(0, ends)
    }

    @Test
    fun theSheetCollapsesToTheFloatingCardAndExpandsBackWithTheDraftKept() {
        start()
        var dismissed = 0
        rule.setContent { TouchpointChatSheet(session, visible = true, onDismiss = { dismissed++ }, collapseToMini = true) }
        transport.reply("Hi! How can I help?")
        waitFor("Agent: Hi! How can I help?")
        rule.onNodeWithContentDescription("Type something").performTextInput("half typed")
        rule.onNodeWithContentDescription("Collapse").performClick()
        waitFor("Expand chat")
        // Web's floating card: the whole chat, small — the transcript and the draft are in it.
        rule.onNodeWithContentDescription("Agent: Hi! How can I help?").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasText("half typed")).assertIsDisplayed()
        rule.onNodeWithContentDescription("Expand chat").performClick()
        waitFor("Type something")
        rule.onNode(androidx.compose.ui.test.hasText("half typed")).assertIsDisplayed()
        assertEquals(0, dismissed)
    }

    @Test
    fun anAssistantActionCollapsesTheSheetWhenAskedAndTheCardsCloseDismisses() {
        start()
        val actions = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var dismissed = 0
        rule.setContent { TouchpointChatSheet(session, visible = true, onDismiss = { dismissed++ }, collapseToMiniOn = actions) }
        transport.reply("Opening your card.")
        waitFor("Agent: Opening your card.")
        actions.tryEmit(Unit) // the assistant acted
        waitFor("Expand chat")
        rule.onNodeWithContentDescription("Collapse").performClick() // the card's close, web's Collapse
        rule.waitUntil(WAIT_MS) { dismissed == 1 }
    }

    /** Mini is web's floating card, sized for a phone: small, in the bottom trailing corner, the host's screen around it. */
    @Test
    fun theFloatingCardSitsSmallInTheBottomTrailingCorner() {
        start()
        rule.setContent { TouchpointChatSheet(session, visible = true, onDismiss = {}, windowSize = TouchpointWindowSize.MINI, onWindowSizeChange = {}) }
        waitFor("Expand chat")
        rule.waitForIdle()
        Thread.sleep(WINDOW_SETTLE_MS) // the dialog window's own animation
        val root = rule.onNode(androidx.compose.ui.test.isRoot() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("Expand chat"))).fetchSemanticsNode()
        val metrics = context.resources.displayMetrics
        val screenH = metrics.heightPixels
        val screenW = metrics.widthPixels
        val maxW = (420 + 16) * metrics.density
        org.junit.Assert.assertTrue("the card's window is ${root.size.height} of $screenH high", root.size.height < screenH / 2)
        org.junit.Assert.assertTrue("the card's window is ${root.size.width} wide", root.size.width <= maxW + 1)
        org.junit.Assert.assertTrue("the card sits at the bottom", root.positionOnScreen.y > screenH / 2)
        org.junit.Assert.assertTrue("the card sits at the trailing edge", root.positionOnScreen.x + root.size.width >= screenW - 1)
    }

    @Test
    fun theRealViewOffersAttachOnceAnAgentJoins() {
        // Through TouchpointChatView, not the surface alone: its host-controls wrapper once dropped the attachment actions.
        start()
        // Attachments are the host's to turn on, as web's from 1.0.6.
        rule.setContent { TouchpointChatView(session, options = TouchpointChatOptions(attachmentsEnabled = true)) }
        transport.reply("Hi! How can I help?")
        waitFor("Agent: Hi! How can I help?")
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Upload file")).fetchSemanticsNodes().size)
        transport.channel.trySend(TransportEvent.ParticipantJoined(com.amazon.connect.touchpoint.core.Participant("a1", ParticipantRole.AGENT, "Sam")))
        waitFor("Upload file")
    }

    /**
     * On a tablet-wide window (run under `wm size`; skipped on a phone) the sheet is web's half-screen
     * panel: on the right, full height, half the window or 632 from 1280. A screenshot of it — this
     * test's own window, nothing else — goes to the additional test output for a look.
     */
    @Test
    fun onAWideWindowTheSheetIsWebsHalfPanelOnTheRight() {
        val config = context.resources.configuration
        org.junit.Assume.assumeTrue("needs a window 768 wide or more", config.screenWidthDp >= 768)
        start()
        rule.setContent { TouchpointChatSheet(session, visible = true, onDismiss = {}) }
        transport.reply("Hi! How can I help?")
        waitFor("Agent: Hi! How can I help?")
        rule.waitForIdle()
        Thread.sleep(WINDOW_SETTLE_MS) // the dialog window's own animation
        val root = rule.onNode(androidx.compose.ui.test.isRoot() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasContentDescription("Agent: Hi! How can I help?"))).fetchSemanticsNode()
        val metrics = context.resources.displayMetrics
        val panel = sheetPanelWidth(androidx.compose.ui.unit.Dp(config.screenWidthDp.toFloat())) ?: error("a wide window has a panel")
        val expected = kotlin.math.round(panel.value * metrics.density).toInt()
        org.junit.Assert.assertEquals(expected.toFloat(), root.size.width.toFloat(), 2f)
        org.junit.Assert.assertEquals(metrics.widthPixels.toFloat(), root.positionOnScreen.x + root.size.width, 2f)
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { out ->
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(out, "sheet-${config.screenWidthDp}dp.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
    }

    private companion object {
        const val WINDOW_SETTLE_MS = 1_000L
        const val WAIT_MS = 5_000L
    }
}
