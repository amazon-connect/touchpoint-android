package com.acmebank.demo

import com.amazon.connect.touchpoint.voice.redactedFromScreenShare
import com.amazon.connect.touchpoint.livesync.TouchpointActionConfirmation
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.acmebank.demo.support.LiveSyncCoordinator
import com.acmebank.demo.support.SupportCoordinator
import com.acmebank.demo.support.SupportDock
import com.acmebank.demo.support.CallRail
import com.amazon.connect.touchpoint.chat.ui.LiveSyncConsentSheet
import com.amazon.connect.touchpoint.livesync.TouchpointAction
import com.amazon.connect.touchpoint.livesync.TouchpointLiveSync
import com.amazon.connect.touchpoint.livesync.TouchpointLiveSyncOverlay
import com.amazon.connect.touchpoint.livesync.TouchpointScope
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import kotlinx.coroutines.delay
import com.acmebank.demo.support.LiveMode
import com.amazon.connect.touchpoint.core.TouchpointException
import kotlinx.coroutines.launch

/**
 * Signed out → sign in. Signed in → the bank, with the support dock under every screen: the dock
 * takes its height from the bottom and the screen shrinks to the space above it, so the page stays
 * usable with the assistant open.
 */
@Composable
fun Root(model: AppModel, support: SupportCoordinator, modifier: Modifier = Modifier, liveSync: LiveSyncCoordinator? = null) {
    if (!model.isSignedIn) {
        SignInScreen(model, support, modifier)
        return
    }
    LaunchedEffect(model.screenName) { support.currentScreen = model.screenName }
    // The bank's reply notification needs the permission; asked once, on signing in (the SDK asks for none).
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (liveSync == null) {
        Bank(model, support, modifier, null)
        return
    }
    // What each screen can do is declared while it is on show, and the SDK advertises it: the
    // assistant's actions follow the customer, with no call of the app's own.
    TouchpointLiveSync(liveSync.aggregator) {
        Bank(model, support, modifier, liveSync)
        AssistantActions(liveSync, LiveSyncCoordinator.scopeOf(model.screenName))
        // Asked once, before anything is shared; the answer is kept for the deployment.
        liveSync.consent?.let { consent ->
            val asking by consent.needsPrompt.collectAsState()
            val scope = rememberCoroutineScope()
            if (asking) LiveSyncConsentSheet(consent.prompt, onAllow = { scope.launch { consent.grant() } }, onDecline = { scope.launch { consent.deny() } })
        }
        // What the assistant asks for that the customer must allow first: starting a dispute.
        liveSync.client?.let { client -> TouchpointActionConfirmation(client) }
    }
}

/** The screen's actions, from the contract the assistant was built with: in its scope, while it is composed. */
@Composable
private fun AssistantActions(liveSync: LiveSyncCoordinator, scope: String) {
    TouchpointScope(scope) {
        for (action in liveSync.actionsIn(scope)) {
            key(action.name) {
                TouchpointAction(action.name, action.description, action.schema, confirmationRequired = action.confirmationMessage != null, confirmationMessage = action.confirmationMessage) { payload ->
                    liveSync.perform(action.name, payload)
                }
            }
        }
    }
}

@Composable
private fun Bank(model: AppModel, support: SupportCoordinator, modifier: Modifier, liveSync: LiveSyncCoordinator?) {
    // The keyboard is taken here, for the bank and the dock together: edge to edge, the window does
    // not resize for it, and a panel left to pad itself would crush its own header.
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // The panel takes what is left once the bank keeps its top bar, so with the keyboard up the
        // customer still sees where they are and can still go back.
        val panelLimit = maxOf(maxHeight - BANK_STRIP, maxHeight / 2)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when (val screen = model.current) {
                    Screen.Accounts -> AccountsScreen(model)
                    is Screen.Transactions -> TransactionsScreen(model, screen)
                    is Screen.Detail -> DetailScreen(model, screen)
                    is Screen.Dispute -> DisputeScreen(model, screen)
                    Screen.Settings -> SettingsScreen(model, support, liveSync)
                }
                liveSync?.let { AssistantCue(it, Modifier.align(Alignment.BottomCenter)) }
                if (liveSync?.overlay == true) TouchpointLiveSyncOverlay(liveSync.aggregator, Modifier.align(Alignment.TopEnd).padding(8.dp), liveSync.client)
            }
            SupportDock(model, support, maxPanelHeight = panelLimit)
        }
        // Over everything, so it can be dragged anywhere: the call's rail, while the chat is collapsed.
        CallRail(model, support)
    }
}

