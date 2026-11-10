@file:OptIn(com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi::class, com.amazon.connect.touchpoint.core.InternalTouchpointApi::class)

package com.amazon.connect.touchpoint.chat.ui

import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import com.amazon.connect.touchpoint.core.Message
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.ui.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import android.view.WindowManager
import androidx.compose.ui.graphics.RectangleShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import com.amazon.connect.touchpoint.chat.AttachmentHttp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.DocumentsContract
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import android.view.Gravity
import com.amazon.connect.touchpoint.core.ContactEvent
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.EndReason
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The chat UI for [session], embedded wherever the host places it: the host owns
 * the container, the SDK draws everything inside it.
 *
 * Subscribes to the session's events for as long as it is composed, so a surface that goes away
 * takes no one else's events with it, and handing it a different session starts afresh. It fills
 * the space it is given; keyboard insets are the host's to apply (`Modifier.imePadding()`), since
 * only the host knows its window's inset handling.
 *
 * @param theme Colour tokens. Defaults to the light or dark pair the system is using.
 * @param onClose The header's close control. `null` hides it: the host decides what closing means —
 *   collapsing a dock, dismissing a sheet — because only it owns the container.
 * @param onStartConversation How the host starts a chat — normally `session.startChat(request)` with
 *   its own request. Powers Restart conversation (the header and the menu) and, once a conversation
 *   has ended, Start new conversation, as Touchpoint web offers them; Restart ends the current contact
 *   first. `null` disables Restart and hides Start new conversation: only the host knows what a new
 *   conversation starts with.
 * @param options Web's customization options; web's defaults unless set.
 * @param onContactEnded Web's `onContactEnded`: once when a contact this view is showing ends, with
 *   why. Not for one that had already ended when the view subscribed, nor for the end Restart makes
 *   on its way to a new conversation (web's reset does not report one). As on web, there is no
 *   default action — the ended transcript stays. What it throws is dropped, as the session drops a
 *   host callback's.
 *
 * @param connectInstanceUrl The host's Amazon Connect instance, e.g. `https://example.my.connect.aws`:
 *   a flow's View (`ShowView`) opens in Connect's own renderer, loaded from there, as web's
 *   `instanceUrl` does. `null`: as on web, the guide card resolves, and opening it says it cannot load.
 *   Only an `https` Amazon Connect instance is used — a host ending in `.my.connect.aws` or
 *   `.awsapps.com` — and the renderer's page is held to it by its
 *   Content-Security-Policy; anything else is treated as `null`.
 * @param footer The host's own content below the composer, on the chat's surface. Below everything
 *   the panel shows, settings and welcome included, and within its column. **Addition:** web's
 *   chat and voice never share an instance, so web has no such slot. Not for a call's controls: the
 *   guide's voice mini takes the chat's place on screen, rather than sitting inside it.
 * @param settingsRows The host's own rows in the settings, after the SDK's: a call is a
 *   prominent one, as the guide places it (a voice contact, its own dialler). A tap closes the
 *   settings, then runs the row's action.
 */
@Composable
public fun TouchpointChatView(
    session: TouchpointSession,
    modifier: Modifier = Modifier,
    theme: TouchpointTheme = TouchpointTheme.system(),
    onClose: (() -> Unit)? = null,
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    onStartConversation: (suspend () -> Unit)? = null,
    modalities: Map<String, TouchpointModalityContent> = emptyMap(),
    connectInstanceUrl: String? = null,
    footer: (@Composable () -> Unit)? = null,
    settingsRows: List<TouchpointSettingsRow> = emptyList(),
) {
    val watch = remember(session) { EndWatch() }
    WatchEnds(session, watch, onContactEnded)
    ChatView(session, watch, modifier, theme, onClose, options, onStartConversation, modalities = modalities, connectInstanceUrl = connectInstanceUrl, footer = footer, settingsRows = settingsRows)
}

/** The view itself; who hears of an end is its caller's, so a sheet can keep listening while hidden. */
@Composable
private fun ChatView(
    session: TouchpointSession,
    watch: EndWatch,
    modifier: Modifier,
    theme: TouchpointTheme,
    onClose: (() -> Unit)?,
    options: TouchpointChatOptions,
    onStartConversation: (suspend () -> Unit)?,
    mini: Boolean = false,
    onExpand: () -> Unit = {},
    onMiniClose: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    modalities: Map<String, TouchpointModalityContent> = emptyMap(),
    connectInstanceUrl: String? = null,
    footer: (@Composable () -> Unit)? = null,
    settingsRows: List<TouchpointSettingsRow> = emptyList(),
) {
    val model = remember(session) { ChatTranscriptModel() }
    // The latest rows' actions, read at the tap: the actions are remembered per session.
    val rows by rememberUpdatedState(settingsRows)
    val loader = remember(session) { FirstPage(session, model) }
    val state by model.state.collectAsState()
    val close by rememberUpdatedState(onClose)
    val start by rememberUpdatedState(onStartConversation)
    val context by rememberUpdatedState(LocalContext.current)
    // Read through the latest composition: the actions are remembered per session, the names follow the locale.
    val names by rememberUpdatedState(
        TranscriptNames(stringResource(R.string.touchpoint_transcript_name_you), stringResource(R.string.touchpoint_transcript_name_ai), stringResource(R.string.touchpoint_transcript_name_agent)),
    )

    LaunchedEffect(session) {
        // Subscribe before loading the first page — undispatched, so the collector is registered
        // before the load begins — and nothing emitted during the load is lost between the two; the
        // page and the catch-up overlap, and the model deduplicates. Bounded as the session's own
        // per-collector buffer is, so a stalled surface cannot hold a conversation in memory.
        val events = Channel<ContactEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
        launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { events.send(it) } }
        loader.load()
        var typingLapse: kotlinx.coroutines.Job? = null
        for (event in events) {
            // The surface can be shown before the session has a contact — a notification tap resumes
            // into a session already on screen. Load again for the contact it adopted, so stored
            // history shows before the network answers.
            if (event is ContactEvent.StateChanged && session.isActive && loader.loadedFor != session.contactId) loader.load()
            model.ingest(event)
            // Web's typing lapses five seconds after the last report of it.
            if (event is ContactEvent.Typing) {
                val tick = model.state.value.typingTick
                typingLapse?.cancel()
                typingLapse = launch { delay(TYPING_LAPSE); model.typingLapsed(tick) }
            }
        }
    }

    // An agent's file, saved where the customer chooses: the one asked about, kept across a
    // configuration change while the system's "Save to…" is up.
    var saving by rememberSaveable(session) { mutableStateOf<List<String>?>(null) }
    val viewScope = rememberCoroutineScope()
    val saver =
        rememberLauncherForActivityResult(AttachmentFiles.SaveAs) { uri ->
            val asked = saving
            saving = null
            if (uri != null && asked != null) viewScope.launch { saveAttachment(session, context.contentResolver, asked[0], uri) }
        }
    val actions =
        remember(session) {
            ChatActions(
                send = { body -> accepted { session.send(body) } },
                retry = { message -> accepted { session.resend(message) } },
                loadEarlier = {
                    // Retrying a failed first page means loading the first page again — through the
                    // same path, so the contact it was loaded for is recorded.
                    if (!model.state.value.initialLoaded) {
                        loader.load()
                    } else {
                        model.loadEarlier {
                            val page = session.earlierTranscript(model.state.value.messages.firstOrNull())
                            page.messages to page.hasMore
                        }
                    }
                },
                close = { close?.invoke() },
                dismissProblem = model::dismissProblem,
                announced = model::clearAnnouncement,
                restart = {
                    accepted {
                        // As web: end the contact, then start afresh; the new contact's empty first page
                        // clears the transcript as it loads.
                        watch.quietly { session.end() }
                        start?.invoke()
                    }
                },
                end = { accepted { session.end() } },
                transcript = { transcriptText(session, names) },
                openLink = { url -> openLink(context, url) },
                // The session normalises the words once, as web sends them, and keeps the id only if it differs.
                choose = { text, choiceId -> accepted { session.sendChoice(text, choiceId) } },
                slot = { id, value -> accepted { session.sendSlot(id, value) } },
                typing = { session.sendTyping() },
                attach = { upload -> accepted { session.sendAttachment(upload) } },
                download = { file ->
                    saving = listOf(file.id)
                    try {
                        saver.launch(file)
                    } catch (_: ActivityNotFoundException) {
                        saving = null // no "Save to…" on this device: as web's failed download, nothing
                    }
                },
                // A refusal is the card's to show: the transport has already moved it on.
                authentication =
                    AuthenticationActions(
                        begin = {
                            try {
                                session.beginAuthentication()
                            } catch (_: TouchpointException) {
                                null
                            }
                        },
                        cancel = { unlessRedirected -> session.cancelAuthentication(unlessRedirected) },
                        // The sign-in tab handed the redirect back itself: the host's activity is not involved.
                        complete = { redirect ->
                            try {
                                session.completeAuthentication(redirect)
                            } catch (_: TouchpointException) {
                                // the card already says it failed
                            }
                        },
                    ),
            )
        }

    // Connect's Views, through this session: DescribeView, and the answer as web's submitView.
    val viewHost =
        remember(session, connectInstanceUrl) {
            run {
                ViewHost(
                    connectInstanceUrl,
                    describe = { token ->
                        try {
                            session.describeView(token)
                        } catch (_: TouchpointException) {
                            null
                        }
                    },
                    submit = { action, data, name -> accepted { session.submitView(action, data, name) } },
                    rejected = { error -> viewScope.launch { session.reportSecurityRejection(error) } },
                )
            }
        }
    CompositionLocalProvider(LocalTouchpointTheme provides theme, LocalCustomModalities provides modalities, LocalViewHost provides viewHost) {
        // A call on the session (the host's, beside the chat) sends the chat nothing: it says so by waiting for nothing.
        val shown = if (session.modality == com.amazon.connect.touchpoint.core.Modality.VOICE && session.isActive) state.copy(inCall = true) else state
        val hosted = actions.withHostControls(close = onClose != null, start = onStartConversation != null, settingsRows = settingsRows.mapIndexed { i, row -> TouchpointSettingsRow(row.label, row.prominent) { rows.getOrNull(i)?.action?.invoke() } })
        ChatSurface(shown, hosted, modifier, options, mini = mini, onExpand = onExpand, onMiniClose = onMiniClose, footer = footer, onDismiss = onDismiss)
    }
}

