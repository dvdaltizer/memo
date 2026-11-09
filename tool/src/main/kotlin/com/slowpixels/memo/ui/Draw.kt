package com.slowpixels.memo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Type and lettering on the drawn face, in Light's own font. */

internal fun DrawScope.style(family: FontFamily, px: Float, color: Color, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, tabular: Boolean = false) =
    TextStyle(
        fontFamily = family, fontSize = px.toSp(), color = color, fontWeight = weight, letterSpacing = (px * spacing).toSp(),
        fontFeatureSettings = if (tabular) "tnum" else null,
    )

/** Draws [text] with its middle at [x], [y]. Returns its width. */
internal fun DrawScope.centred(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle): Float {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x - laid.size.width / 2f, y - laid.size.height / 2f))
    return laid.size.width.toFloat()
}

/** Draws [text] starting at [x], centred on [y]. Returns its width. */
internal fun DrawScope.leftAt(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle): Float {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x, y - laid.size.height / 2f))
    return laid.size.width.toFloat()
}

/** Draws [text] ending at [x], centred on [y]. Returns its width. */
internal fun DrawScope.rightAt(m: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle): Float {
    val laid = m.measure(text, style)
    drawText(laid, topLeft = Offset(x - laid.size.width, y - laid.size.height / 2f))
    return laid.size.width.toFloat()
}

/** [text] cut with an ellipsis so it fits [maxWidth]. */
internal fun TextMeasurer.clipped(text: String, style: TextStyle, maxWidth: Float): String {
    if (measure(text, style).size.width <= maxWidth) return text
    var n = text.length
    while (n > 1 && measure(text.take(n) + "…", style).size.width > maxWidth) n--
    return text.take(n).trimEnd() + "…"
}
