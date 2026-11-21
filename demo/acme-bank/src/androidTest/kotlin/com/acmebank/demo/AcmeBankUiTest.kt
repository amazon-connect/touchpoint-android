package com.acmebank.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.acmebank.demo.support.SupportCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val WAIT_MS = 10_000L
private const val GREETING = "offline demo"
private const val CARD_REPLY = "posted two business days ago"

/**
 * The demo end to end in the real activity: sign in → Support → send → the scripted reply is on
 * screen, with the Accessibility Test Framework checking every interaction — the Android form of the
 * accessibility audit. Offline mode: no network, no credentials. `make connected`.
 */
@RunWith(AndroidJUnit4::class)
class AcmeBankUiTest {
    @get:Rule(order = 0) val notifications = NotificationsGranted()

    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<AcmeBankApplication>()

    @Before
    fun reset() {
        // The application holds the demo's state for the process, and every test shares the process.
        runBlocking { app.support.endChat().join() }
        // Offline, whatever the build: a debug build with live.properties starts in live mode.
        runBlocking { app.support.changeMode(com.acmebank.demo.support.SupportCoordinator.Mode.OFFLINE).join() }
        rule.runOnUiThread { app.model.signOut() }
        rule.enableAccessibilityChecks()
    }

    private fun waitFor(text: String) =
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun count(text: String) = rule.onAllNodes(message(text)).fetchSemanticsNodes().size

    private fun signIn() {
        rule.onNodeWithTag("signin.username").performTextInput("jordan")
        rule.onNodeWithTag("signin.submit").performClick()
        rule.onNodeWithText("Accounts").assertIsDisplayed()
    }

