@file:OptIn(com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.chat.ui.TranscriptSpacing.Edge
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlinx.coroutines.Dispatchers
import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.Attachment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.amazon.connect.touchpoint.ui.R
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.TranscriptNotice
import com.amazon.connect.touchpoint.core.viewReference
import com.amazon.connect.touchpoint.core.viewSummary
import com.amazon.connect.touchpoint.core.modalities
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the surface can ask of the session. Each returns whether it was accepted, so a refusal is
 * surfaced rather than swallowed.
 */
internal class ChatActions(
    val send: suspend (String) -> Boolean,
    val retry: suspend (Message) -> Boolean,
    val loadEarlier: suspend () -> Unit,
    val close: (() -> Unit)?,
    val dismissProblem: () -> Unit,
    val announced: () -> Unit,
    /** Ends the contact and starts a new one. `null` when the host gave no way to start: Restart disabled, no Start new. */
    val restart: (suspend () -> Boolean)? = null,
    /** Ends the conversation (the header's added Close, after its confirmation). `null`: that Close only closes. */
    val end: (suspend () -> Boolean)? = null,
    /** The transcript as plain text for the share sheet, `[time] name: text` per line; `null` hides Download. */
    val transcript: (suspend () -> String?)? = null,
    /** Opens a message's link — only one [Markdown.safeUrl] allows reaches here. */
    val openLink: (String) -> Unit = {},
    /** Answers the newest message's choice: its words, and its id. `null`: the choices are shown, disabled. */
    val choose: (suspend (text: String, choiceId: String) -> Boolean)? = null,
    /** A modality's pick filling a slot, as web's `sendSlots`. */
    val slot: (suspend (id: String, value: String) -> Boolean)? = null,
    /** The customer is typing: the session throttles it, as web does. */
    val typing: suspend () -> Unit = {},
    /** Uploads a picked file; `false` if it could not be. `null`: no Attach, as on a transport without files. */
    val attach: (suspend (AttachmentUpload) -> Boolean)? = null,
    /** Saves an agent's file where the customer chooses. `null`: its pill is not pressable. */
    val download: ((Attachment) -> Unit)? = null,
    /** Connect's sign-in card: open the page, or continue without. `null`: the card is shown, its actions disabled. */
    val authentication: AuthenticationActions? = null,
    /** The host's settings rows (a call, as the guide places it), after the SDK's own. */
    val settingsRows: List<TouchpointSettingsRow> = emptyList(),
)

/**
 * The chat surface, drawn as the Touchpoint design guide draws it on a phone, with Touchpoint web's
 * tokens, icons, copy and rules where the guide agrees:
 *
 * - **Header:** the brand (the guide's sparkle without one) and the title; Restart, Settings and
 *   Collapse on the right — Back in Settings' place while the settings are open, and no Restart. Round
 *   40 ghost buttons with 16 glyphs, no divider; Restart the main button after a failure.
 * - **Background:** web's translucent fill and its depth layers — a wash from the right, a soft-light
 *   sheen, a shade toward the bottom, grain.
 * - **Welcome:** the guide's loading dot until the greeting arrives; then, until the customer first
 *   sends, the greeting larger and left-aligned, with the composer and any choices under it, the
 *   block held in the middle of the panel. Not in a half-height panel, where it is the first message.
 * - **Transcript:** 12 in from the panel's edges, 32 between messages. The assistant's words plain, 4
 *   further in; a human agent's in a `secondary40` bubble under their avatar and name; the customer's
 *   in a `primary10` bubble at the end. Times and receipts 8 in from their message, "time • receipt";
 *   choices under the message that offers them; notices; "Thinking..." with web's shimmer; a failure
 *   as web's red banner.
 * - **Input:** web's composer, its text 24 in, outlined in the focus colour while focused; Send is the
 *   main button. Start new conversation once the conversation has ended.
 * - **Settings:** Restart conversation and Download transcript, and the host's Call us, as the
 *   guide's settings frame has them: no End conversation; Restart ends the contact on its way.
 *
 * Mobile differences: 48dp targets around web's 40 buttons; Try again on a failed message (decided
 * 2026-10-14); the transcript shared rather than downloaded; the composer stays while connecting, for
 * the outbox; a reconnect shown as an interim line; TalkBack labels and one live region.
 *
 * Every dimension comes from [TouchpointMetrics], every colour from [LocalTouchpointTheme],
 * every string from resources.
 */
@Composable
internal fun ChatSurface(
    state: TranscriptState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    mini: Boolean = false,
    onExpand: () -> Unit = {},
    onMiniClose: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
    /** The sheet's own close, for web's added `header.close`; `null` outside a sheet, where Close is Collapse's action. */
    onDismiss: (() -> Unit)? = null,
) {
    // Web's `md:` is the window's width, not the panel's: a half-screen panel on a tablet gets it too.
    val window = LocalWindowWidth.current ?: LocalConfiguration.current.screenWidthDp.dp
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    CompositionLocalProvider(LocalWideWindow provides (window >= TouchpointMetrics.MEDIUM_WINDOW)) {
        BoxWithConstraints(modifier) {
            // The guide's half-screen layout: a panel well short of the window's height — a bottom
            // sheet, a floating card — has no room for the welcome screen.
            val half = maxHeight < windowHeight * HALF_LAYOUT_FRACTION
            Surface(state, actions, Modifier.fillMaxSize(), options, mini, onExpand, onMiniClose, footer, onDismiss, half)
        }
    }
}

@Composable
private fun Surface(
    state: TranscriptState,
    actions: ChatActions,
    modifier: Modifier,
    options: TouchpointChatOptions,
    mini: Boolean,
    onExpand: () -> Unit,
    onMiniClose: () -> Unit,
    footer: (@Composable () -> Unit)?,
    onDismiss: (() -> Unit)?,
    half: Boolean,
) {
    val metrics = TouchpointMetrics.current()
    var draft by rememberSaveable { mutableStateOf("") }
    // While the customer types, the welcome screen's suggestions step aside, as the guide's.
    var composerFocused by remember { mutableStateOf(false) }
    // The composer is drawn in the welcome, then under the transcript: two places, so the first send
    // from the welcome would drop the keyboard. It is focused again where it lands.
    val composerFocus = remember { FocusRequester() }
    var refocusComposer by remember { mutableStateOf(false) }
    val accessibility = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    val touchExploration = accessibility?.isTouchExplorationEnabled == true
    val focusAfterPick = remember { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Survives the settings menu: closing it must not send the reader back to the top.
    val listState = rememberLazyListState()
    // What the surface itself has to say — a refused send, a status change, the error — spoken
    // through the one live region, after the model's own announcements.
    val spoken = remember { mutableStateListOf<String>() }
    val sendFailed = stringResource(R.string.touchpoint_composer_send_failed)
    val errorText = stringResource(R.string.touchpoint_problem_banner)
    val endedText = stringResource(R.string.touchpoint_notice_ended)
    val shareTitle = stringResource(R.string.touchpoint_transcript_share_title)
    val connectingText = stringResource(R.string.touchpoint_status_connecting)
    val thinkingText = stringResource(R.string.touchpoint_status_thinking)
    val typingText = stringResource(R.string.touchpoint_status_typing)
    val statusText = state.statusLine?.takeIf { it != StatusLine.ENDED }?.let { statusString(it) }
    val restartFailed = stringResource(R.string.touchpoint_restart_failed)
    val choiceFailed = stringResource(R.string.touchpoint_choice_failed)
    // The message whose choice has just been tapped: its choices are gone from then on.
    var answeredFor by remember { mutableStateOf<UUID?>(null) }
    val endFailed = stringResource(R.string.touchpoint_end_failed)
    val joinedText = stringResource(R.string.touchpoint_notice_agent_joined)
    val leftText = stringResource(R.string.touchpoint_notice_agent_left)
    // Whether this conversation's transcript has been opened at its newest message: once, not every
    // time the transcript comes back from the settings.
    val openedAtEnd = remember { mutableStateOf(false) }
    // Messages parsed once, not again each time a row scrolls back into view or is announced.
    val markdown = remember { MarkdownCache() }

    // The guide's welcome screen: the opening turn in the middle until the customer sends, and the
    // transcript as soon as anything else is in it — a notice, a failure, earlier pages. Not in the
    // half-screen layout.
    val welcome = options.welcomeScreen && !half && state.welcome && !state.ended && !state.problem && !state.pagingFailed && !state.canLoadEarlier && state.notices.isEmpty()
    // Web's logo above the greeting, where the host asks for it; the header keeps its own.
    val welcomeLogo = options.brandIcon?.takeIf { welcome && options.shows(TouchpointHiddenElement.WELCOME_SCREEN_LOGO) }
    // The loading dot until the greeting arrives, in either layout.
    val opening = options.welcomeScreen && state.welcome && !state.ended && !state.problem && !state.pagingFailed && !state.canLoadEarlier && state.notices.isEmpty()
    val loaderLabel =
        when {
            !opening || state.messages.isNotEmpty() || state.inCall -> null
            state.contactState == ContactState.Connected -> if (state.typing) typingText else state.interimText ?: thinkingText
            state.contactState == ContactState.Connecting || state.contactState is ContactState.Reconnecting -> connectingText
            else -> null // not started, or suspended: nothing is on its way, and a spinner would say it is
        }
    val choices = if (state.choiceMessageId != null && state.choiceMessageId == answeredFor) emptyList() else state.choices
    // The welcome screen holds the composer under the greeting; the transcript, the choices under
    // the message that offers them.
    val welcomeShown = welcome && state.messages.isNotEmpty()
    // Keyed on the welcome alone: clearing the request must not restart (and so cancel) this.
    LaunchedEffect(welcomeShown) {
        if (!refocusComposer || welcomeShown) return@LaunchedEffect
        refocusComposer = false
        // The field is attached in its new place within a frame or two; until then the request throws.
        repeat(REFOCUS_FRAMES) {
            withFrameNanos { }
            if (runCatching { composerFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // Web's one interim line: thinking after a send; typing, or the assistant's own line, in its
    // place. Shown under a connecting line, which is Android's; a drop says so in its own notice.
    val liveInterim =
        when {
            state.ended -> null
            state.typing -> typingText
            state.interimText != null -> state.interimText
            state.awaitingReply -> thinkingText
            else -> null
        }
    val interim = if (!state.ended && state.contactState == ContactState.Connecting) statusText else liveInterim

    fun submit(text: String) {
        val body = text.trim()
        if (body.isEmpty() || !state.canSend) return
        if (composerFocused && welcomeShown) refocusComposer = true
        draft = ""
        // Atomic and non-cancellable: closing the sheet right after Send must not cancel the send
        // before the message is in the outbox, losing it and the draft together.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val accepted = withContext(NonCancellable) { actions.send(body) }
            if (!accepted) {
                // Only restore into an empty box: the user may have started the next message, and their text wins.
                if (draft.isEmpty()) draft = body
                spoken += sendFailed
            }
        }
    }

    // A choice is sent as Send sends: atomic, past a closing sheet. As web, the choices go the moment
    // one is tapped — not when the answer is back from the store — so a second tap sends nothing; a
    // refusal brings them back, and is spoken.
    fun choose(
        choice: Choice,
        label: String,
    ) {
        val c = actions.choose ?: return
        val message = state.choiceMessageId ?: return
        if (!state.canSend || answeredFor == message) return
        answeredFor = message
        // The choices go with the pick, and TalkBack's focus with them, to the top of the panel: it is
        // sent on to the customer's answer instead, where the conversation goes on.
        if (touchExploration) focusAfterPick.value = true
        scope.launch(start = CoroutineStart.ATOMIC) {
            if (!withContext(NonCancellable) { c(label, choice.id) }) {
                if (answeredFor == message) answeredFor = null
                spoken += choiceFailed
            }
        }
    }

    // Each change to a draft with words in it tells the other side, as web's input does.
    fun typed(text: String) {
        draft = text
        if (text.isNotEmpty()) scope.launch { actions.typing() }
    }

    // Web's attachment: one file at a time, uploaded as soon as it is picked; gone from the composer
    // once it is sent, and kept there with its error when it is not, until the customer removes it.
    var file by remember { mutableStateOf<PendingFile?>(null) }
    val tooBig = stringResource(R.string.touchpoint_attach_too_big)
    val uploadFailed = stringResource(R.string.touchpoint_attach_failed)
    val unreadable = stringResource(R.string.touchpoint_attach_unreadable)
    fun failed(name: String?, message: String) {
        file = PendingFile(name, message)
        spoken += message // web's role="alert"
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val attach = actions.attach ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult // the customer backed out
            // Like Send, an upload runs to the end once begun: closing the sheet must not leave a file
            // half sent. Its provider is read off the main thread — a cloud one can take a while.
            // Held from the pick, before the provider answers, so a second tap cannot pick another meanwhile.
            file = PendingFile(null, error = null)
            scope.launch(start = CoroutineStart.ATOMIC) {
                val upload = withContext(Dispatchers.IO) { AttachmentFiles.describe(context.contentResolver, uri) } ?: return@launch failed(null, unreadable)
                if (upload.sizeBytes > AttachmentUpload.MAX_BYTES) return@launch failed(upload.name, tooBig)
                val pending = PendingFile(upload.name, error = null)
                file = pending
                val sent = withContext(NonCancellable) { attach(upload) }
                // Only the file it was for: the customer may have removed it meanwhile.
                if (file === pending) if (sent) file = null else failed(upload.name, uploadFailed)
            }
        }
    // An upload is its contact's: once that ends (a restart mid-upload), its chip goes, and
    // a late result, which looks for the file it was for, finds none and says nothing.
    LaunchedEffect(state.ended) { if (state.ended) file = null }
    // Web offers Attach only where the host turns attachments on (1.0.6), and only while an agent is
    // with the customer.
    val canAttach = options.attachmentsEnabled && actions.attach != null && state.humanAgentActive && !state.ended
    fun attach() {
        if (!state.canSend || file != null) return
        try {
            picker.launch(AttachmentFiles.TYPES)
        } catch (_: ActivityNotFoundException) {
            failed(null, unreadable) // no document picker on this device
        }
    }
    val composer: @Composable () -> Unit = {
        Composer(
            draft, ::typed, enabled = state.canSend && !state.composerLocked, onSubmit = ::submit,
            lockedHint = if (state.composerLocked) stringResource(R.string.touchpoint_authentication_locked_hint) else null,
            attach = if (canAttach) ::attach else null,
            attachEnabled = state.canSend && file == null,
            file = file,
            onRemove = { file = null },
            uploading = file?.let { it.error == null } == true,
            onFocus = { composerFocused = it },
            focusRequester = composerFocus,
        )
    }

    // Restart and End, like Send, run to completion even if the sheet closes straight after: a start
    // cancelled mid-connect would tear the new contact down.
    fun restart() {
        val r = actions.restart ?: return
        menuOpen = false
        scope.launch(start = CoroutineStart.ATOMIC) {
            if (withContext(NonCancellable) { r() }) draft = "" else spoken += restartFailed
        }
    }

    fun end() {
        val e = actions.end ?: return
        scope.launch(start = CoroutineStart.ATOMIC) {
            if (!withContext(NonCancellable) { e() }) spoken += endFailed
        }
    }

    // Web's added Close (1.0.6): while the conversation is live it asks first, and its yes ends the
    // conversation and closes the chat; ended, or not yet begun, it only closes.
    val closeChat = onDismiss ?: (if (mini) onMiniClose else actions.close)
    val live = !state.ended && state.contactState != ContactState.Idle
    val addedClose: (() -> Unit)? =
        if (actions.end != null && live && closeChat != null && TouchpointAddedElement.HEADER_CLOSE in options.addedElements) ({ confirmEnd = true }) else onDismiss

    // Copy, a mobile need: native text is not selectable as a web page's is. Android 13 and later say
    // so themselves; on 12 the surface does.
    val copiedText = stringResource(R.string.touchpoint_copied)
    val clipboardLabel = stringResource(R.string.touchpoint_transcript_label)
    fun copy(text: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(clipboardLabel, text))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) spoken += copiedText
    }

    fun download() {
        val t = actions.transcript ?: return
        scope.launch { t()?.let { shareTranscript(context, it, shareTitle) } }
    }

    // WCAG 4.1.3: a connection change, a failure, the end are status messages, announced rather than noticed.
    LaunchedEffect(statusText) { if (statusText != null) spoken += statusText }
    LaunchedEffect(state.problem) { if (state.problem) spoken += errorText }
    LaunchedEffect(state.ended) { if (state.ended) spoken += endedText }
    // As web's announcer: a notice, and "Thinking..." after a send. Only what happened since the
    // surface opened: the session replays the conversation's notices to it, and those are history.
    val openedAt = remember { java.time.Instant.now() }
    var noticesSpoken by remember { mutableIntStateOf(state.notices.size) }
    LaunchedEffect(state.notices.size) {
        for (n in state.notices.drop(noticesSpoken)) {
            if (n.at < openedAt) continue
            spoken +=
                when (n.kind) {
                    TranscriptNotice.Kind.AGENT_JOINED -> joinedText
                    TranscriptNotice.Kind.AGENT_LEFT -> leftText
                    TranscriptNotice.Kind.CONNECTION_LOST -> errorText
                }
        }
        noticesSpoken = state.notices.size
    }
    // The sign-in card's status, each time it moves after the surface opened.
    val card = state.authenticationCard
    val cardText = card?.let { statusText(it.status) }
    var cardHeard by remember { mutableStateOf(card) }
    LaunchedEffect(card) {
        val heard = cardHeard
        if (card != null && cardText != null && (heard == null || heard.id != card.id || heard.status != card.status)) spoken += cardText
        cardHeard = card
    }
    // As web's announcer, one slot: each new interim line is spoken once, and one that has gone before
    // its turn is not spoken at all. A reconnect in between does not make it new again.
    var interimQueued by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(liveInterim) {
        interimQueued?.let { spoken.remove(it) }
        interimQueued = liveInterim
        if (liveInterim != null) spoken += liveInterim
    }
    // A new conversation opens at its newest message again.
    LaunchedEffect(welcome) { if (welcome) openedAtEnd.value = false }
    // Back walks out of the settings before it closes anything (the confirmation, a dialog, takes its own).
    BackHandler(enabled = menuOpen) { menuOpen = false }
    // The end confirmation is over the panel, not a window of its own: Back cancels it first.
    BackHandler(enabled = confirmEnd) { confirmEnd = false }

    // One node for the host, and one announcer for both forms, so collapsing or expanding neither drops
    // nor repeats a line.
    CompositionLocalProvider(LocalMarkdownCache provides markdown, LocalChatOptions provides options) {
        Box(modifier) {
            // Floating (web's `floating`, the sheet's mini): the whole chat in a small card, with Expand back
            // to the sheet and a close that closes.
            run {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val panelHeight = maxHeight
                    CompositionLocalProvider(LocalPanelHeight provides panelHeight) {
                        WebBackground(Modifier.matchParentSize(), depth = options.backgroundDepthLayer)
                        // Web's `@3xl/main`: on a panel this wide the header floats over the content, which runs
                        // to the top beneath it; the content is held to web's 640 from `md:`, centred.
                        val floatingHeader = maxWidth >= TouchpointMetrics.WIDE_PANEL
                        val held = if (metrics.wide) Modifier.widthIn(max = TouchpointMetrics.contentMaxWidth) else Modifier
                        // The header and the host's notice under it, stacked in either placement (the floating
                        // one is a Box, which would draw them over each other).
                        val header: @Composable () -> Unit = { Column(Modifier.fillMaxWidth()) {
                            Header(
                                brand = options.brandIcon,
                                menuOpen = menuOpen,
                                onRestart = if (actions.restart != null) ::restart else null,
                                onToggleMenu = { menuOpen = !menuOpen },
                                onClose = if (mini) onMiniClose else actions.close,
                                onExpand = onExpand.takeIf { mini },
                                // The guide's: a wide brand 32 tall and as wide as it is, in every form.
                                brandSquare = false,
                                // Web's added Close: where Collapse drops the sheet to mini, it closes it outright.
                                onDismiss = addedClose,
                                restartMain = state.problem,
                            )
                            HeaderNotice(options.headerNotice)
                        } }
                        // Floating, it is still the first thing TalkBack and the keyboard meet, as on web: composed
                        // first, drawn over the content.
                        if (floatingHeader) {
                            Box(Modifier.zIndex(1f).then(if (confirmEnd) Modifier.clearAndSetSemantics {} else Modifier).semantics { isTraversalGroup = true; traversalIndex = -1f }) { header() }
                        }
                        // Under the end confirmation, the panel is out of TalkBack's reach, as web's veil leaves it.
                        val underConfirm = if (confirmEnd) Modifier.clearAndSetSemantics {} else Modifier
                        Column(Modifier.fillMaxSize().then(underConfirm), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!floatingHeader) header()
                            // Web's settings pad the full width and hold only their list to 640.
                            Box(Modifier.weight(1f).then(if (menuOpen) Modifier else held).fillMaxWidth()) {
                                when {
                                    menuOpen ->
                                        SettingsMenu(
                                            // Web keeps the header's Restart, but not the menu's, while a person is answering.
                                            onRestart = if (actions.restart != null && !(state.humanAgentActive && !state.ended)) ::restart else null,
                                            onDownload = if (actions.transcript != null) ::download else null,
                                            // A host row closes the settings, then runs.
                                            rows =
                                                actions.settingsRows.map { row ->
                                                    TouchpointSettingsRow(row.label, row.prominent) {
                                                        menuOpen = false
                                                        row.action()
                                                    }
                                                },
                                        )
                                    loaderLabel != null -> Loading(loaderLabel)
                                    welcomeShown ->
                                        Welcome(
                                            state.messages,
                                            state.participantNames,
                                            actions.openLink,
                                            logo = welcomeLogo,
                                            composer = composer,
                                            // The guide's: out of the way while the customer types.
                                            choices =
                                                if (choices.isNotEmpty() && !composerFocused) {
                                                    { Choices(choices, if (actions.choose != null && state.canSend) ::choose else null) }
                                                } else {
                                                    null
                                                },
                                        )
                                    else ->
                                        Transcript(
                                            state, interim, actions, listState, openedAtEnd, onRetryRefused = { spoken += sendFailed }, onCopy = ::copy,
                                            onPickRefused = { spoken += choiceFailed }, focusAfterPick = focusAfterPick,
                                            choices = choices, onChoose = if (actions.choose != null && state.canSend) ::choose else null,
                                        )
                                }
                            }
                            if (!menuOpen && loaderLabel == null && !welcomeShown) {
                                // Web's input container: p-2 (md:p-3), gap-2.
                                Column(held.fillMaxWidth().padding(metrics.inset), verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
                                    if (state.ended) {
                                        // Web replaces the composer with a way to begin again.
                                        if (actions.restart != null) RowButton(stringResource(R.string.touchpoint_action_start_new), TouchpointIcons.Restart, ::restart, main = true)
                                    } else {
                                        composer()
                                    }
                                }
                            }
                            // The host's footer — a call's controls, say — below everything the panel shows, on
                            // its surface and within its column, so it is part of the tray, not a second surface.
                            if (footer != null) Box(held.fillMaxWidth()) { footer() }
                        }
                        if (confirmEnd) {
                            // Over the floating header too (a panel 768 and wider), so no header tap gets
                            // past the veil.
                            Box(Modifier.fillMaxSize().zIndex(2f)) {
                                EndConfirmation(
                                    copy = options.copy.endConversationConfirm,
                                    onCancel = { confirmEnd = false },
                                    onEnd = {
                                        confirmEnd = false
                                        menuOpen = false
                                        end()
                                        closeChat?.invoke()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Announcer(state, actions, spoken)
        }
    }
}

/** A modality's pick, as `choose` runs a choice: not cancelled by the row going, and a refusal reported. */
private fun pick(
    scope: kotlinx.coroutines.CoroutineScope,
    onRefused: () -> Unit,
    send: suspend () -> Boolean,
) {
    scope.launch(start = CoroutineStart.ATOMIC) { if (!withContext(NonCancellable) { send() }) onRefused() }
}

/** Parsed messages, by text: bounded, most recently used kept. Main thread only. */
internal class MarkdownCache {
    private val parsed =
        object : LinkedHashMap<String, List<Markdown.Block>>(CACHE_SIZE, CACHE_LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Markdown.Block>>?): Boolean = size > CACHE_SIZE
        }

    fun blocks(body: String): List<Markdown.Block> = parsed.getOrPut(body) { Markdown.parse(body) }

    fun plainText(body: String): String = Markdown.plainText(blocks(body))
}

internal val LocalMarkdownCache = staticCompositionLocalOf { MarkdownCache() }

// Background

// Header and settings

// Welcome and loading

/**
 * The guide's welcome screen: the opening messages, markdown, left-aligned at Large 24/32 (Extra
 * Large from `md:`), 16 apart, then the [composer] and any [choices] under it, 8 apart — the block in
 * the middle of the panel, scrolling when the greeting is long. The greeting lines up with the
 * transcript's text, the composer with the panel's input.
 */
@Composable
private fun Welcome(
    messages: List<Message>,
    names: Map<String, String>,
    onLink: (String) -> Unit,
    logo: Painter?,
    composer: @Composable () -> Unit,
    choices: (@Composable () -> Unit)?,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(metrics.inset),
            verticalArrangement = Arrangement.Center,
        ) {
            // Web's logo above the greeting, where the host asks for it.
            if (logo != null && messages.any { it.body.isNotBlank() }) {
                Box(Modifier.padding(start = metrics.transcriptInset - metrics.inset + metrics.textIndent, bottom = metrics.sectionGap)) { BrandImage(logo, TouchpointMetrics.welcomeLogo) }
            }
            Column(
                Modifier.padding(horizontal = metrics.transcriptInset - metrics.inset + metrics.textIndent),
                verticalArrangement = Arrangement.spacedBy(metrics.welcomeGap),
            ) {
                val cache = LocalMarkdownCache.current
                val openLabel = stringResource(R.string.touchpoint_action_open_link)
                for (m in messages) {
                    if (m.body.isBlank()) continue
                    val blocks = cache.blocks(m.body)
                    val label = stringResource(R.string.touchpoint_transcript_bubble_label, spokenSender(m, names), Markdown.plainText(blocks))
                    val actions = remember(blocks, onLink, openLabel) { linkActions(Markdown.links(blocks), { w -> openLabel.format(w) }, onLink) }
                    // One node per message for TalkBack, as in the transcript: its words, and its links as actions.
                    MarkdownContent(
                        blocks,
                        theme.text(theme.primary, metrics.welcomeText, metrics.welcomeLineHeight),
                        blockGap = metrics.blockGap,
                        onLink = onLink,
                        modifier =
                            Modifier.clearAndSetSemantics {
                                contentDescription = label
                                text = AnnotatedString(Markdown.plainText(blocks))
                                if (actions.isNotEmpty()) customActions = actions
                            },
                    )
                }
            }
            Spacer(Modifier.height(metrics.welcomeGap))
            composer()
            if (choices != null) {
                Spacer(Modifier.height(metrics.smallGap))
                choices()
            }
        }
    }
}

// Transcript

@Composable
private fun Transcript(
    state: TranscriptState,
    interim: String?,
    actions: ChatActions,
    listState: LazyListState,
    openedAtEnd: MutableState<Boolean>,
    onRetryRefused: () -> Unit,
    /** A modality's pick the session refused: said, as a refused choice is. */
    onPickRefused: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    /** Set by a pick made with TalkBack: the customer's answer, when it lands, takes the focus. */
    focusAfterPick: MutableState<Boolean> = remember { mutableStateOf(false) },
    /** The newest message's choices, drawn under it, as the guide's; [onChoose] `null` draws them disabled. */
    choices: List<Choice> = emptyList(),
    onChoose: ((Choice, String) -> Unit)? = null,
) {
    val metrics = TouchpointMetrics.current()
    val theme = LocalTouchpointTheme.current
    val scope = rememberCoroutineScope()
    val options = LocalChatOptions.current
    val rows = state.rows
    // The answer a pick made: a row of the customer's newer than the one there when they picked.
    val newestOwn = rows.lastOrNull { it.message.direction == Direction.OUTBOUND }?.id
    var ownAtPick by remember { mutableStateOf<java.util.UUID?>(null) }
    val picking = focusAfterPick.value
    LaunchedEffect(picking) { if (picking) ownAtPick = newestOwn }
    val answer = newestOwn?.takeIf { picking && it != ownAtPick }
    val answerFocus = remember { FocusRequester() }
    LaunchedEffect(answer) {
        if (answer == null) return@LaunchedEffect
        // Laid out first: a request before the row is placed goes nowhere.
        withFrameNanos { }
        runCatching { answerFocus.requestFocus() }
        focusAfterPick.value = false
    }
    // Web's message times: which rows show one, each judged against the next drawn message.
    val times = rememberMessageTimes()
    val stamped = remember(rows, options, times) { times.shown(rows.map { it.message }, options) }
    val label = stringResource(R.string.touchpoint_transcript_label)
    val reduceMotion = reduceMotion()
    val noticesAfter = state.notices.groupBy { it.afterMessageId }
    val ids = rows.mapTo(HashSet()) { it.id }
    val placedNotices = state.notices.count { it.afterMessageId == null || it.afterMessageId in ids }
    // The sign-in card, placed as a notice is: after the message it followed, at the start when there
    // was none, and at the end when that message draws no row (a filled slot).
    val card = state.authenticationCard
    val cardAfter: UUID? = card?.afterMessageId?.let { if (it in ids) it else rows.lastOrNull()?.id }

    // "At the end" stands in for "the reader is not reading back": an arrival follows them only then.
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    // The index of the last item, from state rather than from a layout that has not caught up yet:
    // the paging control, the rows and their notices, the interim line, the error, the end.
    val lastIndex =
        (if (state.pagingFailed || state.canLoadEarlier) 1 else 0) + rows.size + placedNotices + (if (card != null) 1 else 0) +
            (if (choices.isNotEmpty()) 1 else 0) + (if (interim != null) 1 else 0) + (if (state.problem) 1 else 0) + (if (state.ended) 1 else 0) - 1
    // A conversation opens at its newest message — once: coming back from the settings, a reader keeps their place.
    LaunchedEffect(state.initialLoaded) {
        if (state.initialLoaded && lastIndex >= 0 && !openedAtEnd.value) {
            listState.scrollToItem(lastIndex)
            openedAtEnd.value = true
        }
    }
    // A viewport that shrinks — the keyboard opening, a host resizing its panel — keeps the first
    // visible row, which strands a reader who was at the newest message. Whether they were is set
    // by scrolling alone, never by a layout pass, and a resize puts a follower back at the end.
    // Requested, not scrolled: a request takes no scroll mutex, so a fling still settling cannot
    // cancel it (and with it this effect), and it lands at the next measure.
    val end by rememberUpdatedState(lastIndex)
    LaunchedEffect(listState) {
        var following = true
        launch { snapshotFlow { listState.isScrollInProgress to atEnd }.collect { (scrolling, at) -> if (scrolling) following = at } }
        snapshotFlow { listState.layoutInfo.viewportSize.height }.drop(1).distinctUntilChanged().collect {
            if (following && end >= 0) listState.requestScrollToItem(end)
        }
    }
    // What arrives at the end: a newer server message, one more unsent message of ours, a notice, the
    // interim line. Not the total count, which also grows when older pages are prepended. Followed only
    // while the reader is at the end; someone reading back keeps their place, and the announcement
    // still tells them.
    val endKey = (state.messages.mapNotNull { it.sequence }.maxOrNull() ?: 0L) to state.messages.count { it.sequence == null }
    LaunchedEffect(endKey, state.problem, state.ended, interim, placedNotices, card?.id, choices.size) {
        if (lastIndex >= 0 && atEnd) {
            if (reduceMotion) listState.scrollToItem(lastIndex) else listState.animateScrollToItem(lastIndex)
        }
    }

    // A guide opens full screen, from here: the answer runs on this scope, which outlives it.
    var activeGuide by remember { mutableStateOf<OpenGuide?>(null) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGuideOpener provides { activeGuide = it }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
                // The guide's: 12 in from the edges, and room under the last message before the composer.
                contentPadding = PaddingValues(start = metrics.transcriptInset, end = metrics.transcriptInset, top = metrics.inset, bottom = metrics.transcriptEnd),
            ) {
                // The guide's rhythm (TranscriptSpacing): the item above's bottom padding, the spacing,
                // then this item's top padding — a run's messages close together, a new sender, a
                // notice, the error and the end further apart. Set per item: the first has none above it.
                var count = 0
                var afterRow = false
                var below = Edge.NONE
                fun gap(top: Edge = Edge.TEXT, bottom: Edge = Edge.TEXT, continuesRun: Boolean = false): Dp {
                    val g = if (count++ == 0) 0.dp else metrics.tinyGap * TranscriptSpacing.units(below, top, continuesRun && afterRow)
                    afterRow = false
                    below = bottom
                    return g
                }
                if (state.pagingFailed) {
                    val top = gap()
                    item(key = "paging-failed") {
                        Column(Modifier.fillMaxWidth().padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(stringResource(R.string.touchpoint_transcript_load_failed), style = theme.text(theme.errorPrimary, metrics.captionText, metrics.captionLineHeight))
                            TextControl(stringResource(R.string.touchpoint_action_retry)) { scope.launch { actions.loadEarlier() } }
                        }
                    }
                } else if (state.canLoadEarlier) {
                    val top = gap()
                    item(key = "load-earlier") {
                        TextControl(stringResource(R.string.touchpoint_transcript_load_earlier), Modifier.fillMaxWidth().padding(top = top)) { scope.launch { actions.loadEarlier() } }
                    }
                }
                noticesAfter[null]?.forEachIndexed { i, n ->
                    val top = gap()
                    item(key = "notice-start-$i") { Box(Modifier.padding(top = top)) { NoticeRow(n) } }
                }
                if (card != null && cardAfter == null) {
                    val top = gap(Edge.MODALITY, Edge.MODALITY)
                    item(key = "authentication-${card.id}") { Box(Modifier.padding(top = top)) { AuthenticationCardView(card, actions.authentication.takeIf { !state.ended }) } }
                }
                for (row in rows) {
                    val (rowTop, rowBottom) = rowEdges(row, options, timed = row.message.clientMessageId in stamped)
                    val top = gap(rowTop, rowBottom, continuesRun = !row.showsSenderName)
                    afterRow = true
                    item(key = row.id) {
                        Box(Modifier.padding(top = top).then(if (row.id == answer) Modifier.focusRequester(answerFocus).focusable() else Modifier)) {
                            // Only the newest message's modalities take input, as on web; a filled slot draws
                            // no row, so its modality stays live for another pick, as web's does.
                            val newest = row.id == rows.lastOrNull()?.id && state.canSend
                            val modalityActions =
                                TouchpointModalityActions(
                                    // As a choice: run to the end even if the row scrolls away, and a refusal is said.
                                    fillSlot = actions.slot?.takeIf { newest }?.let { f -> { id: String, value: String -> pick(scope, onPickRefused) { f(id, value) } } },
                                    choose = actions.choose?.takeIf { newest }?.let { c -> { text: String, id: String -> pick(scope, onPickRefused) { c(text, id) } } },
                                )
                            MessageRow(
                                row, canRetry = state.canSend, onRetry = { message -> scope.launch { if (!actions.retry(message)) onRetryRefused() } }, onLink = actions.openLink,
                                names = state.participantNames, onDownload = actions.download.takeIf { !state.ended && state.contactState != ContactState.Idle }, onCopy = onCopy,
                                modalityActions = modalityActions, time = row.message.takeIf { it.clientMessageId in stamped }?.let { times.format(it.createdAt) },
                            )
                        }
                    }
                    noticesAfter[row.id]?.forEachIndexed { j, n ->
                        val noticeTop = gap()
                        item(key = "notice-${row.id}-$j") { Box(Modifier.padding(top = noticeTop)) { NoticeRow(n) } }
                    }
                    if (card != null && cardAfter == row.id) {
                        // The sign-in card the message asked for: in that message's run, as a modality.
                        val cardTop = gap(Edge.MODALITY, Edge.MODALITY, continuesRun = true)
                        item(key = "authentication-${card.id}") { Box(Modifier.padding(top = cardTop)) { AuthenticationCardView(card, actions.authentication.takeIf { !state.ended }) } }
                    }
                }
                // The guide's choices: under the message that offers them, in its run, as a modality.
                if (choices.isNotEmpty()) {
                    val top = gap(Edge.MODALITY, Edge.MODALITY, continuesRun = true)
                    // The transcript scrolls them: no cap of their own.
                    item(key = "choices-${state.choiceMessageId}") { Box(Modifier.padding(top = top)) { Choices(choices, onChoose, capped = false) } }
                }
                if (interim != null) {
                    // Whoever is answering, under their own participant info where theirs is shown.
                    val who = if (state.humanAgentActive) AvatarRole.AGENT else AvatarRole.BOT
                    val top = gap(if (options.showsInfo(who) && options.showParticipantNames) Edge.NONE else Edge.TEXT)
                    val agentName = rows.lastOrNull { it.message.participantRole.isHuman && it.message.direction == Direction.INBOUND }?.message?.participantId?.let(state.participantNames::get)
                    item(key = "interim") { Box(Modifier.padding(top = top)) { Participant(who, participantName(who, agentName), outbound = false, bubble = false) { Shimmer(interim) } } }
                }
                if (state.problem) {
                    val top = gap()
                    item(key = "problem") { Box(Modifier.padding(top = top)) { ErrorRow(onDismiss = actions.dismissProblem) } }
                }
                if (state.ended) {
                    val top = gap()
                    item(key = "ended") { Box(Modifier.padding(top = top)) { Notice(stringResource(R.string.touchpoint_notice_ended)) } }
                }
            }
        }
        // The answer runs on the transcript's scope, which outlives the modal, to the end, as a pick does;
        // the card is complete only once the session has taken it.
        val viewHost = LocalViewHost.current
        activeGuide?.let { guide ->
            GuideModal(
                guide,
                onSubmit = { action, data ->
                    val submit = viewHost?.submit ?: return@GuideModal
                    scope.launch(start = CoroutineStart.ATOMIC) {
                        if (withContext(NonCancellable) { submit(action, data, guide.view.name) }) guide.onComplete() else onPickRefused()
                    }
                },
                onClose = { activeGuide = null },
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: TranscriptNotice) {
    when (notice.kind) {
        TranscriptNotice.Kind.AGENT_JOINED -> Notice(stringResource(R.string.touchpoint_notice_agent_joined))
        TranscriptNotice.Kind.AGENT_LEFT -> Notice(stringResource(R.string.touchpoint_notice_agent_left))
        // Web's Failure response: its error message, left in the transcript where the drop happened.
        TranscriptNotice.Kind.CONNECTION_LOST -> ErrorRow(onDismiss = null)
    }
}

/** Web's notice: centred, `primary60`, body size — "Agent has joined", "Agent has left", "Conversation has ended". */
@Composable
private fun Notice(label: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    BasicText(label, Modifier.fillMaxWidth(), style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center))
}

@Composable
private fun MessageRow(
    row: TranscriptRow,
    canRetry: Boolean,
    onRetry: (Message) -> Unit,
    onLink: (String) -> Unit,
    names: Map<String, String>,
    onDownload: ((Attachment) -> Unit)?,
    onCopy: (String) -> Unit = {},
    modalityActions: TouchpointModalityActions = TouchpointModalityActions(null, null),
    time: String? = null,
) {
    val options = LocalChatOptions.current
    val metrics = TouchpointMetrics.current()
    val m = row.message
    val role = avatarRole(m)
    val shownName = participantName(role, m.participantId?.let(names::get))
    val sender = spokenSender(m, names)
    // The assistant's and agents' messages are markdown; the customer's own are text, as typed.
    val cache = LocalMarkdownCache.current
    val file = m.attachment
    val blocks = if (m.direction == Direction.INBOUND && file == null) cache.blocks(m.body) else null
    val label = stringResource(R.string.touchpoint_transcript_bubble_label, sender, blocks?.let { Markdown.plainText(it) } ?: m.body)
    val openLabel = stringResource(R.string.touchpoint_action_open_link)
    val copyLabel = stringResource(R.string.touchpoint_action_copy)
    // What a copy takes: the words as read, not their markup. A file is not text to copy.
    val words = if (file == null) blocks?.let { Markdown.plainText(it) } ?: m.body else null
    val currentCopy by rememberUpdatedState(onCopy)
    val actions =
        remember(blocks, onLink, openLabel, words, copyLabel) {
            blocks?.let { linkActions(Markdown.links(it), { w -> openLabel.format(w) }, onLink) }.orEmpty() +
                listOfNotNull(words?.let { w -> CustomAccessibilityAction(copyLabel) { currentCopy(w); true } })
        }
    val view = LocalView.current
    val delivery = if (row.showsDeliveryState) deliveryText(m.deliveryState) else null
    val retryable = row.isRetryable && canRetry
    // One element per message for TalkBack: sender and text, with the delivery state as its state.
    // Retry is its own control beside it, so it is reachable without a custom action. The label
    // replaces the text's own semantics, which TalkBack would read again after it; the words stay the
    // node's text, set here since clearing drops a BasicText's own.
    val semantics =
        Modifier
            .clearAndSetSemantics {
                contentDescription = label
                if (words != null) text = AnnotatedString(words)
                // The delivery state and the time, in the order the screen shows them.
                listOfNotNull(time, delivery).joinToString(", ").takeIf { it.isNotEmpty() }?.let { stateDescription = it }
                if (actions.isNotEmpty()) customActions = actions
            }.then(
                if (words == null) {
                    Modifier
                } else {
                    // A long press copies, as text elsewhere on the phone does; a tap still opens a link.
                    Modifier.pointerInput(words) {
                        detectTapGestures(onLongPress = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            currentCopy(words)
                        })
                    }
                },
            )
    val guide = m.interactive?.viewSummary
    val modalities = m.interactive?.modalities.orEmpty()
    val bubble = options.bubbles(role)
    Participant(role, shownName, outbound = m.direction == Direction.OUTBOUND, bubble = bubble && file == null && guide == null && (blocks == null || m.body.isNotBlank()), first = row.showsSenderName) {
        when {
            file != null -> AttachmentRow(file, outbound = m.direction == Direction.OUTBOUND, label, onDownload, time)
            // A Connect View: web's guide card in place of a message with nothing to say.
            guide != null -> Stamped(time) { GuideCard(guide, m.interactive?.viewReference, m.clientMessageId.toString()) }
            blocks == null -> OutboundRow(m, delivery, retryable, onRetry, semantics, time)
            modalities.isEmpty() -> Stamped(time) { InboundRow(blocks, semantics, onLink, bubble) }
            else ->
                Stamped(time) {
                    // The guide's 16 between a message's words and what it offers under them.
                    Column(verticalArrangement = Arrangement.spacedBy(metrics.modalityGap)) {
                        if (m.body.isNotBlank()) InboundRow(blocks, semantics, onLink, bubble)
                        ModalityStack(modalities, modalityActions)
                    }
                }
        }
    }
}

/**
 * A message row's padding at its top and bottom ([TranscriptSpacing]): none at a participant header
 * over it or a footer (time, receipt) under it; else a modality's where one leads or ends it, and
 * text's otherwise. A file counts as text.
 */
private fun rowEdges(
    row: TranscriptRow,
    options: TouchpointChatOptions,
    timed: Boolean,
): Pair<Edge, Edge> {
    val m = row.message
    val header = row.showsSenderName && options.showsInfo(avatarRole(m)) && options.showParticipantNames
    val footer = timed || (m.direction == Direction.OUTBOUND && row.showsDeliveryState)
    val guide = m.interactive?.viewSummary != null
    val modalities = m.direction == Direction.INBOUND && m.attachment == null && m.interactive?.modalities.orEmpty().isNotEmpty()
    val top = if (header) Edge.NONE else if (guide || (modalities && m.body.isBlank())) Edge.MODALITY else Edge.TEXT
    val bottom = if (footer) Edge.NONE else if (guide || modalities) Edge.MODALITY else Edge.TEXT
    return top to bottom
}

/** Whose avatar: web's `you`, `agent`, `bot`. */
private enum class AvatarRole { YOU, AGENT, BOT }

private fun avatarRole(m: Message): AvatarRole =
    when {
        m.direction == Direction.OUTBOUND -> AvatarRole.YOU
        m.participantRole.isHuman -> AvatarRole.AGENT
        else -> AvatarRole.BOT
    }

/** Who TalkBack says sent [m]: the name the screen shows, where it shows one, else web's sender. */
@Composable
private fun spokenSender(
    m: Message,
    names: Map<String, String>,
): String {
    val role = avatarRole(m)
    return if (LocalChatOptions.current.showsInfo(role)) participantName(role, m.participantId?.let(names::get)) else senderName(TranscriptLayout.sender(m))
}

/** Whether [role]'s avatar (and, with names, name) is shown: everyone's with participant info, a human agent's by default. */
private fun TouchpointChatOptions.showsInfo(role: AvatarRole): Boolean = showParticipantInfo || (role == AvatarRole.AGENT && agentParticipantInfo)

/** Whether [role]'s messages are in a bubble. */
private fun TouchpointChatOptions.bubbles(role: AvatarRole): Boolean =
    when (role) {
        AvatarRole.YOU -> userMessageBubble
        AvatarRole.AGENT -> agentMessageBubble
        AvatarRole.BOT -> assistantMessageBubble
    }

@Composable
private fun participantName(
    role: AvatarRole,
    agentName: String?,
): String =
    when (role) {
        AvatarRole.YOU -> stringResource(R.string.touchpoint_transcript_name_you)
        AvatarRole.AGENT -> agentName ?: stringResource(R.string.touchpoint_transcript_name_agent)
        AvatarRole.BOT -> LocalChatOptions.current.assistantName ?: stringResource(R.string.touchpoint_transcript_name_ai)
    }

/**
 * A message under its sender's participant info, as the guide's two forms: the avatar and name over
 * it, 8 apart and 8 above it; or, without names, the avatar beside it, 8 from it and level with its
 * first line ([bubble]: inside a bubble's padding). The customer's at the end, everyone else's at the
 * start. Without participant info for [role], the message alone. Shown on the [first] message of a
 * run from one sender only, as web's `isFirstInSequence`: a continuation keeps the avatar's room
 * beside it, and drops the name and avatar over it.
 */
@Composable
private fun Participant(
    role: AvatarRole,
    name: String,
    outbound: Boolean,
    bubble: Boolean,
    first: Boolean = true,
    content: @Composable () -> Unit,
) {
    val options = LocalChatOptions.current
    val metrics = TouchpointMetrics.current()
    val theme = LocalTouchpointTheme.current
    if (!options.showsInfo(role)) {
        content()
        return
    }
    if (options.showParticipantNames) {
        if (!first) {
            Box(Modifier.fillMaxWidth(), contentAlignment = if (outbound) Alignment.TopEnd else Alignment.TopStart) { content() }
            return
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(metrics.smallGap), horizontalAlignment = if (outbound) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
                Avatar(role)
                // Already in the message's own label for TalkBack.
                BasicText(name, Modifier.clearAndSetSemantics {}, style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight))
            }
            content()
        }
        return
    }
    val avatar: @Composable () -> Unit = {
        Box(Modifier.padding(top = if (bubble) metrics.bubblePaddingVertical else 0.dp)) {
            if (first) Avatar(role) else Spacer(Modifier.size(TouchpointMetrics.avatarDisc))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
        if (!outbound) avatar()
        Box(Modifier.weight(1f)) { content() }
        if (outbound) avatar()
    }
}

/**
 * The host's line under the header ([TouchpointChatOptions.headerNotice]), a host's
 * notice: centred, in `primary`, over a `primary20` hairline, on the panel's own
 * surface rather than the header's fill. Nothing for `null` or empty.
 */
@Composable
private fun HeaderNotice(notice: String?) {
    if (notice.isNullOrEmpty()) return
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    Column(Modifier.fillMaxWidth()) {
        BasicText(
            notice,
            Modifier.fillMaxWidth().padding(horizontal = metrics.transcriptInset, vertical = metrics.blockGap),
            style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center),
        )
        Box(Modifier.fillMaxWidth().height(metrics.hairline).background(theme.primary20).clearAndSetSemantics {})
    }
}

/**
 * The assistant's or an agent's message with its time, when it shows one: on its own line after the
 * words and modalities, 8 in from the message's start and 8 under it, as the guide's. Read as part of
 * the message, so hidden here. The content stays in one place whether or not the time shows: the time
 * moves to a newer message from the same sender in the same minute, and a modality's state (a picked
 * date, a picked card) must outlive that.
 */
@Composable
private fun Stamped(
    time: String?,
    content: @Composable () -> Unit,
) {
    val metrics = TouchpointMetrics.current()
    Column(verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
        content()
        if (time != null) Box(Modifier.padding(start = metrics.stampIndent)) { TimeText(time) }
    }
}

/** The guide's time stamp: Small 12/16 in `primary60`. Hidden: the message's state already says it. */
@Composable
private fun TimeText(time: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    BasicText(time, Modifier.clearAndSetSemantics {}, style = theme.text(theme.primary60, metrics.captionText, metrics.captionLineHeight))
}

/**
 * The guide's participant avatar: 20, round or square ([TouchpointChatOptions.avatarShape]) — the
 * assistant's own image where the host gives one, else a 12 glyph in `primary60` on a `primary5`
 * disc: the customer's person, an agent's headset, the assistant's sparkle. Its name is the message's.
 */
@Composable
private fun Avatar(role: AvatarRole) {
    val theme = LocalTouchpointTheme.current
    val options = LocalChatOptions.current
    val shape = if (options.avatarShape == TouchpointAvatarShape.SQUARE) RectangleShape else CircleShape
    val icon = options.assistantIcon.takeIf { role == AvatarRole.BOT }
    Box(
        Modifier
            .size(if (icon != null) TouchpointMetrics.avatarImage else TouchpointMetrics.avatarDisc)
            .clip(shape)
            .background(if (icon == null) theme.primary5 else Color.Transparent)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val glyph =
                when (role) {
                    AvatarRole.YOU -> TouchpointIcons.User
                    AvatarRole.AGENT -> TouchpointIcons.Agent
                    AvatarRole.BOT -> TouchpointIcons.Sparkle
                }
            Glyph(glyph, theme.primary60, TouchpointMetrics.avatarGlyph)
        }
    }
}

/**
 * Web's AttachmentPill: `primary5`, 12×10, radius 20, a paperclip and the name, as wide as it reads.
 * The customer's own is not pressable, as on web, and sits to the end; an agent's saves the file.
 * TalkBack hears who sent it and its name, and Download.
 */
@Composable
private fun AttachmentRow(
    file: Attachment,
    outbound: Boolean,
    label: String,
    onDownload: ((Attachment) -> Unit)?,
    time: String? = null,
) {
    val metrics = TouchpointMetrics.current()
    // Web stamps an upload's message like any other: under the pill, on its side, 8 in.
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(metrics.smallGap),
        horizontalAlignment = if (outbound) Alignment.End else Alignment.Start,
    ) {
        AttachmentPill(file, outbound, label, onDownload)
        if (time != null) Box(Modifier.padding(horizontal = metrics.stampIndent)) { TimeText(time) }
    }
}

