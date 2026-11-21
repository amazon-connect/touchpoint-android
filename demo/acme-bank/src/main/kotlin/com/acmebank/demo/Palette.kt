package com.acmebank.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.amazon.connect.touchpoint.chat.ui.TouchpointTheme

/** Acme Bank's palette. Invented for the demo; nothing here reproduces another bank's identity ([PartnerLook] does, on purpose). */
object Palette {
    /** Deep teal: the brand colour, for primary actions and the header bar. A partner's tint replaces it ([PartnerBrand.tint]). */
    val brand = PartnerBrand.current.tint ?: Color(0xFF006B73)
    val brandDark = PartnerBrand.current.tint?.let { lerp(it, Color.Black, 0.28f) } ?: Color(0xFF004D54)

    /** The brand on dark surfaces: light enough to carry dark text, as the dark scheme's primary. */
    val brandLight = PartnerBrand.current.tint?.let { lerp(it, Color.White, 0.5f) } ?: Color(0xFF7FD3DA)

    /** Warm sand: tints and secondary surfaces. */
    val sand = Color(0xFFF5EDDB)
    val ink = Color(0xFF1B1C20)

    /** Secondary text that still clears WCAG AA on white. */
    val muted = Color(0xFF55575F)
    val positive = Color(0xFF15773F)
    val negative = Color(0xFFB32929)

    /** The same meanings on dark surfaces, light enough to clear AA there. */
    val positiveOnDark = Color(0xFF7DDBA3)
    val negativeOnDark = Color(0xFFFF8A80)

    /**
     * The chat in the SDK's own light and dark themes, unbranded, so a design review sees Touchpoint's
     * default colours; it follows the system as the bank does.
     */
    val chatLight: TouchpointTheme = TouchpointTheme.Light
    val chatDark: TouchpointTheme = TouchpointTheme.Dark

    /** The chat's theme: the partner's, while the partner bank look is on ([PartnerLook]), whatever the system's. */
    @Composable
    fun chatTheme(): TouchpointTheme {
        val dark = Appearance.dark()
        return when {
            PartnerLook.on -> if (dark) PartnerLook.darkTheme else PartnerLook.theme
            else -> if (dark) chatDark else chatLight
        }
    }
}
