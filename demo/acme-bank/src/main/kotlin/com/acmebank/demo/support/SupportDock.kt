package com.acmebank.demo.support

import com.amazon.connect.touchpoint.chat.ui.TouchpointLaunchButton
import com.amazon.connect.touchpoint.voice.TouchpointVoiceRail
import com.amazon.connect.touchpoint.voice.TouchpointVoiceCall
import androidx.compose.runtime.collectAsState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.acmebank.demo.AppModel
import com.acmebank.demo.Palette
import com.acmebank.demo.R
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatSheet
import com.amazon.connect.touchpoint.chat.ui.TouchpointChatView
import com.amazon.connect.touchpoint.chat.ui.TouchpointWindowSize

/**
 * The assistant docked at the bottom of every signed-in screen: a collapsed
 * pill that expands into a panel with a grabber, the SDK's chat view, and the demo's own
 * suggested-reply chips.
 *
 * The SDK supplies [TouchpointChatView] and nothing else here — the dock, the chips and the disabled
 * voice footer are host chrome. A chip is a shortcut for typing and calls `session.send`, so it
 * needs no SDK surface (interactive content is increment 2).
 */
@Composable
fun SupportDock(model: AppModel, support: SupportCoordinator, maxPanelHeight: Dp) {
    // Compact shows the newest exchange and the composer — enough to keep talking while navigating
    // the bank. Full is the reading height.
    var compact by rememberSaveable { mutableStateOf(true) }
    val fontScale = LocalDensity.current.fontScale
    // The SDK's header and composer take about 140dp, the chips and footer about 100: compact leaves
    // room for the newest exchange, full for reading back.
    val base = if (compact) 420.dp else 620.dp
    val height = minOf(if (fontScale >= 1.3f) base * 1.35f else base, maxPanelHeight)

    // Removed animations (the system setting) mean a fade, not a slide.
    // Unclipped either way: a panel cut to its animating size reads, to TalkBack and
    // the accessibility checks, as controls a few pixels tall.
    val resolver = LocalContext.current.contentResolver
    val reduceMotion = remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // Chat is the SDK's own sheet, as Touchpoint web's widget: the lower half of the screen, the bank
    // above it; Collapse drops it to the mini bar, and so does the assistant acting (Live Sync), so
    // the customer sees what changed and expands to answer. Composed while closed too, so a draft
    // left in it is there when the customer opens it again.
    // Call us, from the chat's settings, as the guide places it: the microphone is asked for at the tap,
    // as the moment the customer expects it; the chat closes, and the voice mini takes its place.
    val context = LocalContext.current
    val callUs = {
        model.supportExpanded = false
        support.callUs()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) callUs() }
    val callUsLabel = stringResource(R.string.support_call_us)
    // Offline only: the developer plays the other side, from the bank's chrome or the chat's settings.
    val console = rememberAgentConsole(support.offlineConsole.takeIf { support.mode == SupportCoordinator.Mode.OFFLINE })
    val sheetSession = support.session
    if (sheetSession != null) {
        key(support.sessionGeneration) {
            TouchpointChatSheet(
                sheetSession,
                visible = model.supportExpanded && !support.voiceMode && support.lastError == null,
                onDismiss = { model.supportExpanded = false },
                theme = Palette.chatTheme(),
                windowSize = TouchpointWindowSize.HALF,
                collapseToMini = true,
                collapseToMiniOn = support.liveSync?.actions,
                options = model.chatOptions,
                onStartConversation = { support.openChat().join() },
                modalities = AcmeModalities,
                connectInstanceUrl = if (support.mode == SupportCoordinator.Mode.LIVE) LiveMode.connectInstanceUrl() else null,
                // Call us, a prominent settings row while no call is live.
                settingsRows =
                    (if (support.voiceOffered && support.inAppVoice) {
                        listOf(
                            com.amazon.connect.touchpoint.chat.ui.TouchpointSettingsRow(callUsLabel, prominent = true) {
                                // The offline call is scripted: no microphone to ask for.
                                val granted = support.mode == SupportCoordinator.Mode.OFFLINE ||
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (granted) callUs() else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                        )
                    } else {
                        emptyList()
                    }) + console.settingsRows,
            )
        }
    }
    AnimatedContent(
        model.supportExpanded,
        transitionSpec = {
            val enter = if (reduceMotion) fadeIn() else fadeIn() + slideInVertically { it / 2 }
            (enter togetherWith fadeOut()) using SizeTransform(clip = false)
        },
        label = "dock",
    ) { expanded ->
        val session = support.session
        if (expanded && !support.voiceMode && session != null && support.lastError == null) {
            // The sheet, above, is up. The bank stays usable under it: the pill is not shown meanwhile.
            Box(Modifier.fillMaxWidth())
        } else if (expanded) {
            Panel(support, compact, onResize = { compact = !compact }, onClose = { model.supportExpanded = false }, Modifier.height(height))
        } else {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                val inCall = support.voiceCall?.phase?.collectAsState()?.value.let { it == TouchpointVoiceCall.Phase.CONNECTING || it == TouchpointVoiceCall.Phase.CONNECTED }
                // In a call the floating rail (CallRail) is the launcher: the pill steps aside.
                if (!inCall) {
                    Pill {
                        model.supportExpanded = true
                        support.currentScreen = model.screenName
                        support.openSupport()
                    }
                }
                console.Button(Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

/**
 * The guide's voice mini during a call: in the chat's place, not inside it — the chat closes as the
 * call begins. The SDK's draggable rail, floating over every screen from its handle, the layer taking
 * no touches but the rail's: vertical on a phone and a row from web's `md:`, from the bottom end, as
 * the guide's. Its controls are the guide's — the microphone, Speakers, screen sharing when the agent
 * offers it, and the red close — and nothing more. Kept composed (hidden) while the chat is open, so
 * it comes back where the customer left it.
 */
@Composable
fun CallRail(model: AppModel, support: SupportCoordinator) {
    val call = support.voiceCall ?: return
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 768
    TouchpointVoiceRail(
        call,
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).testTag("support.rail"),
        theme = Palette.chatTheme(),
        vertical = !wide,
        draggable = true,
        anchor = Alignment.BottomEnd,
        visible = !model.supportExpanded,
        options = model.chatOptions,
    )
}

/** The SDK's launch button, 8 in from the screen's corner as the guide places it; the partner's own mark when it has one. */
@Composable
private fun Pill(onOpen: () -> Unit) {
    val partnerMark = com.acmebank.demo.PartnerBrand.current.headerMark
    TouchpointLaunchButton(
        onClick = onOpen,
        modifier = Modifier.padding(8.dp).testTag("support.open"),
        label = stringResource(R.string.support_open),
        icon = if (com.acmebank.demo.PartnerLook.on && partnerMark != null) androidx.compose.ui.res.painterResource(partnerMark) else null,
        theme = Palette.chatTheme(),
        onClickLabel = stringResource(R.string.support_open_hint, com.acmebank.demo.PartnerBrand.current.bankName),
    )
}

@Composable
private fun Panel(
    support: SupportCoordinator,
    compact: Boolean,
    onResize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val pane = stringResource(R.string.support_panel, com.acmebank.demo.PartnerBrand.current.bankName)
    val theme = Palette.chatTheme()
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Surface(
        modifier.fillMaxWidth().testTag("support.panel").semantics { paneTitle = pane },
        shape = shape,
        shadowElevation = 12.dp,
    ) {
        // The panel wears the chat's own background, so the grabber, chips and footer read as one
        // surface with the SDK's view rather than as the bank's page showing through.
        Column(
            Modifier.background(theme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            Grabber(compact, onResize)
            if (support.voiceMode && support.inAppVoice) {
                InAppVoicePanel(support, theme, onClose, Modifier.weight(1f))
            } else if (support.voiceMode) {
                VoicePanel(support, onClose, Modifier.weight(1f))
            } else {
                val session = support.session
                if (session != null) {
                    // A new session object gets a new chat view: the SDK view keys its subscription on
                    // the session, and the generation makes that explicit rather than identity-based.
                    key(support.sessionGeneration) {
                        // Restart and Start new conversation start a chat the way the dock does.
                        TouchpointChatView(session, Modifier.weight(1f), theme = theme, onClose = onClose, onStartConversation = { support.openChat().join() }, modalities = AcmeModalities)
                    }
                    // A start that failed after the session was built — a backend refusal, a transport
                    // that never connected — leaves the view idle; say so here, not only in Settings.
                    support.lastError?.let { StartFailed(it, support) }
                    Chips(support)
                } else {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // A spinner beside an error would say it is still trying; it is not.
                        val error = support.lastError
                        if (error == null) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            error ?: stringResource(R.string.support_connecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (error != null) TextButton({ support.openChat() }) { Text(stringResource(R.string.support_retry)) }
                    }
                }
            }
            VoiceFooter(support)
        }
    }
}

@Composable
private fun StartFailed(error: String, support: SupportCoordinator) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton({ support.openChat() }, Modifier.testTag("support.retry")) { Text(stringResource(R.string.support_retry)) }
    }
}

/** The grabber toggles between compact and full; the chat header's close control minimises to the pill. */
@Composable
private fun Grabber(compact: Boolean, onResize: () -> Unit) {
    val label = stringResource(if (compact) R.string.support_expand else R.string.support_shrink)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onResize)
            .semantics { contentDescription = label }.testTag("support.resize"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.width(44.dp).height(5.dp)) {
                drawLine(tint.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(size.height / 2, size.height / 2), androidx.compose.ui.geometry.Offset(size.width - size.height / 2, size.height / 2), strokeWidth = size.height, cap = StrokeCap.Round)
            }
            Spacer(Modifier.height(2.dp))
            Canvas(Modifier.size(width = 12.dp, height = 7.dp)) {
                val up = compact
                val path = Path().apply {
                    moveTo(0f, if (up) size.height else 0f)
                    lineTo(size.width / 2, if (up) 0f else size.height)
                    lineTo(size.width, if (up) size.height else 0f)
                }
                drawPath(path, tint, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * Suggested replies. Demo-owned: the SDK does not yet surface interactive content, and a chip
 * is only a shortcut for typing the same words.
 */
@Composable
private fun Chips(support: SupportCoordinator) {
    val label = stringResource(R.string.support_suggested)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (reply in SuggestedReply.entries) {
            val text = stringResource(reply.text)
            Button(
                { support.send(text) },
                Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.brand, contentColor = Color.White),
            ) {
                Text(text)
            }
        }
    }
}

/**
 * Switch to voice, under the chat: the SDK's in-app call (scripted offline), or, with "Ring my phone"
 * in live mode, Connect calls the customer's phone into the Live Sync voice flow. Where neither can
 * be placed, the footer says why rather than offering a control that does nothing.
 */
@Composable
private fun VoiceFooter(support: SupportCoordinator) {
    if (support.voiceOffered && !support.voiceMode) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ support.switchMode(toVoice = true) }, Modifier.testTag("support.voice")) { Text(stringResource(R.string.support_voice_switch)) }
        }
        return
    }
    if (support.voiceOffered) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.support_voice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.support_voice_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Voice in the app: the SDK's own voice view, as Touchpoint web's — it starts the call when shown, and
 * its close hangs up and closes the panel. Live Sync binds to the call as it does to a chat.
 */