@Composable
private fun AttachmentPill(
    file: Attachment,
    outbound: Boolean,
    label: String,
    onDownload: ((Attachment) -> Unit)?,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.innerRadius)
    val download = if (outbound) null else onDownload
    val downloadLabel = stringResource(R.string.touchpoint_attach_download)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start) {
        // A pressable pill keeps the 48 floor as a target around web's 40, as a button does; the
        // customer's own, not pressable, is only the pill.
        Box(
            Modifier
                .then(
                    if (download != null) {
                        Modifier
                            .heightIn(min = metrics.minimumTouchTarget)
                            .clickable(interactionSource = interaction, indication = null, onClickLabel = downloadLabel, role = Role.Button) { download(file) }
                    } else {
                        Modifier
                    },
                ).clearAndSetSemantics {
                    contentDescription = label
                    text = AnnotatedString(file.name)
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .focusRing(focused, shape)
                    .clip(shape)
                    .background(if (pressed) theme.primary10 else theme.primary5)
                    .padding(horizontal = metrics.bubblePaddingHorizontal, vertical = metrics.bubblePaddingVertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.smallGap),
            ) {
                Glyph(TouchpointIcons.Attach, theme.primary, metrics.iconSize)
                BasicText(file.name, Modifier.weight(1f, fill = false), style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The assistant's or an agent's message. Plain — the guide's assistant: markdown on the background,
 * full width, 4 further in than cards and bubbles so its words line up with theirs. In a [bubble] —
 * the guide's agent: `secondary40`, 12×10, as wide as it reads and held 16 short of the far side,
 * with [TouchpointChatOptions.agentMessageBubbleStyle] over it. 12 between its paragraphs. TalkBack
 * reads it as text, and reaches each link.
 */
@Composable
private fun InboundRow(
    blocks: List<Markdown.Block>,
    semantics: Modifier,
    onLink: (String) -> Unit,
    bubble: Boolean,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val options = LocalChatOptions.current
    if (!bubble) {
        MarkdownContent(blocks, theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight), metrics.blockGap, onLink, semantics.fillMaxWidth().padding(horizontal = metrics.textIndent))
        return
    }
    val style = options.agentMessageBubbleStyle
    Box(Modifier.fillMaxWidth().padding(end = metrics.bubbleInset)) {
        MarkdownContent(
            blocks,
            theme.text(style?.textColor ?: theme.primary, metrics.bodyText, metrics.bodyLineHeight),
            metrics.blockGap,
            onLink,
            semantics.bubble(style, theme.secondary40, theme),
        )
    }
}

/** A bubble as web draws one, with a host's style over it: fill, radius, border, padding. */
@Composable
private fun Modifier.bubble(
    style: TouchpointBubbleStyle?,
    fill: Color,
    theme: TouchpointTheme,
): Modifier {
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(style?.cornerRadius ?: theme.innerRadius)
    val border = style?.border
    return clip(shape)
        .background(style?.fill ?: fill)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .padding(horizontal = style?.paddingHorizontal ?: metrics.bubblePaddingHorizontal, vertical = style?.paddingVertical ?: metrics.bubblePaddingVertical)
}

/**
 * The customer's message: at the end, held 16 from the start, in a `primary10` bubble with `primary`
 * text, as the guide's; its time and delivery row 8 under it and 8 in from its end.
 */
@Composable
private fun OutboundRow(
    m: Message,
    delivery: String?,
    retryable: Boolean,
    onRetry: (Message) -> Unit,
    semantics: Modifier,
    time: String? = null,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val options = LocalChatOptions.current
    val style = options.userMessageBubbleStyle.takeIf { options.userMessageBubble }
    Column(
        Modifier.fillMaxWidth().padding(start = metrics.bubbleInset),
        verticalArrangement = Arrangement.spacedBy(metrics.smallGap),
        horizontalAlignment = Alignment.End,
    ) {
        BasicText(
            m.body,
            if (options.userMessageBubble) semantics.bubble(style, theme.primary10, theme) else semantics.padding(horizontal = metrics.textIndent),
            style = theme.text(style?.textColor ?: theme.primary, metrics.bodyText, metrics.bodyLineHeight),
        )
        if (delivery != null || time != null) {
            DeliveryRow(m.deliveryState, delivery, if (retryable) ({ onRetry(m) }) else null, time = time)
        }
    }
}

/**
 * The guide's status row under the customer's message: the time, a bullet, then the receipt — a 12
 * tick in the state's colour and its label — Small 12/16 in `primary60`, 8 in from the bubble's end.
 */
@Composable
private fun DeliveryRow(
    state: DeliveryState,
    label: String?,
    onRetry: (() -> Unit)?,
    time: String? = null,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val (icon, tint) =
        when (state) {
            DeliveryState.SENDING -> TouchpointIcons.Time to theme.primary40
            DeliveryState.SENT -> TouchpointIcons.Check to theme.primary40
            DeliveryState.DELIVERED -> TouchpointIcons.CheckDouble to theme.primary40
            DeliveryState.READ -> TouchpointIcons.CheckDouble to theme.successPrimary
            DeliveryState.FAILED -> TouchpointIcons.Error to theme.errorPrimary
        }
    val caption = theme.text(theme.primary60, metrics.captionText, metrics.captionLineHeight)
    Row(Modifier.padding(horizontal = metrics.stampIndent), verticalAlignment = Alignment.CenterVertically) {
        if (time != null) TimeText(time)
        // Already the message's state for TalkBack: shown here, not read twice.
        if (time != null && label != null) BasicText(STATUS_BULLET, Modifier.padding(horizontal = metrics.smallGap).clearAndSetSemantics {}, style = caption)
        if (label != null) {
            Glyph(icon, tint, metrics.receiptIconSize)
            BasicText(label, Modifier.padding(start = metrics.tinyGap).clearAndSetSemantics {}, style = caption)
            // Kept on a phone (2026-10-14), where connections drop far more often; web offers none.
            if (onRetry != null) TextControl(stringResource(R.string.touchpoint_action_retry), onClick = onRetry)
        }
    }
}

/** The guide's separator between a message's time and its receipt. */
private const val STATUS_BULLET = "•"

/**
 * Web's interim line (`.shimmer`), made stronger: an 80-wide band peaking at
 * `primary` crossing `primary60` text every 1.5s. Still when animations are off. **Deviation (WCAG
 * AA):** the text at rest is `primary60` (5.6:1 light), not web's `primary40` (2.8:1).
 */
@Composable
private fun Shimmer(label: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight)
    val band = with(LocalDensity.current) { metrics.shimmerBand.toPx() }
    var width by remember { mutableIntStateOf(0) }
    val still = reduceMotion()
    Box(Modifier.graphicsLayer {}.onSizeChanged { width = it.width }) {
        BasicText(label, style = style)
        if (!still) {
            val progress by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)))
            // Web moves the band's left edge from -80 to the text's full width.
            val left = -band + (width + band) * progress
            BasicText(
                label,
                Modifier.clearAndSetSemantics {},
                style = style.copy(brush = Brush.horizontalGradient(listOf(Color.Transparent, theme.primary, Color.Transparent), startX = left, endX = left + band, tileMode = TileMode.Decal)),
            )
        }
    }
}