    /**
     * One of the greeting's choices. The greeting scrolls, and so do the choices, held to a share of the
     * panel: on the half sheet the last ones are below the fold of both. Swiped to, as a customer would.
     */
    private fun choose(text: String) {
        // The greeting and its choices both scroll on a half sheet, and scrolling them leaves the composer
        // part-way off the panel: a scroll position, which the checks would measure as a small touch
        // target. Paused for the scroll and the tap; every interaction after is checked again.
        rule.disableAccessibilityChecks()
        try {
            // Scrolled to exactly: a swipe past a long list of choices can leave the one wanted clipped
            // at the panel's edge, where a click misses it.
            rule.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(text))).onFirst().performScrollToNode(hasText(text))
            rule.waitForIdle()
            rule.onNodeWithText(text).performClick()
        } finally {
            rule.enableAccessibilityChecks()
        }
    }

    /** Opens the dock and lets its transition finish, so the checks see the panel at rest. */
    private fun openSupport() {
        rule.onNodeWithTag("support.open").performClick()
        rule.waitForIdle()
    }

    private fun openSupportAndSend(text: String) {
        openSupport()
        waitFor(GREETING)
        rule.onNodeWithContentDescription("Type something").performTextInput(text)
        rule.onNodeWithContentDescription("Send message").performClick()
        waitFor(CARD_REPLY)
    }

    @Test
    fun signInSupportSendAndCheckAccessibility() {
        signIn()
        rule.onRoot().tryPerformAccessibilityChecks()
        openSupportAndSend("Where is my replacement card?")
        rule.onNode(message(CARD_REPLY)).assertIsDisplayed()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    /** The showcase's cards journey, offline: the carousel, a pick filling the flow's slot, then a card with a date input. */
    @Test
    fun showMeCardsIsACarouselWhosePickLeadsToTheDateInput() {
        signIn()
        openSupport()
        waitFor(GREETING)
        choose("Show me cards")
        waitFor("Merchant Harbour Coffee")
        rule.onRoot().tryPerformAccessibilityChecks()
        rule.onNode(hasContentDescription("Merchant Harbour Coffee", substring = true)).performClick()
        waitFor("first notice this charge")
        // The placeholder is the picker's state: one stop for TalkBack.
        rule.onNodeWithContentDescription("Open date picker").assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "MM / DD / YYYY"))
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    /** The sign-in card, offline: the flow's "Sign in" shows web's card and holds the composer; continuing without frees it. */
    @Test
    fun signInCardHoldsTheComposerUntilTheCustomerContinuesWithout() {
        signIn()
        openSupport()
        waitFor(GREETING)
        choose("Sign in")
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Authenticate")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Please authenticate").assertIsDisplayed()
        rule.onNodeWithContentDescription("Complete or skip authentication before continuing.").assertIsNotEnabled()
        rule.onRoot().tryPerformAccessibilityChecks()
        rule.onNodeWithText("Continue without signing in").performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasText("Authentication cancelled")).fetchSemanticsNodes().isNotEmpty() }
        waitFor("keep going without signing in")
        rule.onNodeWithContentDescription("Type something").assertIsEnabled()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun minimiseAndReopenKeepsTheConversation() {
        signIn()
        openSupportAndSend("Where is my replacement card?")
        // Collapse drops the SDK's sheet to web's floating card; the card's own Collapse puts the dock's pill back.
        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.onNodeWithContentDescription("Expand chat").assertIsDisplayed()
        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.onNodeWithTag("support.open").assertIsDisplayed()
        openSupport()
        // The exchange from before is still there, newest on screen: minimising did not end the contact.
        waitFor(CARD_REPLY)
        rule.onNode(message(CARD_REPLY)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Type something").assertIsEnabled()
    }

    @Test
    fun secondChatAfterSignOutIsANewContact() {
        signIn()
        openSupportAndSend("Where is my replacement card?")
        // The sheet's Collapse, to the floating card; the card's own Collapse, closed.
        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.onNodeWithContentDescription("Expand chat").assertIsDisplayed()
        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.onNodeWithTag("nav.settings").performClick()
        rule.onNodeWithTag("settings.signOut").performScrollTo().performClick()
        signIn()
        openSupport()
        // A fresh greeting, and not the old transcript sitting there with a dead contact.
        waitFor(GREETING)
        rule.waitUntil(WAIT_MS) { count(CARD_REPLY) == 0 }
        // Its reply proves the new contact is live.
        rule.onNodeWithContentDescription("Type something").performTextInput("Where is my replacement card?")
        rule.onNodeWithContentDescription("Send message").performClick()
        waitFor(CARD_REPLY)
    }

    @Test
    fun backCollapsesTheSheetThenWalksTheStack() {
        signIn()
        rule.onNodeWithTag("account.cc").performClick()
        openSupport()
        waitFor(GREETING)
        // Back reaches the sheet first, and collapses it to the floating card, the bank showing around it;
        // the card is the whole chat and takes focus, so the next Back closes it, back to the dock's pill.
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithContentDescription("Expand chat").assertIsDisplayed()
        rule.onNodeWithText("${PartnerBrand.current.bankShortName} Rewards Card").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("support.open").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Accounts").assertIsDisplayed()
    }
}

/**
 * Larger text, measured rather than inferred: the bank's own screen lays out taller at a large font
 * scale. (The chat is the SDK's sheet, a window of its own that a composition override cannot reach;
 * the SDK's own suite covers it at twice the text size.)
 */
@RunWith(AndroidJUnit4::class)
class AcmeBankFontScaleTest {
    @get:Rule(order = 0) val notifications = NotificationsGranted()

    @get:Rule(order = 1) val rule = createComposeRule()

    @Test
    fun largerTextGrowsTheTranscript() {
        val model = AppModel().apply { signIn() }
        val support = SupportCoordinator(ApplicationProvider.getApplicationContext())
        var scale by mutableFloatStateOf(1f)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                AcmeBankTheme { Root(model, support) }
            }
        }
        fun height() = rule.onNodeWithText("${PartnerBrand.current.bankShortName} Rewards Card").getBoundsInRoot().let { it.bottom - it.top }.value
        val regular = height()
        scale = 2f
        rule.waitForIdle()
        val large = height()
        assertTrue("the screen did not grow with the font scale: $regular → $large", large > regular * 1.3f)
    }
}

/** A message on screen, not the live region that announces it (which reads the same text). */
private fun message(text: String) = hasContentDescription(text, substring = true) and !SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
