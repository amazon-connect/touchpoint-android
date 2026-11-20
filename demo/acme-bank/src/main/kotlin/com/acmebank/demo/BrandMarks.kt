package com.acmebank.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * The header's brand, from Settings: none; the wide logo alone (the mark and the
 * name in one image, whole only in mini, as web draws it); or the square mark beside a title.
 */
enum class ChatBrand { NONE, LOGO, LOGO_AND_NAME }

/** Acme's mark: a white "A" on the brand's rounded square. Square, as web draws a docked header's icon. */
@Composable
fun rememberAcmeMark(): Painter = remember { AcmeMark }

/**
 * The mark beside "Acme Bank": wide, for web's floating header, which draws a brand 40 tall and as
 * wide as it is (`isBrandIconSquare={!isFloating}`). Its ink follows the header's: light on a dark
 * one.
 */
@Composable
fun rememberAcmeWordmark(ink: Color): Painter {
    val measurer = rememberTextMeasurer()
    return remember(measurer, ink) {
        // The name is part of the artwork: measured on the mark's own grid (1 px a unit, no font
        // scale), so the painter is as wide as its text whatever the device's text size.
        val style = TextStyle(color = ink, fontSize = NAME_SIZE.sp, fontWeight = FontWeight.SemiBold)
        val name = measurer.measure(NAME, style, softWrap = false, density = Density(1f, 1f))
        object : Painter() {
            override val intrinsicSize = Size(MARK + GAP + name.size.width + 1, MARK)

            override fun DrawScope.onDraw() {
                val unit = size.height / MARK
                drawMark(unit)
                val text = measurer.measure(NAME, style, softWrap = false, density = Density(unit, 1f))
                drawText(text, topLeft = Offset((MARK + GAP) * unit, (size.height - text.size.height) / 2))
            }
        }
    }
}

private object AcmeMark : Painter() {
    override val intrinsicSize = Size(MARK, MARK)

    override fun DrawScope.onDraw() = drawMark(size.height / MARK)
}

private fun DrawScope.drawMark(unit: Float) {
    val side = MARK * unit
    drawRoundRect(Palette.brand, size = Size(side, side), cornerRadius = CornerRadius(9 * unit))
    // An "A": two legs and a bar, drawn as one outline with the counter cut out.
    val a =
        Path().apply {
            moveTo(20 * unit, 8 * unit)
            lineTo(31 * unit, 32 * unit)
            lineTo(26.5f * unit, 32 * unit)
            lineTo(24.2f * unit, 27 * unit)
            lineTo(15.8f * unit, 27 * unit)
            lineTo(13.5f * unit, 32 * unit)
            lineTo(9 * unit, 32 * unit)
            close()
            moveTo(20 * unit, 16.5f * unit)
            lineTo(17.4f * unit, 23 * unit)
            lineTo(22.6f * unit, 23 * unit)
            close()
        }
    drawPath(a, Color.White)
}

private const val MARK = 40f
private const val GAP = 8f
private const val NAME = "Acme Bank"
private const val NAME_SIZE = 17