/**
 * A failure, inline: web's error Banner (1.0.5-6). Tapping dismisses, where there is something to
 * dismiss: a drop's notice stays.
 */
@Composable
private fun ErrorRow(onDismiss: (() -> Unit)?) {
    val dismiss = stringResource(R.string.touchpoint_problem_dismiss)
    val tap = if (onDismiss != null) Modifier.clickable(onClickLabel = dismiss, role = Role.Button, onClick = onDismiss) else Modifier
    TouchpointBanner(TouchpointBannerType.ERROR, stringResource(R.string.touchpoint_problem_banner), Modifier.clip(RoundedCornerShape(LocalTouchpointTheme.current.outerRadius)).then(tap))
}

// Input

@Composable
private fun statusString(status: StatusLine): String =
    stringResource(
        when (status) {
            StatusLine.CONNECTING -> R.string.touchpoint_status_connecting
            StatusLine.ENDED -> R.string.touchpoint_status_ended
        },
    )

/**
 * Web's choices (MessageChoices, a TextButtonGroup): one card — `primary5`, a `primary10` hairline,
 * radius 28, at most 40 % of the window — of 56-high rows with the Send glyph, rules from edge to edge
 * drawn over each row's top, as the guide's; `primary10` while pressed. Choosing one answers the message ([onChoose]); `null` draws them
 * disabled, in `primary40`, as web draws a disabled row. A choice with no title is numbered, as web's.
 */