/** Tells the host of each end [watch] lets through, for as long as it is composed. */
@Composable
private fun WatchEnds(
    session: TouchpointSession,
    watch: EndWatch,
    onContactEnded: ((EndReason) -> Unit)?,
) {
    val callback by rememberUpdatedState(onContactEnded)
    LaunchedEffect(session, watch) {
        // Read before subscribing: the catch-up then carries anything that happened between the two.
        watch.arm(alreadyEnded = session.state.value is ContactState.Ended)
        session.events.collect { event ->
            val reason = watch.ended(event) ?: return@collect
            try {
                callback?.invoke(reason)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive() // ours stays ours
            }
        }
    }
}

/**
 * When to tell the host a contact ended — web's once per end, re-armed when a new conversation
 * starts. The session replays a finished contact's end to a late subscriber; that end is not news.
 * Main thread only.
 */
internal class EndWatch {
    private var told = true

    /** At subscription: whether the contact is already over, so its replayed end is not reported. */
    fun arm(alreadyEnded: Boolean) {
        told = alreadyEnded
    }

    /** Ends [block] makes are not reported — Restart's, which web's reset does not report either. */
    suspend fun quietly(block: suspend () -> Unit) {
        val before = told
        told = true
        try {
            block()
        } catch (e: Throwable) {
            told = before // it did not end, so a later end is news
            throw e
        }
    }

