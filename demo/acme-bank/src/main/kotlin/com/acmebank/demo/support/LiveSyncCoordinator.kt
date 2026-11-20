package com.acmebank.demo.support

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.acmebank.demo.AppModel
import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.livesync.ConsentDecision
import com.amazon.connect.touchpoint.core.ConsentPrompt
import com.amazon.connect.touchpoint.core.ConsentStore
import com.amazon.connect.touchpoint.core.android.AndroidStores
import com.amazon.connect.touchpoint.livesync.LiveSyncConsentController
import com.amazon.connect.touchpoint.livesync.TouchpointActionAggregator
import kotlinx.serialization.json.JsonElement
import com.amazon.connect.touchpoint.livesync.LiveSyncActionInvocation
import com.amazon.connect.touchpoint.livesync.LiveSyncClient
import com.amazon.connect.touchpoint.livesync.LiveSyncConfiguration
import com.amazon.connect.touchpoint.livesync.LiveSyncConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Live Sync for the demo, through the SDK as an app would use it: binds to the contact the app just
 * started — or one a deep link names — and lets the assistant drive the bank through the five dispute
 * actions, which each screen declares while it is composed ([aggregator]; `AssistantActions`). The
 * customer is asked first, once ([consent]), and can stop at any time in Settings. Credentials exist
 * in debug builds only ([LiveMode]); without them this is inert.
 */
class LiveSyncCoordinator(private val context: Context, private val app: AppModel) {
    var state by mutableStateOf<LiveSyncConnectionState>(LiveSyncConnectionState.Idle)
        private set

    /** The newest action, for the on-screen cue and its announcement: a form that fills itself silently is disorienting. */
    var lastAction by mutableStateOf<String?>(null)
        private set

    /** Bumps on every action, so the cue fires once per action — including the same one twice. */
    var actionCount by mutableStateOf(0)
        private set

    /** Each action the assistant takes, for the chat sheet to collapse on (its `collapseToMiniOn`). */
    val actions = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    var contactId by mutableStateOf("")
        private set
    val log = mutableStateListOf<String>()

    val isConfigured: Boolean get() = LiveMode.liveSyncCredentials() != null

    /** The client for the contact bound now, for the overlay. */
    var client by mutableStateOf<LiveSyncClient?>(null)
        private set

    /** What the screens declare, advertised to whichever client is current; its invocations routed back. */
    val aggregator =
        // What the customer must confirm goes to whichever client is current.
        TouchpointActionAggregator(report = { note("error: $it") }, confirmations = { client?.confirmationRequirements = it }) { context ->
            client?.sendContext(context)
            note("context → ${context.scopes.orEmpty().joinToString()}: ${context.actions.joinToString { it.name }}")
        }

    /** The customer's consent, through the SDK: asked once for the deployment, kept, and revocable. */
    var consent by mutableStateOf<LiveSyncConsentController?>(null)
        private set

    /** The SDK's debug overlay, from Settings in debug builds. */
    var overlay by mutableStateOf(false)

    /** The session whose contact this binds to: a human agent joining it stops the assistant moving the app. */
    var session: () -> com.amazon.connect.touchpoint.core.TouchpointSession? = { null }
    private var stateWatch: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Binds to [contactId]; the screen on show is advertised as it declares its actions. */
    fun bind(contactId: String, @Suppress("UNUSED_PARAMETER") screen: String) {
        this.contactId = contactId
        connect()
    }

    fun connect() {
        val credentials = LiveMode.liveSyncCredentials()
        if (credentials == null || contactId.isEmpty()) {
            note("not configured, or no contact id")
            return
        }
        client?.close()
        stateWatch?.cancel()
        // Held until the customer answers the SDK's consent sheet, or answered before for this deployment.
        val c =
            LiveSyncClient(
                LiveSyncConfiguration(LiveMode.region(), contactId, LiveSyncChannel.from(Locale.US)),
                credentials,
                consent = ConsentDecision.HELD,
                autoConnect = false,
            )
        c.onError = { note("error: $it") }
        // Each invocation goes to the screen that declared it.
        c.onAction = aggregator::route
        // Consent re-granted after a denial cleared the client's context: the screen is advertised again,
        // or every invocation is refused as not advertised. The aggregator is client-less, so it is set here.
        c.onContextNeeded = aggregator::readvertise
        consent = LiveSyncConsentController(c, ConsentStore(AndroidStores.secureStore(context), DEPLOYMENT), PROMPT).also { scope.launch { it.load() } }
        c.onNavigation = { note("navigation ${it.kind} (not acted on)") }
        c.onDispatchStateChange = { state, reason -> note("dispatch → $state${reason?.let { " ($it)" }.orEmpty()}") }
        // The gate: registered with the session, so an agent joining suspends dispatch, and a client
        // made while one is on the contact starts suspended.
        session()?.let(c::attach)
        stateWatch = scope.launch { c.connectionState.collect { s -> state = s; note("state → ${s.javaClass.simpleName}") } }
        client = c
        c.connect()
        // The new socket gets the screen already on show.
        aggregator.readvertise()
        note("connecting for contact ${contactId.take(8)}…")
    }

