package com.slowpixels.memo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.slowpixels.memo.deck.Deck
import com.slowpixels.memo.deck.DeckState
import com.slowpixels.memo.deck.Mode
import com.slowpixels.memo.store.Memo
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.abs
import kotlin.math.hypot

/** Where everything sits on the face, for its size. Line weights are given for the phone's 1080-wide screen and scale with it. */
private class Plan(val w: Float, val h: Float) {
    val s = w / 1080f
    val line = 3f * s
    val thin = 2f * s
    val cx = w / 2f

    // The speaker grille: rows of holes in a band with round ends.
    val pitch = 30f * s
    val rowGap = pitch * 0.866f
    val hole = 4.2f * s
    val rows = 4
    val grilleTop = 26f * s
    val grilleBottom = grilleTop + (rows - 1) * rowGap + 2 * hole
    val grilleLeft = 96f * s
    val grilleRight = w - 96f * s

    // The display window and its four lines: what the deck is doing, the counter, the meter or the bar, the foot.
    val winLeft = 54f * s
    val winRight = w - 54f * s
    val winTop = grilleBottom + 40f * s
    val winBottom = h - 20f * s
    val winH = winBottom - winTop
    val inLeft = winLeft + 40f * s
    val inRight = winRight - 40f * s
    val statusY = winTop + winH * 0.14f
    val counterY = winTop + winH * 0.42f
    val counterPx = minOf(winH * 0.30f, 150f * s)
    val barY = winTop + winH * 0.69f
    val footY = winTop + winH * 0.875f
    val smallPx = 29f * s
    val barH = 26f * s
    val meterLeft = inLeft + 64f * s

    fun fraction(x: Float) = ((x - inLeft) / (inRight - inLeft)).coerceIn(0f, 1f)
    fun onBar(p: Offset) = abs(p.y - barY) < 70f * s && p.x > winLeft && p.x < winRight
    val barBand get() = Rect(0f, barY - 80f * s, w, barY + 80f * s)
}

/**
 * The recorder's face in Light's line-and-type look: a speaker grille of round holes, then the display window, which
 * says what the deck is doing, counts, and shows the level meter while recording or the memo's bar while listening.
 * Holes and cells are hollow; a cell fills when the mic hears something, the bar fills as a memo plays. [level] is read
 * only where the meter is drawn, so a moving meter redraws the picture and nothing else.
 */
@Composable
fun Face(
    state: DeckState,
    memo: Memo?,
    level: State<Int>,
    nextNumber: Int,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,
) {
    val colors = LightThemeTokens.colors
    val ink = colors.content
    val faint = colors.contentSecondary
    val family = LightThemeTokens.typography.heading.fontFamily ?: FontFamily.Default
    val measurer = rememberTextMeasurer()
    val seek by rememberUpdatedState(onSeek)
    val listening by rememberUpdatedState(state.listening && memo != null && memo.length > 0)
    // Where a finger is on the bar, while it's there; the memo moves when it lifts.
    var held by remember { mutableStateOf<Float?>(null) }
    val words = words(state, memo, nextNumber)

    Canvas(
        modifier
            .systemGestureExclusion { Plan(it.size.width.toFloat(), it.size.height.toFloat()).barBand }
            .semantics { contentDescription = words.spoken }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = Plan(size.width.toFloat(), size.height.toFloat())
                    if (!listening || !p.onBar(down.position)) return@awaitEachGesture
                    down.consume()
                    held = p.fraction(down.position.x)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { c.consume(); held?.let(seek); break }
                        held = p.fraction(c.position.x)
                        c.consume()
                    }
                    held = null
                }
            },
    ) {
        val p = Plan(size.width, size.height)
        grille(p, faint)
        drawRoundRect(ink, Offset(p.winLeft, p.winTop), Size(p.winRight - p.winLeft, p.winH), CornerRadius(30f * p.s), style = Stroke(p.line))
        status(p, words, measurer, family, ink, faint)
        centred(measurer, words.counter, p.cx, p.counterY, style(family, p.counterPx, ink, FontWeight.Light, tabular = true))
        if (state.listening) {
            val shown = held ?: memo?.let { if (it.length > 0) state.at.toFloat() / it.length else 0f } ?: 0f
            bar(p, shown.coerceIn(0f, 1f), ink)
        } else meter(p, if (state.mode == Mode.Recording) level.value else 0, ink, faint)
        foot(p, words, measurer, family, ink, faint)
    }
}

