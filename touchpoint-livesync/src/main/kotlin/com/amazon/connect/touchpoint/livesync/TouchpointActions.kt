@file:OptIn(com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Declarative actions. The Compose half is TouchpointActionComposables.kt.

/**
 * One declared action, as collected from the composition: what the engine is told — the name, the
 * description, the schema, the scope — and, kept on the device, the handler and whether the customer
 * must confirm it first.
 */
public class TouchpointActionDeclaration(
    public val name: String,
    public val description: String,
    public val schema: JsonObject,
    /** The nearest enclosing `TouchpointScope`'s, if any. */
    public val scope: String? = null,
    /**
     * The customer confirms each invocation before [handler] runs: the client holds it, the SDK asks,
     * and a refusal drops it. For a reversible action the customer should see coming; it does not
     * make an irreversible one (a payment, a submission) safe to declare. Never told to the engine.
     */
    public val confirmationRequired: Boolean = false,
    /** What the customer is asked, in the app's words ("Change your statement address?"); `null` shows the [description]. */
    public val confirmationMessage: String? = null,
    internal val handler: (JsonElement) -> Unit,
) {
    /** What the engine is told. */
    public val advertised: LiveSyncAction get() = LiveSyncAction(name, description, schema)

    /**
     * Equality by content — what would change the advertised context. The handler is left out on
     * purpose: a recomposition that makes a new lambda must not post again.
     */
    public data class Fingerprint(val name: String, val description: String, val schema: JsonObject, val scope: String?)

    public val fingerprint: Fingerprint get() = Fingerprint(name, description, schema, scope)
}

/**
 * Everything the composition declares right now, by name, so two compositions that declare the same
 * things advertise the same context whatever their layout. For a name declared twice, the one
 * declared last wins.
 */
public class TouchpointActionSet(declarations: List<TouchpointActionDeclaration> = emptyList()) {
    public val declarations: List<TouchpointActionDeclaration> = declarations.associateBy { it.name }.values.sortedBy { it.name }

    public val isEmpty: Boolean get() = declarations.isEmpty()

    public val names: List<String> get() = declarations.map { it.name }

    /** The scopes present, sorted; `null` when none carries one, so no `scopes` key is sent, as the imperative call sends none. */
    public val scopes: List<String>? get() = declarations.mapNotNull { it.scope }.toSortedSet().toList().ifEmpty { null }

    /** Equal fingerprints, equal advertised contexts. */
    public val fingerprint: Set<TouchpointActionDeclaration.Fingerprint> get() = declarations.mapTo(HashSet()) { it.fingerprint }

    public val context: LiveSyncContext get() = LiveSyncContext(declarations.map { it.advertised }, scopes)

    public operator fun get(name: String): TouchpointActionDeclaration? = declarations.firstOrNull { it.name == name }

    /** What the customer confirms, by name: on the device only, so not in [fingerprint], but a change still reaches the aggregator. */
    public val confirmationRequirements: Map<String, ActionConfirmation>
        get() = declarations.filter { it.confirmationRequired }.associate { it.name to ActionConfirmation(it.description, it.confirmationMessage) }

    override fun equals(other: Any?): Boolean = other is TouchpointActionSet && other.fingerprint == fingerprint && other.confirmationRequirements == confirmationRequirements

    override fun hashCode(): Int = 31 * fingerprint.hashCode() + confirmationRequirements.hashCode()

    // Names and counts only: a schema can carry the host's field names, never its data, but stays out anyway.
    override fun toString(): String = "TouchpointActionSet(names=$names, scopes=$scopes)"
}

/**
 * The JSON Schema for a payload type, from its serializer: what the engine is told, what the client
 * checks each payload against on the device (the engine does not), and
 * what the handler decodes are one declaration, so they cannot drift. Objects, strings, numbers,
 * booleans, enums (by their serial names) and lists; a property is required only if it is neither
 * nullable nor defaulted, as the engine sends only what the customer said. A shape this cannot
 * describe — a map, a polymorphic class — is an object with no properties: give the schema by hand.
 */
public object TouchpointActionSchema {
    /** For an action with no parameters: an empty object, and nothing else. */
    public val NONE: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", JsonObject(emptyMap()))
    }

    /** A shape this cannot describe (a map, a polymorphic class, a type within itself): any object. */
    internal val UNDESCRIBED: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }

    public fun of(serializer: KSerializer<*>): JsonObject = of(serializer.descriptor)

    /** A type that contains itself — a tree, a list of its own kind — is described once, then as an object of no properties. */
    public fun of(descriptor: SerialDescriptor): JsonObject = of(descriptor, HashSet())

    @OptIn(ExperimentalSerializationApi::class)
    private fun of(
        descriptor: SerialDescriptor,
        seen: MutableSet<String>,
    ): JsonObject {
        val structure = descriptor.kind is StructureKind
        if (structure && !seen.add(descriptor.serialName)) return UNDESCRIBED
        try {
            // A nullable property stays its plain type: the engine turns a type list into a string, and
            // the device's check takes a null for a property that does not admit one as not sent.
            return describe(descriptor, seen)
        } finally {
            if (structure) seen.remove(descriptor.serialName)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun describe(
        descriptor: SerialDescriptor,
        seen: MutableSet<String>,
    ): JsonObject =
        when (val kind = descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            SerialKind.ENUM ->
                buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }))
                }
            StructureKind.LIST ->
                buildJsonObject {
                    put("type", "array")
                    put("items", of(descriptor.getElementDescriptor(0), seen))
                }
            StructureKind.CLASS, StructureKind.OBJECT ->
                buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject((0 until descriptor.elementsCount).associate { descriptor.getElementName(it) to of(descriptor.getElementDescriptor(it), seen) }))
                    val required = (0 until descriptor.elementsCount).filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }
                    if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(descriptor.getElementName(it)) }))
                }
            StructureKind.MAP, is PolymorphicKind, SerialKind.CONTEXTUAL -> UNDESCRIBED
            else -> if (kind is PrimitiveKind) type("string") else UNDESCRIBED
        }

    private fun type(name: String): JsonObject = buildJsonObject { put("type", name) }
}

