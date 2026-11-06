@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlinx.coroutines.Dispatchers
import com.amazon.connect.touchpoint.core.AttachmentUpload
import com.amazon.connect.touchpoint.core.Attachment
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.isSpecified
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.graphics.nativePaint
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.TextUnit
import com.amazon.connect.touchpoint.ui.R
import com.amazon.connect.touchpoint.core.ContactState
import com.amazon.connect.touchpoint.core.DeliveryState
import com.amazon.connect.touchpoint.core.Direction
import com.amazon.connect.touchpoint.core.Message
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

// Touchpoint web's common controls, shared by the chat and voice surfaces. Public only for the SDK's
// own modules: each is marked [InternalTouchpointUi].

/**
 * Web's panel fill (BackgroundDecoration.tsx, index.css): the translucent background, then — behind
 * all content, hidden from TalkBack — a radial wash from the right edge (skipped when the host sets its
 * own background, as web skips it), a soft-light sheen at 158.26°, a shade toward the bottom, and grain.
 */
@Composable
@InternalTouchpointUi
public fun WebBackground(
    modifier: Modifier,
    depth: Boolean,
) {
    val theme = LocalTouchpointTheme.current
    val density = LocalDensity.current.density
    val grain = remember(density) { grainBrush(density) }
    val wash = theme.background == TouchpointTheme.Light.background || theme.background == TouchpointTheme.Dark.background
    Canvas(modifier.clearAndSetSemantics {}) {
        // Twice, as web stacks it: the surface's own fill, then the decoration's first layer.
        drawRect(theme.background)
        drawRect(theme.background)
        if (wash) {
            // An ellipse 62.5 % of the width by the full height, centred on the right edge, 48 % down.
            val centre = Offset(size.width, size.height * WASH_CENTRE_Y)
            val radius = size.width * WASH_RADIUS_X
            val colour = if (theme.isDark) Color(WASH_DARK) else Color(WASH_LIGHT)
            if (radius > 0f) {
                scale(scaleX = 1f, scaleY = size.height / radius, pivot = centre) {
                    drawCircle(Brush.radialGradient(listOf(colour, colour.copy(alpha = 0f)), centre, radius), radius, centre)
                }
            }
        }
        // Web's depth layer, which a host can turn off for a flatter surface.
        if (depth) {
            drawRect(sheen(if (theme.isDark) SHEEN_DARK else SHEEN_LIGHT), blendMode = BlendMode.Softlight)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (theme.isDark) SHADE_DARK else SHADE_LIGHT))))
            drawRect(grain, alpha = if (theme.isDark) GRAIN_DARK else GRAIN_LIGHT)
        }
    }
}

/** CSS `linear-gradient(158.26deg, white 1%, transparent 99%)`: 0deg points up, clockwise, across the box's projection. */
@InternalTouchpointUi
public fun DrawScope.sheen(alpha: Float): Brush {
    val angle = SHEEN_DEGREES * PI / 180.0
    val dx = sin(angle).toFloat()
    val dy = -cos(angle).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(
        SHEEN_START to Color.White.copy(alpha = alpha),
        SHEEN_END to Color.White.copy(alpha = 0f),
        start = centre - Offset(dx * half, dy * half),
        end = centre + Offset(dx * half, dy * half),
    )
}

/**
 * Web's grain: a 160 tile of noise masking black. Generated, not shipped, as web generates it; one noise
 * cell per dp, as web's turbulence has about one per CSS pixel.
 */
@InternalTouchpointUi
public fun grainBrush(density: Float): ShaderBrush {
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) { random.nextInt(0, ALPHA_LEVELS) shl ALPHA_SHIFT }
    val tile = Bitmap.createBitmap(pixels, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    shader.setLocalMatrix(Matrix().apply { setScale(density, density) })
    return ShaderBrush(shader)
}

