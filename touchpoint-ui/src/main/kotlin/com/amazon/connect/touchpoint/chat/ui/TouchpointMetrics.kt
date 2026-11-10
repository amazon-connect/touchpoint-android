@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.runtime.ProvidableCompositionLocal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Every dimension the chat UI uses, and the only file allowed literal `dp`/`sp` (the lint's one
 * exemption). The values are the Touchpoint design guide's at phone width, which Touchpoint web's
 * follow where they agree (CSS px as dp, its text as sp): the transcript 12 in from the panel's
 * edges and text messages 4 further, the composer 8 in, 32 between messages, 40-wide round buttons
 * with 16 icons, 20 and 28 corner radii, and the guide's type scale — Small 12/16 (times and
 * receipts), Medium 16/20 (messages, buttons, inputs, cards, participants, chat events), Large 24/32
 * (the welcome message on a phone), Extra Large 32/40 (on a tablet).
 *
 * Spacing is a multiple of the user's font scale, so the transcript grows at the largest text sizes
 * instead of clipping; corner radii stay fixed, as web's px radii do; text sizes are `sp`, which the
 * system scales itself.
 *
 * One mobile difference: a touch target is never under 48dp (the Android accessibility floor). A 40
 * button is drawn inside a 48 target, and the 4 left over comes out of the padding around it, so the
 * header and the composer keep web's outer sizes.
 */
