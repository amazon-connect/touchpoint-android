package com.acmebank.demo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** The chat's colours on their surfaces, white on the brand teal, and the bank's secondary text, against WCAG AA. */
class PaletteTest {
    /** Contrast of [text] composited over [background]: opacity matters, and ignoring it would pass at any. */
    private fun contrast(text: Color, background: Color): Double {
        val l1 = text.compositeOver(background).luminance().toDouble()
        val l2 = background.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun `the send glyph clears the non-text minimum on the brand accent, light and dark`() {
        // WCAG 1.4.11: a control's glyph against its fill, 3:1.
        for (theme in listOf(Palette.chatLight, Palette.chatDark)) {
            assertTrue(contrast(theme.secondary, theme.accent) >= 3.0)
        }
    }

    @Test
    fun `the customer's own text clears AA in its bubble, light and dark`() {
        // The panel's fill over the page (white or near-black), the bubble over that, the text over both.
        val pages = mapOf(Palette.chatLight to Color.White, Palette.chatDark to Color(0xFF141218))
        for ((theme, page) in pages) {
            val bubble = theme.primary10.compositeOver(theme.background.compositeOver(page))
            assertTrue(contrast(theme.primary60, bubble) >= 4.5)
            assertTrue(contrast(theme.primary, theme.background.compositeOver(page)) >= 4.5)
        }
    }

    @Test
    fun `white on the brand colour (pill, chips) clears AA`() {
        assertTrue(contrast(Color.White, Palette.brand) >= 4.5)
    }

    @Test
    fun `secondary and signed amounts clear AA on their surfaces`() {
        assertTrue(contrast(Palette.muted, Color.White) >= 4.5)
        assertTrue(contrast(Palette.positive, Color.White) >= 4.5)
        assertTrue(contrast(Palette.negative, Color.White) >= 4.5)
        // The dark scheme's surfaces are near-black; Material's dark default is 0xFF141218.
        val dark = Color(0xFF141218)
        assertTrue(contrast(Palette.positiveOnDark, dark) >= 4.5)
        assertTrue(contrast(Palette.negativeOnDark, dark) >= 4.5)
        assertTrue(contrast(Palette.ink, Palette.brandLight) >= 4.5)
        // And the method is not vacuous: a faint grey fails it.
        assertTrue(contrast(Color.Black.copy(alpha = 0.3f), Color.White) < 4.5)
    }
}