@Composable
@InternalTouchpointUi
public fun Header(
    brand: Painter?,
    menuOpen: Boolean,
    onRestart: (() -> Unit)?,
    onToggleMenu: () -> Unit,
    onClose: (() -> Unit)?,
    speakersOn: Boolean? = null,
    onSpeakers: () -> Unit = {},
    closeIsError: Boolean = false,
    onExpand: (() -> Unit)? = null,
    style: TouchpointHeaderStyle? = LocalChatOptions.current.headerStyle,
    brandSquare: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    onMinimise: (() -> Unit)? = null,
    /** After a failure, the guide's Restart is its main button: the way out. */
    restartMain: Boolean = false,
) {
    val metrics = TouchpointMetrics.current()
    val theme = LocalTouchpointTheme.current
    // Web's headerStyle: the scheme first, so the controls' inks resolve in it, then the fill. The
    // scheme's inks are the default theme's: a host's single theme has no other side to flip to.
    val scheme = style?.dark?.takeIf { it != theme.isDark }?.let { dark -> headerTheme(theme, dark) } ?: theme
    CompositionLocalProvider(LocalTouchpointTheme provides scheme) {
        HeaderRow(brand, brandSquare, menuOpen, onRestart, onToggleMenu, onClose, onDismiss, onMinimise, speakersOn, onSpeakers, closeIsError, onExpand, restartMain, Modifier.then(style?.background?.let { Modifier.background(it) } ?: Modifier))
    }
}

/** [theme] with the inks of the light or dark default, its fills and shapes kept: the header's controls in web's flipped `colorScheme`. */
private fun headerTheme(
    theme: TouchpointTheme,
    dark: Boolean,
): TouchpointTheme {
    val inks = if (dark) TouchpointTheme.Dark else TouchpointTheme.Light
    return theme.copy(
        primary = inks.primary, primary60 = inks.primary60, primary40 = inks.primary40, primary20 = inks.primary20, primary10 = inks.primary10,
        primary5 = inks.primary5, primary1 = inks.primary1, isDark = dark,
    )
}

