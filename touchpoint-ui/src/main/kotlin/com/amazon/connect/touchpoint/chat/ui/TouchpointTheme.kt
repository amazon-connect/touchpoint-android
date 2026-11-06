@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.runtime.ProvidableCompositionLocal

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp

/**
 * The chat UI's colour tokens — Touchpoint web's (src/Theme.tsx), name for name, with web's default
 * values, so a host that themes web themes Android the same way. Propagated **only** through a
 * composition local the SDK owns — never the host's `MaterialTheme`.
 *
 * Web layers translucent colours over its background, and so does this: the `primary` and `secondary`
 * scales are the base colour at falling opacity — `primary` for text and icons, `primary10` for the
 * customer's bubble, `primary5` for the composer and the choices, and so on. [withPrimary],
 * [withSecondary] and [withAccent] derive a whole scale from one colour, as web does when a host sets
 * only the base.
 *
 * The defaults are [Dark] and [Light]. Web defaults to dark; Android follows the system ([system])
 * — a host that wants web's default passes [Dark].
 *
 * Every parameter has a default — [Light]'s value — so a host sets only what it changes. Not a data
 * class, deliberately: its generated `copy` and `componentN` would make every new token a breaking
 * change. [copy] is written by hand for that reason: once a version has shipped, a token added after it
 * keeps the shipped `copy` as a `@Deprecated(level = HIDDEN)` overload, so a host compiled against it
 * keeps linking. (None has shipped yet.)
 *
 * @param primary Text, icons, and the scale's base: messages, labels.
 * @param primary60 Secondary text: the customer's own message, notices, the delivery label; idle icons.
 * @param primary40 Placeholder and "Thinking…" text; the delivery icon.
 * @param primary20 Disabled text and icons; rules.
 * @param primary10 The customer's bubble; settings rows; borders.
 * @param primary5 The composer and the choices card; disabled rows. Web's default is 3 %, not 5 %;
 *   [withPrimary] derives 5 %, as web's merge does.
 * @param primary1 The shimmer's ends.
 * @param secondary Text and icons on [accent] and on the error fill; the pressed overlay's base.
 * @param secondary60 Text on [accent] when it is disabled.
 * @param secondary40 An agent's bubble, when a host turns those on.
 * @param secondary20 Reserved, as web's.
 * @param secondary10 The pressed overlay on any button.
 * @param accent The send button's fill, the caret, the loader.
 * @param accent20 The send button's fill while there is nothing to send.
 * @param background The panel's base fill, under the depth layers.
 * @param overlay The dim behind a dialog.
 * @param errorPrimary Failure icons; the end-conversation button's fill.
 * @param errorSecondary The inline error row's fill.
 * @param warningPrimary Warning icons.
 * @param successPrimary The "Read" tick.
 * @param warningSecondary A warning banner's fill (web 1.0.5-6).
 * @param successSecondary A success banner's fill (web 1.0.5-6).
 * @param focus The focus ring; the composer's outline while it is focused.
 * @param accentEffect The design guide's Accent Effects: the voice level rings, the loading dot's
 *   glow, the text selection highlight.
 */