@Composable
private fun Choices(
    choices: List<Choice>,
    onChoose: ((Choice, String) -> Unit)?,
    /** Held to 40 % of the panel, scrolling inside: under the welcome screen's composer, not in the transcript. */
    capped: Boolean = true,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.outerRadius)
    val groupLabel = stringResource(R.string.touchpoint_choices_label)
    // A share of the panel, not of the window: on a half sheet the window's would leave the transcript a strip.
    val panel = LocalPanelHeight.current ?: with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (capped) Modifier.heightIn(max = panel * CHOICES_MAX_FRACTION) else Modifier)
            .clip(shape)
            .background(theme.primary5)
            .border(metrics.hairline, theme.primary10, shape)
            .then(if (capped) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            // Web's group: role="group", "Response options".
            .semantics {
                contentDescription = groupLabel
                isTraversalGroup = true
                collectionInfo = CollectionInfo(rowCount = choices.size, columnCount = 1)
            },
    ) {
        choices.forEachIndexed { i, choice ->
            val label = choice.text ?: stringResource(R.string.touchpoint_choice_untitled, i + 1)
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val focused by interaction.collectIsFocusedAsState()
            val colour = if (onChoose != null) theme.primary else theme.primary40
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.rowMinHeight)
                    // Inside the row: the card clips anything drawn outside it.
                    .focusRing(focused, RectangleShape, inside = true)
                    .background(if (pressed) theme.primary10 else Color.Transparent)
                    // The rule between rows is drawn over the row's top edge, side to side: it adds no height.
                    .drawBehind {
                        if (i > 0) drawLine(theme.primary10, Offset(0f, 0f), Offset(size.width, 0f), metrics.hairline.toPx())
                    }
                    .clickable(interactionSource = interaction, indication = null, enabled = onChoose != null, role = Role.Button) { onChoose?.invoke(choice, label) }
                    .semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(rowIndex = i, rowSpan = 1, columnIndex = 0, columnSpan = 1) }
                    .padding(horizontal = metrics.rowPaddingHorizontal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(label, Modifier.weight(1f), style = theme.text(colour, metrics.bodyText, metrics.bodyLineHeight))
                Glyph(TouchpointIcons.Send, colour, metrics.iconSize)
            }
        }
    }
}