@Composable
private fun HeaderRow(
    brand: Painter?,
    brandSquare: Boolean,
    menuOpen: Boolean,
    onRestart: (() -> Unit)?,
    onToggleMenu: () -> Unit,
    onClose: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    onMinimise: (() -> Unit)?,
    speakersOn: Boolean?,
    onSpeakers: () -> Unit,
    closeIsError: Boolean,
    onExpand: (() -> Unit)?,
    restartMain: Boolean,
    modifier: Modifier,
) {
    val metrics = TouchpointMetrics.current()
    Row(modifier.fillMaxWidth().padding(metrics.headerPadding), verticalAlignment = Alignment.CenterVertically) {
        // The guide's header: the brand and the title, then Restart, Settings and Minimise on the right.
        // Web's hiddenElements may leave Restart and Settings out; with the settings open, the guide's
        // header has no Restart (the menu's own row is there).
        val options = LocalChatOptions.current
        val theme = LocalTouchpointTheme.current
        val restart: @Composable () -> Unit = {
            if (options.shows(TouchpointHiddenElement.HEADER_RESTART) && !menuOpen) {
                IconButton(TouchpointIcons.Restart, stringResource(R.string.touchpoint_header_restart), onRestart, kind = if (restartMain && onRestart != null) IconKind.MAIN else IconKind.GHOST)
            }
        }
        val branded = options.shows(TouchpointHiddenElement.HEADER_BRAND)
        if (branded) {
            // The host's brand, 32 square; without one, the guide's assistant sparkle in its place.
            // The sparkle centred in the brand's 32; a host's image sizes itself (a wordmark runs wider).
            Box(Modifier.padding(metrics.aroundTarget).padding(start = TouchpointMetrics.brandInset).then(if (brand == null) Modifier.size(TouchpointMetrics.brandIcon) else Modifier).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                if (brand != null) BrandImage(brand, TouchpointMetrics.brandIcon, brandSquare) else Glyph(TouchpointIcons.Sparkle, theme.primary, TouchpointMetrics.brandGlyph)
            }
        } else {
            restart()
        }
        // The guide's title after the brand, "AI Assistant" unless the host names it; an empty one
        // leaves it out. Web's body size; it takes all the room the controls leave and wraps only
        // when that is not enough (a spacer weighted beside it would halve that room).
        val title = options.headerTitle ?: stringResource(R.string.touchpoint_header_title)
        if (title.isNotEmpty()) {
            BasicText(
                title,
                Modifier.weight(1f).padding(horizontal = metrics.smallGap).semantics { heading() },
                style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (branded) restart()
        // Open, Settings is the way back: web's Undo, "Close settings".
        if (options.shows(TouchpointHiddenElement.HEADER_SETTINGS)) {
            IconButton(
                if (menuOpen) TouchpointIcons.Undo else TouchpointIcons.Settings,
                stringResource(if (menuOpen) R.string.touchpoint_header_settings_close else R.string.touchpoint_header_settings),
                onToggleMenu,
            )
        }
        // In a call, web adds Speakers — ghost either way, the glyph says which — and its close hangs up, in red.
        if (speakersOn != null) {
            IconButton(
                if (speakersOn) TouchpointIcons.Volume else TouchpointIcons.VolumeOff,
                stringResource(R.string.touchpoint_voice_speakers),
                onSpeakers,
                state = stringResource(if (speakersOn) R.string.touchpoint_voice_on else R.string.touchpoint_voice_off),
            )
        }
        // The floating card's way back to the sheet: a phone has no room to leave a card as the only size.
        if (onExpand != null) IconButton(TouchpointIcons.ArrowUp, stringResource(R.string.touchpoint_mini_expand), onExpand)
        // Minimise, beside a call's red close: back to the host's rail, the call going on.
        if (onMinimise != null) {
            IconButton(TouchpointIcons.Minimize, stringResource(R.string.touchpoint_voice_minimise), onMinimise, clickLabel = stringResource(R.string.touchpoint_voice_minimise_action))
        }
        // Web's Collapse, Minimize's glyph; in a call, its red Close, which hangs up — TalkBack is told so.
        val endsCall = stringResource(R.string.touchpoint_call_end_action)
        if (onClose != null) {
            IconButton(
                if (closeIsError) TouchpointIcons.Close else TouchpointIcons.Minimize,
                stringResource(if (closeIsError) R.string.touchpoint_header_close else R.string.touchpoint_header_collapse),
                onClose,
                kind = if (closeIsError) IconKind.ERROR else IconKind.GHOST,
                clickLabel = endsCall.takeIf { closeIsError },
            )
        }
        // Web's added `header.close`, after Collapse: while the conversation is live, the surface's
        // [onDismiss] asks to end it first (web 1.0.6); otherwise it closes the chat.
        if (onClose != null && !closeIsError && TouchpointAddedElement.HEADER_CLOSE in options.addedElements) {
            IconButton(TouchpointIcons.Close, stringResource(R.string.touchpoint_header_close), onDismiss ?: onClose)
        }
    }
}

/**
 * The guide's settings: Restart conversation and Download transcript (web's End conversation is not
 * among them). A `null` [onRestart] disables its row; a `null` [onDownload] leaves it out. Then the
 * host's [rows]: a call, as the guide places it, is a prominent one — never a button fixed
 * under the chat. A row's tap is the caller's to wire (it closes the settings first).
 */
@Composable
@InternalTouchpointUi
public fun SettingsMenu(
    onRestart: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    rows: List<TouchpointSettingsRow> = emptyList(),
) {
    val metrics = TouchpointMetrics.current()
    val title = stringResource(R.string.touchpoint_header_settings)
    val held = if (metrics.wide) Modifier.widthIn(max = TouchpointMetrics.contentMaxWidth) else Modifier
    Column(
        Modifier.fillMaxSize().padding(metrics.inset).semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(metrics.smallGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RowButton(stringResource(R.string.touchpoint_menu_restart), TouchpointIcons.Restart, onRestart, held)
        if (onDownload != null) RowButton(stringResource(R.string.touchpoint_menu_download), TouchpointIcons.Download, onDownload, held)
        for (row in rows) RowButton(row.label, null, row.action, held, main = row.prominent)
    }
}

/**
 * Web's `EndConversationConfirm` (1.0.6), over the panel as web's is, not a window of its
 * own: a `secondary60` veil that takes every touch, and on it the card — a warning and "Are you
 * sure?" with a close that cancels, the body, then Cancel and a red End conversation, stacked. Centred,
 * scrolling where the panel is too short for it. [copy] is the host's words, each `null` the SDK's.
 * The caller covers the whole panel with it, cancels it on Back, and hides what is under it from
 * TalkBack while it shows.
 */
@Composable
@InternalTouchpointUi
public fun EndConfirmation(
    onCancel: () -> Unit,
    onEnd: () -> Unit,
    copy: TouchpointChatCopy.EndConversationConfirm = TouchpointChatCopy.EndConversationConfirm(),
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val title = copy.title ?: stringResource(R.string.touchpoint_confirm_end_title)
    val cancel = copy.cancel ?: stringResource(R.string.touchpoint_confirm_cancel)
    val shape = RoundedCornerShape(theme.outerRadius)
    // The keyboard goes: it would otherwise stay up under the card.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.clearFocus()
        keyboard?.hide()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.secondary60)
            // Web's veil has no handler: a touch beside the card does nothing, and nothing under it. In
            // the main pass, after the card's own controls have had theirs.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(metrics.dialogPadding)
                .widthIn(max = metrics.dialogMaxWidth)
                .fillMaxWidth()
                .clip(shape)
                // Web blurs what is behind its card; a second coat of the translucent fill hides it the same.
                .background(theme.background)
                .background(theme.background)
                .border(metrics.hairline, theme.primary10, shape)
                .padding(metrics.dialogPadding)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(TouchpointIcons.Warning, theme.primary, metrics.dialogIconSize)
                Spacer(Modifier.size(metrics.smallGap))
                BasicText(title, Modifier.weight(1f).semantics { heading() }, style = theme.text(theme.primary, metrics.titleText, metrics.titleLineHeight))
                // "Close", not a second "Cancel": TalkBack heard two identical controls side by side.
                IconButton(TouchpointIcons.Close, stringResource(R.string.touchpoint_guide_close), onCancel)
            }
            BasicText(copy.body ?: stringResource(R.string.touchpoint_confirm_end_body), style = theme.text(theme.primary, metrics.smallText, metrics.smallLineHeight))
            Column(verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
                RowButton(cancel, TouchpointIcons.Close, onCancel)
                RowButton(copy.confirm ?: stringResource(R.string.touchpoint_confirm_end), TouchpointIcons.LogOut, onEnd, error = true)
            }
        }
    }
}

