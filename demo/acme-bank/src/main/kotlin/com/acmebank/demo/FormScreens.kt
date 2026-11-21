package com.acmebank.demo

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.acmebank.demo.support.LiveMode
import com.acmebank.demo.support.LiveSyncCoordinator
import com.acmebank.demo.support.SupportCoordinator
import com.amazon.connect.touchpoint.chat.ui.LiveSyncConsentRevoke
import com.amazon.connect.touchpoint.livesync.ConsentDecision
import kotlinx.coroutines.launch

/**
 * The dispute form. Live Sync can fill it in — and **Submit** stays the one
 * thing no assistant action can press: the customer commits, never the assistant.
 */
@Composable
internal fun DisputeScreen(model: AppModel, screen: Screen.Dispute) {
    val tx = model.transaction(screen.accountId, screen.transactionId) ?: return
    var reason by rememberSaveable(screen) { mutableStateOf(Reason.UNRECOGNISED) }
    var card by rememberSaveable(screen) { mutableStateOf(CardPossession.HAVE_CARD) }
    var authorised by rememberSaveable(screen) { mutableStateOf(false) }
    var details by rememberSaveable(screen) { mutableStateOf("") }
    var submitted by rememberSaveable(screen) { mutableStateOf(false) }
    // The assistant fills fields one at a time as the customer explains; the form follows. The
    // customer can still change any of them, and only the customer can submit. What was applied is
    // remembered across rotation, so a restored form keeps the customer's own edits.
    var applied by rememberSaveable(screen) { mutableStateOf(arrayListOf<String?>(null, null, null, null)) }
    val incoming = listOf(model.disputeReason, model.disputeCardPossession, model.disputeAuthorized?.toString(), model.disputeDetails)
    LaunchedEffect(incoming) {
        if (incoming[0] != applied[0]) model.disputeReason?.let(Reason::fromContract)?.let { reason = it }
        if (incoming[1] != applied[1]) model.disputeCardPossession?.let(CardPossession::fromContract)?.let { card = it }
        if (incoming[2] != applied[2]) model.disputeAuthorized?.let { authorised = it }
        if (incoming[3] != applied[3]) model.disputeDetails?.let { details = it }
        applied = ArrayList(incoming)
    }
    FormScaffold(stringResource(R.string.dispute_title), model) {
        SectionHeader(stringResource(R.string.dispute_section_transaction))
        LabeledRow(stringResource(R.string.detail_merchant), tx.merchant)
        LabeledRow(stringResource(R.string.detail_amount), money(tx.amount))

        SectionHeader(stringResource(R.string.dispute_section_reason))
        Choices(Reason.entries, reason, { it.label }) { reason = it }

        SectionHeader(stringResource(R.string.dispute_section_card))
        Choices(CardPossession.entries, card, { it.label }) { card = it }

        ToggleRow(stringResource(R.string.dispute_authorised), authorised, Modifier) { authorised = it }
        OutlinedTextField(
            details,
            { details = it },
            label = { Text(stringResource(R.string.dispute_details)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button({ submitted = true }, Modifier.fillMaxWidth().padding(16.dp).testTag("dispute.submit"), enabled = !submitted) {
            Text(stringResource(R.string.dispute_submit))
        }
        // A live region, so TalkBack says "Submitted…" when the footer changes to it.
        Footnote(
            if (submitted) stringResource(R.string.dispute_submitted, tx.id.uppercase()) else stringResource(R.string.dispute_footer),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** [contract] is the action contract's enum value for the reason. */
internal enum class Reason(@StringRes val label: Int, val contract: String) {
    UNRECOGNISED(R.string.dispute_reason_unrecognised, "not_recognized"),
    DUPLICATE(R.string.dispute_reason_duplicate, "duplicate"),
    WRONG_AMOUNT(R.string.dispute_reason_wrong_amount, "incorrect_amount"),
    CANCELLED(R.string.dispute_reason_cancelled, "cancelled_service"),
    NOT_RECEIVED(R.string.dispute_reason_not_received, "not_received"),
    OTHER(R.string.dispute_reason_other, "other"),
    ;

    companion object {
        fun fromContract(value: String): Reason? = entries.firstOrNull { it.contract == value }
    }
}

internal enum class CardPossession(@StringRes val label: Int, val contract: String) {
    HAVE_CARD(R.string.dispute_card_have, "have_card"),
    LOST(R.string.dispute_card_lost, "lost"),
    STOLEN(R.string.dispute_card_stolen, "stolen"),
    ;

    companion object {
        fun fromContract(value: String): CardPossession? = entries.firstOrNull { it.contract == value }
    }
}

@Composable
internal fun SettingsScreen(model: AppModel, support: SupportCoordinator, liveSync: LiveSyncCoordinator? = null) {
    FormScaffold(stringResource(R.string.settings_title), model) {
        SectionHeader(stringResource(R.string.settings_livesync_header))
        // The SDK's consent: Stop sharing while it is on, and Ask me again to put the question back.
        val consent = liveSync?.consent
        if (consent != null) {
            val decision by consent.decision.collectAsState()
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(horizontal = 16.dp)) {
                LiveSyncConsentRevoke(decision == ConsentDecision.GRANTED, onRevoke = { scope.launch { consent.deny() } }, Modifier.testTag("settings.stopSharing"))
                TextButton({ scope.launch { consent.reset() } }, Modifier.testTag("settings.askAgain")) { Text(stringResource(R.string.settings_livesync_ask_again)) }
            }
        }
        Footnote(stringResource(R.string.settings_livesync_footer))
        if (LiveMode.AVAILABLE && liveSync != null) {
            ToggleRow(stringResource(R.string.settings_livesync_overlay), liveSync.overlay, Modifier.testTag("settings.overlay")) { liveSync.overlay = it }
        }
        ToggleRow(stringResource(R.string.settings_mask_balances), model.maskBalances, Modifier.testTag("settings.maskBalances")) { model.maskBalances = it }
        Footnote(stringResource(R.string.settings_mask_balances_footer))

        SectionHeader(stringResource(R.string.settings_appearance_header))
        Choices(Appearance.Mode.entries, Appearance.mode, ::appearanceLabel) { Appearance.mode = it }
        Footnote(stringResource(R.string.settings_appearance_footer))

        // Touchpoint's chat options, on the device: what a host turns on, to see before it ships.
        SectionHeader(stringResource(R.string.settings_chat_header))
        if (PartnerLook.AVAILABLE) {
            ToggleRow(stringResource(R.string.settings_partner_look), PartnerLook.on, Modifier.testTag("settings.partnerLook")) { PartnerLook.on = it }
            Footnote(stringResource(R.string.settings_partner_look_footer))
        }
        ToggleRow(stringResource(R.string.settings_chat_times), model.chatTimes, Modifier.testTag("settings.chatTimes")) { model.chatTimes = it }
        ToggleRow(stringResource(R.string.settings_chat_brand_header), model.chatBrandHeader, Modifier.testTag("settings.brandHeader")) { model.chatBrandHeader = it }
        ToggleRow(stringResource(R.string.settings_chat_participant_info), model.chatParticipantInfo, Modifier.testTag("settings.participantInfo")) { model.chatParticipantInfo = it }
        ToggleRow(stringResource(R.string.settings_chat_agent_bubbles), model.chatAgentBubbles, Modifier.testTag("settings.agentBubbles")) { model.chatAgentBubbles = it }
        // The switches a design review walks through.
        SectionHeader(stringResource(R.string.settings_design_header))
        Footnote(stringResource(R.string.settings_chat_brand))
        Choices(ChatBrand.entries, model.chatBrand, ::brandLabel) { model.chatBrand = it }
        ToggleRow(stringResource(R.string.settings_design_avatar_logo), model.assistantAvatarIsLogo, Modifier.testTag("settings.assistantAvatar")) { model.assistantAvatarIsLogo = it }
        ToggleRow(stringResource(R.string.settings_design_square_avatars), model.squareAvatars, Modifier.testTag("settings.squareAvatars")) { model.squareAvatars = it }
        if (support.mode == SupportCoordinator.Mode.OFFLINE) {
            ToggleRow(stringResource(R.string.settings_design_slow_motion), support.slowMotion, Modifier.testTag("settings.slowMotion")) { support.slowMotion = it }
        }
        Footnote(stringResource(R.string.settings_design_footer))

        if (LiveMode.AVAILABLE) {
            SectionHeader(stringResource(R.string.settings_mode_header))
            Choices(SupportCoordinator.Mode.entries, support.mode, ::modeLabel) { support.changeMode(it) }
            Footnote(stringResource(R.string.settings_mode_footer))
            if (support.mode == SupportCoordinator.Mode.LIVE) {
                Column(Modifier.padding(16.dp)) { LiveMode.SettingsSection() }
            }
            support.lastError?.let { Footnote(it) }
            liveSync?.takeIf { it.isConfigured }?.let { LiveSyncSection(it) }
        }

        Button(
            {
                support.endChat()
                liveSync?.disconnect()
                // The demo user's tokens and credentials go with them.
                LiveMode.signOutUser()
                model.signOut()
            },
            Modifier.fillMaxWidth().padding(16.dp).testTag("settings.signOut"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) {
            Text(stringResource(R.string.settings_sign_out))
        }
    }
}

/** Debug: the Live Sync binding at a glance — state, contact, the newest log lines. */
@Composable
private fun LiveSyncSection(liveSync: LiveSyncCoordinator) {
    SectionHeader(stringResource(R.string.settings_livesync_debug_header))
    Footnote(stringResource(R.string.settings_livesync_debug_state, liveSync.state.javaClass.simpleName, liveSync.contactId.take(8).ifEmpty { "—" }))
    Row(Modifier.padding(horizontal = 8.dp)) {
        androidx.compose.material3.TextButton({ liveSync.connect() }, enabled = liveSync.contactId.isNotEmpty()) { Text(stringResource(R.string.settings_livesync_connect)) }
        androidx.compose.material3.TextButton({ liveSync.disconnect() }) { Text(stringResource(R.string.settings_livesync_disconnect)) }
    }
    for (line in liveSync.log.takeLast(LOG_LINES).asReversed()) {
        Text(line, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 3)
    }
}

private const val LOG_LINES = 12

@StringRes
private fun appearanceLabel(mode: Appearance.Mode): Int =
    when (mode) {
        Appearance.Mode.SYSTEM -> R.string.settings_appearance_system
        Appearance.Mode.LIGHT -> R.string.settings_appearance_light
        Appearance.Mode.DARK -> R.string.settings_appearance_dark
    }

private fun brandLabel(brand: ChatBrand): Int =
    when (brand) {
        ChatBrand.NONE -> R.string.settings_chat_brand_none
        ChatBrand.LOGO -> R.string.settings_chat_brand_logo
        ChatBrand.LOGO_AND_NAME -> R.string.settings_chat_brand_logo_and_name
    }

@StringRes
private fun modeLabel(mode: SupportCoordinator.Mode): Int =
    when (mode) {
        SupportCoordinator.Mode.OFFLINE -> R.string.settings_mode_offline
        SupportCoordinator.Mode.LIVE -> R.string.settings_mode_live
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(title: String, model: AppModel, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (model.stack.size > 1) BackButton { model.back() } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            content()
        }
    }
}

/** A radio group: one row per option, the whole row the target, announced as a group. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (option in options) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(option == selected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(option == selected, onClick = null)
                Text(stringResource(label(option)))
            }
        }
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