/**
 * A short, spoken cue when the assistant changes the screen — a form that fills itself silently is
 * disorienting — with a light haptic. A live region, so TalkBack reads it.
 */
@Composable
private fun AssistantCue(liveSync: LiveSyncCoordinator, modifier: Modifier) {
    val action = liveSync.lastAction
    var shown by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    LaunchedEffect(liveSync.actionCount) {
        if (action == null) return@LaunchedEffect
        shown = action
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        delay(CUE_MS)
        shown = null
    }
    val text = shown ?: return
    Text(
        stringResource(R.string.assistant_cue, text.replace('_', ' ')),
        modifier.padding(8.dp).background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.inverseOnSurface,
        style = MaterialTheme.typography.labelLarge,
    )
}

private const val CUE_MS = 2_500L

/** The status bar and a top app bar: what the bank keeps above an open panel. */
private val BANK_STRIP = 96.dp

/**
 * The bank's sign-in. Offline, any username and password work: the screen is a prop. In live mode
 * on the demo backend's identity pool, it signs a demo user in for real ([LiveMode.signInUser]), which
 * is where the app's credentials for the backend come from, and the Live Sync keys too: the backend
 * vends them at each sign-in. Live mode without that sign-in (temporary credentials) asks here for
 * the Live Sync keys, kept in this device's Keystore-backed store; empty fields keep the saved ones.
 */