/**
 * Web's composer: `primary5` with a `primary10` hairline — the focus colour while it is focused, the
 * guide's focus outline — radius 28. The field grows upward to 6 lines, or 40 % of the panel, and
 * scrolls past that, never clipped; its caret is `primary` and its selection the Accent Effects
 * colour. Send is the main button: the accent, and the guide's disabled fill while there is nothing
 * to send. Attach is a ghost button.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    attach: (() -> Unit)? = null,
    attachEnabled: Boolean = false,
    file: PendingFile? = null,
    onRemove: () -> Unit = {},
    uploading: Boolean = false,
    /**
     * Shown in place of the placeholder while sign-in holds the composer. **Deviation:** web shows it
     * over the input on hover; a touch screen has none.
     */
    lockedHint: String? = null,
    /** Told when the field gains or loses focus. */
    onFocus: (Boolean) -> Unit = {},
    /** Lets the surface focus the field again after it moves. */
    focusRequester: FocusRequester? = null,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val placeholder = lockedHint ?: stringResource(R.string.touchpoint_composer_placeholder)
    val shape = RoundedCornerShape(theme.outerRadius)
    // As web: Send waits while a file uploads.
    val canSubmit = enabled && draft.isNotBlank() && !uploading
    val field = remember { MutableInteractionSource() }
    val focused by field.collectIsFocusedAsState()
    val reportFocus by rememberUpdatedState(onFocus)
    LaunchedEffect(focused) { reportFocus(focused) }
    val panel = LocalPanelHeight.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.primary5)
            .border(metrics.hairline, if (focused) theme.focus else theme.primary10, shape),
    ) {
        val error = file?.error
        if (error != null) {
            // Web's banner: px-2 py-1 on errorSecondary, an Error glyph and the message, 8 above the file.
            Row(
                Modifier
                    .padding(start = metrics.composerPadding, end = metrics.composerPadding, top = metrics.composerPadding)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.innerRadius))
                    .background(theme.errorSecondary)
                    .padding(horizontal = metrics.bannerPaddingHorizontal, vertical = metrics.bannerPaddingVertical)
                    // Spoken once by the announcer, as web's alert is, and there to find again after.
                    .clearAndSetSemantics { contentDescription = error },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.tinyGap),
            ) {
                Glyph(TouchpointIcons.Error, theme.primary, metrics.iconSize)
                BasicText(error, Modifier.weight(1f), style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (file != null) {
            if (error != null) Spacer(Modifier.height(metrics.tinyGap)) // web's 8, less the 4 the target adds
            Row(
                Modifier.fillMaxWidth().padding(start = metrics.composerPadding * 2, end = metrics.aroundTarget, top = if (error == null) metrics.aroundTarget else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) {
                    when {
                        uploading -> UploadDot()
                        error != null -> Glyph(TouchpointIcons.Error, theme.errorPrimary, metrics.iconSize)
                        else -> Glyph(TouchpointIcons.Check, theme.primary60, metrics.iconSize)
                    }
                }
                BasicText(
                    file.name.orEmpty(),
                    Modifier.weight(1f).padding(start = metrics.fileNameGap),
                    style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(TouchpointIcons.Delete, stringResource(R.string.touchpoint_attach_delete), if (enabled) onRemove else null)
            }
            // Web's rule under it: a hairline in the background colour, edge to edge.
            Spacer(Modifier.height(metrics.tinyGap))
            Box(Modifier.fillMaxWidth().height(metrics.hairline).background(theme.background))
        }
        Row(Modifier.fillMaxWidth().padding(metrics.aroundTarget), verticalAlignment = Alignment.Bottom) {
            if (attach != null) {
                // The guide's ghost button, disabled while a file is pending.
                IconButton(TouchpointIcons.Attach, stringResource(R.string.touchpoint_attach_upload), if (attachEnabled) attach else null)
            }
            CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = theme.accentEffect, backgroundColor = theme.accentEffect.copy(alpha = SELECTION_ALPHA))) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                // The whole height of the composer is the field's target, not just its line of text.
                modifier =
                    Modifier.weight(1f).then(if (panel != null) Modifier.heightIn(max = panel * COMPOSER_MAX_FRACTION) else Modifier)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .semantics { contentDescription = placeholder },
                enabled = enabled,
                interactionSource = field,
                textStyle = theme.text(if (enabled) theme.primary else theme.primary40, metrics.bodyText, metrics.bodyLineHeight),
                cursorBrush = SolidColor(theme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit(draft) }),
                maxLines = COMPOSER_MAX_LINES,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .heightIn(min = metrics.minimumTouchTarget)
                            .padding(
                                start = if (attach != null) metrics.fieldPaddingAfterAttach else metrics.fieldPaddingStart,
                                end = metrics.fieldPaddingEnd,
                                top = metrics.fieldPaddingVertical,
                                bottom = metrics.fieldPaddingVertical,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // `primary60`, not web's `primary40`: a placeholder that names the field meets AA.
                        if (draft.isEmpty()) BasicText(placeholder, Modifier.clearAndSetSemantics {}, style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight))
                        inner()
                    }
                },
            )
            }
            IconButton(TouchpointIcons.Send, stringResource(R.string.touchpoint_composer_send), if (canSubmit) ({ onSubmit(draft) }) else null, kind = IconKind.MAIN)
        }
    }
}