/** What the display says, worked out once a draw. */
private class Words(
    val status: String,
    val glyph: Glyph,
    val right: String,
    val counter: String,
    val footLeft: String,
    val footRight: String,
    val footIsNote: Boolean,
    val spoken: String,
)

private enum class Glyph { Ready, Recording, Playing, Stopped, None }

private fun words(state: DeckState, memo: Memo?, nextNumber: Int): Words {
    val note = state.note
    return if (!state.listening) {
        val recording = state.mode == Mode.Recording
        val status = if (recording) "RECORDING" else "READY"
        val right = if (state.room == Long.MAX_VALUE) "" else Say.room(state.room).uppercase() + " LEFT"
        val foot = when {
            note.isNotEmpty() -> note
            state.saved != null -> "Memo ${state.saved} saved"
            recording && memo != null -> Say.date(memo.made)
            else -> ""
        }
        val number = if (recording) state.memo ?: nextNumber else nextNumber
        Words(
            status, if (recording) Glyph.Recording else Glyph.Ready, right, Say.counter(if (recording) state.at else 0L),
            foot, "", note.isNotEmpty(),
            "Memo recorder, Record. " + (if (recording) "Recording memo $number, ${Say.spoken(state.at)}." else "Ready to record memo $number.") +
                (if (foot.isNotEmpty()) " $foot." else ""),
        )
    } else if (memo == null) {
        Words("NO MEMOS", Glyph.None, "", Say.counter(0L), note.ifEmpty { "Slide to Record to make one" }, "", note.isNotEmpty(),
            "Memo recorder, Listen. No memos yet.")
    } else {
        val playing = state.mode == Mode.Playing
        val speed = Say.speed(state.speed) + if (!state.keepPitch && state.speed != 1f) " TAPE" else ""
        Words(
            if (playing) "PLAYING" else "STOPPED", if (playing) Glyph.Playing else Glyph.Stopped, speed, Say.counter(state.at),
            note.ifEmpty { Say.date(memo.made) }, if (note.isEmpty()) Say.length(memo.length) else "", note.isNotEmpty(),
            "Memo recorder, Listen. ${memo.title}, ${if (playing) "playing" else "stopped"} at ${Say.spoken(state.at)} of " +
                "${Say.spoken(memo.length)}, speed $speed." + (if (note.isNotEmpty()) " $note." else ""),
        )
    }
}

// ---- the picture ----

/** The holes of the speaker grille, in offset rows, inside a band with round ends. */
private fun DrawScope.grille(p: Plan, faint: Color) {
    val top = p.grilleTop
    val bottom = p.grilleBottom
    val r = (bottom - top) / 2f
    val cy = (top + bottom) / 2f
    for (row in 0 until p.rows) {
        val y = top + p.hole + row * p.rowGap
        var x = p.grilleLeft + p.hole + if (row % 2 == 1) p.pitch / 2f else 0f
        while (x <= p.grilleRight - p.hole) {
            val inside = when {
                x < p.grilleLeft + r -> hypot(x - (p.grilleLeft + r), y - cy) <= r - p.hole * 0.5f
                x > p.grilleRight - r -> hypot(x - (p.grilleRight - r), y - cy) <= r - p.hole * 0.5f
                else -> true
            }
            if (inside) drawCircle(faint, p.hole, Offset(x, y))
            x += p.pitch
        }
    }
}

