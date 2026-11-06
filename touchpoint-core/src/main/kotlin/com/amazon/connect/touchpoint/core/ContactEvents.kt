package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Events

/** Message identifier as the server assigns it. Increment-1 chat uses the Connect message id. */
@JvmInline
public value class MessageId(public val rawValue: String)

/** Whether Live Sync is allowed to dispatch invocations into the host right now. */
public enum class DispatchState {
    ENABLED,
    SUSPENDED,
}

/** Why dispatch changed. Surfaced, never silent. */
public enum class DispatchChangeReason {
    /** A human agent-side participant joined the contact. */
    HUMAN_AGENT_JOINED,

    /** The last human agent-side participant left. */
    HUMAN_AGENT_LEFT,

    /**
     * Before reconciliation: resumed from the store, or reconnecting after a dropped connection.
     * Dispatch stays off until the server transcript confirms who is on the contact.
     */
    RESUMED_UNRECONCILED,

    /** Reconciliation found no human agent on the contact; the safe default lifts. */
    TRANSCRIPT_RECONCILED,
}

/**
 * One normalised stream over dissimilar transports. Cases for voice and Live Sync are declared now
 * so the hierarchy is complete at publication; Core emits the chat and lifecycle ones.
 */
public sealed interface ContactEvent {
    public data class StateChanged(val state: ContactState) : ContactEvent

    public data class MessageReceived(val message: Message) : ContactEvent

    /**
     * One of *our* messages moved along `SENDING → SENT → DELIVERED → READ` (or to `FAILED`), keyed
     * on the client id `send()` returned, which is what a UI already holds. The server's id and
     * ordering ride along on the `SENT` transition that assigns them.
     */
    public data class DeliveryStateChanged(val update: DeliveryUpdate) : ContactEvent

    /** The transcript settled after a reconnect or resume. Carries no counts on purpose. */
    public data object TranscriptReconciled : ContactEvent

    public data class ParticipantsChanged(val participants: ParticipantSet) : ContactEvent

    public data class LiveSyncDispatchChanged(val state: DispatchState, val reason: DispatchChangeReason) : ContactEvent

    public data class PermissionRequired(val permission: Permission) : ContactEvent

    public data class Failed(val error: TouchpointException) : ContactEvent

    public data class ContactEnded(val reason: EndReason) : ContactEvent

    /**
     * A line the transcript shows between messages, as Touchpoint web's: an agent joining or leaving,
     * or the connection dropping. Live only: a resume reconciles who is on the contact silently, as web
     * has no reload to replay one from. Replayed to a late subscriber, like the messages around it, and
     * kept past the end: an ended chat on web still shows the agent leaving.
     */
    public data class Notice(val notice: TranscriptNotice) : ContactEvent

    /**
     * The sign-in card changed: the flow's "Authenticate Customer" block. While it is pending the
     * composer is held, as web's input is. Replayed to a late subscriber, the current card
     * synthesised when the replay lost it; gone with the contact.
     */
    public data class AuthenticationChanged(val card: AuthenticationCard) : ContactEvent

    /**
     * Someone on the other side is typing — an agent, or an assistant that reports it. Momentary: it
     * lapses on its own (Touchpoint web shows it for five seconds) and is never replayed. The customer's
     * own typing is not reported.
     */
    public data class Typing(val role: ParticipantRole) : ContactEvent

    /**
     * A line the assistant shows while it works ("Looking that up…"), as Touchpoint web shows its
     * interim message: until the next message. Momentary and never replayed; [toString] omits the text
     *.
     */
    public data class InterimMessage(val text: String) : ContactEvent {
        override fun toString(): String = "InterimMessage(text=<redacted>)"
    }
}

/** One of web's transcript notices. See [ContactEvent.Notice]. */
public data class TranscriptNotice(
    val kind: Kind,
    /** The newest message when it happened; `null` when the conversation had none. Drawn after it. */
    val afterMessageId: UUID?,
    /** When it happened: a surface that opens later can tell a replayed notice from news. */
    val at: java.time.Instant = java.time.Instant.now(),
    val id: UUID = UUID.randomUUID(),
) {
    public enum class Kind {
        /** A participant with the agent role joined. As web: an agent only, not a supervisor. */
        AGENT_JOINED,
        AGENT_LEFT,

        /** A live connection dropped (web's `Failure` response). Reconnection is automatic; this says it happened. */
        CONNECTION_LOST,
    }
}

// Transport seam

/**
 * What a transport tells the coordinator. Deliberately small: the coordinator normalises these into
 * [ContactEvent] and owns every state decision.
 */
public sealed interface TransportEvent {
    public data object Connected : TransportEvent

    /**
     * The connection dropped and came back **by itself** — an SDK that reconnects on its own after a
     * network blip or a spell in the background — with no `Disconnected` in between. Whatever
     * happened meanwhile is unknown, so the session treats it as a reconnect: dispatch off, the
     * transcript reconciled, dispatch back only if no human is on the contact.
     */
    public data object Reestablished : TransportEvent

