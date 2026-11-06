package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.InteractiveContent
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Web's modalities and the guide card on a device, from frames the live showcase flow sent. `make connected`. */
@RunWith(AndroidJUnit4::class)
class ModalitiesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    private var seq = 0L

    private fun frame(name: String, template: String = InteractiveContent.CHOICE_TEMPLATE): Message {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("showcase/$name.json").use { it.readBytes().decodeToString() }
        val content = InteractiveContent(template, raw)
        return Message(
            StoredMessage(
                UUID.randomUUID(), "s${++seq}", name, Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = seq,
                deliveryState = DeliveryState.DELIVERED, interactiveJson = InteractiveContent.encode(content),
            ),
        )
    }

    private val greeting = Message(StoredMessage(UUID.randomUUID(), "s0", "Hello!", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 0, deliveryState = DeliveryState.SENT))

    private val slots = mutableListOf<Pair<String, String>>()
    private val actions = ChatActions(send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {}, slot = { id, v -> slots += id to v; true })

    private fun state(vararg messages: Message) = TranscriptState(messages = listOf(greeting) + messages, contactState = ContactState.Connected, initialLoaded = true)

    private fun selected(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, on)

    @Test
    fun aCarouselCardFillsTheFlowsSlotWithItsIdAndIsShownSelected() {
        rule.setContent { ChatSurface(state(frame("carousel")), actions) }
        val harbour = hasContentDescription("Merchant Harbour Coffee", substring = true)
        rule.onNode(harbour).assertHasClickAction().assert(selected(false)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Charge" to "harbour"), slots)
        rule.onNode(harbour).assert(selected(true))
        // The guide's spent carousel: one pick, then the others are dimmed and take none.
        rule.onNode(hasContentDescription("Merchant Streamly", substring = true)).assertHasNoClickAction()
        assertEquals(1, slots.size)
    }

    @Test
    fun onlyTheNewestMessagesModalitiesTakeInput() {
        val later = Message(StoredMessage(UUID.randomUUID(), "s9", "Anything else?", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = 9, deliveryState = DeliveryState.DELIVERED))
        rule.setContent { ChatSurface(state(frame("carousel"), later), actions) }
        rule.onNode(hasContentDescription("Merchant Harbour Coffee", substring = true)).assertHasNoClickAction()
    }

    @Test
    fun aCardShowsItsRowsAndTheDateInputCannotSendBeforeADateIsPicked() {
        rule.setContent { ChatSurface(state(frame("cardAndDate")), actions) }
        rule.onNode(hasContentDescription("Amount \$6.75", substring = true)).assertIsDisplayed().assertHasNoClickAction()
        // The placeholder is the picker's state: one stop for TalkBack.
        rule.onNodeWithContentDescription("Open date picker").assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "MM / DD / YYYY"))
        // Two Sends, named apart: the composer's, disabled while empty, and the date's, until a pick.
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Send date").assertIsNotEnabled()
    }

    @Test
    fun aVideoIsItsPosterAndAPlayButtonAndAHostsModalityDrawsWhereTheFlowNamesIt() {
        val drawn = mutableListOf<String>()
        val custom: Map<String, TouchpointModalityContent> =
            mapOf(
                "AcmeDisputeSummary" to { m, _ ->
                    val status = (m.data as JsonObject)["status"]!!.jsonPrimitive.content
                    drawn += status
                    BasicText("Dispute: $status")
                },
            )
        rule.setContent { CompositionLocalProvider(LocalCustomModalities provides custom) { ChatSurface(state(frame("summaryAndVideo")), actions) } }
        rule.onNodeWithText("Dispute: Submitted for review").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play video").assertIsDisplayed().assertHasClickAction()
        assertEquals("Submitted for review", drawn.first())
    }

    @Test
    fun aVideoPlaysInItsCardOnATap() {
        rule.setContent { ChatSurface(state(frame("summaryAndVideo")), actions) }
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.waitForIdle()
        // The poster's play button gives way to the player, in the card: no other app is asked.
        assertEquals(0, rule.onAllNodes(hasContentDescription("Play video")).fetchSemanticsNodes().size)
    }

    @Test
    fun aHostsModalityCannotReplaceWebsDefaults() {
        val custom: Map<String, TouchpointModalityContent> = mapOf("DefaultVideo" to { _, _ -> BasicText("host video") })
        rule.setContent { CompositionLocalProvider(LocalCustomModalities provides custom) { ChatSurface(state(frame("summaryAndVideo")), actions) } }
        rule.onNodeWithContentDescription("Play video").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("host video")).fetchSemanticsNodes().size)
    }

    @Test
    fun aConnectViewIsWebsGuideCardWithItsHeadingShownNotOpened() {
        rule.setContent { ChatSurface(state(frame("view", "ViewResource")), actions) }
        rule.onNodeWithText("Before we connect you").assertIsDisplayed()
        rule.onNodeWithText("Tell us a little about your request.").assertIsDisplayed()
        rule.onNodeWithText("Not started").assertIsDisplayed()
        rule.onNode(hasText("Before we connect you")).assertHasNoClickAction()
    }

    private val resolved = com.amazon.connect.touchpoint.core.ResolvedView("v1", "Form", 1, "{}", "{}", listOf("Submit"))

    @Test
    fun aResolvedGuideIsTitledByItsViewAndOpensOverTheTranscriptInProgress() {
        val host = ViewHost("https://example.invalid", describe = { resolved }, submit = { _, _, _ -> true })
        rule.setContent { CompositionLocalProvider(LocalViewHost provides host) { ChatSurface(state(frame("view", "ViewResource")), actions) } }
        // Web's title rule: the view's name once resolved, the heading before.
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Form")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Form").assertHasClickAction().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("In progress").assertIsDisplayed()
    }

    @Test
    fun aViewThatCannotBeResolvedSaysSoAndDoesNotOpen() {
        val host = ViewHost("https://example.invalid", describe = { null }, submit = { _, _, _ -> true })
        rule.setContent { CompositionLocalProvider(LocalViewHost provides host) { ChatSurface(state(frame("view", "ViewResource")), actions) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Couldn't load the guide")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Couldn't load the guide")).assertHasNoClickAction()
    }
}
