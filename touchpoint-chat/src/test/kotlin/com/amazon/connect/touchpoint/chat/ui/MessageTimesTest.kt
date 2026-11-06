package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.ParticipantRole
import com.amazon.connect.touchpoint.core.StoredMessage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Web 1.0.5's message times. */
class MessageTimesTest {
    private val now = Instant.parse("2026-10-23T15:30:00Z")
    private val times =
        MessageTimes(
            DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DateTimeFormatter.ofPattern("MMM d", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
            ZoneOffset.UTC,
            now = { now },
        )

    private fun msg(
        at: String,
        direction: Direction = Direction.INBOUND,
        role: ParticipantRole = ParticipantRole.ASSISTANT,
    ) = Message(StoredMessage(UUID.randomUUID(), null, "hi", direction, role, deliveryState = DeliveryState.DELIVERED, createdAt = Instant.parse(at)))

    @Test
    fun `web's defaults - no times, and no header style`() {
        assertFalse(TouchpointChatOptions.Default.userMessageTimestamp)
        assertFalse(TouchpointChatOptions.Default.agentMessageTimestamp)
        assertEquals(null, TouchpointChatOptions.Default.headerStyle)
        assertTrue(times.shown(listOf(msg("2026-10-23T15:00:00Z")), TouchpointChatOptions.Default).isEmpty())
    }

    @Test
    fun `today is the time only, earlier this year adds the day, another year adds the year`() {
        assertEquals("3:05 PM", times.format(Instant.parse("2026-10-23T15:05:00Z")))
        assertEquals("Oct 2, 9:00 AM", times.format(Instant.parse("2026-10-02T09:00:00Z")))
        assertEquals("Dec 31, 2025, 11:59 PM", times.format(Instant.parse("2025-12-31T23:59:00Z")))
    }

    @Test
    fun `each side is gated by its own option`() {
        val mine = msg("2026-10-23T15:00:00Z", Direction.OUTBOUND, ParticipantRole.CUSTOMER)
        val theirs = msg("2026-10-23T15:01:00Z")
        assertEquals(setOf(mine.clientMessageId), times.shown(listOf(mine, theirs), TouchpointChatOptions(userMessageTimestamp = true)))
        assertEquals(setOf(theirs.clientMessageId), times.shown(listOf(mine, theirs), TouchpointChatOptions(agentMessageTimestamp = true)))
    }

    @Test
    fun `within a run, a stamp the next one repeats is dropped - another sender or minute keeps it`() {
        val options = TouchpointChatOptions(agentMessageTimestamp = true)
        val a = msg("2026-10-23T15:00:10Z")
        val b = msg("2026-10-23T15:00:40Z") // the same minute, the same sender: a's goes
        val c = msg("2026-10-23T15:00:50Z", role = ParticipantRole.AGENT) // another sender: b's stays
        val d = msg("2026-10-23T15:02:00Z", role = ParticipantRole.AGENT) // another minute: c's stays
        assertEquals(setOf(b, c, d).map { it.clientMessageId }.toSet(), times.shown(listOf(a, b, c, d), options))
    }

    @Test
    fun `the customer's own are never deduplicated`() {
        val options = TouchpointChatOptions(userMessageTimestamp = true)
        val one = msg("2026-10-23T15:00:10Z", Direction.OUTBOUND, ParticipantRole.CUSTOMER)
        val two = msg("2026-10-23T15:00:20Z", Direction.OUTBOUND, ParticipantRole.CUSTOMER)
        assertEquals(setOf(one.clientMessageId, two.clientMessageId), times.shown(listOf(one, two), options))
    }
}
