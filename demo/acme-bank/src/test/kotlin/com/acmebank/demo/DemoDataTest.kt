package com.acmebank.demo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one piece of the demo that is load-bearing: the showcase has the assistant find "about two
 * hundred and forty dollars, last Tuesday", so the fixture must keep producing that transaction
 * relative to whatever day the demo is run.
 */
class DemoDataTest {
    private val base = LocalDate.of(2026, 10, 8)

    @Test
    fun `lastTuesday is strictly before today, on a Tuesday, within the last seven days`() {
        for (offset in 0L until 14L) {
            val today = base.plusDays(offset)
            val t = DemoData.lastTuesday(today)
            assertEquals(DayOfWeek.TUESDAY, t.dayOfWeek, "not a Tuesday for offset $offset")
            val back = ChronoUnit.DAYS.between(t, today)
            assertTrue(back in 1..7, "$back days back for offset $offset")
        }
    }

    @Test
    fun `on a Tuesday, last Tuesday is a week ago, not today`() {
        val tuesday = LocalDate.of(2026, 10, 6)
        assertEquals(DayOfWeek.TUESDAY, tuesday.dayOfWeek)
        assertEquals(7, ChronoUnit.DAYS.between(DemoData.lastTuesday(tuesday), tuesday))
    }

    @Test
    fun `the dispute candidate is on the credit card, 240 dollars, dated last Tuesday, whatever today is`() {
        for (offset in listOf(0L, 3L, 30L, 365L)) {
            val today = base.plusDays(offset)
            val card = DemoData.accounts(today).first { it.kind == Account.Kind.CREDIT_CARD }
            val candidate = card.transactions.first { it.merchant == DemoData.DISPUTE_CANDIDATE_MERCHANT }
            assertEquals(0, candidate.amount.compareTo(DemoData.disputeCandidateAmount.negate()))
            assertEquals(DemoData.lastTuesday(today), candidate.date)
        }
    }

    @Test
    fun `no transaction is dated in the future, and every account has at least one`() {
        for (account in DemoData.accounts(base)) {
            assertTrue(account.transactions.isNotEmpty(), "${account.nickname} has no transactions")
            assertTrue(account.transactions.all { !it.date.isAfter(base) })
        }
    }

    @Test
    fun `transaction ids are unique across accounts`() {
        val ids = DemoData.transactions(base).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the assistant's loose filter finds the candidate from a spoken description`() {
        val today = base
        val card = DemoData.accounts(today).first { it.kind == Account.Kind.CREDIT_CARD }
        // "About two hundred and forty dollars, last Tuesday", with the model's today a day off.
        val filter = TransactionFilter(amount = java.math.BigDecimal("240.50"), date = DemoData.lastTuesday(today).plusDays(1))
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), card.transactions.filter(filter::matches).map { it.merchant })
        assertTrue(card.transactions.none(TransactionFilter(merchant = "nowhere")::matches))
    }

    private val card get() = DemoData.accounts(base).first { it.kind == Account.Kind.CREDIT_CARD }.transactions
    private val amount = java.math.BigDecimal("240")

    @Test
    fun `apply keeps the exact matches when every field matches`() {
        val filter = TransactionFilter(amount = amount, date = DemoData.lastTuesday(base), merchant = "riverside")
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), filter.apply(card).map { it.merchant })
    }

    @Test
    fun `apply falls back to the amount alone when the merchant is misheard`() {
        val filter = TransactionFilter(amount = amount, merchant = "Delta")
        assertTrue(card.none(filter::matches))
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), filter.apply(card).map { it.merchant })
    }

    @Test
    fun `apply falls back to the amount alone when the date is a few days off`() {
        val filter = TransactionFilter(amount = amount, date = DemoData.lastTuesday(base).minusDays(3))
        assertTrue(card.none(filter::matches))
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), filter.apply(card).map { it.merchant })
    }

    @Test
    fun `apply does not fall back without an amount, or when the amount itself matches nothing`() {
        assertTrue(TransactionFilter(merchant = "nowhere").apply(card).isEmpty())
        assertTrue(TransactionFilter(date = base.minusDays(60)).apply(card).isEmpty())
        assertTrue(TransactionFilter(amount = java.math.BigDecimal("999"), merchant = "Riverside").apply(card).isEmpty())
        assertTrue(TransactionFilter(amount = java.math.BigDecimal("999")).apply(card).isEmpty())
    }
}