/** A file in the composer: its name, and why it did not go, if it did not. */
internal class PendingFile(
    val name: String?,
    val error: String?,
)

/** Web's upload pulse: a 6 dot in `primary60`, growing to 1.5× and back each second. */
@Composable
private fun UploadDot() {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val still = reduceMotion()
    val scale =
        if (still) {
            1f
        } else {
            val s by rememberInfiniteTransition().animateFloat(
                1f,
                1f,
                infiniteRepeatable(keyframes { durationMillis = UPLOAD_PULSE_MS; UPLOAD_PULSE_PEAK at UPLOAD_PULSE_MS / 2 using FastOutSlowInEasing }),
            )
            s
        }
    Box(Modifier.size(metrics.uploadDot).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(theme.primary60))
}

private const val UPLOAD_PULSE_MS = 1000
private const val REFOCUS_FRAMES = 10
private const val UPLOAD_PULSE_PEAK = 1.5f

// Controls

/** The transcript into the share sheet: web downloads chat-transcript.txt, a phone shares the text. */
private fun shareTranscript(
    context: Context,
    text: String,
    title: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val chooser = Intent.createChooser(send, title).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No app can take text: nothing to hand it to, and nothing to crash.
    }
}

// Announcements

/**
 * One live region for the surface, never recreated: everything spoken goes through it, so TalkBack
 * speaks it without losing its place. An arriving message is read as its own text, as web's
 * announcer reads it. One drain loop, paced so each announcement is spoken rather than all but the
 * last dropped, and the region is emptied between them — setting it to the text it already holds
 * would say nothing, which is how two identical messages would go unheard.
 */