@InternalTouchpointUi
public class TouchpointMetrics(
    fontScale: Float,
    /** Web's `md:`: a window 768 wide or more ([MEDIUM_WINDOW]). */
    public val wide: Boolean = false,
) {
    /** `1.0` at the default text size. Clamped so a broken configuration cannot collapse the layout. */
    public val scale: Float = fontScale.coerceIn(0.5f, 3f)

    private fun scaled(base: Float): Dp = (base * scale).dp

    // Insets and rhythm: web's p-2 (md:p-3), space-y-8 between responses, gap-1 / gap-2 / gap-3 / gap-6.
    public val inset: Dp get() = scaled(if (wide) 12f else 8f)

    /** The transcript's inset from the panel's edges: bubbles, cards and choices sit here (the guide's 12). */
    public val transcriptInset: Dp get() = scaled(12f)

    /** A text message's extra indent, so its words line up with the text inside cards and buttons. */
    public val textIndent: Dp get() = scaled(4f)

    /** Where a time or a receipt starts (or ends), in from its message's edge: the guide's 8. */
    public val stampIndent: Dp get() = scaled(8f)

    /** Between a message's words and the cards, carousel or input under it. */
    public val modalityGap: Dp get() = scaled(16f)

    /** Between a message's paragraphs, lists and headings. */
    public val blockGap: Dp get() = scaled(12f)

    /** The space under the transcript's last item, so it does not end against the composer. */
    public val transcriptEnd: Dp get() = scaled(16f)
    public val responseGap: Dp get() = scaled(32f)
    public val tinyGap: Dp get() = scaled(4f)
    public val smallGap: Dp get() = scaled(8f)

    /** The guide's launch button: 16 before its mark, 24 after its words, the mark 20. */
    public val launchPaddingStart: Dp get() = scaled(16f)

    public val launchPaddingEnd: Dp get() = scaled(24f)

    public val launchMark: Dp get() = scaled(20f)
    public val loaderGap: Dp get() = scaled(12f)
    public val welcomeGap: Dp get() = scaled(16f)
    public val sectionGap: Dp get() = scaled(24f)
    public val dialogPadding: Dp get() = scaled(16f)
    public val dialogMaxWidth: Dp get() = 384.dp

    // Buttons: a 40 circle with a 16 icon, centred in a 48 target.
    public val buttonSize: Dp get() = 40.dp
    public val iconSize: Dp get() = 16.dp
    public val smallIconSize: Dp get() = 14.dp

    /** A delivery receipt's tick, beside its 12/16 label: the guide's 12. */
    public val receiptIconSize: Dp get() = 12.dp
    public val dialogIconSize: Dp get() = 20.dp

    /** Around a 40 button or the brand in its row: what the 48 target adds. The composer's padding, web's 8 less it. */
    public val aroundTarget: Dp get() = 4.dp

    /** The header's padding: web's p-2 (md:p-3), less what the 48 target adds. */
    public val headerPadding: Dp get() = if (wide) 8.dp else 4.dp

    // A bubble: 12×10 around a 20 line (40 tall), radius 20, held 16 short of the opposite side.
    public val bubblePaddingHorizontal: Dp get() = scaled(12f)
    public val bubblePaddingVertical: Dp get() = scaled(10f)
    public val bubbleInset: Dp get() = scaled(16f)

    // Rows: settings entries, choices, Start new — 56 tall, padding 20×16.
    public val rowMinHeight: Dp get() = scaled(56f)
    public val rowPaddingHorizontal: Dp get() = scaled(20f)

    /**
     * The composer's field: its text 24 in from the composer's edge, as the guide's (start), and web's
     * 8 margin and 4 gap before Send (end) — less the 4 the 48 target adds.
     */
    public val fieldPaddingStart: Dp get() = scaled(20f)
    public val fieldPaddingEnd: Dp get() = scaled(16f)

    /** Above and below the text once the field grows past one line: 18 around a 20 line, so one line is 56 tall, less the 4 of the target. */
    public val fieldPaddingVertical: Dp get() = scaled(14f)

    // Attachments: the composer's own p-2 (no md: step), its file row and error banner (px-2 py-1),
    // the 12 before a file's name, and the pulsing dot while it uploads (w-1.5 in a w-4).
    public val composerPadding: Dp get() = scaled(8f)
    public val bannerPaddingHorizontal: Dp get() = scaled(8f)
    public val bannerPaddingVertical: Dp get() = scaled(4f)
    public val fileNameGap: Dp get() = scaled(12f)
    public val uploadDot: Dp get() = 6.dp

    /** The field's start after the attach button: web's px-2 after its gap-1, less the 4 the target adds. */
    public val fieldPaddingAfterAttach: Dp get() = scaled(8f)

    // Voice (FullscreenVoice.tsx): the mic bar's py-4, the Retry bar's h-20 px-3, and a title's mb-2.
    public val voiceBarPadding: Dp get() = scaled(16f)
    public val voiceRetryBarHeight: Dp get() = scaled(80f)
    public val voiceRetryPadding: Dp get() = scaled(12f)
    public val titleGap: Dp get() = scaled(8f)

    /** The guide's loading dot (12 across) and the glow around it. */
    public val loaderSize: Dp get() = 12.dp
    public val loaderGlow: Dp get() = 8.dp

    /** The error row. */
    public val errorPadding: Dp get() = scaled(12f)

    /** Web's Banner (1.0.5-6): py-2 pl-2 pr-4, gap-1, its glyph centred in a 40 box. */
    public val noticeBannerPaddingStart: Dp get() = scaled(8f)
    public val noticeBannerPaddingEnd: Dp get() = scaled(16f)
    public val noticeBannerPaddingVertical: Dp get() = scaled(8f)

    /** A card's rows: 12 in from its sides, 16 from its top and bottom, 16 between them. */
    public val modalityRowPaddingHorizontal: Dp get() = scaled(12f)
    public val modalityRowPaddingVertical: Dp get() = scaled(16f)
    public val modalityRowGap: Dp get() = scaled(16f)

    public val hairline: Dp get() = 1.dp

    /** Web's focus outline: 2 wide, 1 outside the control, in the focus colour. */
    public val focusRing: Dp get() = 2.dp
    public val focusOffset: Dp get() = 1.dp


    /** The sheet's top corners on Android; web's panel is full-bleed on a phone, a sheet is the mobile form. */
    public val sheetCornerRadius: Dp get() = 28.dp

    /** The bottom sheet's top handle, which expands it to the whole screen: a 32 × 4 bar in a 16 strip. */
    public val sheetHandleWidth: Dp get() = 32.dp
    public val sheetHandleHeight: Dp get() = 4.dp
    public val sheetHandleStrip: Dp get() = 16.dp

    /** Web blurs what is behind its translucent panel by 48; the sheet blurs the host behind it the same. */
    public val backdropBlur: Dp get() = 48.dp

    /** The Android and Material accessibility floor. A floor, not a scaled value: it never shrinks. */
    public val minimumTouchTarget: Dp get() = 48.dp

    // Text: the guide's Medium 16/20 for messages and controls, Large 24/32 (Extra Large 32/40 from
    // `md:`) for the welcome message; Tailwind's xl 20/28 and sm 14/20 where the guide has no role.
    public val bodyText: TextUnit get() = 16.sp
    public val bodyLineHeight: TextUnit get() = 20.sp
    public val welcomeText: TextUnit get() = if (wide) 32.sp else 24.sp
    public val welcomeLineHeight: TextUnit get() = if (wide) 40.sp else 32.sp
    public val titleText: TextUnit get() = 20.sp
    public val titleLineHeight: TextUnit get() = 28.sp
    public val smallText: TextUnit get() = 14.sp
    public val smallLineHeight: TextUnit get() = 20.sp
    public val captionText: TextUnit get() = 12.sp
    public val captionLineHeight: TextUnit get() = 16.sp

    /** Chrome's disc list marker, measured: the item's text starts 1.375em in, after a disc 0.3125em across. */
    public val listMarkerWidth: TextUnit get() = 1.375.em
    public val listMarkerHeight: TextUnit get() = 1.em

    /** The shimmer's band on "Thinking…": 80 wide. */
    public val shimmerBand: Dp get() = 80.dp

    public companion object {
        /** The design grid icons are drawn on. A coordinate system, not a size. */
        public const val ICON_GRID: Float = 24f

        /** The disc's diameter as a fraction of the text size (5 of 16 px, in Chrome). */
        public const val LIST_DISC: Float = 0.3125f

        /** Web's two radii (Theme.tsx), the theme's defaults: bubbles and the error row; rows, cards, the composer and dialogs. */
        public val DEFAULT_INNER_RADIUS: Dp = 20.dp
        public val DEFAULT_OUTER_RADIUS: Dp = 28.dp

        /**
         * Participant info, as the guide's: a 20 avatar — the host's image, or a 12 glyph on its disc —
         * 8 from the name beside it, or from the bubble it sits beside.
         */
        public val avatarSize: Dp = 20.dp
        public val avatarImage: Dp = 20.dp
        public val avatarDisc: Dp = 20.dp
        public val avatarGlyph: Dp = 12.dp

        /** The welcome screen's brand logo (web's w-12), and the header's brand: 32, as the guide's. */
        public val welcomeLogo: Dp = 48.dp
        public val brandIcon: Dp = 32.dp

        /**
         * The header brand's start inset in web's 40 brand slot, (40 - 32) / 2: the brand starts where
         * the right-hand buttons' 40 circles would, not at the panel's edge padding.
         */
        public val brandInset: Dp = 4.dp

        /** The guide's assistant sparkle in the header, when the host gives no brand of its own. */
        public val brandGlyph: Dp = 20.dp

        /** Where a participant-info message continues its sender's run: web's 32 less its -mt-4. */
        public val continuationGap: Dp = 16.dp

        /** Web's `md:` breakpoint, on the window: wider insets, and content held to [contentMaxWidth]. */
        public val MEDIUM_WINDOW: Dp = 768.dp

        /** Web's `@3xl/main`, on the panel itself: from this wide, the header floats over the content. */
        public val WIDE_PANEL: Dp = 768.dp

        /** Web's `xl:`: where its half-screen panel stops being half and holds at [halfPanelMaxWidth]. */
        public val LARGE_WINDOW: Dp = 1280.dp

        /** Web's `max-w-content`: the transcript, the composer, the settings and the welcome screen at `md:`. */
        public val contentMaxWidth: Dp = 640.dp

        /** Web's half-screen panel at `xl:` (`grid-cols-[1fr_632px]`). */
        public val halfPanelMaxWidth: Dp = 632.dp

        /** Web's voice orb (128, p-4), the glyph of its ended and error screens (w-20), and a ripple's 2 border. */
        public val voiceOrb: Dp = 128.dp

        /** Web's `CustomCard`: w-80, and its image row h-52. */
        public val modalityCardWidth: Dp = 320.dp
        public val modalityThumbnailHeight: Dp = 208.dp

        /** A video's play button: the guide's 40 cover-up button, its glyph 16. */
        public val videoPlaySize: Dp = 40.dp
        public val videoPlayGlyph: Dp = 16.dp

        /** Web's guide card: its icon tile w-11 h-11, the glyph w-5 h-5. */
        public val guideIconBox: Dp = 44.dp
        public val guideGlyph: Dp = 20.dp

        /**
         * Web's `floating` card, 8 off the edges and 420 wide at most (the window less 16). From
         * [MEDIUM_WINDOW] it is web's tall card: the window's height less 16, at most
         * [floatingTallMaxHeight]. On a phone: 420 high at most and 45% of the window.
         */
        public val floatingMaxWidth: Dp = 420.dp
        public val floatingMaxHeight: Dp = 420.dp
        public val floatingTallMaxHeight: Dp = 1080.dp
        public val floatingMargin: Dp = 8.dp

        /** The guide's Float effect: a 12 drop shadow, black at 8 %, under the floating card, the voice mini and a launcher. */
        public val floatingShadow: Dp = 12.dp
        public const val FLOATING_SHADOW_ALPHA: Float = 0.08f

        /** The room above the half-height sheet that its shadow falls into: twice the shadow's reach. */
        public val sheetShadowRoom: Dp = 24.dp
        public const val FLOATING_HEIGHT_FRACTION: Float = 0.45f

        /** Web's sign-in card: the working dot, w-1.5 h-1.5. */
        public val authenticationPulseDot: Dp = 6.dp

        /** Web's `voiceMiniPanelClass`: max-w-[360px]. */
        public val voiceMiniPanelMaxWidth: Dp = 360.dp
        public val voiceOrbPadding: Dp = 32.dp
        public val voiceStateGlyph: Dp = 80.dp
        public val rippleBorder: Dp = 2.dp

        /** The size icons are declared at; the layout sizes them with [iconSize]. */
        public val iconDefaultSize: Dp = 24.dp

        @Composable
        @ReadOnlyComposable
        public fun current(): TouchpointMetrics = TouchpointMetrics(LocalDensity.current.fontScale, LocalWideWindow.current)
    }
}

/** Whether the window is web's `md:` — set once, by the surface, from the window it is in. */
@InternalTouchpointUi
public val LocalWideWindow: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** The window's width, where a test stands in for a tablet; `null` reads the real one. */
@InternalTouchpointUi
public val LocalWindowWidth: ProvidableCompositionLocal<Dp?> = staticCompositionLocalOf<Dp?> { null }