    /** [error] is a case name or identifier, never a response body or message text. */
    public data class Disconnected(val error: String?) : TransportEvent

    /**
     * An inbound message, or the server echo of one of ours (same server id; the store
     * deduplicates). Inbound rows arrive as `DELIVERED`: the transport has already acknowledged them
     * on the wire by the time the coordinator sees them.
     */
    public data class MessageReceived(val message: StoredMessage) : TransportEvent

    /** The server accepted our send: `SENDING → SENT`, with the id and ordering it assigned. */
    public data class MessageDelivered(val clientMessageId: UUID, val serverMessageId: String, val sequence: Long?) : TransportEvent

    /** The other side acknowledged one of our messages. */
    public data class Receipt(val serverMessageId: String, val kind: ReceiptKind) : TransportEvent

    public data class ParticipantJoined(val participant: Participant) : TransportEvent

    public data class ParticipantLeft(val id: String) : TransportEvent {
        override fun toString(): String = "ParticipantLeft(id=<redacted>)"
    }

    public data class ContactEnded(val reason: EndReason) : TransportEvent

    /** The other side is typing. */
    public data class Typing(val role: ParticipantRole) : TransportEvent

    /** An assistant's interim line. [toString] omits the text. */
    public data class InterimMessage(val text: String) : TransportEvent {
        override fun toString(): String = "InterimMessage(text=<redacted>)"
    }

    /** The sign-in card [id] moved to [status]. A new id is a new card: a fresh `initiated`. */
    public data class AuthenticationChanged(val id: UUID, val status: AuthenticationStatus) : TransportEvent

    /**
     * Something the transport refused for the customer's safety — a sign-in redirect that was not
     * Connect's — for the host's events as a [ContactEvent.Failed] that ends nothing (a
     * `securityRejection`). [error] names why, never what was refused.
     */
    public data class SecurityRejection(val error: TouchpointException) : TransportEvent
}

/**
 * One of our messages moved along the delivery states. A payload rather than bare properties on the
 * event so the server's ordering can ride along without every pattern changing again.
 *
 * @property clientMessageId The id `send()` returned — what a UI already holds.
 * @property serverMessageId First known on the `SENT` transition and carried on the later ones;
 *   `null` only for `SENDING` and for a `FAILED` that never reached the server.
 * @property sequence The server's ordering, on the transition that assigns it.
 */
public data class DeliveryUpdate(
    val clientMessageId: UUID,
    val state: DeliveryState,
    val serverMessageId: MessageId? = null,
    val sequence: Long? = null,
)

/**
 * One page of server transcript, oldest first. Participant joins and leaves are returned as the
 * events they are so the coordinator can apply them to its **set** in server order.
 *
 * @property nextToken Opaque token for the page *before* this one; `null` when the transcript is exhausted.
 */
/**
 * One line of the server's own transcript, as Connect records it — for the customer's copy of the
 * conversation, Touchpoint web's Download transcript: when ([absoluteTime], the server's own form),
 * who ([name], the display name, else the role), and the words. [toString] omits the name and the
 * words.
 */
public data class TranscriptEntry(
    val absoluteTime: String,
    val name: String,
    val role: ParticipantRole,
    val text: String,
) {
    override fun toString(): String = "TranscriptEntry(absoluteTime=$absoluteTime, role=$role, name=<redacted>, text=<redacted>)"
}

public data class TranscriptPage(
    val messages: List<StoredMessage>,
    val participantEvents: List<TransportEvent>,
    val nextToken: String?,
) {
    public companion object {
        public val EMPTY: TranscriptPage = TranscriptPage(emptyList(), emptyList(), null)
    }
}

/**
 * A modality transport. Production implementations wrap the Connect chat SDK and Chime
 * (Phase 4); Core proves the coordinator against stubs.
 *
 * **Ordering contract.** The coordinator starts collecting [events] *before* calling [connect], but
 * a flow's collection can begin after that call returns, so a transport must buffer events until its
 * collector attaches. Dropping pre-collection events leaves the session stuck in `Connecting`.
 *
 * **Threading.** Every member may be called from any thread and must not block it.
 */
public interface ContactTransport {
    /**
     * The transport's events. Ideally one flow that survives reconnects and completes only on
     * [disconnect]; a flow that completes (or throws) while the contact is live is treated as a lost
     * connection, and collected afresh after the reconnect's [connect] returns.
     */
    public val events: Flow<TransportEvent>

    /** Connect using credentials the broker vended. Must emit [TransportEvent.Connected] on success. */
    public suspend fun connect(credentials: ExpiringCredentials)

    public suspend fun send(message: OutboundMessage)

    /** End this participant's connection to the contact: the customer, or the contact, is done. */
    public suspend fun disconnect()

    /**
     * Drop the connection but leave the participant on the contact, so a later `resume` can pick
     * it up — the session's response to a connection it has given up on. Default: [disconnect], for a
     * transport that cannot tell the two apart.
     */
    public suspend fun release() {
        disconnect()
    }