@Composable
private fun Announcer(
    state: TranscriptState,
    actions: ChatActions,
    spoken: SnapshotStateList<String>,
) {
    val metrics = TouchpointMetrics.current()
    var announced by remember { mutableStateOf("") }
    val received = stringResource(R.string.touchpoint_announcement_received)
    val resources = LocalContext.current.resources
    // The latest of both: a host can hand the view another session, and with it another model to drain.
    val current by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val markdown = LocalMarkdownCache.current
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { current.announcements.isNotEmpty() || spoken.isNotEmpty() }.first { it }
            val arrival = current.announcements.firstOrNull()
            // The state can change between the wait and the read: what was there may be gone.
            // A message is read as its text, not its markup.
            // As web's announcer: the words, or "Response received"; then how many options it offers.
            val text =
                arrival?.let {
                    val words = markdown.plainText(it.body).ifBlank { received }
                    val count = TranscriptLayout.choices(it).size
                    if (count > 0) words + " " + resources.getQuantityString(R.plurals.touchpoint_announcement_choices, count, count) else words
                } ?: spoken.firstOrNull() ?: continue
            announced = text
            delay(ANNOUNCEMENT_PACING)
            // Only what was spoken: the queue may have changed under it — an interim line replaced.
            if (arrival != null) currentActions.announced() else if (spoken.firstOrNull() == text) spoken.removeAt(0)
            announced = ""
            delay(ANNOUNCEMENT_GAP)
        }
    }
    // Described only while it speaks: an empty description made an invisible stop for TalkBack's swipe.
    Box(
        Modifier.size(metrics.hairline).semantics {
            liveRegion = LiveRegionMode.Polite
            if (announced.isNotEmpty()) contentDescription = announced
        },
    )
}

