package com.acmebank.demo

import com.acmebank.demo.support.LiveSyncCoordinator
import com.amazon.connect.touchpoint.livesync.TouchpointActionAggregator
import com.amazon.connect.touchpoint.livesync.TouchpointActionDeclaration
import com.amazon.connect.touchpoint.livesync.TouchpointActionSet
import com.amazon.connect.touchpoint.livesync.LiveSyncContext
import com.amazon.connect.touchpoint.livesync.LiveSyncException
import com.amazon.connect.touchpoint.livesync.LiveSyncConfiguration
import com.amazon.connect.touchpoint.livesync.LiveSyncClient
import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.livesync.LiveSyncAction
import com.amazon.connect.touchpoint.livesync.ConsentDecision
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import com.amazon.connect.touchpoint.core.LiveSyncCredentialSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo's actions against the contract the ACXD node was built from:
 * each screen declares exactly its own, and no action — by name or by field — can submit the dispute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionContractTest {
    private val contract: Map<String, JsonObject> =
        File("src/main/assets/action-contract").listFiles { f -> f.name.endsWith(".schema.json") }.orEmpty().associate { f ->
            f.name.removeSuffix(".schema.json") to Json.parseToJsonElement(f.readText()).jsonObject
        }

    @Test
    fun `every contract file is its action - titled, described, an object of optional fields that refuses others`() {
        assertEquals(setOf("open_account", "filter_transactions", "select_transaction", "start_dispute", "set_dispute_details"), contract.keys)
        for ((name, schema) in contract) {
            assertEquals(JsonPrimitive(name), schema["title"], "$name's title")
            assertTrue((schema["description"] as? JsonPrimitive)?.content.orEmpty().isNotBlank(), "$name has no description for the model to choose on")
            assertEquals(JsonPrimitive("object"), schema["type"])
            assertNull(schema["required"], "$name requires a field: the assistant sends only what the customer said")
            assertEquals(JsonPrimitive(false), schema["additionalProperties"], "$name must refuse fields it does not declare")
        }
    }

    @Test
    fun `the contract is inside the subset the device checks, so no action is refused for its schema alone`() =
        runTest {
            val client =
                LiveSyncClient(
                    LiveSyncConfiguration("us-west-2", "contract-test", LiveSyncChannel.from(java.util.Locale.US)),
                    object : LiveSyncCredentialSource {
                        override suspend fun liveSyncCredentials(): LiveSyncCredentials = throw UnsupportedOperationException("offline")

                        override suspend fun invalidateLiveSyncCredentials() = Unit
                    },
                    consent = ConsentDecision.HELD,
                    autoConnect = false,
                    callbackDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                )
            val reported = mutableListOf<LiveSyncException>()
            client.onError = { reported += it }
            client.sendContext(LiveSyncContext(contract.map { (name, schema) -> LiveSyncAction(name, name, schema) }))
            assertEquals(emptyList(), reported.filterIsInstance<LiveSyncException.InvalidConfiguration>().map { it.detail })
            client.close()
        }

    @Test
    fun `each screen declares exactly its contract actions, in its scope, and every action has a screen`() {
        assertEquals(contract.keys, LiveSyncCoordinator.SCOPE_OF.keys)
        fun inScope(scope: String) = LiveSyncCoordinator.SCOPE_OF.filterValues { it == scope }.keys.sorted()
        assertEquals(listOf("open_account"), inScope(LiveSyncCoordinator.scopeOf("accounts")))
        assertEquals(listOf("filter_transactions", "select_transaction"), inScope(LiveSyncCoordinator.scopeOf("transactions:credit_card")))
        assertEquals(listOf("set_dispute_details", "start_dispute"), inScope(LiveSyncCoordinator.scopeOf("dispute")))
        assertEquals(listOf("set_dispute_details", "start_dispute"), inScope(LiveSyncCoordinator.scopeOf("transaction-detail")))
    }

    @Test
    fun `navigating changes the advertised context each time, with no call of the app's own`() =
        runTest {
            val posted = mutableListOf<LiveSyncContext>()
            val aggregator = TouchpointActionAggregator(scope = backgroundScope, timeSource = testScheduler.timeSource) { posted += it }
            for (scope in listOf("accounts", "transactions", "dispute")) {
                val names = LiveSyncCoordinator.SCOPE_OF.filterValues { it == scope }.keys
                aggregator.update(TouchpointActionSet(names.map { TouchpointActionDeclaration(it, it, contract.getValue(it), scope) {} }))
                aggregator.flushNow()
                runCurrent()
            }
            assertEquals(listOf(listOf("accounts"), listOf("transactions"), listOf("dispute")), posted.map { it.scopes })
        }

    @Test
    fun `no action can submit the dispute - by its name or by any field`() {
        val forbidden = listOf("submit", "confirm", "file", "send_dispute", "finalize", "finalise", "complete")
        for ((name, schema) in contract) {
            for (f in forbidden) assertFalse(f in name.lowercase(), "action $name looks like it submits")
            val fields = (schema["properties"] as? JsonObject)?.keys.orEmpty().map { it.lowercase() }
            for (f in forbidden) assertFalse(fields.any { f in it }, "$name has a submit-like field")
        }
    }

    @Test
    fun `driving every action through the model leaves nothing submitted - submission is the customer's tap alone`() {
        val model = AppModel()
        model.signIn()
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("select_transaction", emptyMap(), null)
        model.apply("start_dispute", emptyMap(), null)
        model.apply("set_dispute_details", mapOf("reason" to "not_recognized", "cardPossession" to "have_card", "authorized" to "false", "additionalDetails" to "x"), null)
        // The model has no submitted state at all: it is the dispute screen's, behind its own button.
        assertTrue(AppModel::class.java.declaredFields.none { "submit" in it.name.lowercase() })
    }
}
