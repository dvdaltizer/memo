package com.slowpixels.memo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Record's one big key. Off, it's a ring with a dot in it and "RECORD". On, it's a solid disc with a square in it and
 * "STOP": the one solid shape on the screen, only while recording, so on and off can't be confused. It acts the moment
 * it's touched, as a real key does, with Light's click under the finger.
 */
@Composable
fun RecordKey(on: Boolean, vibrator: LightVibrator, modifier: Modifier = Modifier, onPress: () -> Unit) {
    val colors = LightThemeTokens.colors
    val ink = colors.content
    val paper = colors.background
    val family = LightThemeTokens.typography.heading.fontFamily ?: FontFamily.Default
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticsEnabled.current
    val press by rememberUpdatedState(onPress)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxHeight(0.86f).aspectRatio(1f)
                .semantics {
                    contentDescription = if (on) "Stop recording" else "Record"
                    role = Role.Button
                    onClick { press(); true }
                }
                .pointerInput(haptics) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        if (hypot(down.position.x - c.x, down.position.y - c.y) > size.width / 2f) return@awaitEachGesture
                        down.consume()
                        if (haptics) vibrator.click()
                        press()
                        waitForUpOrCancellation()
                    }
                },
        ) {
            val s = size.width / 300f
            val line = 3f * s
            val r = size.width / 2f - line / 2f
            val word = style(family, 30f * s, if (on) paper else ink, spacing = 0.16f)
            if (on) {
                drawCircle(ink, size.width / 2f, center)
                val side = 62f * s
                drawRect(paper, Offset(center.x - side / 2f, center.y - 26f * s - side / 2f), Size(side, side))
                centred(measurer, "STOP", center.x, center.y + 62f * s, word)
            } else {
                drawCircle(ink, r, center, style = Stroke(line))
                drawCircle(ink, 34f * s, Offset(center.x, center.y - 26f * s))
                centred(measurer, "RECORD", center.x, center.y + 62f * s, word)
            }
        }
    }
}

/**
 * Listen's keys, in Light's own icons: ◀◀ ■ ▶ ▶▶. ▶ has a line under it while a memo plays, as a deck's play key stays
 * down. ◀◀ and ▶▶ step on while held, further the longer they're held.
 */
@Composable
fun Transport(
    playing: Boolean,
    enabled: Boolean,
    vibrator: LightVibrator,
    modifier: Modifier = Modifier,
    onBack: (Int) -> Unit,
    onStop: () -> Unit,
    onPlay: () -> Unit,
    onAhead: (Int) -> Unit,
) {
    Row(
        modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("Rewind", LightIcons.REWIND, enabled, false, vibrator, repeat = true, onStep = onBack)
        Key("Stop", LightIcons.STOP, enabled, false, vibrator) { onStop() }
        Key(if (playing) "Playing" else "Play", LightIcons.PLAY, enabled, playing, vibrator) { onPlay() }
        Key("Fast forward", LightIcons.FAST_FORWARD, enabled, false, vibrator, repeat = true, onStep = onAhead)
    }
}

/** A deck key: acts as it's touched. With [repeat] it steps again every quarter second while held, [onStep] counting. */
@Composable
private fun Key(
    name: String,
    icon: LightIconConfiguration,
    enabled: Boolean,
    down: Boolean,
    vibrator: LightVibrator,
    repeat: Boolean = false,
    onStep: (Int) -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val haptics = LocalHapticsEnabled.current
    val ink = LightThemeTokens.colors.content
    Column(
        Modifier.size(width = 4f.gridUnitsAsDp(), height = 4.5f.gridUnitsAsDp())
            .semantics(mergeDescendants = true) {
                contentDescription = name
                role = Role.Button
                selected = down
                if (!enabled) disabled()
                onClick { if (enabled) step(0); enabled }
            }
            .pointerInput(enabled, haptics, repeat) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        if (haptics) vibrator.click()
                        step(0)
                        val again = if (!repeat) null else this@coroutineScope.launch {
                            delay(450)
                            var n = 1
                            while (true) { step(n++); delay(250) }
                        }
                        waitForUpOrCancellation()
                        again?.cancel()
                    }
                }
            }
            .alpha(if (enabled) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LightIcon(icon, size = 2.2f, contentDescription = null)
        Spacer(Modifier.height(0.45f.gridUnitsAsDp()))
        Box(Modifier.width(1.8f.gridUnitsAsDp()).height(3.dp).then(if (down) Modifier.background(ink) else Modifier))
    }
}

/**
 * The mode switch, along the foot where Light keeps a screen's main choices: RECORD on the left, LISTEN on the right,
 * the one in use underlined as Light's own lists mark a choice, and a slide switch between them. Moving it never starts
 * anything.
 */
@Composable
fun ModeSwitch(listening: Boolean, vibrator: LightVibrator, onChange: (Boolean) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticsEnabled.current
    Row(
        Modifier.fillMaxWidth().height(4f.gridUnitsAsDp()).padding(horizontal = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Side("RECORD", chosen = !listening, Modifier.weight(1f), TextAlign.End) { change(false) }
        val ink = LightThemeTokens.colors.content
        Canvas(
            Modifier.padding(horizontal = 0.8f.gridUnitsAsDp()).size(width = 3.2f.gridUnitsAsDp(), height = 1.6f.gridUnitsAsDp())
                .semantics { contentDescription = if (listening) "Mode switch, Listen" else "Mode switch, Record"; role = Role.Switch }
                .pointerInput(haptics) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        if (haptics) vibrator.click()
                        change(!listening)
                        waitForUpOrCancellation()
                    }
                },
        ) {
            val line = size.height * 0.07f
            val h = size.height
            drawRoundRect(ink, Offset(line / 2f, line / 2f), Size(size.width - line, h - line),
                androidx.compose.ui.geometry.CornerRadius(h / 2f), style = Stroke(line))
            val knob = h / 2f - line * 2.2f
            val x = if (listening) size.width - h / 2f else h / 2f
            drawCircle(ink, knob, Offset(x, h / 2f))
        }
        Side("LISTEN", chosen = listening, Modifier.weight(1f), TextAlign.Start) { change(true) }
    }
}

@Composable
private fun Side(word: String, chosen: Boolean, modifier: Modifier, align: TextAlign, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().lightClickable(onClick = onClick).semantics(mergeDescendants = true) {
            selected = chosen
            role = Role.RadioButton
        },
        contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        LightText(word, LightTextVariant.Button, underline = chosen, lighten = !chosen)
    }
}