/** Before the greeting: the guide's loading dot, centred, with its interim line. Spoken through the announcer. */
@Composable
@InternalTouchpointUi
public fun Loading(label: String) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    Column(
        Modifier.fillMaxSize().padding(metrics.inset).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Loader()
        BasicText(label, style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.Center))
    }
}

/**
 * The guide's loading animation: one dot in the accent, glowing in its Accent Effects colour, the
 * glow breathing in and out every 1.5 s. Still, at its brightest, when animations are off.
 */
@Composable
@InternalTouchpointUi
public fun Loader() {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val glow = remember { Paint() }
    val breath: State<Float>? =
        if (reduceMotion()) {
            null
        } else {
            rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(LOADER_BREATH_MS, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse))
        }
    // Its own layer: a frame of the loader redraws the loader, not the background under it.
    // Room for the whole glow: drawn to half its reach past the dot, and blurred a reach further.
    Canvas(Modifier.size(metrics.loaderSize + metrics.loaderGlow * LOADER_GLOW_ROOM).graphicsLayer {}.clearAndSetSemantics {}) {
        val t = breath?.value ?: 1f
        val dot = metrics.loaderSize.toPx() / 2f
        val reach = metrics.loaderGlow.toPx() * (LOADER_GLOW_LOW + (1f - LOADER_GLOW_LOW) * t)
        if (reach > 0f) {
            glow.color = theme.accentEffect.copy(alpha = theme.accentEffect.alpha * (LOADER_GLOW_ALPHA_LOW + (LOADER_GLOW_ALPHA - LOADER_GLOW_ALPHA_LOW) * t))
            glow.nativePaint.maskFilter = BlurMaskFilter(reach, BlurMaskFilter.Blur.NORMAL)
            drawIntoCanvas { it.drawCircle(center, dot + reach / 2f, glow) }
        }
        drawCircle(theme.accent, dot, center)
    }
}

/**
 * Web's BrandIcon; decorative. [square], as its docked and full panels draw it: [size] square,
 * fitted. Otherwise, as its floating header does: [size] tall and as wide as the image, at least
 * [size], so a wordmark (a logo and the brand's name) shows whole.
 */
@Composable
@InternalTouchpointUi
public fun BrandImage(
    brand: Painter,
    size: Dp,
    square: Boolean = true,
) {
    val intrinsic = brand.intrinsicSize
    val wide = !square && intrinsic.isSpecified && intrinsic.height > 0f && intrinsic.width > intrinsic.height
    val box = if (wide) Modifier.height(size).width(size * (intrinsic.width / intrinsic.height)) else Modifier.size(size)
    Image(brand, contentDescription = null, modifier = box.clearAndSetSemantics {}, contentScale = ContentScale.Fit)
}

/** Web's IconButton types this surface uses. */
@InternalTouchpointUi
public enum class IconKind { GHOST, SUBTLE, MAIN, PRIMARY, ERROR }