@Composable
private fun SignInScreen(model: AppModel, support: SupportCoordinator, modifier: Modifier) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deploymentKey by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val live = support.mode == SupportCoordinator.Mode.LIVE
    val realSignIn = live && LiveMode.realSignIn
    // A real sign-in fetches the Live Sync keys from the backend: nothing to type.
    val asksForKeys = live && !realSignIn
    val keysReady = LiveMode.keysLoaded
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pre-filled once the saved keys have loaded, never before: empty fields over keys that hadn't
    // loaded yet would read as "no keys".
    LaunchedEffect(keysReady, live) {
        if (!keysReady || !live) return@LaunchedEffect
        deploymentKey = LiveMode.savedDeploymentKey
        apiKey = LiveMode.savedApiKey
        showKeys = !LiveMode.hasLiveSyncKeys
    }
    fun submit() {
        if (signingIn) return
        signingIn = true
        failure = null
        scope.launch {
            try {
                // The session notices on its next chat: its contact flow depends on the keys.
                if (asksForKeys) LiveMode.setKeys(context, deploymentKey, apiKey)
                if (realSignIn) LiveMode.signInUser(username.trim(), password)
                password = ""
                model.signIn()
            } catch (e: TouchpointException) {
                failure = (e as? TouchpointException.InvalidConfiguration)?.detail ?: context.getString(R.string.signin_failed)
            } catch (e: Exception) {
                // The keys' write to the secure store is the one step that throws otherwise.
                failure = context.getString(R.string.signin_keys_not_saved)
            } finally {
                signingIn = false
            }
        }
    }
    Scaffold(modifier.fillMaxSize()) { padding ->
        // Above the keyboard, and scrolling when it leaves no room: Sign in is never under it.
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BankMark()
            Text(PartnerBrand.current.bankName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.signin_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                username,
                { username = it },
                label = { Text(stringResource(R.string.signin_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("signin.username"),
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text(stringResource(R.string.signin_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("signin.password"),
            )
            if (asksForKeys) LiveSyncKeyFields(deploymentKey, { deploymentKey = it }, apiKey, { apiKey = it }, showKeys, { showKeys = it }) {
                scope.launch {
                    LiveMode.forgetKeys(context)
                    deploymentKey = ""
                    apiKey = ""
                }
            }
            Button(
                ::submit,
                Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("signin.submit"),
                enabled = !signingIn && keysReady && (!realSignIn || (username.isNotBlank() && password.isNotEmpty())),
            ) {
                if (signingIn) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.signin_submit), style = MaterialTheme.typography.titleMedium)
                }
            }
            val note = failure
            if (note != null) {
                Text(
                    note,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("signin.error"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(if (realSignIn) R.string.signin_hint_live else R.string.signin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Live mode's Live Sync keys: folded away once saved, with a way to forget them. */
@Composable
private fun LiveSyncKeyFields(
    deploymentKey: String,
    onDeploymentKey: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val saved = LiveMode.hasLiveSyncKeys
    Column(Modifier.fillMaxWidth()) {
        TextButton({ onExpanded(!expanded) }, Modifier.testTag("signin.keys")) {
            Text(stringResource(if (saved) R.string.signin_keys_saved else R.string.signin_keys))
        }
        if (!expanded) return@Column
        // The keyboard learns from what it is told is text: neither key is prose, and the API key is a secret.
        OutlinedTextField(
            deploymentKey,
            onDeploymentKey,
            label = { Text(stringResource(R.string.signin_deployment_key)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("signin.deploymentKey"),
        )
        OutlinedTextField(
            apiKey,
            onApiKey,
            label = { Text(stringResource(R.string.signin_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("signin.apiKey"),
        )
        Text(
            stringResource(R.string.signin_keys_footer),
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved) {
            TextButton(onForget, Modifier.testTag("signin.forgetKeys")) {
                Text(stringResource(R.string.signin_forget_keys), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** A drawn mark rather than a bitmap: three columns under a pediment. Decorative. */
@Composable
private fun BankMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(48.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val roof = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w, h * 0.28f)
            lineTo(0f, h * 0.28f)
            close()
        }
        drawPath(roof, color)
        for (i in 0..2) {
            val x = w * (0.2f + i * 0.3f)
            drawLine(color, Offset(x, h * 0.36f), Offset(x, h * 0.84f), strokeWidth = w * 0.12f)
        }
        drawRect(color, topLeft = Offset(0f, h * 0.88f), size = Size(w, h * 0.12f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankScaffold(
    title: String,
    model: AppModel,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (model.stack.size > 1) BackButton { model.back() } },
                actions = { actions() },
            )
        },
        // The dock below owns the bottom inset; the top bar owns the top one.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp), content = content)
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.nav_back)
    IconButton(onClick, Modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.size(24.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.62f, size.height * 0.2f)
                lineTo(size.width * 0.32f, size.height * 0.5f)
                lineTo(size.width * 0.62f, size.height * 0.8f)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun AccountsScreen(model: AppModel) {
    BankScaffold(
        stringResource(R.string.accounts_title),
        model,
        actions = {
            TextButton({ model.open(Screen.Settings) }, Modifier.testTag("nav.settings")) { Text(stringResource(R.string.nav_settings)) }
        },
    ) {
        item {
            SectionHeader(stringResource(R.string.accounts_greeting, DemoData.CUSTOMER_NAME), emphasised = true)
        }
        items(model.accounts, key = { it.id }) { account ->
            Row(
                Modifier.fillMaxWidth().clickable { model.open(Screen.Transactions(account.id)) }
                    .testTag("account.${account.id}").padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.nickname, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.account_subtitle, kindLabel(account.kind), account.maskedNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A balance is the customer's to show: black in a screen share, when Settings asks for it.
                Text(money(account.balance), Modifier.maskedBalance(model), color = if (account.balance.signum() < 0) negativeColor() else MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun TransactionsScreen(model: AppModel, screen: Screen.Transactions) {
    val account = model.account(screen.accountId) ?: return
    val filter = model.transactionFilter
    val shown = account.transactions.sortedByDescending { it.date }.let { all -> if (filter == null) all else filter.apply(all) }
    BankScaffold(account.nickname, model) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.transactions_balance), Modifier.weight(1f))
                Text(money(account.balance), Modifier.maskedBalance(model), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
        }
        item { SectionHeader(stringResource(if (filter == null) R.string.transactions_recent else R.string.transactions_matching)) }
        items(shown, key = { it.id }) { tx ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (filter != null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) // the match highlights
                    .clickable { model.open(Screen.Detail(account.id, tx.id)) }
                    .testTag("transaction.${tx.id}").padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryBadge(tx.category)
                Column(Modifier.weight(1f)) {
                    Text(tx.merchant)
                    Text(shortDate(tx.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(money(tx.amount), color = if (tx.amount.signum() > 0) positiveColor() else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (filter != null) {
            item { TextButton({ model.transactionFilter = null }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.transactions_show_all)) } }
        }
    }
}

/** A tinted disc with the category's initial: a symbol, without an icon set. Decorative. */
@Composable
private fun CategoryBadge(category: Transaction.Category) {
    val label = categoryLabel(category)
    Box(
        Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(1), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailScreen(model: AppModel, screen: Screen.Detail) {
    val account = model.account(screen.accountId) ?: return
    val tx = model.transaction(screen.accountId, screen.transactionId) ?: return
    BankScaffold(stringResource(R.string.detail_title), model) {
        item {
            LabeledRow(stringResource(R.string.detail_merchant), tx.merchant)
            LabeledRow(stringResource(R.string.detail_amount), money(tx.amount))
            LabeledRow(stringResource(R.string.detail_date), tx.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)))
            LabeledRow(stringResource(R.string.detail_account), "${account.nickname} ${account.maskedNumber}")
            LabeledRow(stringResource(R.string.detail_category), categoryLabel(tx.category))
        }
        if (tx.amount.signum() < 0) {
            item {
                // Pushed, not a dialog: the assistant is docked on every screen, and a dialog would
                // cover the support panel exactly when the assistant is helping with the form.
                Button({ model.startDispute(account.id, tx.id) }, Modifier.fillMaxWidth().padding(16.dp).testTag("transaction.dispute")) {
                    Text(stringResource(R.string.detail_dispute))
                }
                Footnote(stringResource(R.string.detail_dispute_footer))
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String, emphasised: Boolean = false) {
    Text(
        text,
        style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        color = if (emphasised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(label, Modifier.width(112.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.weight(1f))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
internal fun kindLabel(kind: Account.Kind): String =
    stringResource(
        when (kind) {
            Account.Kind.CHEQUING -> R.string.account_kind_chequing
            Account.Kind.SAVINGS -> R.string.account_kind_savings
            Account.Kind.CREDIT_CARD -> R.string.account_kind_credit_card
        },
    )

@Composable
internal fun categoryLabel(category: Transaction.Category): String =
    stringResource(
        when (category) {
            Transaction.Category.GROCERIES -> R.string.category_groceries
            Transaction.Category.SUBSCRIPTIONS -> R.string.category_subscriptions
            Transaction.Category.AUTOMOTIVE -> R.string.category_automotive
            Transaction.Category.DINING -> R.string.category_dining
            Transaction.Category.TRANSPORT -> R.string.category_transport
            Transaction.Category.HEALTH -> R.string.category_health
            Transaction.Category.PAYMENT -> R.string.category_payment
            Transaction.Category.INCOME -> R.string.category_income
            Transaction.Category.HOUSING -> R.string.category_housing
            Transaction.Category.TRANSFER -> R.string.category_transfer
            Transaction.Category.UTILITIES -> R.string.category_utilities
        },
    )

/** Canadian dollars in the customer's own number format. */
@Composable
internal fun money(amount: BigDecimal): String {
    val locale = LocalConfiguration.current.locales[0]
    return NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("CAD") }.format(amount)
}

/** Weekday, day and month, in the order the customer's locale puts them. */
@Composable
internal fun shortDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale))
}

@Composable
internal fun positiveColor(): Color = if (MaterialTheme.colorScheme.isLight()) Palette.positive else Palette.positiveOnDark

@Composable
internal fun negativeColor(): Color = if (MaterialTheme.colorScheme.isLight()) Palette.negative else Palette.negativeOnDark

private fun androidx.compose.material3.ColorScheme.isLight(): Boolean = background.luminance() > 0.5f

/** The SDK's screen-share redaction on a balance, while Settings' Mask balances is on. */
private fun Modifier.maskedBalance(model: AppModel): Modifier = if (model.maskBalances) redactedFromScreenShare() else this