@Composable
private fun InAppVoicePanel(
    support: SupportCoordinator,
    theme: com.amazon.connect.touchpoint.chat.ui.TouchpointTheme,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    // The session this panel's view is for: a new one — a mode change — gets a new view.
    var session by remember(support.sessionGeneration) { mutableStateOf<com.amazon.connect.touchpoint.core.TouchpointSession?>(support.session) }
    LaunchedEffect(support.sessionGeneration) { if (session == null) session = support.voiceSession() }
    val s = session
    val voice = support.voiceTransport
    if (s != null && voice != null) {
        key(support.sessionGeneration) {
            // The bank asks for the microphone when the customer taps Call, as that is the moment they
            // expect it; without it, the view says so and offers chat.
            com.amazon.connect.touchpoint.voice.TouchpointVoiceView(
                s, voice, onStartCall = { support.startInAppCall() }, modifier, theme = theme, onClose = onClose,
                permissionPolicy = com.amazon.connect.touchpoint.core.PermissionPolicy(setOf(com.amazon.connect.touchpoint.core.Permission.MICROPHONE)),
                onContinueWithChat = { support.switchMode(toVoice = false) },
            )
        }
    } else {
        Box(modifier.fillMaxWidth())
    }
}

/**
 * Voice mode, after the web Touchpoint's: the chat's surface with a glowing orb and a status where the
 * transcript would be. The conversation itself is the phone call Connect places; the panel says
 * where it is, and the assistant drives the bank behind it. Live mode only.
 */
