package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazon.connect.touchpoint.core.AuthenticationCard
import com.amazon.connect.touchpoint.core.AuthenticationStatus
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID

/** Web's sign-in card on a device, and the composer it holds. `make connected`. */
@RunWith(AndroidJUnit4::class)
class AuthenticationCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before
    fun auditAccessibility() {
        rule.enableAccessibilityChecks(AccessibilityAudit.validator())
    }

    private val ask = Message(StoredMessage(UUID.randomUUID(), "s1", "Let's get you signed in.", Direction.INBOUND, ParticipantRole.ASSISTANT, sequence = 1, deliveryState = DeliveryState.DELIVERED))
    private val mine = Message(StoredMessage(UUID.randomUUID(), "s0", "Sign in", Direction.OUTBOUND, ParticipantRole.CUSTOMER, sequence = 0, deliveryState = DeliveryState.SENT))
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private val actions =
        ChatActions(
            send = { true }, retry = { true }, loadEarlier = {}, close = {}, dismissProblem = {}, announced = {},
            authentication = AuthenticationActions(begin = { calls += "begin"; null }, cancel = { calls += "cancel:$it" }),
        )

    private fun state(status: AuthenticationStatus) =
        TranscriptState(
            messages = listOf(mine, ask), contactState = ContactState.Connected, initialLoaded = true,
            authenticationCard = AuthenticationCard(UUID.randomUUID(), status, ask.clientMessageId),
        )

    @Test
    fun theSignInTabIsEphemeralAsIosWebAuthSessionIs() {
        // Its own cookie jar: the identity provider's session never lands in the customer's browser.
        assertEquals(true, signInTab().intent.getBooleanExtra(EXTRA_ENABLE_EPHEMERAL_BROWSING, false))
    }

    @Test
    fun theSignInOpensAsAnEphemeralAuthTabThatHandsTheRedirectBack() {
        val url = android.net.Uri.parse("https://idp.example/authorize?state=abc")
        val scheme = signInAuthTab(url, com.amazon.connect.touchpoint.chat.AuthenticationRedirect.Scheme("acmebank-touchpoint"))
        assertEquals(url, scheme.data)
        assertEquals(true, scheme.getBooleanExtra(EXTRA_LAUNCH_AUTH_TAB, false))
        assertEquals(true, scheme.getBooleanExtra(EXTRA_ENABLE_EPHEMERAL_BROWSING, false))
        assertEquals("acmebank-touchpoint", scheme.getStringExtra(EXTRA_REDIRECT_SCHEME))
        val https = signInAuthTab(url, com.amazon.connect.touchpoint.chat.AuthenticationRedirect.Https("bank.example", "/touchpoint/auth"))
        assertEquals("bank.example", https.getStringExtra(EXTRA_HTTPS_REDIRECT_HOST))
        assertEquals("/touchpoint/auth", https.getStringExtra(EXTRA_HTTPS_REDIRECT_PATH))
        // Its answer is the redirect it caught, on OK only.
        val back = android.content.Intent().setData(android.net.Uri.parse("acmebank-touchpoint://auth?code=c&state=abc"))
        assertEquals("acmebank-touchpoint://auth?code=c&state=abc", SignInTabResult.parseResult(android.app.Activity.RESULT_OK, back))
        assertEquals(null, SignInTabResult.parseResult(android.app.Activity.RESULT_CANCELED, back))
    }

    @Test
    fun aPromptOffersWebsTwoActionsAndHoldsTheComposerWithWebsHint() {
        rule.setContent { ChatSurface(state(AuthenticationStatus.PROMPT), actions) }
        rule.onNodeWithText("Please authenticate").assertIsDisplayed()
        rule.onNodeWithText("Authenticate").assertHasClickAction()
        rule.onNodeWithContentDescription("Complete or skip authentication before continuing.").assertIsNotEnabled()
        rule.onNodeWithText("Continue without signing in").performClick()
        rule.waitForIdle()
        assertEquals(listOf("cancel:false"), calls.toList())
    }

    @Test
    fun authenticateAsksTheSessionForThePageAndARefusalOpensNothing() {
        rule.setContent { ChatSurface(state(AuthenticationStatus.PROMPT), actions) }
        rule.onNodeWithText("Authenticate").performClick()
        rule.waitForIdle()
        assertEquals(listOf("begin"), calls.toList())
    }

    @Test
    fun eachOutcomeIsOneStatusLineAndOnlyAPendingCardHoldsTheComposer() {
        var s by mutableStateOf(state(AuthenticationStatus.IN_PROGRESS))
        rule.setContent { ChatSurface(s, actions) }
        rule.onNodeWithText("Authentication in progress").assertIsDisplayed()
        rule.onNodeWithContentDescription("Complete or skip authentication before continuing.").assertIsNotEnabled()
        s = s.copy(authenticationCard = s.authenticationCard?.copy(status = AuthenticationStatus.SUCCESS))
        rule.onNodeWithText("Authentication successful").assertIsDisplayed()
        rule.onNodeWithContentDescription("Type something").assertIsDisplayed()
        s = s.copy(authenticationCard = s.authenticationCard?.copy(status = AuthenticationStatus.EXPIRED))
        rule.onNodeWithText("Authentication expired").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Authenticate")).fetchSemanticsNodes().size)
    }
}