    /** The reason to report for [event], or `null`. */
    fun ended(event: ContactEvent): EndReason? {
        when (event) {
            is ContactEvent.StateChanged -> if (event.state !is ContactState.Ended && event.state != ContactState.Idle) told = false
            is ContactEvent.ContactEnded -> if (!told) {
                told = true
                return event.reason
            }
            else -> Unit
        }
        return null
    }
}

/** The close control, Restart / Start new, and Call us exist exactly when the host supplies an action for them. */
private fun ChatActions.withHostControls(
    close: Boolean,
    start: Boolean,
    settingsRows: List<TouchpointSettingsRow>,
): ChatActions =
    // Named, every one: a positional rebuild once dropped the attachment actions without a word.
    ChatActions(
        send = send, retry = retry, loadEarlier = loadEarlier, close = this.close.takeIf { close }, dismissProblem = dismissProblem,
        announced = announced, restart = restart.takeIf { start }, end = end, transcript = transcript, openLink = openLink,
        choose = choose, slot = slot, typing = typing, attach = attach, download = download, authentication = authentication,
        settingsRows = settingsRows,
    )

/** Who said it, in the transcript text: web's "You", "AI" and "Agent". */
private class TranscriptNames(val you: String, val ai: String, val agent: String)

/**
 * The conversation as plain text for the share sheet, as web's Download transcript writes it: the
 * server's newest 100 lines, oldest first, each `[time] name: words` with Connect's own times and
 * display names. Offline, or once the connection has gone, the local store instead, in the same shape
 * with local names and times. `null` when there is nothing to share.
 */
