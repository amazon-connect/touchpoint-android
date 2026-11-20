package com.acmebank.demo

import com.amazon.connect.touchpoint.chat.ui.TouchpointHeaderStyle
import com.amazon.connect.touchpoint.chat.ui.TouchpointAvatarShape
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.time.LocalDate

/** Where the customer is. A small back stack of our own: the demo needs no navigation library. */
sealed interface Screen {
    data object Accounts : Screen

    data class Transactions(val accountId: String) : Screen

    data class Detail(val accountId: String, val transactionId: String) : Screen

    data class Dispute(val accountId: String, val transactionId: String) : Screen

    data object Settings : Screen
}

/**
 * Session state for the demo: who is signed in, where they are, whether the support dock is open.
 * Synthetic — the sign-in models the authenticated session a real app already has. Held by the
 * application object, so it survives rotation.
 */
class AppModel {
    var isSignedIn by mutableStateOf(false)
        private set
    var supportExpanded by mutableStateOf(false)

    /**
     * Settings: the balances painted black in a screen share, as the SDK's redaction shows it. Off by
     * default, so a presentation shares the screen as it looks.
     */
    var maskBalances by mutableStateOf(false)

    /** The chat's look, from Settings: Touchpoint's chat options, on the device. */
    var chatTimes by mutableStateOf(false)
    var chatBrandHeader by mutableStateOf(false)
    var chatParticipantInfo by mutableStateOf(false)
    var chatAgentBubbles by mutableStateOf(true)
    var chatBrand by mutableStateOf(ChatBrand.NONE)

    /** Design review: the assistant's avatar the bank's mark, and square avatars. */
    var assistantAvatarIsLogo by mutableStateOf(false)
    var squareAvatars by mutableStateOf(false)

    /** The SDK's options for those switches. The assistant is named. */
    val chatOptions: TouchpointChatOptions
        @androidx.compose.runtime.Composable get() {
            if (PartnerLook.on) return PartnerLook.options()
            val mark = rememberAcmeMark()
            val wordmark = rememberAcmeWordmark(if (chatBrandHeader) androidx.compose.ui.graphics.Color.White else Palette.chatTheme().primary)
            return TouchpointChatOptions(
                showParticipantInfo = chatParticipantInfo,
                assistantName = PartnerBrand.current.assistantName,
                assistantIcon = mark.takeIf { assistantAvatarIsLogo },
                avatarShape = if (squareAvatars) TouchpointAvatarShape.SQUARE else TouchpointAvatarShape.ROUND,
                brandIcon =
                    when (chatBrand) {
                        ChatBrand.NONE -> null
                        ChatBrand.LOGO -> wordmark
                        ChatBrand.LOGO_AND_NAME -> mark
                    },
                // The header has Minimise alone, as the guide's: no added Close beside it.
                // The guide's "AI Assistant" by default; the bank's name beside its mark, or no name beside its wordmark.
                headerTitle =
                    when (chatBrand) {
                        ChatBrand.NONE -> null
                        ChatBrand.LOGO -> ""
                        ChatBrand.LOGO_AND_NAME -> PartnerBrand.current.bankName
                    },
                agentMessageBubble = chatAgentBubbles,
                userMessageTimestamp = chatTimes,
                agentMessageTimestamp = chatTimes,
                // The brand's header: a dark bar in the bank's colour, with white controls, over a light chat.
                headerStyle = if (chatBrandHeader) TouchpointHeaderStyle(background = Palette.brandDark, dark = true) else null,
                // Attach is off by default from web 1.0.6; the demo shows the customer sending a file.
                attachmentsEnabled = true,
            )
        }

    /** A filter the assistant applied to the transaction list (Live Sync); null shows everything. */
    var transactionFilter by mutableStateOf<TransactionFilter?>(null)

    // Dispute form state the assistant can fill; Submit is the customer's alone. Field names follow
    // the action contract (assets/action-contract) exactly: the assistant can only send what the
    // schema declares, so the app listens for those names and no others.
    var disputeReason by mutableStateOf<String?>(null) // not_recognized | duplicate | incorrect_amount | cancelled_service | not_received | other
    var disputeCardPossession by mutableStateOf<String?>(null) // have_card | lost | stolen
    var disputeAuthorized by mutableStateOf<Boolean?>(null)
    var disputeDetails by mutableStateOf<String?>(null)

    /** The transaction the assistant's dispute answers belong to: another dispute must not inherit them. */
    private var disputeFieldsFor: String? = null

    val stack = mutableStateListOf<Screen>(Screen.Accounts)
    val current: Screen get() = stack.last()

    val accounts: List<Account> = DemoData.accounts()

    fun account(id: String): Account? = accounts.firstOrNull { it.id == id }

    fun transaction(accountId: String, id: String): Transaction? = account(accountId)?.transactions?.firstOrNull { it.id == id }

