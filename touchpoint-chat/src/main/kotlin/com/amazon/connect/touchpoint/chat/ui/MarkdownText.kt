@file:OptIn(com.amazon.connect.touchpoint.chat.ui.InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.amazon.connect.touchpoint.chat.ui.Markdown.Align
import com.amazon.connect.touchpoint.chat.ui.Markdown.Block
import com.amazon.connect.touchpoint.chat.ui.Markdown.Inline

/**
 * [Markdown] blocks drawn as web's `.markdown` styles leave them over its CSS reset: headings at the
 * text's own size, h1–h3 bold; lists with their marker inside the text and no indent; links
 * underlined; rules in `primary20`; code in the same font, a block scrolling sideways rather than
 * wrapping; quotes and tables unstyled. [blockGap] between top-level blocks — web's `space-y-6` in the
 * transcript, `null` on the welcome screen — and none inside a list item or a quote.
 *
 * Tapping a link calls [onLink]. TalkBack does not reach a link inside text on its own: the message
 * node carries one action per link ([linkActions]).
 */
@Composable
internal fun MarkdownContent(
    blocks: List<Block>,
    style: TextStyle,
    blockGap: Dp?,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, verticalArrangement = if (blockGap != null) Arrangement.spacedBy(blockGap) else Arrangement.Top, horizontalAlignment = horizontalAlignment) {
        for (b in blocks) MarkdownBlock(b, style, onLink)
    }
}

@Composable
private fun MarkdownBlock(
    block: Block,
    style: TextStyle,
    onLink: (String) -> Unit,
) {
    val theme = LocalTouchpointTheme.current
    val metrics = TouchpointMetrics.current()
    when (block) {
        is Block.Paragraph -> BasicText(remember(block, onLink) { annotated(block.inlines, onLink) }, style = style)
        is Block.Heading ->
            BasicText(
                remember(block, onLink) { annotated(block.inlines, onLink) },
                Modifier.semantics { heading() },
                style = if (block.level <= BOLD_HEADINGS) style.copy(fontWeight = FontWeight.Bold) else style,
            )
        is Block.Code ->
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                BasicText(block.text, style = style, softWrap = false)
            }
        is Block.Quote -> MarkdownContent(block.blocks, style, null, onLink, horizontalAlignment = Alignment.CenterHorizontally.takeIf { style.textAlign == TextAlign.Center } ?: Alignment.Start)
        is Block.Items ->
            Column {
                block.items.forEachIndexed { n, item ->
                    val marker = if (block.ordered) "${block.start + n}. " else null
                    val first = item.firstOrNull()
                    val lead = (first as? Block.Paragraph)?.inlines.orEmpty()
                    // The marker sits inside the first line, as web's list-inside does, so a wrapped line
                    // starts under it.
                    BasicText(remember(item, onLink) { annotated(lead, onLink, marker = marker, disc = !block.ordered) }, style = style, inlineContent = disc(style, metrics))
                    for (b in if (first is Block.Paragraph) item.drop(1) else item) MarkdownBlock(b, style, onLink)
                }
            }
        Block.Rule -> Box(Modifier.fillMaxWidth().height(metrics.hairline).background(theme.primary20))
        is Block.Table ->
            Column {
                TableRow(block.header, block.align, style.copy(fontWeight = FontWeight.Bold), onLink)
                for (row in block.rows) TableRow(row, block.align, style, onLink)
            }
    }
}

@Composable
private fun TableRow(
    cells: List<List<Inline>>,
    align: List<Align>,
    style: TextStyle,
    onLink: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { c, cell ->
            val textAlign =
                when (align.getOrElse(c) { Align.START }) {
                    Align.START -> TextAlign.Start
                    Align.CENTER -> TextAlign.Center
                    Align.END -> TextAlign.End
                }
            BasicText(remember(cell, onLink) { annotated(cell, onLink) }, Modifier.weight(1f), style = style.copy(textAlign = textAlign))
        }
    }
}

private fun annotated(
    inlines: List<Inline>,
    onLink: (String) -> Unit,
    marker: String? = null,
    disc: Boolean = false,
): AnnotatedString =
    buildAnnotatedString {
        if (disc) appendInlineContent(DISC, BULLET)
        if (marker != null) append(marker)
        appendInlines(inlines, onLink)
    }

/** Chrome's disc: a circle in the text's colour, at the start of the marker's box, centred on the line. */
private fun disc(
    style: TextStyle,
    metrics: TouchpointMetrics,
): Map<String, InlineTextContent> =
    mapOf(
        DISC to
            InlineTextContent(Placeholder(metrics.listMarkerWidth, metrics.listMarkerHeight, PlaceholderVerticalAlign.TextCenter)) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.height * TouchpointMetrics.LIST_DISC / 2f
                    drawCircle(style.color, radius, Offset(radius, size.height / 2f))
                }
            },
    )

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<Inline>,
    onLink: (String) -> Unit,
) {
    for (i in inlines) {
        when (i) {
            is Inline.Text -> append(i.text)
            is Inline.Code -> append(i.text)
            is Inline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(i.children, onLink) }
            is Inline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(i.children, onLink) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(i.children, onLink) }
            is Inline.Link -> {
                val url = i.url
                if (url == null) {
                    appendInlines(i.children, onLink)
                } else {
                    withLink(LinkAnnotation.Url(url, LINK_STYLES) { onLink(url) }) { appendInlines(i.children, onLink) }
                }
            }
            Inline.Break -> append('\n')
        }
    }
}

/**
 * A message's link, opened as web opens one in a new tab: a web link in a Custom Tab over the host's
 * app, mail and phone links in their apps. Only what [Markdown.safeUrl] allows, checked again here;
 * nothing to open it with is not a crash.
 */
internal fun openLink(
    context: Context,
    url: String,
) {
    val safe = Markdown.safeUrl(url) ?: return
    try {
        val uri = Uri.parse(safe)
        val activity = findActivity(context)
        val launcher = activity ?: context
        if (uri.scheme == "http" || uri.scheme == "https") {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(launcher, uri)
        } else {
            launcher.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    } catch (_: ActivityNotFoundException) {
        // No browser, mail or phone app: nothing to hand it to.
    } catch (_: RuntimeException) {
        // A handler the system will not let us start, or a context in a state to start nothing:
        // a link that does not open, never a host that crashes.
    }
}

/** TalkBack's way to a message's links: one action each, named by its words. */
internal fun linkActions(
    links: List<Pair<String, String>>,
    label: (String) -> String,
    onLink: (String) -> Unit,
): List<CustomAccessibilityAction> =
    links.map { (words, url) ->
        CustomAccessibilityAction(label(words)) {
            onLink(url)
            true
        }
    }

internal tailrec fun findActivity(context: Context?): Activity? =
    when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }

private val LINK_STYLES = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
private const val DISC = "disc"

/** What the disc is read as, where anything reads it. */
private const val BULLET = "•"
private const val BOLD_HEADINGS = 3
