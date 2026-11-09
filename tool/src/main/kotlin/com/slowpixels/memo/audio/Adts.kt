package com.slowpixels.memo.audio

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * AAC in ADTS frames, as Memo records it. Each frame carries its own seven-byte header, so a file stands on its own
 * however it ends: if the phone dies mid-memo, every whole frame up to then plays, and [mend] cuts off a frame left
 * half written. Plain Kotlin, so the tests can read and write it.
 */
object Adts {
    const val HEADER = 7
    const val SAMPLES_PER_FRAME = 1024
    private val RATES = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

    /** The header for one frame of AAC-LC holding [payload] bytes, written into [into]. */
    fun header(payload: Int, rate: Int, channels: Int, into: ByteArray = ByteArray(HEADER)): ByteArray {
        val length = payload + HEADER
        val freq = RATES.indexOf(rate)
        require(freq >= 0) { "ADTS has no index for $rate Hz" }
        val profile = 1 // AAC LC, object type 2, written less one
        into[0] = 0xFF.toByte()
        into[1] = 0xF1.toByte() // MPEG-4, no CRC
        into[2] = ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte()
        into[3] = (((channels and 3) shl 6) or (length shr 11)).toByte()
        into[4] = ((length shr 3) and 0xFF).toByte()
        into[5] = (((length and 7) shl 5) or 0x1F).toByte()
        into[6] = 0xFC.toByte()
        return into
    }

    /** What a walk through a file's frames found: how many, the sound they hold, and where the last whole one ends. */
    data class Walk(val frames: Long, val samples: Long, val rate: Int, val end: Long) {
        val ms: Long get() = if (rate > 0) samples * 1_000 / rate else 0L
    }

    /** Walks the frames from the start, stopping at the first thing that isn't a whole frame. */
    fun walk(file: File): Walk {
        var frames = 0L
        var samples = 0L
        var rate = 0
        var end = 0L
        val total = file.length()
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            val h = ByteArray(HEADER)
            while (true) {
                try { input.readFully(h) } catch (_: EOFException) { break }
                if ((h[0].toInt() and 0xFF) != 0xFF || (h[1].toInt() and 0xF0) != 0xF0) break
                val length = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
                val index = (h[2].toInt() shr 2) and 0xF
                if (length < HEADER || index >= RATES.size || end + length > total) break
                var left = (length - HEADER).toLong()
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) break
                    left -= skipped
                }
                if (left > 0) break
                rate = RATES[index]
                frames++
                samples += ((h[6].toInt() and 3) + 1) * SAMPLES_PER_FRAME
                end += length
            }
        }
        return Walk(frames, samples, rate, end)
    }

    /** Cuts off whatever follows the last whole frame: a frame half written when the phone stopped. True if it did. */
    fun mend(file: File): Boolean {
        if (!file.isFile) return false
        val w = walk(file)
        if (w.frames == 0L || w.end >= file.length()) return false
        RandomAccessFile(file, "rw").use { it.setLength(w.end) }
        return true
    }
}
