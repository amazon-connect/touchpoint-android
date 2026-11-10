package com.amazon.connect.touchpoint.chat.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The launch button, as web's: the mark and its words open the chat; the mark alone is spoken as the assistant. */
@RunWith(AndroidJUnit4::class)
class TouchpointLaunchButtonTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    @Test
    fun withWordsItIsOneButtonThatOpens() {
        var opened = 0
        rule.setContent { TouchpointLaunchButton(onClick = { opened++ }, label = "Talk to AI Assistant") }
        rule.onNodeWithText("Talk to AI Assistant").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theMarkAloneIsSpokenAsTheAssistant() {
        var opened = 0
        rule.setContent { TouchpointLaunchButton(onClick = { opened++ }) }
        rule.onNodeWithContentDescription("AI Assistant").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }
}
