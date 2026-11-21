package com.acmebank.demo

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The five dispute actions as the assistant drives them — the journey in the demo's video:
 * open the card, filter to the $240 charge, open it, start the dispute, fill the form. Submit is not
 * an action, by design.
 */
class LiveSyncActionsTest {
    private val model = AppModel().apply { signIn() }

    @Test
    fun `the whole dispute journey, as the assistant drives it`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        assertEquals(listOf(Screen.Accounts, Screen.Transactions("cc")), model.stack.toList())

        model.apply("filter_transactions", emptyMap(), BigDecimal("240"))
        val shown = model.account("cc")!!.transactions.filter(model.transactionFilter!!::matches)
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), shown.map { it.merchant })

        model.apply("select_transaction", emptyMap(), null) // no id: the single highlighted match
        assertEquals(Screen.Detail("cc", "t3"), model.current)

        model.apply("start_dispute", emptyMap(), null)
        assertEquals(Screen.Dispute("cc", "t3"), model.current)

        model.apply("set_dispute_details", mapOf("reason" to "not_recognized"), null)
        model.apply("set_dispute_details", mapOf("cardPossession" to "lost", "authorized" to "false"), null)
        assertEquals("not_recognized", model.disputeReason)
        assertEquals("lost", model.disputeCardPossession)
        assertEquals(false, model.disputeAuthorized)
        assertNull(model.disputeDetails) // set only what the customer spoke to
    }

    @Test
    fun `an account named by id wins over the type, and opening one clears the filter`() {
        model.transactionFilter = TransactionFilter(amount = BigDecimal.ONE)
        model.apply("open_account", mapOf("accountId" to "sav", "accountType" to "credit_card"), null)
        assertEquals(Screen.Transactions("sav"), model.current)
        assertNull(model.transactionFilter)
    }

    @Test
    fun `an empty filter invocation clears the filter, and a date narrows it`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", mapOf("date" to DemoData.lastTuesday(java.time.LocalDate.now()).toString()), null)
        assertTrue(model.account("cc")!!.transactions.any(model.transactionFilter!!::matches))
        model.apply("filter_transactions", emptyMap(), null)
        assertNull(model.transactionFilter)
    }

    @Test
    fun `select with no id takes the first highlighted match, and with no filter nothing`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("select_transaction", emptyMap(), null) // no filter: nothing is highlighted
        assertEquals(Screen.Transactions("cc"), model.current)
        model.apply("filter_transactions", mapOf("merchant" to "e"), null) // several match
        val first = model.account("cc")!!.transactions.first(model.transactionFilter!!::matches)
        model.apply("select_transaction", emptyMap(), null)
        assertEquals(Screen.Detail("cc", first.id), model.current)
    }

    @Test
    fun `a misheard merchant still shows and selects the charge with the right amount`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", mapOf("merchant" to "Delta"), BigDecimal("240"))
        val shown = model.transactionFilter!!.apply(model.account("cc")!!.transactions)
        assertEquals(listOf(DemoData.DISPUTE_CANDIDATE_MERCHANT), shown.map { it.merchant })
        model.apply("select_transaction", emptyMap(), null)
        assertEquals(Screen.Detail("cc", "t3"), model.current)
    }

    @Test
    fun `start_dispute with an id the app does not know opens the form for the transaction on screen`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", emptyMap(), BigDecimal("240"))
        model.apply("select_transaction", emptyMap(), null)
        model.apply("start_dispute", mapOf("transactionId" to "riverside-auto-240"), null) // an id the model made up
        assertEquals(Screen.Dispute("cc", "t3"), model.current)
    }

    @Test
    fun `dispute details that arrive on the transaction screen open the form`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", emptyMap(), BigDecimal("240"))
        model.apply("select_transaction", emptyMap(), null)
        model.apply("set_dispute_details", mapOf("reason" to "not_recognized", "cardPossession" to "lost"), null) // start_dispute skipped
        assertEquals(Screen.Dispute("cc", "t3"), model.current)
        assertEquals("lost", model.disputeCardPossession)
    }

    @Test
    fun `answers given for one transaction do not carry into another's dispute`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", emptyMap(), BigDecimal("240"))
        model.apply("select_transaction", emptyMap(), null)
        model.apply("start_dispute", emptyMap(), null)
        model.apply("set_dispute_details", mapOf("reason" to "not_recognized"), null)
        model.back()
        model.back() // to the list
        model.startDispute("cc", "t1") // a different charge, by hand
        assertNull(model.disputeReason)
    }

    @Test
    fun `fields set before the form opens are kept for it`() {
        model.apply("open_account", mapOf("accountType" to "credit_card"), null)
        model.apply("filter_transactions", emptyMap(), BigDecimal("240"))
        model.apply("select_transaction", emptyMap(), null)
        model.apply("set_dispute_details", mapOf("reason" to "not_recognized"), null) // on the detail screen: opens the form
        model.apply("start_dispute", emptyMap(), null) // already open: nothing changes
        assertEquals(Screen.Dispute("cc", "t3"), model.current)
        assertEquals("not_recognized", model.disputeReason)
    }

    @Test
    fun `unknown actions and malformed values are ignored`() {
        val before = model.stack.toList()
        model.apply("submit_dispute", emptyMap(), null) // deliberately not an action
        assertEquals(before, model.stack.toList())
        // `authorized` is read leniently: a leading Y, y, T, t or digit 1-9 is true.
        model.apply("set_dispute_details", mapOf("authorized" to "yes"), null)
        assertEquals(true, model.disputeAuthorized)
        model.apply("set_dispute_details", mapOf("authorized" to "maybe"), null)
        assertEquals(false, model.disputeAuthorized)
    }
}
