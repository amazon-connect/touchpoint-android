package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazon.connect.touchpoint.core.ConsentPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The UI kit's consent prompt and its Stop sharing: the presentation, drawn as web's card. */
@RunWith(AndroidJUnit4::class)
class ConsentSheetTest {
    @get:Rule val rule = createComposeRule()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    @Test
    fun thePromptSaysWhatIsSharedWithWhomAndTakesEitherAnswer() {
        val answers = mutableListOf<String>()
        rule.setContent {
            LiveSyncConsentSheet(
                ConsentPrompt("which screen you are on", "Acme Bank's assistant", "https://example.com/privacy"),
                onAllow = { answers += "allow" },
                onDecline = { answers += "decline" },
            )
        }
        rule.onNodeWithText("Share what you're doing in the app?").assertIsDisplayed()
        rule.onNodeWithText("While you talk with us, the app can share which screen you are on with Acme Bank's assistant", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Privacy policy").assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(listOf("allow", "decline"), answers)
    }

    @Test
    fun aPolicyThatIsNotAWebPageIsNotLinked() {
        rule.setContent { LiveSyncConsentSheet(ConsentPrompt("x", "y", "javascript:alert(1)"), onAllow = {}, onDecline = {}) }
        rule.onNodeWithText("Allow").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Privacy policy")).fetchSemanticsNodes().size)
    }

    @Test
    fun stopSharingShowsWhileGrantedAndThenSaysSharingStopped() {
        var granted by mutableStateOf(true)
        rule.setContent { LiveSyncConsentRevoke(granted, onRevoke = { granted = false }) }
        rule.onNodeWithText("Stop sharing").performClick()
        rule.onNodeWithText("Sharing stopped. The assistant can no longer see the app.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Stop sharing")).fetchSemanticsNodes().size)
    }
}