/**
 * Turns what the composition declares into context posts, and routes each invocation to the handler
 * that declared it.
 *
 * **Debounce.** A navigation is a burst of changes — the old screen's actions go, the new screen's
 * come, a few recompositions settle. The first change starts a [debounce] timer, each further one
 * restarts it, and the post goes when the composition has been quiet for [debounce] or [maxWait]
 * after the first change, whichever is sooner: a composition that never settles still advertises.
 *
 * **By content.** A set equal to the one last advertised starts no timer at all, so recompositions
 * with nothing new, and new lambdas, never post.
 *
 * Main thread only, as the composition that feeds it.
 *
 * **Confirmation.** What the screen declares `confirmationRequired` goes to [confirmations] as it
 * changes — what was advertised and what is up now, together, so an action is never let through
 * by a screen that has just moved on — for the client to hold (`LiveSyncClient.confirmationRequirements`).
 *
 * @param post where contexts go: [LiveSyncClient.sendContext], made by the `client` constructor.
 * @param report where failures go: [LiveSyncClient.onError].
 * @param confirmations where the actions the customer must confirm go: the current client's
 *   `confirmationRequirements`. **Set it** with the main constructor, or `confirmationRequired`
 *   actions run unconfirmed.
 */
public class TouchpointActionAggregator(
    public val debounce: Duration = DEFAULT_DEBOUNCE,
    public val maxWait: Duration = DEFAULT_MAX_WAIT,
    private val report: (LiveSyncException) -> Unit = {},
    private val scope: CoroutineScope = MainScope(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val confirmations: (Map<String, ActionConfirmation>) -> Unit = {},
    private val post: suspend (LiveSyncContext) -> Unit,
) {
    /**
     * Bound to [client]: contexts go to its [LiveSyncClient.sendContext], failures to its
     * [LiveSyncClient.onError], what the customer must confirm to its
     * [LiveSyncClient.confirmationRequirements], and its [LiveSyncClient.onAction] is routed through
     * the declared handlers. For a client per contact under one composition, use the main
     * constructor, send [post] and [confirmations] to whichever client is current, set that client's
     * `onAction` to [route], and call [readvertise] when it changes.
     */
    public constructor(client: LiveSyncClient, debounce: Duration = DEFAULT_DEBOUNCE, maxWait: Duration = DEFAULT_MAX_WAIT) :
        this(
            debounce, maxWait, report = { e -> client.onError?.invoke(e) }, confirmations = { client.confirmationRequirements = it },
            post = { client.sendContext(it) },
        ) {
        client.onAction = ::route
        // Consent re-granted after a denial cleared the client's context: advertise the screen again.
        client.onContextNeeded = ::readvertise
    }

    private val mutableCurrent = MutableStateFlow(TouchpointActionSet())
    private val mutableAdvertised = MutableStateFlow(TouchpointActionSet())
    private val mutableLast = MutableStateFlow<LiveSyncActionInvocation?>(null)
    private val mutablePosts = MutableStateFlow(0)
    private val mutableUnrouted = MutableStateFlow(0)

    /** What the composition declares now. */
    public val current: StateFlow<TouchpointActionSet> = mutableCurrent.asStateFlow()

    /** What was last advertised. */
    public val advertised: StateFlow<TouchpointActionSet> = mutableAdvertised.asStateFlow()

    /** The latest invocation routed, for the debug overlay. */
    public val lastInvocation: StateFlow<LiveSyncActionInvocation?> = mutableLast.asStateFlow()

    /** Contexts posted. */
    public val postCount: StateFlow<Int> = mutablePosts.asStateFlow()

    /** Invocations of a name nothing declares: the engine's context is stale, or a name is misspelt at one end. */
    public val unroutedInvocations: StateFlow<Int> = mutableUnrouted.asStateFlow()

    private var required: Map<String, ActionConfirmation> = emptyMap()

    private var timer: Job? = null
    private var burstMark: kotlin.time.TimeMark? = null
    private var pending: Set<TouchpointActionDeclaration.Fingerprint>? = null
    private var inFlight: Job? = null

    /** The composition reported a set: on every change of what it declares. */
    public fun update(set: TouchpointActionSet) {
        mutableCurrent.value = set
        requireConfirmations()
        val fingerprint = set.fingerprint
        // Nothing new against what is advertised, or already waiting: the timer is not touched, so
        // churn cannot keep putting a real change off. A revert while a change waits is recorded,
        // and the flush then posts nothing.
        if (fingerprint == (pending ?: mutableAdvertised.value.fingerprint)) return
        pending = fingerprint
        schedule()
    }

    /** Posts what is waiting now, without the debounce: when the host knows the composition has settled. */
    public fun flushNow() {
        timer?.cancel()
        timer = null
        burstMark = null
        val waiting = pending ?: return
        pending = null
        // A change reverted within the window nets to nothing: the engine has it already.
        if (waiting == mutableAdvertised.value.fingerprint) return
        val set = mutableCurrent.value
        mutableAdvertised.value = set
        requireConfirmations()
        mutablePosts.value += 1
        val previous = inFlight
        inFlight =
            scope.launch {
                previous?.join() // in order: an older context never lands after a newer one
                try {
                    post(set.context)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not advertised after all: the next report of the same set posts it again.
                    if (mutableAdvertised.value === set) {
                        mutableAdvertised.value = TouchpointActionSet()
                        requireConfirmations()
                    }
                    report(e as? LiveSyncException ?: LiveSyncException.RequestFailed(LiveSyncException.describe(e)))
                }
            }
    }

    /** Advertises what is on show again, to a new client behind [post]: its socket gets the screen already up. */
    public fun readvertise() {
        pending = mutableCurrent.value.fingerprint
        mutableAdvertised.value = TouchpointActionSet()
        // A new client is told what to hold before anything is advertised to it.
        requireConfirmations(force = true)
        flushNow()
    }

    /**
     * Hands [invocation] to the handler that declared it, routing by what was advertised — the
     * engine acted on the context it was given; a screen that has moved on still honours what it
     * promised, and the next post corrects the engine. What the handler throws, or a payload that
     * does not decode, propagates: the client reports it as [LiveSyncException.ActionHandlerFailed].
     */
    public fun route(invocation: LiveSyncActionInvocation) {
        mutableLast.value = invocation
        val advertised = mutableAdvertised.value[invocation.action]
        val current = mutableCurrent.value[invocation.action]
        val declaration = advertised ?: current
        if (declaration == null) {
            mutableUnrouted.value += 1
            TouchpointSecurityEvents.report(TouchpointSecurityEvent.LiveSyncActionUnrouted(invocation.action))
            report(LiveSyncException.ActionHandlerFailed(invocation.action, "no declared action with this name"))
            return
        }
        declaration.handler(invocation.payload)
    }

    /** What the customer must confirm: the advertised set's and the current one's, so neither side of a navigation lets one through. */
    private fun requireConfirmations(force: Boolean = false) {
        val next = mutableAdvertised.value.confirmationRequirements + mutableCurrent.value.confirmationRequirements
        if (next == required && !force) return
        required = next
        confirmations(next)
    }

    private fun schedule() {
        val started = burstMark ?: timeSource.markNow().also { burstMark = it }
        val ceiling = maxWait - started.elapsedNow()
        val wait = minOf(debounce, maxOf(Duration.ZERO, ceiling))
        timer?.cancel()
        timer =
            scope.launch {
                delay(wait)
                flushNow()
            }
    }

    public companion object {
        public val DEFAULT_DEBOUNCE: Duration = 50.milliseconds
        public val DEFAULT_MAX_WAIT: Duration = 300.milliseconds
    }
}