/** The top line of the display: a lamp or a mark, a word, and on the right the room left or the speed. */
private fun DrawScope.status(p: Plan, w: Words, m: TextMeasurer, family: FontFamily, ink: Color, faint: Color) {
    val y = p.statusY
    val x = p.inLeft
    val g = 10f * p.s
    when (w.glyph) {
        Glyph.Recording -> drawCircle(ink, g, Offset(x + g, y))
        Glyph.Ready -> drawCircle(ink, g - p.line / 2f, Offset(x + g, y), style = Stroke(p.line))
        Glyph.Playing -> drawPath(Path().apply { moveTo(x + 2f * p.s, y - g); lineTo(x + 2f * p.s + g * 1.7f, y); lineTo(x + 2f * p.s, y + g); close() }, ink)
        Glyph.Stopped -> drawRect(ink, Offset(x + 2f * p.s, y - g * 0.85f), Size(g * 1.7f, g * 1.7f))
        Glyph.None -> {}
    }
    val textX = if (w.glyph == Glyph.None) x else x + g * 2f + 16f * p.s
    leftAt(m, w.status, textX, y, style(family, p.smallPx, ink, spacing = 0.14f))
    if (w.right.isNotEmpty()) rightAt(m, w.right, p.inRight, y, style(family, p.smallPx, faint, spacing = 0.12f))
}

/** The level meter: a microphone, then twenty cells rising left to right, hollow, filling as the mic hears. */
private fun DrawScope.meter(p: Plan, lit: Int, ink: Color, faint: Color) {
    val y = p.barY
    // The microphone: a capsule, the cradle under it, the stand.
    val mx = p.inLeft + 16f * p.s
    drawRoundRect(ink, Offset(mx - 9f * p.s, y - 28f * p.s), Size(18f * p.s, 30f * p.s), CornerRadius(9f * p.s), style = Stroke(p.thin))
    drawArc(ink, 0f, 180f, false, Offset(mx - 15f * p.s, y - 22f * p.s), Size(30f * p.s, 30f * p.s), style = Stroke(p.thin))
    drawLine(ink, Offset(mx, y + 8f * p.s), Offset(mx, y + 20f * p.s), p.thin)
    drawLine(ink, Offset(mx - 9f * p.s, y + 20f * p.s), Offset(mx + 9f * p.s, y + 20f * p.s), p.thin, cap = StrokeCap.Round)

    val n = Deck.LEVELS
    val cell = (p.inRight - p.meterLeft) / n
    val width = cell * 0.6f
    val base = y + 20f * p.s
    for (i in 0 until n) {
        val tall = 10f * p.s + 34f * p.s * i / (n - 1)
        val left = p.meterLeft + i * cell + (cell - width) / 2f
        if (i < lit) drawRect(ink, Offset(left, base - tall), Size(width, tall))
        else drawRect(faint, Offset(left + p.thin / 2f, base - tall + p.thin / 2f), Size(width - p.thin, tall - p.thin), style = Stroke(p.thin * 0.8f))
    }
}

/** Listen's bar: a plain outlined track that fills as the memo plays, so it can't be taken for the meter. */
private fun DrawScope.bar(p: Plan, fraction: Float, ink: Color) {
    val top = p.barY - p.barH / 2f
    drawRoundRect(ink, Offset(p.inLeft, top), Size(p.inRight - p.inLeft, p.barH), CornerRadius(p.barH / 2f), style = Stroke(p.line))
    val inset = 6f * p.s
    val inner = p.barH - 2 * inset
    val full = p.inRight - p.inLeft - 2 * inset
    val fill = full * fraction
    if (fill >= inner) drawRoundRect(ink, Offset(p.inLeft + inset, top + inset), Size(fill, inner), CornerRadius(inner / 2f))
    else if (fraction > 0f) drawCircle(ink, inner / 2f, Offset(p.inLeft + inset + inner / 2f, p.barY))
}

/** The foot line: a note, or when the memo was made and how long it is. */
private fun DrawScope.foot(p: Plan, w: Words, m: TextMeasurer, family: FontFamily, ink: Color, faint: Color) {
    val st = style(family, p.smallPx, if (w.footIsNote) ink else faint)
    val right = if (w.footRight.isNotEmpty()) rightAt(m, w.footRight, p.inRight, p.footY, style(family, p.smallPx, faint)) + 24f * p.s else 0f
    if (w.footLeft.isNotEmpty()) leftAt(m, m.clipped(w.footLeft, st, p.inRight - p.inLeft - right), p.inLeft, p.footY, st)
}