/**
 * Web's IconButton: a 40 circle with a 16 glyph, centred in a 48 target — ghost (`primary60` glyph),
 * subtle (`primary10` behind, `primary` glyph) or main (the accent behind, `secondary` glyph) — with
 * web's pressed overlay. `null` [onClick] disables it, in web's disabled colours. [clickLabel] is what
 * TalkBack says a tap does, when the label alone does not.
 */
@Composable
@InternalTouchpointUi
public fun IconButton(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
    kind: IconKind = IconKind.GHOST,
    state: String? = null,
    clickLabel: String? = null,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val enabled = onClick != null
    val (fill, glyph, overlay) =
        when (kind) {
            IconKind.GHOST -> Triple(Color.Transparent, if (enabled) theme.primary60 else theme.primary20, theme.secondary10)
            IconKind.SUBTLE -> Triple(if (enabled) theme.primary10 else theme.primary5, if (enabled) theme.primary else theme.primary20, theme.primary10)
            // Disabled, the guide's: a subtle fill and the disabled ink, the same in every theme.
            IconKind.MAIN -> Triple(if (enabled) theme.accent else theme.primary10, if (enabled) theme.secondary else theme.primary20, theme.secondary10)
            // Web's attach label: bg-primary, text-secondary, and the same on hover.
            IconKind.PRIMARY -> Triple(theme.primary, theme.secondary, Color.Transparent)
            // Web's error button: errorPrimary behind a secondary glyph; disabled, secondary20 and primary20.
            IconKind.ERROR -> Triple(if (enabled) theme.errorPrimary else theme.secondary20, if (enabled) theme.secondary else theme.primary20, theme.primary10)
        }
    Box(
        Modifier
            .sizeIn(minWidth = metrics.minimumTouchTarget, minHeight = metrics.minimumTouchTarget)
            .focusRing(focused, CircleShape, metrics.buttonSize)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = clickLabel, role = Role.Button) { onClick?.invoke() }
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(metrics.buttonSize).clip(CircleShape).background(fill).background(if (pressed) overlay else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Glyph(icon, glyph, metrics.iconSize) }
    }
}

/**
 * Web's TextButton: 56 high, radius 28, the label left and a 16 glyph right, with its pressed overlay.
 * Web's "ghost" type (`primary10` behind, `primary` text); with [error], its error type
 * (`errorPrimary` behind, `secondary` text); with [main], its main type (`primary` behind, `secondary`
 * text). `null` [onClick] disables it: `primary5` behind and the type's disabled text.
 */
@Composable
@InternalTouchpointUi
public fun RowButton(
    label: String,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    main: Boolean = false,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = onClick != null
    val fill =
        when {
            !enabled -> theme.primary5
            error -> theme.errorPrimary
            main -> theme.primary
            else -> theme.primary10
        }
    // **Deviation:** web's disabled main ink is `secondary40` on `primary5`, near-invisible;
    // web never disables a main button, but here one is disabled while its action runs (sign-in,
    // starting a conversation), so its label keeps the subtle row's readable disabled ink.
    val content =
        when {
            !enabled && !error -> theme.primary20
            error || main -> if (enabled) theme.secondary else theme.secondary40
            else -> theme.primary
        }
    val overlay = if (error) theme.primary5 else theme.secondary10
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(theme.outerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = metrics.rowMinHeight)
            .focusRing(focused, shape)
            .clip(shape)
            .background(fill)
            .background(if (pressed) overlay else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { onClick?.invoke() }
            .padding(horizontal = metrics.rowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = theme.text(content, metrics.bodyText, metrics.bodyLineHeight))
        if (icon != null) Glyph(icon, content, metrics.iconSize)
    }
}

/**
 * Web's focus outline — 2 wide, 1 outside the control, in `focus` — for keyboard and switch access
 * (WCAG 2.4.7), which the pressed overlay does not show. Drawn around [inner] when given (a 40 button
 * centred in its 48 target), else around the whole element; placed before any clip, so it is not cut.
 */
@Composable
@InternalTouchpointUi
public fun Modifier.focusRing(
    focused: Boolean,
    shape: Shape,
    inner: Dp? = null,
    inside: Boolean = false,
): Modifier {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    return drawBehind {
        if (!focused) return@drawBehind
        val ring = metrics.focusRing.toPx()
        // Outside by web's offset, or — where a clip would cut it — just inside the edge.
        val out = (metrics.focusOffset.toPx() + ring / 2f) * if (inside) -1f else 1f
        val box = inner?.toPx()?.let { Size(it, it) } ?: size
        val topLeft = Offset((size.width - box.width) / 2f - out, (size.height - box.height) / 2f - out)
        val outline = shape.createOutline(Size(box.width + out * 2, box.height + out * 2), layoutDirection, this)
        translate(topLeft.x, topLeft.y) { drawOutline(outline, theme.focus, style = Stroke(ring)) }
    }
}