    /** The app went to the background, or came back: the socket follows, and resumes where it was. */
    fun background() {
        client?.enterBackground()
    }

    fun foreground() {
        client?.enterForeground()
    }

    /** Ends the binding: a signed-out customer, or a switch to offline, must not leave the assistant driving the app. */
    fun disconnect() {
        if (client == null) return
        client?.close()
        client = null
        consent = null
        stateWatch?.cancel()
        stateWatch = null
        state = LiveSyncConnectionState.Disconnected
        note("disconnected")
    }

    /**
     * The five actions, with their schemas — the same JSON Schema files the ACXD node was built from,
     * bundled as assets — loaded once, off the main thread. Without a schema the assistant has no
     * typed fields and sends numbers as strings.
     */
    var contract by mutableStateOf<List<ContractAction>>(emptyList())
        private set

    init {
        scope.launch { contract = withContext(Dispatchers.IO) { loadContract() } }
    }

    private fun loadContract(): List<ContractAction> {
        val dir = "action-contract"
        return context.assets.list(dir).orEmpty().filter { it.endsWith(".schema.json") }.sorted().mapNotNull { file ->
            runCatching {
                val schema = Json.parseToJsonElement(context.assets.open("$dir/$file").bufferedReader().use { it.readText() }).jsonObject
                val name = (schema["title"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val description = (schema["description"] as? JsonPrimitive)?.content ?: name
                ContractAction(name, description, SCOPE_OF[name] ?: return@mapNotNull null, JsonObject(schema - setOf("\$schema", "title", "description")))
            }.getOrNull()
        }
    }

    /** What a screen declares: the contract's actions in its scope. */
    fun actionsIn(scopeName: String): List<ContractAction> = contract.filter { it.scope == scopeName }

    /** The assistant invoked [name] from the screen that declared it. */
    fun perform(name: String, payload: JsonElement) {
        val invocation = LiveSyncActionInvocation(name, payload)
        note("ACTION $invocation")
        val body = payload as? JsonObject ?: JsonObject(emptyMap())
        val fields =
            body.mapNotNull { (k, v) ->
                // A JSON null is "not said" — never the string "null".
                val p = (v as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return@mapNotNull null
                k to (p.booleanOrNull?.toString() ?: p.content)
            }.toMap()
        // The schema says number, but a model can still send "240" or "$240.00": accept both.
        // The client has checked the payload against the contract already (`amount` is a number); reading
        // a string amount too is defence in depth only, never a way past that check.
        val raw = body["amount"] as? JsonPrimitive
        val amount = raw?.doubleOrNull?.let { BigDecimal.valueOf(it) } ?: raw?.content?.filter { it.isDigit() || it == '.' }?.toBigDecimalOrNull()
        app.apply(name, fields, amount)
        lastAction = name
        actionCount += 1
        actions.tryEmit(name)
    }

    /**
     * One action of the contract, and the screen scope it belongs to, with the question the
     * customer confirms first, if any ([CONFIRMED]).
     */
    data class ContractAction(val name: String, val description: String, val scope: String, val schema: JsonObject) {
        val confirmationMessage: String? get() = CONFIRMED[name]
    }

    private fun note(text: String) {
        log += "${LocalTime.now().format(time)}  $text"
        if (log.size > LOG_LIMIT) log.removeRange(0, log.size - LOG_LIMIT)
    }

    companion object {
        /**
         * What the customer is asked before each of these runs. Empty for now: with confirmation on,
         * the Live Sync engine never delivers `start_dispute` (an SDK issue), so the dispute screen
         * never opens. Opening it is reversible and submitting stays the customer's tap. Put
         * `"start_dispute" to "Start a dispute for this charge?"` back once the SDK delivers
         * confirmed actions.
         */
        private val CONFIRMED = emptyMap<String, String>()

        private const val LOG_LIMIT = 200

        /** The consent decision is kept per deployment: the demo has one. */
        private const val DEPLOYMENT = "acme-bank-demo"

        /** What the customer is told, before anything is shared. */
        val PROMPT = ConsentPrompt(dataDescription = "which screen you are on and what you can do there", recipient = "${com.acmebank.demo.PartnerBrand.current.bankName}'s assistant, run by Amazon Connect")

        /** The scope a host screen name declares (`transactions:credit_card` → `transactions`). */
        fun scopeOf(screen: String): String =
            when {
                screen.startsWith("transactions") -> "transactions"
                screen == "transaction-detail" || screen == "dispute" -> "dispute"
                else -> "accounts"
            }

        internal val SCOPE_OF =
            mapOf(
                "open_account" to "accounts",
                "filter_transactions" to "transactions",
                "select_transaction" to "transactions",
                "start_dispute" to "dispute",
                "set_dispute_details" to "dispute",
            )
    }
}
