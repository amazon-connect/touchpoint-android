@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.amazon.connect.touchpoint.core.TouchpointModality
import com.amazon.connect.touchpoint.core.ResolvedView
import com.amazon.connect.touchpoint.core.ViewReference
import com.amazon.connect.touchpoint.core.ViewSummary
import com.amazon.connect.touchpoint.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * A host's own modality, drawn where the flow names it — web's `customModalities`. It gets the
 * modality's data and, while the message is the newest, what web's `$saveAs` would do.
 */
public typealias TouchpointModalityContent = @Composable (modality: TouchpointModality, actions: TouchpointModalityActions) -> Unit

/** What a host's modality may do, as web's modalities may: fill a slot, or answer as a choice. `null` once the message is no longer the newest. */
public class TouchpointModalityActions internal constructor(
    public val fillSlot: ((id: String, value: String) -> Unit)?,
    public val choose: ((text: String, choiceId: String) -> Unit)?,
)

internal val LocalCustomModalities = staticCompositionLocalOf<Map<String, TouchpointModalityContent>> { emptyMap() }

/** Web's default modalities. A host's of the same name does not replace them, as web's merge has it. */
private val DEFAULTS = setOf("DefaultCard", "DefaultCarousel", "DefaultDateInput", "DefaultVideo")

/**
 * The modalities a message carries, under it, in the flow's order — as web's `Messages.tsx` draws
 * them. Only the newest message's take input ([actions] `null` for the others), as on web.
 */
@Composable
internal fun ModalityStack(
    modalities: List<TouchpointModality>,
    actions: TouchpointModalityActions,
    modifier: Modifier = Modifier,
) {
    val metrics = TouchpointMetrics.current()
    val custom = LocalCustomModalities.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(metrics.smallGap)) {
        for (m in modalities) {
            val data = m.data as? JsonObject
            when {
                m.name == "DefaultCard" && data != null -> CardView(Card.of(data))
                m.name == "DefaultCarousel" && data != null -> Carousel(data, actions)
                m.name == "DefaultDateInput" && data != null -> DateInput(data, actions)
                m.name == "DefaultVideo" && data != null -> Video(data)
                m.name !in DEFAULTS -> custom[m.name]?.invoke(m, actions)
            }
        }
    }
}

/** Web's `CardData`: an id, a thumbnail, and rows of label and value. */
private class Card(val id: String?, val thumbnail: String?, val thumbnailAlt: String?, val rows: List<Pair<String, String>>, val label: String?) {
    companion object {
        fun of(o: JsonObject): Card =
            Card(
                o.text("id"),
                o.text("thumbnail"),
                o.text("thumbnailAlt"),
                (o["rows"] as? JsonArray)?.mapNotNull { r -> (r as? JsonObject)?.let { (it.text("label") ?: "") to (it.text("value") ?: "") } }.orEmpty(),
                o.text("label") ?: o.text("value"),
            )
    }
}

/** What a card says, for TalkBack and a choice's words: its rows, read in order. */
private fun Card.spoken(): String = (listOfNotNull(thumbnailAlt) + rows.map { (l, v) -> "$l $v" }).joinToString(", ").ifBlank { label ?: id.orEmpty() }

/**
 * Web's `CustomCard`, as the guide's: `rounded-inner`, `bg-secondary-40`, a hairline in `primary-10` —
 * the accent when selected — 320 wide; its thumbnail 208 high; its rows 12 in from the sides, 16 from
 * the top and bottom and 16 apart, the label faded. [dimmed], once a card has been picked or the
 * message is no longer the newest: the guide's disabled card, its picture greyed and darkened, its
 * words in `primary40`.
 */
@Composable
private fun CardView(
    card: Card,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    /** The widest it may be: a carousel caps its cards to the transcript's width. */
    maxWidth: Dp = Dp.Infinity,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val shape = RoundedCornerShape(theme.innerRadius)
    val selectedLabel = stringResource(R.string.touchpoint_modality_selected)
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .width(TouchpointMetrics.modalityCardWidth)
            .clip(shape)
            .background(theme.secondary40)
            .border(metrics.hairline, if (selected) theme.accent else theme.primary10, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            // The card's words once: its rows' own text would be read again after them.
            .clearAndSetSemantics {
                contentDescription = card.spoken()
                text = AnnotatedString(card.rows.joinToString(" ") { (l, v) -> "$l $v" })
                // The picked card stays selected for TalkBack once the carousel is spent, as it is drawn.
                if (onClick != null || selected) this.selected = selected
                if (selected) stateDescription = selectedLabel
            },
    ) {
        card.thumbnail?.let {
            Box {
                RemoteImage(it, Modifier.fillMaxWidth().height(TouchpointMetrics.modalityThumbnailHeight), ContentScale.Crop, greyed = dimmed)
                if (dimmed) Box(Modifier.matchParentSize().background(theme.overlay))
            }
        }
        if (card.rows.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = metrics.modalityRowPaddingHorizontal, vertical = metrics.modalityRowPaddingVertical),
                verticalArrangement = Arrangement.spacedBy(metrics.modalityRowGap),
            ) {
                for ((label, value) in card.rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText(label, style = theme.text(if (dimmed) theme.primary40 else theme.primary60, metrics.bodyText, metrics.bodyLineHeight))
                        BasicText(value, Modifier.padding(start = metrics.smallGap), style = theme.text(if (dimmed) theme.primary40 else theme.primary, metrics.bodyText, metrics.bodyLineHeight).copy(textAlign = TextAlign.End))
                    }
                }
            }
        }
    }
}

