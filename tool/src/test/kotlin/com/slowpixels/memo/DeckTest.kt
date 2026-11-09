package com.slowpixels.memo

import com.slowpixels.memo.deck.Deck
import com.slowpixels.memo.ui.Say
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deck's arithmetic and the display's words: the parts that don't need a phone. */
class DeckTest {
    @Test fun keepPitchOrPlayLikeTape() {
        assertEquals(1f, Deck.pitchFor(0.5f, keepPitch = true))
        assertEquals(1f, Deck.pitchFor(2f, keepPitch = true))
        // Like tape: half speed is an octave down, double an octave up.
        assertEquals(0.5f, Deck.pitchFor(0.5f, keepPitch = false))
        assertEquals(2f, Deck.pitchFor(2f, keepPitch = false))
        assertEquals(1f, Deck.pitchFor(1f, keepPitch = false))
    }

    @Test fun skipsGrowWhileHeld() {
        assertEquals(5_000L, Deck.step(0))
        assertEquals(5_000L, Deck.step(3))
        assertEquals(15_000L, Deck.step(5))
        assertEquals(60_000L, Deck.step(20))
    }

    @Test fun theLevelMeter() {
        assertEquals(0, Deck.levelOf(0))
        assertEquals(0, Deck.levelOf(100))
        assertEquals(Deck.LEVELS, Deck.levelOf(32_767))
        assertTrue(Deck.levelOf(3_000) in 8..16)
        // Up at once, down a cell a look.
        assertEquals(15, Deck.meterStep(3, 15))
        assertEquals(14, Deck.meterStep(15, 2))
        assertEquals(0, Deck.meterStep(0, 0))
    }

    @Test fun lengthsAndTheCounter() {
        assertEquals("33 sec", Say.length(33_000))
        assertEquals("1 min", Say.length(60_000))
        assertEquals("4 min", Say.length(245_000))
        assertEquals("1 h 5 min", Say.length(3_900_000))
        assertEquals("2 h", Say.length(7_200_000))
        assertEquals("00:00", Say.counter(0))
        assertEquals("00:33", Say.counter(33_900))
        assertEquals("04:05", Say.counter(245_000))
        assertEquals("1:02:05", Say.counter(3_725_000))
        assertEquals("254 h", Say.room(254 * 3_600_000L + 1_000))
        assertEquals("38 min", Say.room(38 * 60_000L))
        assertEquals("1 minute 23 seconds", Say.spoken(83_000))
        assertEquals("0 seconds", Say.spoken(0))
    }

    @Test fun speeds() {
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"), Deck.SPEEDS.map(Say::speed))
    }
}

class VersionTest {
    @Test fun aboutShowsTheVersionInTheManifest() {
        val toml = File("lighttool.toml").takeIf { it.isFile } ?: File("tool/lighttool.toml")
        val named = Regex("""versionName\s*=\s*"([^"]+)"""").find(toml.readText())?.groupValues?.get(1)
        assertEquals(named, Version.NAME)
    }
}