    fun accountOfKind(kind: String): Account? =
        when (kind) {
            "credit_card" -> accounts.firstOrNull { it.kind == Account.Kind.CREDIT_CARD }
            "checking", "chequing" -> accounts.firstOrNull { it.kind == Account.Kind.CHEQUING }
            "savings" -> accounts.firstOrNull { it.kind == Account.Kind.SAVINGS }
            else -> null
        }

    /**
     * What Live Sync does to the screens: the five dispute actions. One place, so
     * the handlers stay readable. [fields] are the payload's scalars as strings; [amount] is parsed
     * leniently by the caller.
     */
    fun apply(
        action: String,
        fields: Map<String, String>,
        amount: BigDecimal?,
    ) {
        when (action) {
            "open_account" -> {
                val account = fields["accountId"]?.let(::account) ?: accountOfKind(fields["accountType"] ?: "credit_card") ?: return
                replaceStack(Screen.Transactions(account.id))
                transactionFilter = null
            }
            "filter_transactions" -> {
                transactionFilter =
                    if (fields.isEmpty() && amount == null) {
                        null
                    } else {
                        TransactionFilter(amount, fields["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, fields["merchant"])
                    }
            }
            "select_transaction" -> {
                val account = currentAccount() ?: return
                // No id: the first highlighted match; with no filter, nothing is highlighted.
                val tx =
                    fields["transactionId"]?.let { id -> account.transactions.firstOrNull { it.id == id } }
                        ?: transactionFilter?.let { f -> f.apply(account.transactions).firstOrNull() }
                        ?: return
                replaceStack(Screen.Transactions(account.id), Screen.Detail(account.id, tx.id))
            }
            "start_dispute" -> openDispute(fields["transactionId"])
            "set_dispute_details" -> {
                // The details only mean something on the form: if they arrive on the transaction
                // screen — the assistant skipped start_dispute — open it first. Not a flow which
                // relies on start_dispute alone; the demo must not stall on a skipped step.
                if (current is Screen.Detail) openDispute(null)
                disputeFieldsFor = (current as? Screen.Dispute)?.transactionId ?: (current as? Screen.Detail)?.transactionId
                fields["reason"]?.let { disputeReason = it }
                fields["cardPossession"]?.let { disputeCardPossession = it }
                fields["authorized"]?.let { disputeAuthorized = foundationBool(it) }
                fields["additionalDetails"]?.let { disputeDetails = it }
            }
            else -> Unit
        }
    }

    /**
     * Opens the dispute form for the transaction in front of the customer — an id the
     * assistant passes is used only if it names a real transaction, never a reason to do nothing.
     * Fields set already are kept and applied as the form opens.
     */
    private fun openDispute(transactionId: String?) {
        val (accountId, txId) =
            when (val s = current) {
                is Screen.Detail -> s.accountId to (transactionId?.takeIf { transaction(s.accountId, it) != null } ?: s.transactionId)
                is Screen.Dispute -> return // already open
                else -> {
                    val account = currentAccount() ?: return
                    val id = transactionId?.takeIf { transaction(account.id, it) != null } ?: return
                    account.id to id
                }
            }
        startDispute(accountId, txId)
    }

    /** Opens the dispute form; answers given for a different transaction are cleared first. */
    fun startDispute(
        accountId: String,
        transactionId: String,
    ) {
        if (disputeFieldsFor != null && disputeFieldsFor != transactionId) clearDispute()
        open(Screen.Dispute(accountId, transactionId))
    }

    /** The account the filter and list show: the card, until a later step derives it from the screens. */
    private fun currentAccount(): Account? = accountOfKind("credit_card")

    /** The flag is read leniently: true for a leading Y, y, T, t or digit 1-9. */
    private fun foundationBool(value: String): Boolean = value.trimStart().firstOrNull()?.let { it in "YyTt" || it in '1'..'9' } ?: false

    private fun replaceStack(vararg screens: Screen) {
        stack.clear()
        stack += Screen.Accounts
        stack.addAll(screens)
    }

    fun clearDispute() {
        disputeFieldsFor = null
        disputeReason = null
        disputeCardPossession = null
        disputeAuthorized = null
        disputeDetails = null
    }

    fun open(screen: Screen) {
        stack += screen
    }

    /** Returns whether there was somewhere to go back to. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun signIn() {
        isSignedIn = true
    }

    fun signOut() {
        isSignedIn = false
        supportExpanded = false
        transactionFilter = null
        clearDispute()
        stack.clear()
        stack += Screen.Accounts
    }

    /** The screen name sent as a contact attribute, so the contact flow can tailor its introduction. */
    val screenName: String
        get() =
            when (val s = current) {
                Screen.Accounts -> "accounts"
                is Screen.Transactions -> "transactions:" + (account(s.accountId)?.kind?.name?.lowercase() ?: "unknown")
                is Screen.Detail -> "transaction-detail"
                is Screen.Dispute -> "dispute"
                Screen.Settings -> "settings"
            }
}