/**
 * Web's `DefaultCarousel`: the cards side by side, gap 8; a tap saves the card's id where the flow
 * says, and selects it. It runs to the panel's edges, as the guide's, so a card scrolls off the screen
 * rather than being cut at the transcript's inset. Once one is picked — or the message is no longer
 * the newest — the cards are the guide's disabled ones, the pick still outlined.
 */
@Composable
private fun Carousel(
    data: JsonObject,
    actions: TouchpointModalityActions,
) {
    val metrics = TouchpointMetrics.current()
    val cards = (data["cards"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Card::of) }.orEmpty()
    val save = SaveAs.of(data)
    var picked by rememberSaveable(data.toString()) { mutableStateOf<String?>(null) }
    val live = save != null && (actions.choose != null || actions.fillSlot != null)
    val bleed = metrics.transcriptInset
    BoxWithConstraints {
    val cap = maxWidth
    LazyRow(
        Modifier.layout { measurable, constraints ->
            // As wide as the panel: the transcript's inset on either side is the row's to scroll into.
            val extra = (bleed * 2).roundToPx()
            val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
            val placeable = measurable.measure(wide)
            layout(placeable.width - extra, placeable.height) { placeable.place(-bleed.roundToPx(), 0) }
        },
        contentPadding = PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(metrics.smallGap),
    ) {
        items(cards) { card ->
            val id = card.id
            val pick = if (id != null && save != null && picked == null) save.action(actions, id, card.spoken()) else null
            CardView(card, selected = id != null && id == picked, onClick = pick?.let { p -> { picked = id; p() } }, dimmed = save != null && (picked != null || !live), maxWidth = cap)
        }
    }
    }
}

/**
 * Web's `DefaultDateInput`, with the system's date picker in place of web's typed mask — a named
 * deviation: a pick sends `YYYY-MM-DD`, as web's does. `primary-5`, a hairline, `rounded-outer`;
 * the calendar to open it, the date, and web's main Send.
 */
@Composable
private fun DateInput(
    data: JsonObject,
    actions: TouchpointModalityActions,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val context = LocalContext.current
    val save = SaveAs.of(data)
    var date by rememberSaveable(data.toString()) { mutableStateOf<String?>(null) }
    val enabled = save != null && (actions.fillSlot != null || actions.choose != null)
    val shape = RoundedCornerShape(theme.outerRadius)
    fun open() {
        val now = date?.let(LocalDate::parse) ?: LocalDate.now()
        DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, now.year, now.monthValue - 1, now.dayOfMonth).show()
    }
    // The composer's shape and height, full width, as the guide's.
    Row(
        Modifier.fillMaxWidth().clip(shape).background(theme.primary5).border(metrics.hairline, theme.primary10, shape)
            .padding(start = metrics.aroundTarget, end = metrics.aroundTarget, top = metrics.aroundTarget, bottom = metrics.aroundTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = date?.let { LocalDate.parse(it) }?.let { "%02d / %02d / %04d".format(it.monthValue, it.dayOfMonth, it.year) }
        val placeholder = stringResource(R.string.touchpoint_modality_date_placeholder)
        // One stop for TalkBack: the picker, with the date (or the placeholder) as its state.
        IconButton(TouchpointIcons.Date, stringResource(R.string.touchpoint_modality_open_date_picker), if (enabled) ::open else null, state = shown ?: placeholder)
        // The date's slashes are quieter than its numbers: `primary20`.
        BasicText(
            withQuietSlashes(shown ?: placeholder, theme.primary20),
            Modifier.weight(1f).padding(horizontal = metrics.smallGap).clearAndSetSemantics {},
            style = theme.text(if (shown != null) theme.primary else if (enabled) theme.primary60 else theme.primary20, metrics.bodyText, metrics.bodyLineHeight),
        )
        val send = date?.takeIf { enabled }?.let { d -> save?.action(actions, d, d) }
        // The composer's Send, as the guide's; its own name: beside the composer's, TalkBack heard two "Send message"s.
        IconButton(TouchpointIcons.Send, stringResource(R.string.touchpoint_modality_date_send), send, kind = IconKind.MAIN)
    }
}

