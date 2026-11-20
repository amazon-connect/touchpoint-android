package com.acmebank.demo.support

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.acmebank.demo.R
import com.amazon.connect.touchpoint.chat.ui.TouchpointSettingsRow
import com.amazon.connect.touchpoint.core.ReceiptKind

/** Who the developer is speaking as. */
enum class ConsoleSender { ASSISTANT, AGENT }

/** What the console can send besides words: the default modalities, a guide, the sign-in card, a file. */
enum class ConsoleModality(@StringRes val label: Int) {
    CARD(R.string.console_modality_card),
    CAROUSEL(R.string.console_modality_carousel),
    DATE_INPUT(R.string.console_modality_date),
    VIDEO(R.string.console_modality_video),
    MARKDOWN(R.string.console_modality_markdown),
    GUIDE(R.string.console_modality_guide),
    SIGN_IN(R.string.console_modality_sign_in),

    /** A file from the agent, who joins first if they are not on the contact. */
    FILE(R.string.console_modality_file),
}

/**
 * The offline transport's other side, for the developer to play: what an assistant or a person would
 * send over Connect, sent at once. Each call reports whether it went; nothing goes while no chat is
 * connected, and only a person on the contact sends receipts or types as the agent.
 */
interface AgentConsoleControls {
    val isConnected: Boolean

    /** The tour's own replies. Off, only what the console sends comes back. */
    var autoReplies: Boolean

    fun say(text: String, sender: ConsoleSender): Boolean

    /** The assistant's message with suggested replies under it. */
    fun offerChoices(text: String, choices: List<String>): Boolean

    fun send(modality: ConsoleModality): Boolean

    fun typing(sender: ConsoleSender): Boolean

    /** A receipt for the customer's newest message, from the agent. */
    fun receipt(kind: ReceiptKind): Boolean

    fun agentJoins(): Boolean

    fun agentLeaves(): Boolean

    /** The socket drops; the session reconnects by itself. */
    fun dropConnection(): Boolean

    fun endContact(): Boolean
}

/**
 * The agent console's handle for the support dock: the sheet, composed by [rememberAgentConsole]
 * while it is open; a button for the bank's own chrome; and a row for the chat's settings, so it
 * opens over a chat that is up too. Both are absent in live mode, where a real agent answers.
 */
class AgentConsole internal constructor(
    private val controls: AgentConsoleControls?,
    private val open: () -> Unit,
    private val label: String,
) {
    val available: Boolean get() = controls != null

    /** For the chat's `settingsRows`: empty in live mode. */
    val settingsRows: List<TouchpointSettingsRow>
        get() = if (controls == null) emptyList() else listOf(TouchpointSettingsRow(label) { open() })

    @Composable
    fun Button(modifier: Modifier = Modifier) {
        if (controls == null) return
        TextButton(open, modifier.heightIn(min = 48.dp).testTag("console.open")) { Text(label) }
    }
}

/** The console for the offline transport's [controls], or a handle that shows nothing when it is `null`. */
@Composable
fun rememberAgentConsole(controls: AgentConsoleControls?): AgentConsole {
    var showing by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.console_open)
    if (showing && controls != null) AgentConsoleSheet(controls) { showing = false }
    return remember(controls, label) { AgentConsole(controls, { showing = true }, label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentConsoleSheet(
    controls: AgentConsoleControls,
    onDismiss: () -> Unit,
) {
    // Half open by default, so the chat above it shows what each tap does.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        AgentConsoleContent(controls)
    }
}

@Composable
internal fun AgentConsoleContent(controls: AgentConsoleControls) {
    var autoReplies by remember { mutableStateOf(controls.autoReplies) }
    var text by rememberSaveable { mutableStateOf("") }
    var choices by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val sent = stringResource(R.string.console_sent)
    val notSent = stringResource(R.string.console_not_sent)
    val notConnected = stringResource(R.string.console_not_connected)
    fun report(ok: Boolean) {
        status = if (ok) sent else if (controls.isConnected) notSent else notConnected
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().testTag("console.sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.console_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.console_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(autoReplies, role = Role.Switch) {
                    autoReplies = it
                    controls.autoReplies = it
                }.testTag("console.autoReplies"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.console_auto_replies), Modifier.weight(1f))
            Switch(autoReplies, onCheckedChange = null)
        }

        Section(R.string.console_section_message)
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth().testTag("console.text"),
            label = { Text(stringResource(R.string.console_text)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ report(text.isNotBlank() && controls.say(text.trim(), ConsoleSender.ASSISTANT)) }, Modifier.testTag("console.sayAssistant")) {
                Text(stringResource(R.string.console_say_assistant))
            }
            Button({ report(text.isNotBlank() && controls.say(text.trim(), ConsoleSender.AGENT)) }, Modifier.testTag("console.sayAgent")) {
                Text(stringResource(R.string.console_say_agent))
            }
        }
        OutlinedTextField(
            choices,
            { choices = it },
            Modifier.fillMaxWidth().testTag("console.choices"),
            label = { Text(stringResource(R.string.console_choices)) },
            supportingText = { Text(stringResource(R.string.console_choices_hint)) },
        )
        OutlinedButton(
            {
                val list = choices.split(',').map(String::trim).filter(String::isNotEmpty)
                report(text.isNotBlank() && list.isNotEmpty() && controls.offerChoices(text.trim(), list))
            },
            Modifier.testTag("console.offerChoices"),
        ) { Text(stringResource(R.string.console_offer_choices)) }

        Section(R.string.console_section_modalities)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (modality in ConsoleModality.entries) {
                AssistChip({ report(controls.send(modality)) }, { Text(stringResource(modality.label)) }, Modifier.testTag("console.modality.${modality.name.lowercase()}"))
            }
        }

        Section(R.string.console_section_agent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(R.string.console_agent_joins, "agentJoins") { report(controls.agentJoins()) }
            Action(R.string.console_agent_leaves, "agentLeaves") { report(controls.agentLeaves()) }
            Action(R.string.console_typing_agent, "typingAgent") { report(controls.typing(ConsoleSender.AGENT)) }
            Action(R.string.console_typing_assistant, "typingAssistant") { report(controls.typing(ConsoleSender.ASSISTANT)) }
            Action(R.string.console_delivered, "delivered") { report(controls.receipt(ReceiptKind.DELIVERED)) }
            Action(R.string.console_read, "read") { report(controls.receipt(ReceiptKind.READ)) }
        }

        Section(R.string.console_section_contact)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(R.string.console_drop, "drop") { report(controls.dropConnection()) }
            Action(R.string.console_end, "end") { report(controls.endContact()) }
        }

        // Said, not only shown: a tap that went nowhere would otherwise look the same as one that went.
        Text(
            status.orEmpty(),
            Modifier.fillMaxWidth().padding(bottom = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("console.status"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Action(
    @StringRes label: Int,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp).testTag("console.$tag")) { Text(stringResource(label)) }
}
