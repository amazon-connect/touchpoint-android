package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import com.amazon.connect.touchpoint.core.TranscriptNotice
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The chat surface on a device: TalkBack labels and states, touch targets, the composer, and layout
 * at large text sizes. `make connected`.
 */
@RunWith(AndroidJUnit4::class)
class ChatSurfaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    private fun msg(body: String, direction: Direction, role: ParticipantRole, seq: Long?, state: DeliveryState, interactive: String? = null) =
        Message(StoredMessage(UUID.randomUUID(), seq?.let { "s$it" }, body, direction, role, sequence = seq, deliveryState = state, interactiveJson = interactive))

    private val conversation =
        listOf(
            msg("Hello! How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1, DeliveryState.DELIVERED),
            msg("Where is this?", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 2, DeliveryState.SENT),
            msg("The Swiss Alps.", Direction.INBOUND, ParticipantRole.ASSISTANT, 3, DeliveryState.DELIVERED),
        )

    private val sent = mutableListOf<String>()
    private var accept = true
    private var announced = 0

    private var restarts = 0
    private var ends = 0
    private var closes = 0
    private var restartAccepted = true

    private var attachResult = true
    private val attached = mutableListOf<String>()
    private val downloaded = mutableListOf<String>()

    /** Stands in for the system's pickers: hands back [picked] for whatever is asked. */
    private var picked: android.net.Uri? = null
    private val registry =
        object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                dispatchResult(requestCode, android.app.Activity.RESULT_OK, android.content.Intent().setData(picked))
            }
        }

    private fun show(state: TranscriptState, fontScale: Float? = null, canRestart: Boolean = false, transcript: String? = null, options: TouchpointChatOptions = TouchpointChatOptions.Default, files: Boolean = false) {
        val actions =
            ChatActions(
                send = { sent += it; accept }, retry = { true }, loadEarlier = {}, close = { closes++ }, dismissProblem = {}, announced = { announced++ },
                restart = if (canRestart) ({ restarts++; restartAccepted }) else null,
                end = { ends++; true },
                transcript = transcript?.let { t -> { t } },
                attach = if (files) ({ upload -> attached += upload.name; upload.open().use { it.readBytes() }; attachResult }) else null,
                download = if (files) ({ file -> downloaded += file.id }) else null,
            )
        rule.setContent {
            val density = LocalDensity.current
            val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            CompositionLocalProvider(
                LocalDensity provides if (fontScale != null) Density(density.density, fontScale) else density,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides owner,
            ) {
                // Files need the host's attachmentsEnabled, as web's (1.0.6).
                ChatSurface(state, actions, options = if (files) TouchpointChatOptions(attachmentsEnabled = true) else options)
            }
        }
    }

    private val connected = TranscriptState(messages = conversation, contactState = ContactState.Connected, initialLoaded = true)

    private fun label(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    /** Everything the live region says, in order, until [done]. */
    private fun spokenUntil(done: (List<String>) -> Boolean): List<String> {
        val seen = mutableListOf<String>()
        // The live region is described only while it speaks: a gap between two lines is no description,
        // so the same line twice is two announcements.
        var last: String? = null
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) {
            val now =
                rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
                    .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            if (now != null && now != last) seen += now
            last = now
            done(seen)
        }
        return seen
    }

    @Test
    fun systemBackLeavesTheSettingsBeforeAnythingCloses() {
        show(connected, transcript = "[10:00] You: hi")
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Download transcript").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        assertTrue(!rule.activity.isFinishing) // the host's Back was not consumed
    }

    @Test
    fun aRefusedRestartIsSpokenAndKeepsTheDraft() {
        restartAccepted = false
        show(connected, canRestart = true)
        rule.onNodeWithContentDescription("Type something").performTextInput("keep me")
        rule.onNode(label("Restart conversation")).performClick()
        spokenUntil { "The conversation could not be restarted. Please try again." in it }
        rule.onNodeWithText("keep me").assertIsDisplayed()
    }

    @Test
    fun anAgentJoiningIsSpokenAsWebAnnouncesIt() {
        var state by mutableStateOf(connected)
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {})
        rule.setContent { ChatSurface(state, actions) }
        rule.waitForIdle()
        state = state.copy(notices = listOf(TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, conversation.last().clientMessageId)))
        spokenUntil { "Agent has joined" in it }
    }

    @Test
    fun anAssistantsMarkdownIsReadAsTextAndItsLinkOpens() {
        // Connected first, so the platform tree is built as it is for TalkBack.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val opened = mutableListOf<String>()
        val md = msg("**Your card** ships _today_.\n\n[Track it](https://acme.example/track)", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED)
        val hostile = msg("[Tap me](javascript:alert(1))", Direction.INBOUND, ParticipantRole.ASSISTANT, 5, DeliveryState.DELIVERED)
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, openLink = { opened += it })
        rule.setContent { ChatSurface(connected.copy(messages = conversation + md + hostile), actions) }
        // TalkBack hears the words, not the asterisks.
        rule.onNodeWithContentDescription("Support: Your card ships today.\nTrack it").assertIsDisplayed()
        // A tap on the link opens it. The message is one node, its words included, so the tap goes
        // where the link is drawn: the start of its last line.
        fun tapLastLine(description: String) = rule.onNodeWithContentDescription(description, substring = true).performTouchInput { click(Offset(LINK_TAP_X.dp.toPx(), height - LINK_TAP_FROM_BOTTOM.dp.toPx())) }
        tapLastLine("Support: Your card ships today.")
        rule.waitForIdle()
        assertEquals(listOf("https://acme.example/track"), opened)
        // TalkBack's way: the platform's own accessibility tree — what TalkBack reads, not Compose's —
        // offers the link as an action on the message.
        fun platformNode(text: String): android.view.accessibility.AccessibilityNodeInfo? {
            val deadline = System.currentTimeMillis() + PLATFORM_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                automation.waitForIdle(PLATFORM_IDLE_MS, PLATFORM_WAIT_MS)
                // Only this app's own window is read: never the lock screen's or another app's.
                val ours = automation.rootInActiveWindow?.takeIf { it.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName }
                // Compose's provider does not answer the platform's search by text: walk the tree.
                fun find(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int): android.view.accessibility.AccessibilityNodeInfo? {
                    if (n == null || depth > MAX_TREE_DEPTH) return null
                    if (n.contentDescription?.contains(text) == true || n.text?.contains(text) == true) return n
                    for (c in 0 until n.childCount) find(n.getChild(c), depth + 1)?.let { return it }
                    return null
                }
                // The node TalkBack focuses: the nearest screen-reader-focusable one around the words.
                var node = find(ours, 0)
                while (node != null && !node.isScreenReaderFocusable) node = node.parent
                node?.let { return it }
                Thread.sleep(PLATFORM_IDLE_MS)
            }
            return null
        }
        val message = platformNode("Support: Your card ships today.")
        assertNotNull("TalkBack can focus the message (is the phone unlocked, the test in front?)", message)
        val open = message?.actionList?.firstOrNull { it.label == "Open link: Track it" }
        assertNotNull("the link is an action on the message", open)
        open?.let { message.performAction(it.id) }
        rule.waitForIdle()
        assertEquals(listOf("https://acme.example/track", "https://acme.example/track"), opened)
        // A link to anything but the web, mail or a phone is only its text: no action, and a tap opens nothing.
        val unlinked = platformNode("Support: Tap me")
        assertTrue(unlinked?.actionList?.none { it.label?.startsWith("Open link") == true } ?: false)
        tapLastLine("Support: Tap me")
        rule.waitForIdle()
        assertEquals(2, opened.size)
    }

    @Test
    fun typingAndAnAssistantsInterimLineAreShownAndSpokenAsWebShowsThem() {
        var state by mutableStateOf(connected.copy(awaitingReply = true))
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {})
        rule.setContent { ChatSurface(state, actions) }
        rule.onAllNodesWithText("Thinking...")[0].assertIsDisplayed()
        state = state.copy(awaitingReply = false, typing = true)
        rule.waitForIdle()
        rule.onAllNodesWithText("Typing...")[0].assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Thinking...").fetchSemanticsNodes().size)
        spokenUntil { "Typing..." in it }
        state = state.copy(typing = false, interimText = "Looking that up…")
        rule.waitForIdle()
        rule.onAllNodesWithText("Looking that up…")[0].assertIsDisplayed()
        spokenUntil { "Looking that up…" in it }
    }

    @Test
    fun typingADraftTellsTheOtherSideButClearingItDoesNot() {
        var typed = 0
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, typing = { typed++ })
        rule.setContent { ChatSurface(connected, actions) }
        rule.onNodeWithContentDescription("Type something").performTextInput("h")
        rule.waitForIdle()
        assertEquals(1, typed)
        rule.onNodeWithContentDescription("Type something").performTextClearance()
        rule.waitForIdle()
        assertEquals(1, typed)
    }

    @Test
    fun withNothingStartingThereIsNoSpinner() {
        // Not started (a host's start that failed leaves the contact here): no "Connecting..." forever.
        show(TranscriptState(contactState = ContactState.Idle, initialLoaded = true))
        assertEquals(0, rule.onAllNodesWithText("Connecting...").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Type something").assertIsNotEnabled()
    }

    @Test
    fun theHeaderIsWebsAndTheMockOnlyControlsAreGone() {
        show(connected)
        // Restart needs the host's start; without it the control is there but disabled.
        rule.onNode(label("Restart conversation")).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Settings").assertIsEnabled()
        rule.onNodeWithContentDescription("Collapse").assertIsEnabled()
        for (gone in listOf("Attach a file", "Voice input", "Copy", "Helpful", "Not helpful")) {
            assertEquals(gone, 0, rule.onAllNodes(label(gone)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun restartIsEnabledWithTheHostsStartAndDisabledWhileAHumanAgentIsActive() {
        show(connected, canRestart = true)
        rule.onNode(label("Restart conversation")).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, restarts)
    }

    @Test
    fun whileAPersonIsWithTheCustomerOnlyTheMenusRestartWaits() {
        // Web: the header's Restart stays; the menu's is disabled until the agent leaves or the chat ends.
        show(connected.copy(humanAgentActive = true), canRestart = true, transcript = "[10:00] You: hi")
        rule.onNode(label("Restart conversation")).assertIsEnabled()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Restart conversation").assertIsNotEnabled()
        rule.onNodeWithText("Download transcript").assertIsEnabled()
    }

    @Test
    fun beforeTheGreetingThereIsOnlyTheLoader() {
        show(TranscriptState(contactState = ContactState.Connecting, initialLoaded = true))
        rule.onNodeWithText("Connecting...").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(label("Type something")).fetchSemanticsNodes().size) // web hides the input until then
        assertEquals(0, rule.onAllNodes(label("Conversation")).fetchSemanticsNodes().size)
    }

    @Test
    fun theGreetingIsAWelcomeScreenUntilTheCustomerSends() {
        val greeting = msg("Hi! How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1, DeliveryState.DELIVERED)
        show(TranscriptState(messages = listOf(greeting), contactState = ContactState.Connected, initialLoaded = true))
        rule.onNodeWithContentDescription("Support: Hi! How can I help?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(label("Conversation")).fetchSemanticsNodes().size) // not the transcript yet
    }

    @Test
    fun aSendShowsThinkingAndTheTranscriptNotesAnAgentJoining() {
        val notice = TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, conversation.last().clientMessageId)
        show(connected.copy(awaitingReply = true, notices = listOf(notice)))
        rule.onNodeWithText("Agent has joined").assertIsDisplayed()
        rule.onAllNodesWithText("Thinking...")[0].assertIsDisplayed()
    }

    @Test
    fun aFailureIsWebsInlineErrorRow() {
        show(connected.copy(problem = true))
        rule.onNodeWithText("Connection lost. Please try again.").assertIsDisplayed()
    }

    @Test
    fun aDropIsWebsFailureRowWhereItHappenedAndNoReconnectingLine() {
        val drop = TranscriptNotice(TranscriptNotice.Kind.CONNECTION_LOST, conversation.last().clientMessageId)
        show(connected.copy(contactState = ContactState.Reconnecting(1), notices = listOf(drop)))
        rule.onNodeWithText("Connection lost. Please try again.").assertIsDisplayed().assertHasNoClickAction() // it stays, as web's does
        assertEquals(0, rule.onAllNodesWithText("Reconnecting…").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed() // the outbox still takes a message
    }

    @Test
    fun aMessageIsCopiedByItsTalkBackActionOrALongPressAsItsWordsNotItsMarkup() {
        show(connected.copy(messages = conversation + msg("Tap **Dispute** to start.", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED)))
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        fun copied() = clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        val node = rule.onNodeWithContentDescription("Support: Tap Dispute to start.").fetchSemanticsNode()
        rule.runOnIdle { node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Copy" }.action() }
        rule.waitForIdle()
        assertEquals("Tap Dispute to start.", copied())
        rule.onNodeWithContentDescription("You: Where is this?").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("Where is this?", copied())
    }

    @Test
    fun everyClickableTargetIsAtLeast48dp() {
        show(connected)
        val floor = with(rule.density) { 48.dp.toPx() } - 1f
        val clickable = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        assertTrue(clickable.isNotEmpty())
        for (node in clickable) {
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)
            assertTrue("$label is ${node.size}", node.size.width >= floor && node.size.height >= floor)
        }
    }

    @Test
    fun aMessageIsOneElementWithItsSenderAndItsDeliveryStateAsText() {
        show(connected)
        rule.onNodeWithContentDescription("You: Where is this?")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sent"))
        rule.onNodeWithContentDescription("Support: The Swiss Alps.").assertIsDisplayed()
    }

    @Test
    fun typingThenSendHandsTheTextOverAndClearsTheBox() {
        show(connected)
        rule.onNodeWithContentDescription("Type something").performTextInput("  pay my bill  ")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        assertEquals(listOf("pay my bill"), sent)
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled() // the box is empty again: Send greys out, as web
    }

    @Test
    fun aRefusedSendPutsTheTextBack() {
        accept = false
        show(connected)
        rule.onNodeWithContentDescription("Type something").performTextInput("try me")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Send message").assertIsEnabled() // text restored, so Send is live again
    }

    @Test
    fun anEndedConversationSaysSoAndOffersAFreshStartInPlaceOfTheComposer() {
        show(connected.copy(contactState = ContactState.Ended(com.amazon.connect.touchpoint.core.EndReason.AGENT_ENDED)), canRestart = true)
        rule.onNodeWithText("Conversation has ended").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(label("Type something")).fetchSemanticsNodes().size) // the composer is gone, as web
        rule.onNodeWithText("Start new conversation").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, restarts)
    }

    @Test
    fun anEndedConversationWithoutTheHostsStartOffersNothingToPress() {
        show(connected.copy(contactState = ContactState.Ended(com.amazon.connect.touchpoint.core.EndReason.CUSTOMER_ENDED)))
        rule.onNodeWithText("Conversation has ended").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Start new conversation").fetchSemanticsNodes().size)
    }

    @Test
    fun aQuickReplyIsAnsweredByTappingAChoiceAndTheChoicesGo() {
        val raw = """{"templateType":"QuickReply","data":{"content":{"title":"What would you like to do?","elements":[{"title":"Check booking status"},{"title":"Cancel booking"}]}}}"""
        val quick = msg("What would you like to do?", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED, InteractiveContent.encode(InteractiveContent("QuickReply", raw)))
        val chosen = mutableListOf<Pair<String, String>>()
        var state by mutableStateOf(connected.copy(messages = conversation + quick))
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, choose = { text, id -> chosen += text to id; true })
        rule.setContent { ChatSurface(state, actions) }
        rule.onNodeWithText("Cancel booking").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("Cancel booking" to "Cancel booking"), chosen)
        // As web, the choices go at once: before the answer is back from the store, a second tap finds nothing.
        assertEquals(0, rule.onAllNodesWithText("Check booking status").fetchSemanticsNodes().size)
        assertEquals(1, chosen.size)
        // Answered: the choices go at once, and the input stays, as web's.
        val answer =
            Message(
                StoredMessage(
                    UUID.randomUUID(), null, "Cancel booking", Direction.OUTBOUND, ParticipantRole.CUSTOMER, deliveryState = DeliveryState.SENDING,
                    interactiveJson = InteractiveContent.encode(InteractiveContent("TouchpointModality", "")),
                ),
            )
        state = state.copy(messages = state.messages + answer)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Check booking status").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
    }

    @Test
    fun aRefusedChoiceIsSpokenAndTheChoicesComeBack() {
        val raw = """{"templateType":"QuickReply","data":{"content":{"title":"Which?","elements":[{"title":"Debit"},{}]}}}"""
        val quick = msg("Which?", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED, InteractiveContent.encode(InteractiveContent("QuickReply", raw)))
        val chosen = mutableListOf<Pair<String, String>>()
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, choose = { text, id -> chosen += text to id; false })
        rule.setContent { ChatSurface(connected.copy(messages = conversation + quick), actions) }
        rule.onNodeWithText("Option 2").performClick() // untitled: web's id, our numbered words
        spokenUntil { "Your choice was not sent. Please try again." in it }
        assertEquals(listOf("Option 2" to "choice-1"), chosen)
        rule.onNodeWithText("Debit").assertIsDisplayed()
    }

    @Test
    fun withoutAWayToAnswerTheChoicesAreShownDisabled() {
        val raw = """{"templateType":"ListPicker","data":{"content":{"title":"Pick","elements":[{"title":"Red"},{}]}}}"""
        val picker = msg("Pick", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED, InteractiveContent.encode(InteractiveContent("ListPicker", raw)))
        show(connected.copy(messages = conversation + picker))
        rule.onNodeWithText("Red").assertIsNotEnabled()
        rule.onNodeWithText("Option 2").assertIsNotEnabled() // untitled, numbered as web numbers it
    }

    @Test
    fun anArrivalWithChoicesSaysHowManyAsWebDoes() {
        val raw = """{"templateType":"QuickReply","data":{"content":{"title":"Which card?","elements":[{"title":"Debit"},{"title":"Rewards"}]}}}"""
        val quick = msg("Which card?", Direction.INBOUND, ParticipantRole.ASSISTANT, 4, DeliveryState.DELIVERED, InteractiveContent.encode(InteractiveContent("QuickReply", raw)))
        show(connected.copy(messages = conversation + quick, announcements = listOf(quick)))
        spokenUntil { "Which card? 2 options to choose from" in it }
    }

    @Test
    fun theSettingsMenuHoldsTheGuidesEntriesAndItsOwnButtonClosesIt() {
        show(connected, canRestart = true, transcript = "[10:00] You: hi")
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Restart conversation").assertIsEnabled()
        rule.onNodeWithText("Download transcript").assertIsEnabled()
        // The guide's settings frame: no End conversation; Restart ends the contact on its way.
        for (gone in listOf("End conversation", "Undo", "Change language", "Talk to the agent", "Report a problem")) {
            assertEquals(gone, 0, rule.onAllNodesWithText(gone).fetchSemanticsNodes().size)
        }
        // Web's header: Settings turns into Close settings, in place of a separate Back.
        rule.onNodeWithContentDescription("Close settings").performClick()
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
    }

    @Test
    fun websAddedCloseAsksBeforeEndingALiveConversationAndCancelLeavesItAlone() {
        show(connected, options = TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)))
        rule.onNode(label("Close")).performClick()
        rule.onNodeWithText("Are you sure?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, ends)
        assertEquals(0, closes)
        assertEquals(0, rule.onAllNodesWithText("Are you sure?").fetchSemanticsNodes().size)
        // This time, yes: the conversation ends and the chat closes.
        rule.onNode(label("Close")).performClick()
        rule.onNodeWithText("End conversation").performClick()
        rule.waitForIdle()
        assertEquals(1, ends)
        assertEquals(1, closes)
    }

    @Test
    fun theHostsWordsAskBeforeEndingAndTheVeilKeepsTheChatUnderItStill() {
        val words = TouchpointChatCopy(TouchpointChatCopy.EndConversationConfirm(title = "Leave?", body = "Save your transcript first.", confirm = "Yes, end conversation", cancel = "No, continue conversation"))
        show(connected, options = TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE), copy = words))
        rule.onNode(label("Close")).performClick()
        rule.onNodeWithText("Leave?").assertIsDisplayed()
        rule.onNodeWithText("Save your transcript first.").assertIsDisplayed()
        // Over the panel, as web's: what is under it is out of TalkBack's reach meanwhile.
        assertEquals(0, rule.onAllNodesWithContentDescription("Type something").fetchSemanticsNodes().size)
        rule.onNodeWithText("No, continue conversation").performClick()
        rule.waitForIdle()
        assertEquals(0, ends)
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        rule.onNode(label("Close")).performClick()
        rule.onNodeWithText("Yes, end conversation").performClick()
        rule.waitForIdle()
        assertEquals(1, ends)
    }

    @Test
    fun theHostsNoticeSitsUnderTheHeader() {
        show(connected, options = TouchpointChatOptions(headerNotice = "Please note that this chat is being recorded."))
        rule.onNodeWithText("Please note that this chat is being recorded.").assertIsDisplayed()
    }

    @Test
    fun aHostSettingsRowClosesTheSettingsThenRuns() {
        var calls = 0
        val actions =
            ChatActions(
                send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {},
                settingsRows = listOf(TouchpointSettingsRow("Call us", prominent = true) { calls++ }),
            )
        rule.setContent { ChatSurface(connected, actions) }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Call us").performClick()
        rule.waitForIdle()
        assertEquals(1, calls)
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
    }

    @Test
    fun onceTheConversationHasEndedWebsAddedCloseOnlyCloses() {
        show(connected.copy(contactState = ContactState.Ended(com.amazon.connect.touchpoint.core.EndReason.CUSTOMER_ENDED)), options = TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)))
        rule.onNode(label("Close")).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Are you sure?").fetchSemanticsNodes().size)
        assertEquals(0, ends)
        assertEquals(1, closes)
    }

    @Test
    fun atTwiceTheTextSizeTheHeaderAndComposerStillFit() {
        show(connected, fontScale = 2f)
        rule.onNodeWithContentDescription("Collapse").assertIsDisplayed()
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun twoIdenticalArrivalsAreBothSpoken() {
        // The live region must change for each: holding the same text twice would say nothing. The
        // queue drains as the model's does, so the second is really reached.
        var state by mutableStateOf(connected.copy(announcements = listOf(conversation[2], conversation[2])))
        val actions =
            ChatActions(
                send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {},
                announced = { state = state.copy(announcements = state.announcements.drop(1)) },
            )
        rule.setContent { ChatSurface(state, actions) }
        val seen = spokenUntil { it.count { s -> s == "The Swiss Alps." } >= 2 }
        // Read as the message itself, as web's announcer reads it, with the region emptied between.
        assertTrue(seen.toString(), seen.indexOf("The Swiss Alps.") < seen.lastIndexOf("The Swiss Alps."))
        rule.waitUntil(5_000) { state.announcements.isEmpty() }
    }

    @Test
    fun theFirstSendFromTheWelcomeKeepsTheComposerFocused() {
        // The welcome draws the composer in its middle; the transcript draws it at the foot. The
        // first send moves it between the two, and the keyboard must stay up.
        var state by mutableStateOf(TranscriptState(messages = listOf(conversation[0]), contactState = ContactState.Connected, initialLoaded = true))
        val actions =
            ChatActions(
                send = { text -> state = state.copy(messages = state.messages + msg(text, Direction.OUTBOUND, ParticipantRole.CUSTOMER, 2, DeliveryState.SENT)); true },
                retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {},
            )
        rule.setContent { ChatSurface(state, actions) }
        val field = rule.onNode(androidx.compose.ui.test.hasSetTextAction())
        field.performClick()
        field.performTextInput("Where is this?")
        field.performImeAction()
        rule.waitUntil(5_000) { state.messages.size == 2 }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun aLongConversationOpensAtItsNewestMessage() {
        // The customer has spoken, so this is the transcript rather than the welcome screen.
        val long = listOf(msg("hi", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 0, DeliveryState.SENT)) + (1L..60L).map { msg("Message $it", Direction.INBOUND, ParticipantRole.ASSISTANT, it, DeliveryState.DELIVERED) }
        show(TranscriptState(messages = long, contactState = ContactState.Connected, initialLoaded = true))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Support: Message 60").assertIsDisplayed()
    }

    @Test
    fun aShrinkingViewportKeepsAReaderAtTheEndThere() {
        val long = listOf(msg("hi", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 0, DeliveryState.SENT)) + (1L..40L).map { msg("Message $it", Direction.INBOUND, ParticipantRole.ASSISTANT, it, DeliveryState.DELIVERED) }
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {})
        var height by mutableStateOf(600.dp)
        rule.setContent {
            Box(Modifier.height(height)) {
                ChatSurface(TranscriptState(messages = long, contactState = ContactState.Connected, initialLoaded = true), actions)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Support: Message 40").assertIsDisplayed()
        height = 300.dp // a host shrinking its panel, or the keyboard opening
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Support: Message 40").assertIsDisplayed()
    }

    private companion object {
        const val PLATFORM_WAIT_MS = 5_000L
        const val PLATFORM_IDLE_MS = 200L
        const val MAX_TREE_DEPTH = 40

        /** Inside a message's last line, at its start: the bubble's 12 and 8 of padding, half its 24 line. */
        const val LINK_TAP_X = 24
        const val LINK_TAP_FROM_BOTTOM = 20
    }

    @Test
    fun participantInfoNamesEachRunAsWebDoesAndTalkBackHearsTheSameNames() {
        val agent = Message(StoredMessage(UUID.randomUUID(), "s4", "I can help.", Direction.INBOUND, ParticipantRole.AGENT, participantId = "a1", sequence = 4, deliveryState = DeliveryState.DELIVERED))
        val again = Message(StoredMessage(UUID.randomUUID(), "s5", "One moment.", Direction.INBOUND, ParticipantRole.AGENT, participantId = "a1", sequence = 5, deliveryState = DeliveryState.DELIVERED))
        show(
            connected.copy(messages = conversation + agent + again, participantNames = mapOf("a1" to "Sam")),
            options = TouchpointChatOptions(showParticipantInfo = true, assistantName = "Ava"),
        )
        rule.onNodeWithContentDescription("Ava: Hello! How can I help?").assertIsDisplayed()
        rule.onNodeWithContentDescription("You: Where is this?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sam: One moment.").assertIsDisplayed()
        // The names on screen are not stops of their own: each message already says who.
        assertEquals(0, rule.onAllNodesWithText("Sam").fetchSemanticsNodes().size)
    }

    @Test
    fun messageTimesAreShownAndReadAsPartOfTheMessageAndTheHeaderTakesItsStyle() {
        val at = java.time.Instant.now()
        val agent = Message(StoredMessage(UUID.randomUUID(), "s4", "I can help.", Direction.INBOUND, ParticipantRole.AGENT, sequence = 4, deliveryState = DeliveryState.DELIVERED, createdAt = at))
        val mine = Message(StoredMessage(UUID.randomUUID(), "s5", "Thanks.", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 5, deliveryState = DeliveryState.READ, createdAt = at))
        show(
            connected.copy(messages = conversation + agent + mine),
            options = TouchpointChatOptions(userMessageTimestamp = true, agentMessageTimestamp = true, headerStyle = TouchpointHeaderStyle(background = androidx.compose.ui.graphics.Color(0xFF002B1A), dark = true)),
        )
        val time = java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (android.text.format.DateFormat.is24HourFormat(rule.activity)) "Hm" else "hm")).format(at.atZone(java.time.ZoneId.systemDefault()))
        // On screen, and read with the message: TalkBack hears it once, as the message's state.
        assertTrue(rule.onAllNodesWithText(time, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithContentDescription("Agent: I can help.").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, time))
        // In the order the guide shows them: the time, then the receipt.
        rule.onNodeWithContentDescription("You: Thanks.").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$time, Read"))
        rule.onNodeWithContentDescription("Restart conversation").assertIsDisplayed()
    }

    @Test
    fun withoutTheWelcomeScreenTheGreetingOpensTheTranscript() {
        val greeting = msg("Hi! How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1, DeliveryState.DELIVERED)
        show(TranscriptState(messages = listOf(greeting), contactState = ContactState.Connected, initialLoaded = true), options = TouchpointChatOptions(welcomeScreen = false))
        rule.onNode(label("Conversation")).assertExists()
        rule.onNodeWithContentDescription("Support: Hi! How can I help?").assertIsDisplayed()
    }

    @Test
    fun theAssistantsBubbleStopsShortOfTheFarSideAsTheGuidesDoes() {
        show(connected, options = TouchpointChatOptions(assistantMessageBubble = true))
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val bubble = rule.onNodeWithContentDescription("Support: The Swiss Alps.").fetchSemanticsNode().boundsInRoot
        // As wide as it reads, and never within the guide's 16 of the far side, inside the transcript's 12.
        assertTrue(bubble.right <= width - with(rule.density) { (12 + 16).dp.toPx() } + 1f)
        rule.onNodeWithContentDescription("You: Where is this?").assertIsDisplayed()
    }

    private fun file(name: String, bytes: Long): android.net.Uri {
        val f = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        return android.net.Uri.fromFile(f)
    }

    @Test
    fun attachIsOfferedOnlyWhileAnAgentIsWithTheCustomerAsOnWeb() {
        var agent by mutableStateOf(false)
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, attach = { true })
        rule.setContent { ChatSurface(connected.copy(humanAgentActive = agent), actions, options = TouchpointChatOptions(attachmentsEnabled = true)) }
        assertEquals(0, rule.onAllNodes(label("Upload file")).fetchSemanticsNodes().size)
        agent = true
        rule.onNode(label("Upload file")).assertIsEnabled()
    }

    @Test
    fun withoutTheHostsAttachmentsFlagThereIsNoAttachEvenWithAnAgent() {
        // Web 1.0.6: attachments are off unless the host turns them on.
        val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, attach = { true })
        rule.setContent { ChatSurface(connected.copy(humanAgentActive = true), actions) }
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(label("Upload file")).fetchSemanticsNodes().size)
    }

    @Test
    fun aPickedFileUploadsAtOnceAndLeavesTheComposerWhenSent() {
        picked = file("photo.png", 3)
        show(connected.copy(humanAgentActive = true), files = true)
        rule.onNode(label("Upload file")).performClick()
        rule.waitUntil(5_000) { attached.isNotEmpty() }
        assertEquals(listOf("photo.png"), attached)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(label("Delete")).fetchSemanticsNodes().size)
    }

    @Test
    fun aFileThatFailsStaysWithWebsErrorUntilRemoved() {
        attachResult = false
        picked = file("photo.png", 3)
        show(connected.copy(humanAgentActive = true), files = true)
        rule.onNode(label("Upload file")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("The file could not be uploaded.").fetchSemanticsNodes().isNotEmpty() || spokenNow().contains("The file could not be uploaded.") }
        rule.onNodeWithText("photo.png").assertIsDisplayed()
        rule.onNode(label("Upload file")).assertIsNotEnabled() // one file at a time
        rule.onNode(label("Delete")).performClick()
        assertEquals(0, rule.onAllNodesWithText("photo.png").fetchSemanticsNodes().size)
        rule.onNode(label("Upload file")).assertIsEnabled()
    }

    @Test
    fun aFileOverEightMegabytesIsRefusedBeforeAnyUpload() {
        picked = file("video.mp4", 8L * 1024 * 1024 + 1)
        show(connected.copy(humanAgentActive = true), files = true)
        rule.onNode(label("Upload file")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("video.mp4").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(spokenUntil { it.contains("The file is too big. Max file size: 8mb") }.isNotEmpty())
        assertTrue(attached.isEmpty())
    }

    @Test
    fun anAgentsFileIsItsNameAndSavesOnATapButTheCustomersOwnDoesNot() {
        val theirs = Message(StoredMessage(UUID.randomUUID(), "s4", "statement.pdf", Direction.INBOUND, ParticipantRole.AGENT, sequence = 4, deliveryState = DeliveryState.DELIVERED, attachmentId = "att-4", attachmentContentType = "application/pdf"))
        val mine = Message(StoredMessage(UUID.randomUUID(), "s5", "photo.png", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 5, deliveryState = DeliveryState.SENT, attachmentId = "att-5", attachmentContentType = "image/png"))
        show(connected.copy(messages = conversation + theirs + mine, humanAgentActive = true), files = true)
        // A human agent is named as the guide shows them: their avatar and name over their messages.
        rule.onNodeWithContentDescription("Agent: statement.pdf")
            .assert(SemanticsMatcher("its tap says Download") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Download" })
            .performClick()
        assertEquals(listOf("att-4"), downloaded)
        rule.onNodeWithContentDescription("You: photo.png").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    private fun spokenNow(): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
}