@Immutable
public class TouchpointTheme(
    public val primary: Color = Color(0xE6000000),
    public val primary60: Color = Color(0x99000000),
    public val primary40: Color = Color(0x66000000),
    public val primary20: Color = Color(0x33000000),
    public val primary10: Color = Color(0x1A000000),
    public val primary5: Color = Color(0x08000000),
    public val primary1: Color = Color(0x03000000),
    public val secondary: Color = Color(0xF2FFFFFF),
    public val secondary60: Color = Color(0xA6FFFFFF),
    public val secondary40: Color = Color(0x73FFFFFF),
    public val secondary20: Color = Color(0x40FFFFFF),
    public val secondary10: Color = Color(0x1FFFFFFF),
    public val accent: Color = Color(0xE6000000),
    public val accent20: Color = Color(0x33000000),
    public val background: Color = Color(0xE6F2F2F2),
    public val overlay: Color = Color(0x66000209),
    public val errorPrimary: Color = Color(0xFF9D0303),
    public val errorSecondary: Color = Color(0xFFFF5C89),
    public val warningPrimary: Color = Color(0xFFDC9F03),
    public val successPrimary: Color = Color(0xFF0B7846),
    public val focus: Color = Color(0xE6007FD9),
    /** Whether the panel's depth layers — wash, sheen, shade, grain — use their dark or light values. */
    public val isDark: Boolean = false,
    /** Web's `fontFamily`: `null` is the platform's own, as web's system stack is on Android. */
    public val fontFamily: FontFamily? = null,
    /** Web's `innerBorderRadius`: bubbles and the error row. */
    public val innerRadius: Dp = TouchpointMetrics.DEFAULT_INNER_RADIUS,
    /** Web's `outerBorderRadius`: rows, cards, the composer, dialogs. */
    public val outerRadius: Dp = TouchpointMetrics.DEFAULT_OUTER_RADIUS,
    public val warningSecondary: Color = Color(0xFFFFD976),
    public val successSecondary: Color = Color(0xFF9DD773),
    public val accentEffect: Color = Color(0xFF007FD9),
) {
    private val tokens: List<Any?>
        get() =
            listOf(
                primary, primary60, primary40, primary20, primary10, primary5, primary1,
                secondary, secondary60, secondary40, secondary20, secondary10,
                accent, accent20, background, overlay, errorPrimary, errorSecondary, warningPrimary, successPrimary, focus, isDark,
                fontFamily, innerRadius, outerRadius, warningSecondary, successSecondary, accentEffect,
            )

    /**
     * This theme with some tokens replaced, exactly as given — nothing derived. To tint with a brand,
     * [withAccent] (or [withPrimary]) derives the dependent tokens as web does, e.g.
     * `TouchpointTheme.system(Light.withAccent(brand), Dark.withAccent(brandOnDark))`.
     */
    public fun copy(
        primary: Color = this.primary,
        primary60: Color = this.primary60,
        primary40: Color = this.primary40,
        primary20: Color = this.primary20,
        primary10: Color = this.primary10,
        primary5: Color = this.primary5,
        primary1: Color = this.primary1,
        secondary: Color = this.secondary,
        secondary60: Color = this.secondary60,
        secondary40: Color = this.secondary40,
        secondary20: Color = this.secondary20,
        secondary10: Color = this.secondary10,
        accent: Color = this.accent,
        accent20: Color = this.accent20,
        background: Color = this.background,
        overlay: Color = this.overlay,
        errorPrimary: Color = this.errorPrimary,
        errorSecondary: Color = this.errorSecondary,
        warningPrimary: Color = this.warningPrimary,
        successPrimary: Color = this.successPrimary,
        focus: Color = this.focus,
        isDark: Boolean = this.isDark,
        fontFamily: FontFamily? = this.fontFamily,
        innerRadius: Dp = this.innerRadius,
        outerRadius: Dp = this.outerRadius,
        warningSecondary: Color = this.warningSecondary,
        successSecondary: Color = this.successSecondary,
        accentEffect: Color = this.accentEffect,
    ): TouchpointTheme =
        TouchpointTheme(
            primary, primary60, primary40, primary20, primary10, primary5, primary1,
            secondary, secondary60, secondary40, secondary20, secondary10,
            accent, accent20, background, overlay, errorPrimary, errorSecondary, warningPrimary, successPrimary, focus, isDark,
            fontFamily, innerRadius, outerRadius, warningSecondary, successSecondary, accentEffect,
        )

    /** This theme with the primary scale derived from [color], as web's theme merge does: its RGB at 60/40/20/10/5/1 % opacity. */
    public fun withPrimary(color: Color): TouchpointTheme =
        copy(
            primary = color,
            primary60 = color.copy(alpha = 0.60f),
            primary40 = color.copy(alpha = 0.40f),
            primary20 = color.copy(alpha = 0.20f),
            primary10 = color.copy(alpha = 0.10f),
            primary5 = color.copy(alpha = 0.05f),
            primary1 = color.copy(alpha = 0.01f),
        )

    /** This theme with the secondary scale derived from [color], as web's theme merge does: its RGB at 60/40/20/10 % opacity. */
    public fun withSecondary(color: Color): TouchpointTheme =
        copy(
            secondary = color,
            secondary60 = color.copy(alpha = 0.60f),
            secondary40 = color.copy(alpha = 0.40f),
            secondary20 = color.copy(alpha = 0.20f),
            secondary10 = color.copy(alpha = 0.10f),
        )

    /** This theme with [color] as the accent and `accent20` mixed from it, as web's theme merge does (`color-mix` at 20 %). */
    public fun withAccent(color: Color): TouchpointTheme = copy(accent = color, accent20 = color.copy(alpha = color.alpha * 0.20f))

    override fun equals(other: Any?): Boolean = other is TouchpointTheme && other.tokens == tokens

    override fun hashCode(): Int = tokens.hashCode()

    override fun toString(): String = "TouchpointTheme(${if (isDark) "dark" else "light"})"

    public companion object {
        /** Web's light theme (Theme.tsx). */
        public val Light: TouchpointTheme = TouchpointTheme()

        /** Web's dark theme (Theme.tsx) — web's default. */
        public val Dark: TouchpointTheme =
            TouchpointTheme(
                primary = Color(0xF2FFFFFF),
                primary60 = Color(0xA6FFFFFF),
                primary40 = Color(0x73FFFFFF),
                primary20 = Color(0x40FFFFFF),
                primary10 = Color(0x1FFFFFFF),
                primary5 = Color(0x08FFFFFF),
                primary1 = Color(0x03FFFFFF),
                secondary = Color(0xE6000000),
                secondary60 = Color(0x99000000),
                secondary40 = Color(0x66000000),
                secondary20 = Color(0x33000000),
                secondary10 = Color(0x1A000000),
                accent = Color(0xF2FFFFFF),
                accent20 = Color(0x40FFFFFF),
                background = Color(0xF21B1B21),
                overlay = Color(0x66000000),
                errorPrimary = Color(0xFFFF3A69),
                errorSecondary = Color(0xFF9D0303),
                warningPrimary = Color(0xFFFFD66C),
                successPrimary = Color(0xFF88D64F),
                focus = Color(0xB30095FF),
                isDark = true,
                warningSecondary = Color(0xFF835E00),
                successSecondary = Color(0xFF0B7846),
                accentEffect = Color(0xFF0095FF),
            )

        /** [light] or [dark], following the system's dark theme. */
        @Composable
        public fun system(
            light: TouchpointTheme = Light,
            dark: TouchpointTheme = Dark,
        ): TouchpointTheme = if (isSystemInDarkTheme()) dark else light
    }
}

@InternalTouchpointUi
public val LocalTouchpointTheme: ProvidableCompositionLocal<TouchpointTheme> = staticCompositionLocalOf { TouchpointTheme.Light }
