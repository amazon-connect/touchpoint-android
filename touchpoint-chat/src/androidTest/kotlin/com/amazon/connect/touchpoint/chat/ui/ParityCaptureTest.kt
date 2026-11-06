@file:OptIn(com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.TranscriptNotice
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Not a check: renders the surface at web's phone viewport (412×915) in the conversation the web parity
 * harness renders, and saves a PNG per screen for a side-by-side with Touchpoint web. Does nothing
 * unless asked for: `-Pandroid.testInstrumentationRunnerArguments.parityCapture=true`. Writes to
 * Gradle's additional test output (build/outputs/connected_android_test_additional_output).
 */
@RunWith(AndroidJUnit4::class)
class ParityCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val enabled = InstrumentationRegistry.getArguments().getString("parityCapture") == "true"

    private fun msg(body: String, direction: Direction, role: ParticipantRole, seq: Long, state: DeliveryState = DeliveryState.DELIVERED) =
        Message(StoredMessage(UUID.randomUUID(), "s$seq", body, direction, role, sequence = seq, deliveryState = state))

    private val greeting = msg("Hi! I'm the Acme Bank assistant. How can I help?", Direction.INBOUND, ParticipantRole.ASSISTANT, 1)
    private val base =
        listOf(
            greeting,
            msg("I don't recognize a charge on my card", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 2, DeliveryState.READ),
            msg("I can help with that. Which card is the charge on?", Direction.INBOUND, ParticipantRole.ASSISTANT, 3),
            msg("My rewards card", Direction.OUTBOUND, ParticipantRole.CUSTOMER, 4, DeliveryState.DELIVERED),
        )
    private val connected = TranscriptState(messages = base, contactState = ContactState.Connected, initialLoaded = true)

    private val actions =
        ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, restart = { true }, transcript = { "" }, choose = { _, _ -> true })

    private val withFiles = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, restart = { true }, transcript = { "" }, choose = { _, _ -> true }, attach = { true }, download = {})

    private fun capture(name: String, state: TranscriptState, theme: TouchpointTheme = TouchpointTheme.Dark, dialog: Boolean = false, options: TouchpointChatOptions = TouchpointChatOptions.Default, tablet: Tablet? = null, files: Boolean = false, before: () -> Unit = {}) {
        if (!enabled) return // a tool, not a check: passes trivially unless asked for
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // Web's default text size, whatever the phone is set to.
            // A tablet is drawn at 0.75 px per dp, so 1280 fits on the phone; the window it stands in is given.
            val density = if (tablet != null) TABLET_DENSITY else LocalDensity.current.density
            CompositionLocalProvider(LocalTouchpointTheme provides theme, LocalDensity provides Density(density, 1f), LocalWindowWidth provides tablet?.let { TABLET_WIDTH.dp }) {
                val (w, h) = if (tablet != null) tablet.panelWidth to TABLET_HEIGHT else WIDTH to HEIGHT
                Box(Modifier.requiredSize(w.dp, h.dp).testTag("parity")) { ChatSurface(state, if (files) withFiles else actions, options = options) }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        before()
        rule.mainClock.advanceTimeBy(FRAME_MS)
        // A dialog is its own window, drawn on its own schedule: let it draw, then capture its root.
        if (dialog) {
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
        val image =
            if (dialog) {
                // Compose's capture does not see a dialog's window; the system's screenshot does. Cropped
                // here, on the device, to the dialog's own bounds: nothing else on screen is kept.
                Thread.sleep(WINDOW_ANIMATION_MS) // the window's fade-in
                val card = rule.onNode(isRoot() and hasAnyDescendant(hasText("Are you sure?"))).fetchSemanticsNode()
                val at = card.positionOnScreen
                val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                Bitmap.createBitmap(screen, at.x.toInt(), at.y.toInt(), card.size.width, card.size.height).also { screen.recycle() }
            } else {
                rule.onNodeWithTag("parity").captureToImage().asAndroidBitmap()
            }
        // AGP's additional-output directory, pulled into build/outputs/connected_android_test_additional_output.
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = (out?.let(::File) ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "parity")).apply { mkdirs() }
        File(dir, "android-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun transcript() { capture("transcript-dark", connected.copy(awaitingReply = true)) }

    @Test fun transcriptLight() { capture("transcript-light", connected.copy(awaitingReply = true), TouchpointTheme.Light) }

    @Test fun welcome() { capture("welcome-dark", TranscriptState(messages = listOf(greeting), contactState = ContactState.Connected, initialLoaded = true)) }

    @Test fun connecting() {
        // Mid-cycle, discs apart, as web's capture catches them.
        capture("connecting-dark", TranscriptState(contactState = ContactState.Connecting, initialLoaded = true)) { rule.mainClock.advanceTimeBy(LOADER_APART_MS) }
    }

    @Test fun settings() { capture("settings-dark", connected) { clickSettings() } }

    @Test fun ended() {
        val more = base + msg("I've opened a dispute for you.", Direction.INBOUND, ParticipantRole.ASSISTANT, 5)
        capture("ended-dark", connected.copy(messages = more, contactState = ContactState.Ended(EndReason.AGENT_ENDED)))
    }

    private fun quick(text: String, choices: List<String>, seq: Long): Message {
        val raw = """{"templateType":"QuickReply","data":{"content":{"title":"$text","elements":[${choices.joinToString(",") { "{\"title\":\"$it\"}" }}]}}}"""
        return Message(
            StoredMessage(
                UUID.randomUUID(), "s$seq", text, Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = seq, deliveryState = DeliveryState.DELIVERED,
                interactiveJson = InteractiveContent.encode(InteractiveContent("QuickReply", raw)),
            ),
        )
    }

    @Test fun choices() {
        capture("choices-dark", connected.copy(messages = base.take(2) + quick("Which card is the charge on?", listOf("Acme Rewards Card", "Everyday Debit"), 3)))
    }

    @Test fun welcomeChoices() {
        val welcome = quick("Hi! I'm the Acme Bank assistant. How can I help?", listOf("Dispute a charge", "Card replacement", "Something else"), 1)
        capture("welcome-choices-dark", TranscriptState(messages = listOf(welcome), contactState = ContactState.Connected, initialLoaded = true))
    }

    @Test fun markdown() {
        val reply = msg("Here's what I found on your **Acme Rewards** card:\n\n- A charge of **\$240** at *Northwind Travel*\n- Posted ~~today~~ 2 days ago\n\nYou can [dispute it online](https://acme.example/dispute) or reply here.\n\n---\n\n### Next steps\n1. Confirm the charge\n2. Tell us why", Direction.INBOUND, ParticipantRole.ASSISTANT, 3)
        capture("markdown-dark", connected.copy(messages = base.take(2) + reply))
    }

    @Test fun error() { capture("error-dark", connected.copy(problem = true)) }

    @Test fun agent() {
        val handOff = msg("Let me get someone who can help.", Direction.INBOUND, ParticipantRole.ASSISTANT, 5)
        val sam = msg("Hi, I'm Sam. I can see the charge.", Direction.INBOUND, ParticipantRole.AGENT, 6)
        capture("agent-dark", connected.copy(messages = base + handOff + sam, humanAgentActive = true, notices = listOf(TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, handOff.clientMessageId))))
    }

    // The options, each as web's harness sets them (`opts=`): the same brand, a plain square, on both.
    private val brand = ColorPainter(Color(BRAND))
    private val sam = Message(StoredMessage(UUID.randomUUID(), "s6", "Hi, I'm Sam. I can see the charge.", Direction.INBOUND, ParticipantRole.AGENT, participantId = "a1", sequence = 6, deliveryState = DeliveryState.DELIVERED))
    private val lee = Message(StoredMessage(UUID.randomUUID(), "s7", "Hi, Lee here. I'll take it from here.", Direction.INBOUND, ParticipantRole.AGENT, participantId = "a2", sequence = 7, deliveryState = DeliveryState.DELIVERED))
    private val handOff = msg("Let me get someone who can help.", Direction.INBOUND, ParticipantRole.ASSISTANT, 5)

    @Test fun participantInfo() {
        capture(
            "participant-dark",
            connected.copy(
                messages = base + handOff + sam + lee, humanAgentActive = true, participantNames = mapOf("a1" to "Sam", "a2" to "Lee"), awaitingReply = true,
                notices = listOf(TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, handOff.clientMessageId), TranscriptNotice(TranscriptNotice.Kind.AGENT_LEFT, sam.clientMessageId), TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, sam.clientMessageId)),
            ),
            options = TouchpointChatOptions(showParticipantInfo = true, assistantName = "Ava"),
        )
    }

    @Test fun files() {
        val ask = msg("Could you send me a photo of the receipt?", Direction.INBOUND, ParticipantRole.AGENT, 6)
        val mine = Message(StoredMessage(UUID.randomUUID(), "s7", "receipt.png", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 7, deliveryState = DeliveryState.SENT, attachmentId = "att-7", attachmentContentType = "image/png"))
        val theirs = Message(StoredMessage(UUID.randomUUID(), "s8", "dispute-form.pdf", Direction.INBOUND, ParticipantRole.AGENT, sequence = 8, deliveryState = DeliveryState.DELIVERED, attachmentId = "att-8", attachmentContentType = "application/pdf"))
        capture(
            "files-dark",
            connected.copy(messages = base + handOff + ask + mine + theirs, humanAgentActive = true, notices = listOf(TranscriptNotice(TranscriptNotice.Kind.AGENT_JOINED, handOff.clientMessageId))),
            options = TouchpointChatOptions(attachmentsEnabled = true),
            files = true,
        )
    }

    @Test fun bubbles() { capture("bubbles-dark", connected, options = TouchpointChatOptions(userMessageBubble = false, agentMessageBubble = true)) }

    @Test fun brand() { capture("brand-dark", connected, options = TouchpointChatOptions(brandIcon = brand)) }

    @Test fun welcomeBrand() {
        capture("welcome-brand-dark", TranscriptState(messages = listOf(greeting), contactState = ContactState.Connected, initialLoaded = true), options = TouchpointChatOptions(brandIcon = brand))
    }

    @Test fun flat() { capture("flat-dark", connected, options = TouchpointChatOptions(backgroundDepthLayer = false)) }

    /** Web at 1280 × 800: its half-screen panel (632 from `xl:`), or its full window size. */
    enum class Tablet(val panelWidth: Int) { HALF(632), FULL(TABLET_WIDTH) }

    @Test fun tabletHalf() { capture("tablet-half-dark", connected.copy(awaitingReply = true), tablet = Tablet.HALF) }

    @Test fun tabletFull() { capture("tablet-full-dark", connected.copy(awaitingReply = true), tablet = Tablet.FULL) }

    @Test fun tabletSettings() { capture("tablet-settings-dark", connected, tablet = Tablet.FULL) { clickSettings() } }

    @Test fun tabletWelcome() { capture("tablet-welcome-dark", TranscriptState(messages = listOf(greeting), contactState = ContactState.Connected, initialLoaded = true), tablet = Tablet.FULL) }

    private fun clickSettings() {
        rule.onNodeWithTag("parity").assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Settings")).performClick()
    }

    private companion object {
        const val WIDTH = 412
        const val HEIGHT = 915
        const val FRAME_MS = 500L
        const val WINDOW_ANIMATION_MS = 1_000L
        const val LOADER_APART_MS = 740L
        const val BRAND = 0xFF3B82F6
        const val TABLET_WIDTH = 1280
        const val TABLET_HEIGHT = 800
        const val TABLET_DENSITY = 0.75f
    }
}
