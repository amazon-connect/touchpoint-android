@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.livesync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi
import com.amazon.connect.touchpoint.chat.ui.LiveSyncActionConfirmationSheet
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme
import com.amazon.connect.touchpoint.chat.ui.TouchpointMetrics
import com.amazon.connect.touchpoint.core.DispatchState
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The Compose form of the action, scope and Live Sync declarations.

/** What the declarations under one [TouchpointLiveSync] register with: in the order they came, the latest winning a name. */
private class ActionRegistry {
    private var order = 0L
    val entries = mutableStateMapOf<Any, Pair<Long, TouchpointActionDeclaration>>()

    fun put(
        key: Any,
        declaration: TouchpointActionDeclaration,
    ) {
        entries[key] = order++ to declaration
    }

    fun remove(key: Any) {
        entries.remove(key)
    }

    fun set(): TouchpointActionSet = TouchpointActionSet(entries.values.sortedBy { it.first }.map { it.second })
}

private val LocalActionRegistry = staticCompositionLocalOf<ActionRegistry?> { null }
private val LocalActionScope = staticCompositionLocalOf<String?> { null }

/**
 * Binds the actions declared in [content] to [aggregator]. Once, around what declares actions —
 * the app's navigation host, say: every [TouchpointAction] in it is gathered, debounced and
 * advertised, and every invocation the client receives goes to the one that declared it.
 *
 * An action declared `confirmationRequired` is held by the client: show [TouchpointActionConfirmation]
 * for it, once, wherever the client is known.
 */
@Composable
public fun TouchpointLiveSync(
    aggregator: TouchpointActionAggregator,
    content: @Composable () -> Unit,
) {
    val registry = remember { ActionRegistry() }
    LaunchedEffect(registry, aggregator) { snapshotFlow { registry.set() }.collect(aggregator::update) }
    CompositionLocalProvider(LocalActionRegistry provides registry, content = content)
}

/**
 * The SDK's confirmation for a `confirmationRequired` action [client] is holding:
 * what the assistant asked for — the app's message, else the
 * action's description — with Allow and Cancel. Back, or a tap beside it, is Cancel. Nothing while
 * nothing is held; place it once, above the screens that declare actions.
 */
@Composable
public fun TouchpointActionConfirmation(
    client: LiveSyncClient,
    theme: TouchpointTheme = TouchpointTheme.system(),
) {
    val held by client.pendingConfirmation.collectAsState()
    val scope = rememberCoroutineScope()
    held?.let { pending ->
        LiveSyncActionConfirmationSheet(
            pending.message ?: pending.actionDescription,
            onAllow = { scope.launch { client.confirmPendingAction() } },
            onCancel = { scope.launch { client.cancelPendingAction() } },
            theme = theme,
        )
    }
}

/**
 * Tags every action declared in [content] with [scope]: a hint to the engine of where the customer
 * is ("the dispute screen"), sent with the actions. A screen not composed sends no scope.
 */
@Composable
public fun TouchpointScope(
    scope: String,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalActionScope provides scope, content = content)
}

/**
 * Declares an action the assistant may invoke **while this is composed**: composed, it is
 * advertised; gone, it is withdrawn. No imperative call: what is on screen is the context.
 *
 * Typed by [serializer], and by default its schema comes from the same serializer, so the schema
 * the device checks each payload against and what [handler] decodes cannot drift:
 *
 * ```
 * @Serializable data class DisputeDetails(val reason: Reason? = null, val amount: Double? = null)
 *
 * TouchpointAction("set_dispute_details", "Fill in the dispute form with what the customer said", DisputeDetails.serializer()) { details ->
 *     model.apply(details)
 * }
 * ```
 *
 * Make every field optional: the engine sends only what the customer said. The name never enters
 * the semantics tree — TalkBack reads the screen's own labels, not "set_dispute_details".
 * Outside a [TouchpointLiveSync] this declares nothing.
 *
 * Declare only what the customer can undo — never a payment, a transfer or a submission; leave that
 * last step to the customer's own tap. For a reversible action the customer should see coming, set
 * [confirmationRequired]: each invocation then waits for the customer to allow it
 * ([TouchpointActionConfirmation]), shown [confirmationMessage] or else the [description], and a
 * refusal drops it. The engine is never told.
 */
@Composable
public fun <T> TouchpointAction(
    name: String,
    description: String,
    serializer: KSerializer<T>,
    schema: JsonObject = remember(serializer) { TouchpointActionSchema.of(serializer) },
    json: Json = LiveSyncActionInvocation.PayloadJson,
    confirmationRequired: Boolean = false,
    confirmationMessage: String? = null,
    handler: (T) -> Unit,
) {
    val current by rememberUpdatedState(handler)
    Declare(name, description, schema, confirmationRequired, confirmationMessage) { payload -> current(json.decodeFromJsonElement(serializer, payload)) }
}

/** An action with no parameters. */
@Composable
public fun TouchpointAction(
    name: String,
    description: String,
    confirmationRequired: Boolean = false,
    confirmationMessage: String? = null,
    handler: () -> Unit,
) {
    val current by rememberUpdatedState(handler)
    Declare(name, description, TouchpointActionSchema.NONE, confirmationRequired, confirmationMessage) { current() }
}