private suspend fun transcriptText(
    session: TouchpointSession,
    names: TranscriptNames,
): String? {
    val server =
        try {
            session.serverTranscript()
        } catch (e: CancellationException) {
            throw e
        } catch (_: TouchpointException) {
            emptyList()
        }
    if (server.isNotEmpty()) return server.joinToString("\n") { "[${it.absoluteTime}] ${it.name}: ${it.text}" }
    return localTranscriptText(session, names)
}

/** The local store's copy, for when the server cannot be asked. */
private suspend fun localTranscriptText(
    session: TouchpointSession,
    names: TranscriptNames,
): String? {
    val messages = ArrayList<Message>()
    try {
        var page = session.earlierTranscript(before = null)
        messages.addAll(0, page.messages)
        while (page.hasMore && messages.size < TRANSCRIPT_LIMIT) {
            page = session.earlierTranscript(messages.firstOrNull())
            if (page.messages.isEmpty()) break
            messages.addAll(0, page.messages)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: TouchpointException) {
        if (messages.isEmpty()) return null
    }
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    // The conversation as it happened: not our sends that never went out, or have not yet.
    val sent = messages.filterNot { it.direction == Direction.OUTBOUND && (it.deliveryState == DeliveryState.FAILED || it.deliveryState == DeliveryState.SENDING) }
    if (sent.isEmpty()) return null // nothing to share: no blank share sheet
    return sent.takeLast(TRANSCRIPT_LIMIT).joinToString("\n") { m ->
        val name =
            when {
                m.direction == Direction.OUTBOUND -> names.you
                m.participantRole.isHuman -> names.agent
                else -> names.ai
            }
        "[${time.format(m.createdAt)}] $name: ${m.body}"
    }
}

private const val TRANSCRIPT_LIMIT = 100
private val TYPING_LAPSE = 5.seconds

/**
 * Streams an agent's file into the document the customer chose, from an address asked for now. As
 * web, a failure shows nothing; the empty document it would leave is removed.
 */
private suspend fun saveAttachment(
    session: TouchpointSession,
    resolver: ContentResolver,
    attachmentId: String,
    target: Uri,
) {
    try {
        val file = session.transcript().firstNotNullOfOrNull { m -> m.attachment?.takeIf { it.id == attachmentId } } ?: throw TouchpointException.InvalidConfiguration("no such file")
        val address = session.attachmentUrl(file)
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(target)?.use { AttachmentHttp.get(address, it) } ?: throw TouchpointException.TransportFailure("document unwritable")
        }
    } catch (e: Exception) {
        // Cancelled too: an unfinished document is not left behind either way.
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(resolver, target)
            } catch (_: Exception) {
                // a provider that will not delete: the empty document stays, as a browser's failed download does
            }
        }
        if (e is CancellationException) throw e
    }
}

/** Whether a session call was accepted; our own cancellation stays cancellation. */
private suspend fun accepted(block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: TouchpointException) {
        false
    }