/**
 * Web's `DefaultVideo`, as the guide's: full width, the poster under the guide's overlay, and its
 * 40 cover-up play button; a tap hands the video to the system's player, as web lets the phone's take
 * over below `md:`. Only `https` is opened.
 */
@Composable
private fun Video(data: JsonObject) {
    val theme = LocalTouchpointTheme.current
    val url = data.text("url")?.takeIf { it.startsWith("https://") } ?: return
    val play = stringResource(R.string.touchpoint_modality_play_video)
    val poster = data.text("previewImageUrl")
    // Web's DefaultVideo: the poster and its play button until a tap, then the video in the card,
    // with its controls, playing — an HLS stream or a file, as web's player takes.
    var playing by rememberSaveable(url) { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.innerRadius)
    if (playing) {
        InlinePlayer(url, Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT).clip(shape).background(Color.Black))
        return
    }
    // Web's `bg-primary`, under a poster. Without one web shows the video's first frame, which an HLS
    // stream does not give before it plays: black, rather than `primary`'s light box in a dark theme.
    Box(
        Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT).clip(shape).background(if (poster != null) theme.primary else Color.Black)
            .clickable(role = Role.Button, onClickLabel = play) { playing = true }
            .semantics { contentDescription = play },
        contentAlignment = Alignment.Center,
    ) {
        poster?.let { RemoteImage(it, Modifier.fillMaxSize(), ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(theme.overlay))
        // The guide's cover-up button: `secondary60`, its glyph in `primary`.
        Box(Modifier.size(TouchpointMetrics.videoPlaySize).clip(CircleShape).background(theme.secondary60), contentAlignment = Alignment.Center) {
            Glyph(TouchpointIcons.Play, theme.primary, TouchpointMetrics.videoPlayGlyph)
        }
    }
}

/**
 * The video in its card: Media3's player with its own controls, started at once, as web's `<video
 * controls autoPlay>`. It takes audio focus while it plays, pauses when the app goes to the background,
 * and is released when the card leaves the screen.
 */
@Composable
private fun InlinePlayer(
    url: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val player =
        remember(url) {
            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                ).build()
                .apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                    prepare()
                    playWhenReady = true
                }
        }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val pauser = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(pauser)
        onDispose {
            lifecycle.removeObserver(pauser)
            player.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { androidx.media3.ui.PlayerView(it).apply { this.player = player; useController = true } },
        modifier = modifier,
        onRelease = { it.player = null },
    )
}

/**
 * Web's guide card, for a Connect View (`ShowView`): the guide glyph, "Guide", its status, and the
 * view's title — web's rule, its name first — and subheading. It resolves the view through the
 * session and, on a tap, opens it over the transcript in Connect's renderer ([GuideModal]), as web's
 * does; the answer moves the flow on. Its status is the card's own, as on web: not started, in
 * progress once opened, complete once answered.
 */