/** An action with its schema given by hand, and the payload as JSON: for a contract file shared with the backend. */
@Composable
public fun TouchpointAction(
    name: String,
    description: String,
    schema: JsonObject,
    confirmationRequired: Boolean = false,
    confirmationMessage: String? = null,
    rawHandler: (JsonElement) -> Unit,
) {
    val current by rememberUpdatedState(rawHandler)
    Declare(name, description, schema, confirmationRequired, confirmationMessage) { current(it) }
}

@Composable
private fun Declare(
    name: String,
    description: String,
    schema: JsonObject,
    confirmationRequired: Boolean,
    confirmationMessage: String?,
    handler: (JsonElement) -> Unit,
) {
    val registry = LocalActionRegistry.current ?: return
    val scope = LocalActionScope.current
    val key = remember { Any() }
    // Keyed by content: a recomposition with a new lambda changes nothing that is advertised.
    DisposableEffect(registry, name, description, schema, scope, confirmationRequired, confirmationMessage) {
        registry.put(key, TouchpointActionDeclaration(name, description, schema, scope, confirmationRequired, confirmationMessage, handler))
        onDispose { registry.remove(key) }
    }
}

/**
 * What the composition declares and what has been advertised, for a developer: behind the host's
 * own flag, never in a release. Hidden from TalkBack, so action names are never read aloud, and it
 * takes no touches, so it never covers the host's controls.
 */
@Composable
public fun TouchpointLiveSyncOverlay(
    aggregator: TouchpointActionAggregator,
    modifier: Modifier = Modifier,
    client: LiveSyncClient? = null,
) {
    val current by aggregator.current.collectAsState()
    val advertised by aggregator.advertised.collectAsState()
    val posts by aggregator.postCount.collectAsState()
    val unrouted by aggregator.unroutedInvocations.collectAsState()
    val last by aggregator.lastInvocation.collectAsState()
    val connection = client?.connectionState?.collectAsState()?.value
    val dispatch = client?.dispatchState?.collectAsState()?.value
    val notes = client?.diagnostics?.collectAsState()?.value?.noteFrames ?: 0
    val metrics = TouchpointMetrics.current()
    // Developer diagnostics, not product copy: composed here, in English (translation is for what customers read).
    val text =
        (listOf(LiveSyncOverlayText.status(connection, dispatch, posts), LiveSyncOverlayText.traffic(notes, unrouted, last?.action)) +
            LiveSyncOverlayText.detail(current, advertised, last)).joinToString("\n")
    Column(
        modifier
            .clearAndSetSemantics {}
            .clip(RoundedCornerShape(metrics.smallGap))
            .background(Color(OVERLAY_FILL))
            .padding(metrics.smallGap),
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = metrics.captionText, lineHeight = metrics.captionLineHeight, fontFamily = FontFamily.Monospace))
    }
}

private const val OVERLAY_FILL = 0xCC1B1B1F

/** The overlay's lines, pure, so a test can read them without drawing. */
internal object LiveSyncOverlayText {
    fun status(
        connection: LiveSyncConnectionState?,
        dispatch: DispatchState?,
        posts: Int,
    ): String {
        val c =
            when (connection) {
                LiveSyncConnectionState.Idle -> "idle"
                LiveSyncConnectionState.Connecting -> "connecting"
                LiveSyncConnectionState.Connected -> "connected"
                is LiveSyncConnectionState.Reconnecting -> "reconnecting #${connection.attempt}"
                LiveSyncConnectionState.Disconnected -> "disconnected"
                null -> "no client"
            }
        val d = if (dispatch == DispatchState.SUSPENDED) " · dispatch SUSPENDED" else ""
        return "Live Sync: $c$d · $posts post${if (posts == 1) "" else "s"}"
    }

    /** What the socket delivered: an engine talking but routing nothing, or silent, which the status alone cannot tell apart. */
    fun traffic(
        notes: Int,
        unrouted: Int,
        lastAction: String?,
    ): String =
        buildList {
            add("$notes note${if (notes == 1) "" else "s"}")
            if (unrouted > 0) add("$unrouted UNROUTED")
            add("last: ${lastAction ?: "—"}")
        }.joinToString(" · ")

    /** Advertised names, those declared and waiting, and the last invocation's name and payload keys. */
    fun detail(
        current: TouchpointActionSet,
        advertised: TouchpointActionSet,
        last: LiveSyncActionInvocation?,
    ): List<String> =
        buildList {
            val n = advertised.declarations.size
            add("scopes: ${advertised.scopes?.joinToString(", ") ?: "—"} · $n action${if (n == 1) "" else "s"}")
            advertised.declarations.forEach { add("• ${it.name}" + if (current[it.name] == null) " (withdrawn)" else "") }
            current.declarations.filter { advertised[it.name] == null }.forEach { add("+ ${it.name} (pending)") }
            // The payload's keys, not its values: what the customer said stays off the screen too.
            last?.let { add("← $it") }
        }
}