/** Loads the newest page for whichever contact the session holds, and remembers which that was. */
private class FirstPage(
    private val session: TouchpointSession,
    private val model: ChatTranscriptModel,
) {
    var loadedFor: String? = null
        private set

    suspend fun load() {
        val contact = session.contactId
        try {
            val page = session.earlierTranscript(before = null)
            val newContact = loadedFor != null && loadedFor != contact
            loadedFor = contact
            model.setInitial(page.messages, page.hasMore, newContact, names = session.participantNames())
        } catch (e: CancellationException) {
            throw e
        } catch (_: TouchpointException) {
            model.initialLoadFailed() // a blank transcript and a failed read must not look the same
        }
    }
}

/**
 * The chat UI as a sheet over the host's content, shown while [visible], in one of
 * three sizes ([TouchpointWindowSize]): **half** — a bottom sheet over the lower half on a phone, web's
 * half-screen panel on a tablet; **full** — the whole window; **mini** — web's `floating` card, the host's
 * screen usable around it. Closing it (the header's close, the system back gesture, a tap beside it)
 * calls [onDismiss], and the host sets [visible] to `false`; ending the contact is a separate call
 * ([TouchpointSession.end]): closing the sheet leaves the conversation running.
 *
 * @param windowSize The size, as the host controls it. `null`, the default: full on a phone, web's
 *   half-screen panel on a tablet. (Before sizes, a phone's sheet was a bottom sheet of 94% of the
 *   window; full replaces it, as decided with the sizes.)
 * @param onWindowSizeChange Told when the customer or [collapseToMiniOn] changes the size; with it,
 *   the host owns the size (and passes it back as [windowSize]). Without it the sheet keeps its own.
 * @param collapseToMini The header's close drops the sheet to the floating card rather than
 *   dismissing; the card's own close dismisses, and its Expand goes back.
 * @param collapseToMiniOn Each value collapses the sheet to mini, so the customer sees what changed
 *   behind it: pass a Live Sync client's `invocations` to collapse whenever the assistant acts. Off
 *   (`null`) by default.
 *
 * On a phone, half is the guide's bottom sheet: a handle at its top expands it to the whole screen
 * (a tap, or a drag up); a drag down closes it as Collapse does.
 *
 * [onStartConversation], [options], [onContactEnded], [connectInstanceUrl], [footer] and [settingsRows]
 * are the embedded view's; an end while the sheet is closed is reported too.
 */