    /**
     * The server transcript, newest page first; [before] is the previous page's `nextToken`. Default:
     * an empty page — a transport with no server-side history (voice) need not implement it.
     */
    public suspend fun fetchTranscript(before: String?, limit: Int): TranscriptPage = TranscriptPage.EMPTY

    /** Send a `read` receipt for an inbound message. `read` is host-driven. Default: no-op. */
    public suspend fun markRead(upToServerMessageId: String) {}

    /** Tell the other side the customer is typing. Best effort. Default: no-op, for a transport without it. */
    public suspend fun sendTyping() {}

    /**
     * The newest [limit] lines of the server's transcript, oldest first, each as the customer should
     * read it. Default: none, for a transport with no server-side history.
     */
    public suspend fun fetchTranscriptEntries(limit: Int): List<TranscriptEntry> = emptyList()

    /**
     * Upload a file the customer sends, all the way: started, sent and completed. Returns Connect's
     * attachment id; the server then echoes it as a message, which the store keys on that id. Default:
     * not supported, for a transport that cannot carry files.
     */
    public suspend fun uploadAttachment(file: AttachmentUpload): String = throw TouchpointException.InvalidConfiguration("this transport cannot send files")

    /** A short-lived address to fetch an attachment from, asked for afresh each time. Default: not supported. */
    public suspend fun attachmentUrl(attachmentId: String): String = throw TouchpointException.InvalidConfiguration("this transport cannot fetch files")

    /**
     * Sign-in, Connect's "Authenticate Customer": the page to open for the card on screen. Default:
     * not offered, for a transport without it.
     */
    public suspend fun beginAuthentication(): AuthenticationRequest = throw TouchpointException.InvalidConfiguration("this contact does not offer sign-in")

    /** The redirect the sign-in page came back with. Default: not offered. */
    public suspend fun completeAuthentication(redirect: String): Unit = throw TouchpointException.InvalidConfiguration("this contact does not offer sign-in")

    /**
     * Continue without signing in: the flow takes its opted-out branch. With [unlessRedirected], only
     * while the page's redirect has not come back — the page was closed. Default: nothing.
     */
    public suspend fun cancelAuthentication(unlessRedirected: Boolean = false) {}

    /** A Connect View, by the token its message carries (`DescribeView`). Default: not supported. */
    public suspend fun describeView(viewToken: String): ResolvedView = throw TouchpointException.InvalidConfiguration("this transport cannot open views")
}

// Cross-module control seam

/**
 * Core declares it; `touchpoint-livesync` implements it and registers. With nothing registered the
 * coordinator's calls are no-ops — correct for a chat-only consumer.
 */
public interface LiveSyncControl {
    public suspend fun suspendDispatch(reason: DispatchChangeReason)

    public suspend fun resumeDispatch()

    /** Full teardown, not a resumable disconnect. */
    public suspend fun teardown()
}

// Reconnection policy (bounded, deliberately)

/**
 * @property jitterFactor One-sided (0…+factor), so the delay can never collapse to zero — the
 *   absorbed implementation's symmetric jitter could.
 */
public data class ReconnectionPolicy(
    val maxAttempts: Int = 6,
    val totalBudget: Duration = 60.seconds,
    val initialDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 15.seconds,
    val jitterFactor: Double = 0.25,
) {
    /** Delay before [attempt] (1-based). Exponential with a cap and positive-only jitter. */
    public fun delay(forAttempt: Int, random: Double = Random.nextDouble()): Duration {
        val base = initialDelay * (1 shl (forAttempt - 1).coerceIn(0, 20))
        val capped = if (base > maxDelay) maxDelay else base
        // A NaN or negative factor or draw would throw here, inside the reconnect path: no jitter instead.
        val jitter = jitterFactor.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val draw = random.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        return capped * (1 + jitter * draw)
    }

    public companion object {
        public val DEFAULT: ReconnectionPolicy = ReconnectionPolicy()
        public val DISABLED: ReconnectionPolicy = ReconnectionPolicy(maxAttempts = 0, totalBudget = Duration.ZERO)
    }
}

/**
 * New messages from the other side of the live contact, for [TouchpointSession.onIncomingMessages].
 * What to do with them — a local notification, an in-app banner, a badge, nothing — is the host's
 * decision; the SDK posts no notifications.
 *
 * @property contactId the contact the messages belong to.
 * @property messages oldest first; every inbound role (agent, supervisor, assistant, system): filter
 *   on [Message.participantRole] if only some should be announced.
 */
public class IncomingMessages(
    public val contactId: String,
    public val messages: List<Message>,
    public val origin: Origin,
) {
    public enum class Origin {
        /** Arrived on the live connection. Always exactly one message. */
        LIVE,

        /** Missed while the connection was down, and recovered when the transcript was reconciled after a reconnect or resume. */
        CATCH_UP,
    }

    override fun toString(): String = "IncomingMessages(contactId=$contactId, count=${messages.size}, origin=$origin)"
}
