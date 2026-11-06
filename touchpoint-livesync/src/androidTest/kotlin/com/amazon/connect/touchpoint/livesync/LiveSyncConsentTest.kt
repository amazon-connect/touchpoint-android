package com.amazon.connect.touchpoint.livesync

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazon.connect.touchpoint.core.ConsentPrompt
import com.amazon.connect.touchpoint.core.ConsentStore
import com.amazon.connect.touchpoint.core.InMemorySecureStore
import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.TouchpointException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The one-line consent: asks when undecided, and the answer reaches the client. */
@RunWith(AndroidJUnit4::class)
class LiveSyncConsentTest {
    @get:Rule val rule = createComposeRule()

    private val client =
        LiveSyncClient(
            LiveSyncConfiguration("us-west-2", "4411ca47-96d8-4bc8-88d8-1b7a6a1dcd98", LiveSyncChannel.from(Locale.US)),
            object : LiveSyncCredentialSource {
                override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw TouchpointException.ContactNotFound()

                override suspend fun invalidateLiveSyncCredentials() = Unit
            },
            consent = ConsentDecision.HELD,
            autoConnect = false,
        )
    private val secure = InMemorySecureStore()
    private val prompt = ConsentPrompt("which screen you are on", "Acme Bank's assistant")

    @After
    fun close() = client.close()

    @Test
    fun undecidedItAsksAndAllowGrantsThenAKeptDecisionAsksNothing() {
        val consent = LiveSyncConsentController(client, ConsentStore(secure, "acme"), prompt)
        rule.setContent { LiveSyncConsent(consent) }
        rule.waitUntil(5_000) { consent.needsPrompt.value }
        rule.onNodeWithText("Allow").performClick()
        rule.waitUntil(5_000) { client.consent == ConsentDecision.GRANTED }
        assertEquals(ConsentDecision.GRANTED, consent.decision.value)
    }

    @Test
    fun declineDenies() {
        val consent = LiveSyncConsentController(client, ConsentStore(secure, "acme"), prompt)
        rule.setContent { LiveSyncConsent(consent) }
        rule.waitUntil(5_000) { consent.needsPrompt.value }
        rule.onNodeWithText("Not now").performClick()
        rule.waitUntil(5_000) { client.consent == ConsentDecision.DENIED }
    }
}