@Composable
public fun TouchpointChatSheet(
    session: TouchpointSession,
    visible: Boolean,
    onDismiss: () -> Unit,
    theme: TouchpointTheme = TouchpointTheme.system(),
    options: TouchpointChatOptions = TouchpointChatOptions.Default,
    onContactEnded: ((EndReason) -> Unit)? = null,
    windowSize: TouchpointWindowSize? = null,
    onWindowSizeChange: ((TouchpointWindowSize) -> Unit)? = null,
    collapseToMini: Boolean = false,
    collapseToMiniOn: Flow<*>? = null,
    onStartConversation: (suspend () -> Unit)? = null,
    modalities: Map<String, TouchpointModalityContent> = emptyMap(),
    connectInstanceUrl: String? = null,
    footer: (@Composable () -> Unit)? = null,
    settingsRows: List<TouchpointSettingsRow> = emptyList(),
) {
    // Listening whether or not the sheet is up: a contact can end while it is closed.
    val watch = remember(session) { EndWatch() }
    WatchEnds(session, watch, onContactEnded)
    // What the customer left in the chat — the draft, where they had scrolled to, the settings open —
    // is kept while the sheet is closed and back when it opens, as the chat stays mounted while
    // hidden. Per session: a new conversation does not inherit an old one's draft.
    val kept = rememberSaveableStateHolder()
    val keptKey = rememberSaveable(session) { java.util.UUID.randomUUID().toString() }
    // An earlier session's draft goes with it: the holder keeps each key's state until told.
    DisposableEffect(keptKey) { onDispose { kept.removeState(keptKey) } }
    if (!visible) return
    val metrics = TouchpointMetrics.current()
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    // The sheet's own size when the host does not hold it; and the size mini expands back to.
    var own by rememberSaveable(session) { mutableStateOf(windowSize) }
    var expandTo by rememberSaveable(session) { mutableStateOf<TouchpointWindowSize?>(null) }
    val size = if (onWindowSizeChange != null) windowSize else own
    fun resize(next: TouchpointWindowSize?) {
        if (next == TouchpointWindowSize.MINI && size != TouchpointWindowSize.MINI) expandTo = size
        // A host is told a size, never null: back to the default is the size the default looks like here.
        if (onWindowSizeChange != null) onWindowSizeChange(next ?: defaultWindowSize(windowWidth)) else own = next
    }
    val collapse = if (collapseToMini) ({ resize(TouchpointWindowSize.MINI) }) else onDismiss
    val form = sheetForm(windowWidth, size)
    val mini = form is SheetForm.Mini
    // The latest: the collector outlives a composition, and the size it collapses from is today's.
    val collapseNow by rememberUpdatedState { resize(TouchpointWindowSize.MINI) }
    if (collapseToMiniOn != null) {
        LaunchedEffect(collapseToMiniOn) { collapseToMiniOn.collect { collapseNow() } }
    }
    Dialog(
        onDismissRequest = if (mini) onDismiss else collapse,
        // Floating leaves the host's screen usable: a tap beside the card is the host's, not a dismissal.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !mini),
    ) {
        // Web's panel is translucent and blurs what is behind it by 48; the sheet blurs the host the
        // same way, where the device allows cross-window blur (and is plainly translucent where not).
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val density = LocalDensity.current
        val blur = with(density) { metrics.backdropBlur.roundToPx() }
        val configuration = LocalConfiguration.current
        // The half sheet's window reaches above the sheet, into the room its shadow falls in.
        val heightPx = with(density) { (configuration.screenHeightDp.dp * HALF_HEIGHT_FRACTION + TouchpointMetrics.sheetShadowRoom).roundToPx() }
        val shadowRoomPx = with(density) { TouchpointMetrics.sheetShadowRoom.toPx() }
        // The latest, read at the tap: the detector stays alive across recompositions.
        val collapseLatest by rememberUpdatedState(collapse)
        // The floating card, as web's from 768 wide and a compact one on a phone.
        val floating = floatingSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
        // The window as the dialog made it, restored for a form that takes the whole window.
        val original = remember(window) { window?.attributes?.let { WindowManager.LayoutParams().apply { copyFrom(it) } } }
        SideEffect { window?.let { applySheetWindow(it, form, original, blur, heightPx, floating, density) } }
        // One place for the chat in every form, so changing size keeps the conversation, the draft
        // and the scroll position.
        val fill = Modifier.fillMaxSize()
        Box(
            fill
                // Beside a full-window form there is nothing to tap; for the others the window is the
                // sheet itself, and the dialog's own outside tap closes it — and, for the half sheet, a
                // tap in the room above it that its shadow falls in, which is outside the sheet too.
                .then(
                    when (form) {
                        SheetForm.Full -> Modifier
                        SheetForm.Bottom -> Modifier.pointerInput(Unit) { detectTapGestures { if (it.y < shadowRoomPx) collapseLatest() } }
                        else -> Modifier.pointerInput(Unit) { detectTapGestures { } }
                    },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val corners =
                when (form) {
                    SheetForm.Bottom -> RoundedCornerShape(topStart = metrics.sheetCornerRadius, topEnd = metrics.sheetCornerRadius)
                    // Web's floating card: `rounded-outer`, `shadow-2xl`, a `primary-10` hairline.
                    SheetForm.Mini -> RoundedCornerShape(theme.outerRadius)
                    else -> RectangleShape
                }
            // The guide's Float effect: a 12 shadow, black at 8 %, on the floating card and the half sheet.
            val float = Modifier.dropShadow(corners, Shadow(TouchpointMetrics.floatingShadow, Color.Black.copy(alpha = TouchpointMetrics.FLOATING_SHADOW_ALPHA)))
            val card =
                when (form) {
                    SheetForm.Mini -> Modifier.padding(TouchpointMetrics.floatingMargin).then(float).border(metrics.hairline, theme.primary10, corners)
                    SheetForm.Bottom -> Modifier.padding(top = TouchpointMetrics.sheetShadowRoom).then(float)
                    else -> Modifier
                }
            // Where its corners are rounded, drawn whole, then clipped once: clipped layer by layer, the
            // panel's light fill showed as a thin rim round each corner, at the edge it shares with a dark
            // header's. A square form has nothing to clip and keeps the default compositing.
            val rounded = form is SheetForm.Bottom || form is SheetForm.Mini
            Box(fill.then(card).then(if (rounded) Modifier.graphicsLayer { shape = corners; clip = true; compositingStrategy = CompositingStrategy.Offscreen } else Modifier)) {
                kept.SaveableStateProvider(keptKey) {
                    ChatView(
                        session, watch, fill, theme, onClose = collapse, options = options, onStartConversation = onStartConversation,
                        mini = mini,
                        onExpand = { resize(expandTo.takeIf { it != TouchpointWindowSize.MINI } ?: windowSize.takeIf { it != TouchpointWindowSize.MINI }) },
                        onMiniClose = onDismiss,
                        onDismiss = onDismiss,
                        modalities = modalities,
                        connectInstanceUrl = connectInstanceUrl,
                        footer = footer,
                        settingsRows = settingsRows,
                    )
                }
                if (form is SheetForm.Bottom) SheetHandle(onExpand = { resize(TouchpointWindowSize.FULL) }, onCollapse = collapse)
            }
        }
    }
}

/**
 * The guide's bottom-sheet handle: a 32 × 4 bar in `primary20` at the top centre, over the header. A
 * tap or a drag up expands the sheet to the whole screen; a drag down collapses it. TalkBack has it
 * as Expand.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SheetHandle(
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val label = stringResource(R.string.touchpoint_mini_expand)
    val expand by rememberUpdatedState(onExpand)
    val collapse by rememberUpdatedState(onCollapse)
    val threshold = with(LocalDensity.current) { metrics.minimumTouchTarget.toPx() }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .width(metrics.minimumTouchTarget * 2)
            .height(metrics.sheetHandleStrip)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        when {
                            dragged < -threshold -> expand()
                            dragged > threshold -> collapse()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button) { expand() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(metrics.sheetHandleWidth, metrics.sheetHandleHeight).clip(RoundedCornerShape(metrics.sheetHandleHeight)).background(theme.primary20))
    }
}

/** The chat sheet's sizes: Touchpoint web's half, full and floating. */
public enum class TouchpointWindowSize {
    /** A bottom sheet over the lower half on a phone; web's half-screen panel on a tablet. */
    HALF,

    /** The whole window. */
    FULL,

    /**
     * Web's `floating`: the whole chat in a rounded card at the bottom trailing corner, the host's
     * screen usable around it. From 768 wide it is web's tall card, 420 wide and nearly the window's
     * height; on a phone a compact one (420 at most each way, 45% of the window high).
     * Its header adds Expand, back to the sheet. The conversation keeps running.
     */
    MINI,
}

/** Where the sheet's window sits and how big it is, for a size at a window width. */
internal sealed interface SheetForm {
    data object Full : SheetForm

    data object Bottom : SheetForm

    data class Panel(val width: Dp) : SheetForm

    data object Mini : SheetForm
}

/** The size the default (`null`) takes at [window]: web's half panel where it fits, full where not. */
internal fun defaultWindowSize(window: Dp): TouchpointWindowSize =
    if (sheetPanelWidth(window) != null) TouchpointWindowSize.HALF else TouchpointWindowSize.FULL

/** [size] at [window]: `null` is the default — full on a phone, web's half panel on a tablet. */
internal fun sheetForm(
    window: Dp,
    size: TouchpointWindowSize?,
): SheetForm =
    when (size) {
        TouchpointWindowSize.MINI -> SheetForm.Mini
        TouchpointWindowSize.FULL -> SheetForm.Full
        TouchpointWindowSize.HALF -> sheetPanelWidth(window)?.let { SheetForm.Panel(it) } ?: SheetForm.Bottom
        null -> sheetPanelWidth(window)?.let { SheetForm.Panel(it) } ?: SheetForm.Full
    }

/**
 * The dialog's window for [form]. The window is the sheet itself for every form but full, so only
 * what is behind it blurs, as web's panel; the host beside it stays as it is. Mini takes no focus and
 * no touches beside it: the host's screen, its keyboard and its Back stay the host's.
 */
private fun applySheetWindow(
    window: android.view.Window,
    form: SheetForm,
    original: WindowManager.LayoutParams?,
    blur: Int,
    halfHeight: Int,
    floating: androidx.compose.ui.unit.DpSize,
    density: androidx.compose.ui.unit.Density,
) {
    // Floating takes no touches beside it, so the host's screen stays usable, but takes focus: it is
    // the whole chat, and the customer types in it.
    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    if (form is SheetForm.Mini) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    when (form) {
        SheetForm.Full -> {
            if (original != null) {
                window.setLayout(original.width, original.height)
                window.setGravity(original.gravity)
                if (original.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            window.setBackgroundBlurRadius(0)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = blur }
        }
        else -> {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            when (form) {
                is SheetForm.Panel -> {
                    window.setLayout(with(density) { form.width.roundToPx() }, WindowManager.LayoutParams.MATCH_PARENT)
                    window.setGravity(Gravity.END)
                }
                SheetForm.Bottom -> {
                    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, halfHeight)
                    window.setGravity(Gravity.BOTTOM)
                }
                else -> {
                    // The card and the margin it floats in, at the bottom trailing corner.
                    val margin = TouchpointMetrics.floatingMargin * 2
                    with(density) { window.setLayout((floating.width + margin).roundToPx(), (floating.height + margin).roundToPx()) }
                    window.setGravity(Gravity.BOTTOM or Gravity.END)
                }
            }
            // The half sheet's window reaches into the room its shadow falls in, and a blur behind it
            // would show there as a band above the sheet: the sheet's two coats of its fill hide what
            // is behind it instead.
            window.setBackgroundBlurRadius(if (form is SheetForm.Bottom) 0 else blur)
        }
    }
}

/**
 * The sheet's width as web's half-screen panel at [window]: `null` below `md:` (a phone: the bottom
 * sheet), half the window from `md:`, and 632 from `xl:`.
 */
internal fun sheetPanelWidth(window: Dp): Dp? =
    when {
        window >= TouchpointMetrics.LARGE_WINDOW -> TouchpointMetrics.halfPanelMaxWidth
        window >= TouchpointMetrics.MEDIUM_WINDOW -> window / 2
        else -> null
    }

private const val HALF_HEIGHT_FRACTION = 0.5f

/**
 * Web's floating card. From web's 768 breakpoint, web's own:
 * `md:w-[420px] md:h-[calc(100%-1rem)] md:max-h-[1080px]`, 420 wide and the window's height less the
 * two 8 margins, at most 1080. **Named deviation** below it: web's floating covers a phone's whole
 * screen (the same as full), so a phone keeps a compact card, the window less 16 wide (at most 420)
 * and 45% of it high (at most 420), with the app usable around it.
 */
internal fun floatingSize(
    windowWidth: Dp,
    windowHeight: Dp,
): androidx.compose.ui.unit.DpSize {
    val width = minOf(TouchpointMetrics.floatingMaxWidth, windowWidth - TouchpointMetrics.floatingMargin * 2).coerceAtLeast(Dp(0f))
    val height =
        if (windowWidth >= TouchpointMetrics.MEDIUM_WINDOW) {
            minOf(TouchpointMetrics.floatingTallMaxHeight, windowHeight - TouchpointMetrics.floatingMargin * 2)
        } else {
            minOf(TouchpointMetrics.floatingMaxHeight, windowHeight * TouchpointMetrics.FLOATING_HEIGHT_FRACTION)
        }
    return androidx.compose.ui.unit.DpSize(width, height.coerceAtLeast(Dp(0f)))
}
private const val EVENT_BUFFER = 500
