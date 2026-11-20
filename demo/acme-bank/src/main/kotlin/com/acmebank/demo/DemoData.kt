package com.acmebank.demo

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Synthetic Acme Bank data. **Every value is invented**: names, numbers, merchants and amounts.
 *
 * Dates are generated relative to today so the showcase keeps working: the walkthrough has the
 * customer say "about two hundred and forty dollars, last Tuesday" and the assistant find it, and a
 * fixed date would stop matching a fortnight after it was written ("fixture data is load-bearing and
 * must not age").
 */
object DemoData {
    const val CUSTOMER_NAME = "Jordan Rivera"

    /** The transaction the showcase disputes: $240.00 at Riverside Auto, last Tuesday, on the card. */
    const val DISPUTE_CANDIDATE_MERCHANT = "Riverside Auto"
    val disputeCandidateAmount: BigDecimal = BigDecimal("240.00")

    fun accounts(today: LocalDate = LocalDate.now()): List<Account> {
        val t = transactions(today)
        return listOf(
            Account("chk", Account.Kind.CHEQUING, "Everyday Chequing", "•••• 4821", BigDecimal("3418.27"), t.filter { it.accountId == "chk" }),
            Account("sav", Account.Kind.SAVINGS, "Rainy Day Savings", "•••• 9034", BigDecimal("12950.00"), t.filter { it.accountId == "sav" }),
            Account("cc", Account.Kind.CREDIT_CARD, "${PartnerBrand.current.bankShortName} Rewards Card", "•••• 7716", BigDecimal("-1284.63"), t.filter { it.accountId == "cc" }),
        )
    }

    fun transactions(today: LocalDate = LocalDate.now()): List<Transaction> {
        fun day(n: Long) = today.minusDays(n)
        fun tx(id: String, account: String, merchant: String, amount: String, date: LocalDate, category: Transaction.Category) =
            Transaction(id, account, merchant, BigDecimal(amount), date, category)
        return listOf(
            tx("t1", "cc", "Northgate Grocers", "-86.12", day(1), Transaction.Category.GROCERIES),
            tx("t2", "cc", "Streamline Video", "-15.99", day(2), Transaction.Category.SUBSCRIPTIONS),
            tx("t3", "cc", DISPUTE_CANDIDATE_MERCHANT, disputeCandidateAmount.negate().toPlainString(), lastTuesday(today), Transaction.Category.AUTOMOTIVE),
            tx("t4", "cc", "Blue Fern Café", "-7.45", day(4), Transaction.Category.DINING),
            tx("t5", "cc", "Harbourline Transit", "-3.35", day(5), Transaction.Category.TRANSPORT),
            tx("t6", "cc", "Meridian Pharmacy", "-42.80", day(9), Transaction.Category.HEALTH),
            tx("t7", "cc", "Payment — thank you", "900.00", day(12), Transaction.Category.PAYMENT),
            tx("t8", "chk", "Payroll — Lumen Studios", "2640.00", day(3), Transaction.Category.INCOME),
            tx("t9", "chk", "Rent — 14 Orchard Lane", "-1750.00", day(7), Transaction.Category.HOUSING),
            tx("t10", "chk", "Transfer to Rainy Day Savings", "-300.00", day(7), Transaction.Category.TRANSFER),
            tx("t11", "chk", "City Utilities", "-118.40", day(10), Transaction.Category.UTILITIES),
            tx("t12", "sav", "Transfer from Everyday Chequing", "300.00", day(7), Transaction.Category.TRANSFER),
            tx("t13", "sav", "Interest", "9.63", day(14), Transaction.Category.INCOME),
        )
    }

    /**
     * The most recent Tuesday strictly before [today] — "last Tuesday" in the sense a customer means
     * it. On a Tuesday that is seven days ago, never today.
     */
    fun lastTuesday(today: LocalDate): LocalDate {
        var back = (today.dayOfWeek.value - DayOfWeek.TUESDAY.value + 7) % 7
        if (back == 0) back = 7
        return today.minusDays(back.toLong())
    }
}

data class Account(
    val id: String,
    val kind: Kind,
    val nickname: String,
    val maskedNumber: String,
    val balance: BigDecimal,
    val transactions: List<Transaction>,
) {
    enum class Kind { CHEQUING, SAVINGS, CREDIT_CARD }
}

/** @property amount Negative is money out. */
data class Transaction(
    val id: String,
    val accountId: String,
    val merchant: String,
    val amount: BigDecimal,
    val date: LocalDate,
    val category: Category,
) {
    enum class Category { GROCERIES, SUBSCRIPTIONS, AUTOMOTIVE, DINING, TRANSPORT, HEALTH, PAYMENT, INCOME, HOUSING, TRANSFER, UTILITIES }
}

/**
 * A narrowing of the transaction list the assistant applied (Live Sync). Loose on
 * purpose: a spoken amount is approximate, a merchant a fragment, a date the day — within a day either
 * side, since a model's idea of today may be a day off from the device's.
 */
data class TransactionFilter(val amount: BigDecimal? = null, val date: LocalDate? = null, val merchant: String? = null) {
    fun matches(t: Transaction): Boolean {
        if (amount != null && (t.amount.abs() - amount.abs()).abs() > BigDecimal.ONE) return false
        if (!merchant.isNullOrEmpty() && !t.merchant.contains(merchant, ignoreCase = true)) return false
        if (date != null && kotlin.math.abs(ChronoUnit.DAYS.between(date, t.date)) > 1) return false
        return true
    }

    /**
     * The transactions this filter shows. Amount is the reliable part of a spoken request; a misheard
     * merchant ("Delta" for "Dell") or a wrongly resolved date must not hide the charge, so when nothing
     * matches every field, the amount alone decides.
     */
    fun apply(transactions: List<Transaction>): List<Transaction> {
        val exact = transactions.filter(::matches)
        if (exact.isNotEmpty() || amount == null || (merchant.isNullOrEmpty() && date == null)) return exact
        return transactions.filter(TransactionFilter(amount = amount)::matches)
    }
}
