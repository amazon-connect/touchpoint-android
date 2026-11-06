package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Web's message times (`userMessageTimestamp` / `agentMessageTimestamp`): what a time
 * reads, and which messages show one. Pure, for the JVM tests; the formatters come from the device's
 * locale and 12/24-hour setting.
 */
internal class MessageTimes(
    private val time: DateTimeFormatter,
    private val day: DateTimeFormatter,
    private val dayAndYear: DateTimeFormatter,
    private val zone: ZoneId,
    private val now: () -> Instant = Instant::now,
) {
    /** Web's `formatTimestamp`: the time for today; else the month and day too, and the year when it is not this one. */
    fun format(at: Instant): String {
        val date = at.atZone(zone)
        val today = now().atZone(zone)
        val clock = time.format(date)
        return when {
            date.toLocalDate() == today.toLocalDate() -> clock
            date.year == today.year -> "${day.format(date)}, $clock"
            else -> "${dayAndYear.format(date)}, $clock"
        }
    }

    /**
     * The drawn messages that show their time, by client id. The customer's: every one, with
     * [TouchpointChatOptions.userMessageTimestamp]. The other side's, with
     * [TouchpointChatOptions.agentMessageTimestamp], except where the next drawn message is the same
     * sender's (the same role, as a run is) and reads the same time: web keeps only the last of a
     * run's identical stamps. A notice between two replies does not break the run: notices
     * are not messages here.
     */
    fun shown(
        messages: List<Message>,
        options: TouchpointChatOptions,
    ): Set<UUID> {
        if (!options.userMessageTimestamp && !options.agentMessageTimestamp) return emptySet()
        return messages.indices.filter { i ->
            val m = messages[i]
            if (m.direction == Direction.OUTBOUND) return@filter options.userMessageTimestamp
            if (!options.agentMessageTimestamp) return@filter false
            val next = messages.getOrNull(i + 1)
            next == null || next.direction != Direction.INBOUND || next.participantRole != m.participantRole || format(next.createdAt) != format(m.createdAt)
        }.mapTo(HashSet()) { messages[it].clientMessageId }
    }
}

/** The device's [MessageTimes]: its locale's own order for the date, and its 12- or 24-hour clock. */
@androidx.compose.runtime.Composable
internal fun rememberMessageTimes(): MessageTimes {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    return androidx.compose.runtime.remember(locale, twentyFour) {
        fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        MessageTimes(pattern(if (twentyFour) "Hm" else "hm"), pattern("MMMd"), pattern("yMMMd"), ZoneId.systemDefault())
    }
}
