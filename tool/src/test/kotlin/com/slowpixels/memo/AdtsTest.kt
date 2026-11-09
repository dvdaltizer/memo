package com.slowpixels.memo

import com.slowpixels.memo.audio.Adts
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdtsTest {
    private val root: File = Files.createTempDirectory("adts").toFile()

    @After fun clean() { root.deleteRecursively() }

    /** [n] frames of made-up AAC, as the recorder writes them: a header, then the frame. Returns where each ends. */
    private fun write(file: File, n: Int, rate: Int = 48_000): List<Long> {
        val random = Random(7)
        val ends = ArrayList<Long>()
        var at = 0L
        file.outputStream().use { out ->
            repeat(n) {
                val payload = ByteArray(200 + random.nextInt(200)).also(random::nextBytes)
                out.write(Adts.header(payload.size, rate, 1))
                out.write(payload)
                at += Adts.HEADER + payload.size
                ends += at
            }
        }
        return ends
    }

    @Test fun theHeaderIsAacLcMonoAt48k() {
        // 371 bytes of frame: FF F1, LC at index 3 (48 kHz), one channel, length 378, one block.
        val h = Adts.header(371, 48_000, 1)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x40, 0x2F, 0x5F, 0xFC.toByte()), h)
    }

    @Test fun aWalkCountsFramesAndTime() {
        val f = File(root, "memo.aac")
        write(f, 375) // 375 frames of 1024 samples at 48 kHz: 8 seconds
        val w = Adts.walk(f)
        assertEquals(375L, w.frames)
        assertEquals(48_000, w.rate)
        assertEquals(8_000L, w.ms)
        assertEquals(f.length(), w.end)
    }

    @Test fun aFrameLeftHalfWrittenIsCutOff() {
        val f = File(root, "cut.aac")
        val ends = write(f, 50)
        // The phone died partway through the last frame.
        val torn = ends[48] + 100
        RandomAccessFile(f, "rw").use { it.setLength(torn) }
        assertEquals(49L, Adts.walk(f).frames)
        assertTrue(Adts.mend(f))
        assertEquals(ends[48], f.length())
        assertEquals(49L, Adts.walk(f).frames)
        // Whole now: nothing more to do.
        assertFalse(Adts.mend(f))
    }

    @Test fun somethingElseIsLeftAlone() {
        val f = File(root, "song.aac")
        f.writeBytes(ByteArray(5_000) { 0x41 })
        assertEquals(0L, Adts.walk(f).frames)
        assertFalse(Adts.mend(f))
        assertEquals(5_000L, f.length())
    }

    @Test fun anEmptyFileIsNothing() {
        val f = File(root, "empty.aac").apply { writeBytes(ByteArray(0)) }
        assertEquals(0L, Adts.walk(f).ms)
        assertFalse(Adts.mend(f))
    }
}
