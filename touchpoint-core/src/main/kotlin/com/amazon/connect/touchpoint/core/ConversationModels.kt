package com.amazon.connect.touchpoint.core

import java.time.Instant
import java.util.UUID

/** Who is on the contact. Mirrors Connect's participant roles plus `ASSISTANT` for the ACXD bot. */
public enum class ParticipantRole(public val rawValue: String) {
    CUSTOMER("customer"),
    AGENT("agent"),

    /** A supervisor who barged in or is monitoring. Human, so it gates Live Sync dispatch. */
    SUPERVISOR("supervisor"),
    ASSISTANT("assistant"),
    SYSTEM("system"),

    /**
     * A role this SDK version does not recognise. **Counts as human** ([isHuman]), so Live Sync
     * dispatch suspends while such a party is on the contact: the cost of a wrong guess is a paused
     * assistant, not the failure of dispatching into a session a person is handling.
     */
    UNKNOWN("unknown"),
    ;

    /** A person on the agent side — or a party we cannot rule out. The Live Sync dispatch gate keys off this, not off `== AGENT`. */
    public val isHuman: Boolean get() = this == AGENT || this == SUPERVISOR || this == UNKNOWN

    public companion object {
        public fun fromRawValue(rawValue: String): ParticipantRole? = entries.firstOrNull { it.rawValue == rawValue }

        /** Maps Connect's `ParticipantRole` wire strings. Unrecognised values become [UNKNOWN]. */
        public fun fromConnectRole(connectRole: String): ParticipantRole =
            when (connectRole.uppercase()) {
                "AGENT" -> AGENT
                "CUSTOMER" -> CUSTOMER
                "SUPERVISOR" -> SUPERVISOR
                "CUSTOM_BOT" -> ASSISTANT
                "SYSTEM" -> SYSTEM
                else -> UNKNOWN
            }
    }
}

/**
 * One participant on a contact, keyed on Connect's `ParticipantId`. [toString] omits the id and the
 * display name: both identify a person, and a data class would print them into any template.
 */
public data class Participant(val id: String, val role: ParticipantRole, val displayName: String) {
    override fun toString(): String = "Participant(role=$role, id=<redacted>, displayName=<redacted>)"
}

/**
 * The participant set. **A set keyed on id, never an event-history fold.**
 *
 * A leave arriving after a join for the same id — which happens on transfer — must not report "no
 * agent" while one is handling the contact. That is a live defect in the web SDK and the reason this
 * type exists; [humanAgentPresent] is derived from membership, not from the last event.
 *
 * Immutable: [join] and [leave] return a new set, so a set carried on an event never changes under
 * the host that holds it.
 */
public class ParticipantSet private constructor(private val byId: Map<String, Participant>) {
    public constructor() : this(emptyMap())

    public fun join(participant: Participant): ParticipantSet = ParticipantSet(byId + (participant.id to participant))

    public fun leave(id: String): ParticipantSet = ParticipantSet(byId - id)

    public val participants: List<Participant> get() = byId.values.sortedBy { it.id }
    public val count: Int get() = byId.size

    public operator fun contains(id: String): Boolean = id in byId

    /** `true` while any human agent-side participant is present. Drives Live Sync dispatch gating. */
    public val humanAgentPresent: Boolean get() = byId.values.any { it.role.isHuman }

    override fun equals(other: Any?): Boolean = other is ParticipantSet && other.byId == byId

    override fun hashCode(): Int = byId.hashCode()

    override fun toString(): String = "ParticipantSet(count=$count, humanAgentPresent=$humanAgentPresent)"
}

/** Direction of a message relative to the customer. */
public enum class Direction(public val rawValue: String) {
    OUTBOUND("outbound"),
    INBOUND("inbound"),
    ;

    public companion object {
        public fun fromRawValue(rawValue: String): Direction? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Five states, not four: [FAILED] is reachable only from [SENDING]. */
public enum class DeliveryState(public val rawValue: String) {
    SENDING("sending"),
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    FAILED("failed"),
    ;

    public fun canTransitionTo(next: DeliveryState): Boolean =
        when (this) {
            SENDING -> next == SENT || next == FAILED
            SENT -> next == DELIVERED || next == READ
            DELIVERED -> next == READ
            READ, FAILED -> false
        }

    public companion object {
        public fun fromRawValue(rawValue: String): DeliveryState? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** A receipt the other side sent for one of our messages. */
public enum class ReceiptKind {
    DELIVERED,
    READ,
    ;

    public val deliveryState: DeliveryState get() = if (this == DELIVERED) DeliveryState.DELIVERED else DeliveryState.READ
}

/**
 * The storage-facing message value: what transports hand the coordinator and what the store persists.
 * Hosts see `Message`, the projection; this type is public only because the transport seam is.
 * [toString] omits the body.
 */
public data class StoredMessage(
    val clientMessageId: UUID,
    val serverMessageId: String? = null,
    val body: String,
    val direction: Direction,
    val participantRole: ParticipantRole,
    /** Connect `ParticipantId` of the sender, when known. */
    val participantId: String? = null,
    /** Server ordering authority; `null` until reconciled. Local echoes pin to the tail. */
    val sequence: Long? = null,
    /** Display formatting only, never ordering. */
    val createdAt: Instant = Instant.now(),
    val deliveryState: DeliveryState,
    // Locked in schema v1, rendered in increment 2.
    val interactiveJson: String? = null,
    val citationsJson: String? = null,
    val isStreaming: Boolean = false,
    /**
     * A file this message is: Connect's attachment id, its type and size. Its name is [body], encrypted
     * as any body is; these are not content, and are stored in the clear.
     */
    val attachmentId: String? = null,
    val attachmentContentType: String? = null,
    val attachmentSize: Long? = null,
) {
    val id: UUID get() = clientMessageId

    override fun toString(): String =
        "StoredMessage(clientMessageId=$clientMessageId, serverMessageId=$serverMessageId, direction=$direction, " +
            "participantRole=$participantRole, sequence=$sequence, deliveryState=$deliveryState, body=<redacted>)"
}

/** Read-side view of a persisted conversation. */
public data class ConversationRecord(
    val contactId: String,
    val modality: Modality,
    val lastSequence: Long,
    val participants: ParticipantSet,
    val humanAgentPresent: Boolean,
    val lastActivity: Instant,
    /** Everyone's name who has been on the contact, by participant id — those who left too: their messages still carry them. */
    val participantNames: Map<String, String> = emptyMap(),
) {
    // Names are personal data: counts only.
    override fun toString(): String =
        "ConversationRecord(modality=$modality, lastSequence=$lastSequence, participants=$participants, humanAgentPresent=$humanAgentPresent, names=${participantNames.size})"
}

/** One page of the *local* transcript, oldest-first, with whether anything older remains. */
public data class TranscriptSlice(val messages: List<StoredMessage>, val hasMore: Boolean)