// Helpers

@Composable
private fun senderName(sender: Sender): String =
    stringResource(
        when (sender) {
            Sender.YOU -> R.string.touchpoint_sender_you
            Sender.SUPPORT -> R.string.touchpoint_sender_support
            Sender.SUPERVISOR -> R.string.touchpoint_sender_supervisor
        },
    )

@Composable
private fun deliveryText(state: DeliveryState): String =
    stringResource(
        when (state) {
            DeliveryState.SENDING -> R.string.touchpoint_delivery_sending
            DeliveryState.SENT -> R.string.touchpoint_delivery_sent
            DeliveryState.DELIVERED -> R.string.touchpoint_delivery_delivered
            DeliveryState.READ -> R.string.touchpoint_delivery_read
            DeliveryState.FAILED -> R.string.touchpoint_delivery_failed
        },
    )

/** The field grows to six lines, then scrolls. */
private const val COMPOSER_MAX_LINES = 6
private const val COMPOSER_MAX_FRACTION = 0.4f
private const val SELECTION_ALPHA = 0.4f
private const val CACHE_SIZE = 256
private const val CACHE_LOAD = 0.75f
private const val CHOICES_MAX_FRACTION = 0.4f

/** Below this share of the window's height, a panel is the guide's half-screen layout. */
private const val HALF_LAYOUT_FRACTION = 0.6f

/** The panel's height, for what is held to a share of it. */
private val LocalPanelHeight = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

// index.css's shimmer.
private const val SHIMMER_MS = 1500

private val ANNOUNCEMENT_PACING = 600.milliseconds
private val ANNOUNCEMENT_GAP = 50.milliseconds