@Composable
private fun VoicePanel(support: SupportCoordinator, onClose: () -> Unit, modifier: Modifier) {
    val closeLabel = stringResource(R.string.support_voice_close)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.support_voice), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            TextButton(onClose, Modifier.heightIn(min = 48.dp).semantics { contentDescription = closeLabel }) { Text(stringResource(R.string.support_voice_minimise)) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Orb(active = support.voice == SupportCoordinator.Voice.OnCall || support.voice == SupportCoordinator.Voice.Calling)
            val (status, detail) =
                when (val v = support.voice) {
                    SupportCoordinator.Voice.Calling -> stringResource(R.string.support_voice_calling) to stringResource(R.string.support_voice_calling_hint, com.acmebank.demo.PartnerBrand.current.bankName)
                    SupportCoordinator.Voice.OnCall -> stringResource(R.string.support_voice_on_call) to stringResource(R.string.support_voice_on_call_hint)
                    is SupportCoordinator.Voice.Failed -> stringResource(R.string.support_voice_failed) to v.reason
                    SupportCoordinator.Voice.Idle -> stringResource(R.string.support_voice) to ""
                }
            Text(
                status,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            // The app cannot see the call end, so a call that was hung up still reads "on the call":
            // the customer can always ring again, and the new call rebinds Live Sync.
            when (support.voice) {
                is SupportCoordinator.Voice.Failed ->
                    Button({ support.startVoiceCall() }, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.brand, contentColor = Color.White)) {
                        Text(stringResource(R.string.support_retry))
                    }
                SupportCoordinator.Voice.OnCall ->
                    TextButton({ support.startVoiceCall() }, Modifier.heightIn(min = 48.dp).testTag("support.callAgain")) { Text(stringResource(R.string.support_voice_call_again)) }
                else -> Unit
            }
        }
        TextButton({ support.switchMode(toVoice = false) }, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("support.type")) {
            Text(stringResource(R.string.support_voice_type_instead))
        }
    }
}

/** The orb: a soft, glowing disc that breathes while the call is live, and holds still when animations are off. Decorative. */
@Composable
private fun Orb(active: Boolean) {
    val resolver = LocalContext.current.contentResolver
    val still = remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "orb-scale",
    )
    val scale = if (active && !still) pulse else 1f
    val core = Color(0xFFB9CCFF)
    Canvas(Modifier.size(120.dp).clearAndSetSemantics {}) {
        val r = size.minDimension / 2 * scale
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.55f), Color.Transparent), center, r * 1.15f), r * 1.15f)
        drawCircle(Brush.radialGradient(listOf(Color.White, core, core.copy(alpha = 0.7f)), center, r * 0.72f), r * 0.62f)
    }
}

enum class SuggestedReply(@StringRes val text: Int) {
    CARD(R.string.reply_card),
    CHARGE(R.string.reply_charge),
    PASSWORD(R.string.reply_password),
}