@Composable
@InternalTouchpointUi
public fun Glyph(
    icon: ImageVector,
    color: Color,
    size: Dp,
) {
    Image(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** A small text control with a 48 target: Try again, Load earlier messages. */
@Composable
@InternalTouchpointUi
public fun TextControl(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    Box(
        modifier.heightIn(min = metrics.minimumTouchTarget).widthIn(min = metrics.minimumTouchTarget).clickable(role = Role.Button, onClick = onClick).padding(horizontal = metrics.smallGap),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = theme.text(theme.primary, metrics.captionText, metrics.captionLineHeight))
    }
}

/** Text laid out as CSS lays it out — the glyphs centred in the line box, nothing trimmed — in the theme's font. */
@InternalTouchpointUi
public fun TouchpointTheme.text(
    color: Color,
    size: TextUnit,
    lineHeight: TextUnit,
): TextStyle = TextStyle(color = color, fontSize = size, lineHeight = lineHeight, fontFamily = fontFamily, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

/** The system's "remove animations" setting: motion stops, scrolling jumps. */
@Composable
@InternalTouchpointUi
public fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

// index.css's depth layers.
internal const val WASH_CENTRE_Y = 0.48f
internal const val WASH_RADIUS_X = 0.625f
internal const val WASH_LIGHT = 0x33506EB0
internal const val WASH_DARK = 0x33AEBAFF
internal const val SHEEN_DEGREES = 158.26
internal const val SHEEN_START = 0.01f
internal const val SHEEN_END = 0.99f
internal const val SHEEN_LIGHT = 1f
internal const val SHEEN_DARK = 0.5f
internal const val SHADE_LIGHT = 0.05f
internal const val SHADE_DARK = 0.5f
internal const val GRAIN_LIGHT = 0.03f
internal const val GRAIN_DARK = 0.1f
internal const val GRAIN_TILE = 160
internal const val GRAIN_SEED = 7
internal const val ALPHA_LEVELS = 256
internal const val ALPHA_SHIFT = 24

// The guide's loading dot.
internal const val LOADER_BREATH_MS = 750
internal const val LOADER_GLOW_LOW = 0.5f
internal const val LOADER_GLOW_ALPHA = 0.8f
internal const val LOADER_GLOW_ALPHA_LOW = 0.4f

/** The canvas's margin on either side, in glow reaches: 1.5 each side. */
internal const val LOADER_GLOW_ROOM = 3

/** Web's `BannerType`: what a [TouchpointBanner] says, which sets its fill and its glyph. */
public enum class TouchpointBannerType { ERROR, WARNING, SUCCESS }

/**
 * Web's `Banner` (1.0.5-6): a pill announcing an error, a warning or a success — its glyph in a 40
 * box, then the words, in `primary` on the type's secondary colour. An error is announced at once
 * (web's `role="alert"`), the others politely (`role="status"`). For a custom modality's own
 * messages, as web exports it.
 */
@Composable
public fun TouchpointBanner(
    type: TouchpointBannerType,
    text: String,
    modifier: Modifier = Modifier,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val (fill, glyph) =
        when (type) {
            TouchpointBannerType.ERROR -> theme.errorSecondary to TouchpointIcons.Error
            TouchpointBannerType.WARNING -> theme.warningSecondary to TouchpointIcons.Warning
            TouchpointBannerType.SUCCESS -> theme.successSecondary to TouchpointIcons.Check
        }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.outerRadius))
            .background(fill)
            .padding(start = metrics.noticeBannerPaddingStart, end = metrics.noticeBannerPaddingEnd, top = metrics.noticeBannerPaddingVertical, bottom = metrics.noticeBannerPaddingVertical)
            .semantics(mergeDescendants = true) { liveRegion = if (type == TouchpointBannerType.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.tinyGap),
    ) {
        Box(Modifier.size(metrics.buttonSize), contentAlignment = Alignment.Center) { Glyph(glyph, theme.primary, metrics.iconSize) }
        BasicText(text, Modifier.weight(1f), style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight))
    }
}