@Composable
internal fun GuideCard(
    summary: ViewSummary,
    reference: ViewReference? = null,
    key: String = "",
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    val host = LocalViewHost.current
    val opener = LocalGuideOpener.current
    val inner = RoundedCornerShape(theme.innerRadius)
    // Positional: the card sits in its message's keyed transcript item, which keeps it when scrolled away.
    var status by rememberSaveable(key) { mutableStateOf(GuideStatus.NOT_STARTED) }
    val resolving = host != null && reference != null
    // DescribeView, once per card: `null` while asking, a failure kept as such.
    val view by produceState<Result<ResolvedView>?>(null, reference?.viewToken, host) {
        if (host != null && reference != null) {
            value = host.describe(reference.viewToken)?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
        }
    }
    val resolved = view?.getOrNull()
    val title = guideTitle(resolved, summary.heading) ?: stringResource(R.string.touchpoint_guide_default_title)
    val statusText =
        stringResource(
            when (status) {
                GuideStatus.NOT_STARTED -> R.string.touchpoint_guide_not_started
                GuideStatus.IN_PROGRESS -> R.string.touchpoint_guide_in_progress
                GuideStatus.COMPLETE -> R.string.touchpoint_guide_complete
            },
        )
    val opens = resolved != null && reference != null && opener != null
    Row(
        Modifier.fillMaxWidth().clip(inner).background(theme.primary5)
            .then(
                if (opens) {
                    Modifier.clickable(role = Role.Button) {
                        if (resolved == null || reference == null) return@clickable
                        if (status == GuideStatus.NOT_STARTED) status = GuideStatus.IN_PROGRESS
                        opener?.invoke(OpenGuide(title, resolved, reference.inputData, onComplete = { status = GuideStatus.COMPLETE }))
                    }
                } else {
                    Modifier
                },
            )
            .padding(metrics.modalityRowPaddingHorizontal).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(metrics.modalityRowPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TouchpointMetrics.guideIconBox).clip(inner).background(theme.primary10), contentAlignment = Alignment.Center) {
            Glyph(TouchpointIcons.Guide, theme.primary60, TouchpointMetrics.guideGlyph)
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(R.string.touchpoint_guide_label), style = theme.text(theme.primary60, metrics.captionText, metrics.captionLineHeight), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (badge, ink) =
                    when (status) {
                        GuideStatus.NOT_STARTED -> theme.primary10 to theme.primary60
                        // Web's amber text on its amber tint is 1.9:1 in the light theme: the tint stays, the text is ink.
                        GuideStatus.IN_PROGRESS -> theme.warningPrimary.copy(alpha = BADGE_TINT) to theme.primary
                        GuideStatus.COMPLETE -> theme.accent20 to theme.accent
                    }
                BasicText(statusText, Modifier.clip(CircleShape).background(badge).padding(horizontal = metrics.smallGap), style = theme.text(ink, metrics.captionText, metrics.captionLineHeight))
            }
            when {
                resolving && view == null -> BasicText(stringResource(R.string.touchpoint_guide_loading), style = theme.text(theme.primary60, metrics.bodyText, metrics.bodyLineHeight))
                resolving && resolved == null -> BasicText(stringResource(R.string.touchpoint_guide_error), style = theme.text(theme.errorPrimary, metrics.bodyText, metrics.bodyLineHeight))
                else -> {
                    BasicText(title, style = theme.text(theme.primary, metrics.bodyText, metrics.bodyLineHeight))
                    summary.subheading?.takeIf { it.isNotBlank() }?.let { BasicText(it, style = theme.text(theme.primary60, metrics.smallText, metrics.smallLineHeight)) }
                }
            }
        }
    }
}

/** Web's local guide status: not part of the protocol. */
internal enum class GuideStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }

private const val BADGE_TINT = 0.16f

/** Web's `$saveAs`: a slot, or a choice by id. Context is not sent: the chat holds no Live Sync handle. */
private class SaveAs(val type: String, val id: String) {
    fun action(
        actions: TouchpointModalityActions,
        value: String,
        words: String,
    ): (() -> Unit)? =
        when (type) {
            "slot" -> actions.fillSlot?.let { f -> { f(id, value) } }
            "choiceId" -> actions.choose?.let { c -> { c(words, value) } }
            else -> null
        }

    companion object {
        fun of(data: JsonObject): SaveAs? {
            val o = data["\$saveAs"] as? JsonObject ?: return null
            return SaveAs(o.text("type") ?: return null, o.text("id") ?: return null)
        }
    }
}

/** [text] with each `/` in [slash]. */
private fun withQuietSlashes(
    text: String,
    slash: Color,
): AnnotatedString =
    buildAnnotatedString {
        for (part in text.split('/').withIndex()) {
            if (part.index > 0) withStyle(SpanStyle(color = slash)) { append('/') }
            append(part.value)
        }
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * An image a modality names, fetched over `https` only, at most [MAX_IMAGE_BYTES], scaled down as it
 * decodes, and kept in memory: the platform has no image loader, and a third-party one is not
 * allowed. Nothing while it loads, or when it cannot.
 */
@Composable
internal fun RemoteImage(
    url: String,
    modifier: Modifier,
    scale: ContentScale,
    /** Drawn in greys: a disabled card's picture. */
    greyed: Boolean = false,
) {
    val image by produceState<Bitmap?>(Images.cached(url), url) { if (value == null) value = Images.load(url) }
    val filter = if (greyed) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    image?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = scale, colorFilter = filter) } ?: Box(modifier)
}

private object Images {
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://")) return@withContext null
            try {
                val connection = URL(url).openConnection() as? HttpURLConnection ?: return@withContext null
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAX_IMAGE_BYTES) return@withContext null
                    // A bounded read, by hand: InputStream.readNBytes is API 33, and minSdk is 31.
                    val bytes =
                        connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(READ_CHUNK)
                            while (out.size() <= MAX_IMAGE_BYTES) {
                                val n = input.read(chunk)
                                if (n == -1) break
                                out.write(chunk, 0, n)
                            }
                            out.toByteArray()
                        }
                    if (bytes.size > MAX_IMAGE_BYTES) return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= MAX_DECODED_SIDE || bounds.outHeight / (sample * 2) >= MAX_DECODED_SIDE) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(url, it) }
                } finally {
                    connection.disconnect()
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private const val CACHE_ENTRIES = 32
    private const val TIMEOUT_MS = 10_000
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val READ_CHUNK = 16 * 1024
    private const val MAX_DECODED_SIDE = 1024
}

private const val VIDEO_ASPECT = 16f / 9f
