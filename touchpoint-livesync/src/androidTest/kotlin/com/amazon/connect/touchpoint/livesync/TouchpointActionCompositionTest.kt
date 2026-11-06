package com.amazon.connect.touchpoint.livesync

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** The declarations through a real composition: composed, advertised with their scope; gone, withdrawn; invoked, decoded and handled. */
@RunWith(AndroidJUnit4::class)
class TouchpointActionCompositionTest {
    @get:Rule val rule = createComposeRule()

    @Serializable
    data class Open(val accountType: String? = null)

    @Test
    fun whatIsComposedIsAdvertisedAndWhatGoesIsWithdrawn() {
        val posts = Collections.synchronizedList(mutableListOf<LiveSyncContext>())
        val opened = mutableListOf<String?>()
        var onDispute by mutableStateOf(true)
        var recompositions by mutableIntStateOf(0)
        lateinit var aggregator: TouchpointActionAggregator
        rule.setContent {
            aggregator = androidx.compose.runtime.remember { TouchpointActionAggregator { posts += it } }
            TouchpointLiveSync(aggregator) {
                TouchpointScope("accounts") {
                    // A new lambda each recomposition: still one action, posted once.
                    BasicText("Accounts $recompositions")
                    TouchpointAction("open_account", "Open an account", Open.serializer()) { opened += it.accountType }
                }
                if (onDispute) TouchpointScope("dispute") { TouchpointAction("start_dispute", "Start a dispute") {} }
            }
        }
        rule.waitUntil(WAIT_MS) { posts.isNotEmpty() }
        assertEquals(listOf("open_account", "start_dispute"), posts.last().actions.map { it.name })
        assertEquals(listOf("accounts", "dispute"), posts.last().scopes)
        // Recomposing with nothing new posts nothing.
        repeat(3) { recompositions++; rule.waitForIdle() }
        Thread.sleep(400)
        assertEquals(1, posts.size)
        // The dispute screen goes: its action and scope go with it.
        onDispute = false
        rule.waitUntil(WAIT_MS) { posts.size == 2 }
        assertEquals(listOf("open_account"), posts.last().actions.map { it.name })
        assertEquals(listOf("accounts"), posts.last().scopes)
        // An invocation is decoded into the declared type.
        rule.runOnIdle { aggregator.route(LiveSyncActionInvocation("open_account", buildJsonObject { put("accountType", "credit_card") })) }
        assertEquals(listOf<String?>("credit_card"), opened)
        // The action's name is in no semantics: TalkBack never reads it.
        assertFalse("open_account" in rule.onRoot(useUnmergedTree = true).printToString())
    }

    @Test
    fun outsideTouchpointLiveSyncNothingIsDeclared() {
        val aggregator = TouchpointActionAggregator { }
        // Beside a TouchpointLiveSync, not in it: the one it would reach, were it to leak, sees nothing.
        rule.setContent {
            TouchpointLiveSync(aggregator) { TouchpointAction("inside", "Declared within") {} }
            TouchpointAction("lonely", "Declared nowhere") {}
        }
        rule.waitUntil(WAIT_MS) { aggregator.current.value["inside"] != null }
        assertNull(aggregator.current.value["lonely"])
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
