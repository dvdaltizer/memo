package com.slowpixels.memo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Where a memo's sound comes from: the phone's mic, or in a debug build a file standing in for it. */
interface Mic {
    /** Fills [into] with up to [count] samples, mono 16-bit at [Recorder.RATE]; waits until they're there. Below 0 is a failure. */
    fun read(into: ShortArray, count: Int): Int
    fun close()
}

/** The phone's mic, set up for a voice: Android's own levelling helps a memo across a room. */
class PhoneMic private constructor(private val record: AudioRecord) : Mic {
    override fun read(into: ShortArray, count: Int) = record.read(into, 0, count, AudioRecord.READ_BLOCKING)
    override fun close() {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        /** LightOS has granted the mic before this is called. Null when it won't open. */
        @SuppressLint("MissingPermission")
        fun open(): PhoneMic? {
            for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
                val r = runCatching {
                    AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(Recorder.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        // Two seconds held by Android, so a busy moment on the phone loses nothing.
                        .setBufferSizeInBytes(Recorder.RATE * 2 * 2)
                        .build()
                }.getOrNull() ?: continue
                if (r.state == AudioRecord.STATE_INITIALIZED && runCatching { r.startRecording() }.isSuccess &&
                    r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return PhoneMic(r)
                r.release()
            }
            return null
        }
    }
}

/** Raw mono 16-bit sound at [Recorder.RATE] read from a file at the pace a mic would give it, round and round. */
class FeedMic(file: File) : Mic {
    private val samples: ShortArray = file.readBytes().let { b ->
        ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }
    private var at = 0
    private var given = 0L
    private val start = System.nanoTime()

    override fun read(into: ShortArray, count: Int): Int {
        val wait = start + (given + count) * 1_000_000_000L / Recorder.RATE - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        if (samples.isEmpty()) into.fill(0, 0, count)
        else for (i in 0 until count) { into[i] = samples[at]; if (++at >= samples.size) at = 0 }
        given += count
        return count
    }

    override fun close() {}
}

/**
 * One memo being recorded: the mic through Android's AAC encoder into ADTS frames in [file], on a thread of its own.
 * Frames reach the file about once a second, so a memo cut off by the phone dying keeps all but that last second.
 * The memo's length is counted in samples taken from the mic, so the counter and the file agree.
 */
class Recorder(private val file: File, private val mic: Mic) {
    /** Why recording stopped by itself, or null. */
    @Volatile var failed: String? = null
        private set
    @Volatile private var taken = 0L
    @Volatile private var running = true
    private val peak = AtomicInteger(0)
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    private val info = MediaCodec.BufferInfo()
    private val pending = ByteArrayOutputStream(FLUSH_BYTES * 2)
    private val head = ByteArray(Adts.HEADER)
    private var frame = ByteArray(2_048)

    /** How much has been recorded, in ms. */
    val ms: Long get() = taken * 1_000 / RATE

    /** Still going: not stopped, and nothing has gone wrong. */
    val alive: Boolean get() = running && failed == null

    /** Opens the encoder and the file and starts. False if either won't open; the mic is closed then too. */
    fun start(): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK * 2 * 4)
        }
        val c = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        }.getOrElse { Log.e(TAG, "no AAC encoder", it); mic.close(); return false }
        val out = runCatching { FileOutputStream(file) }.getOrElse { c.release(); mic.close(); return false }
        codec = c
        thread = Thread({ run(c, out) }, "memo-recorder").apply { start() }
        return true
    }

    /** The loudest sample since the last look, 0 to 32767: for the level meter. */
    fun takePeak(): Int = peak.getAndSet(0)

    /** Stops, waits for the last frames to reach the file, and gives the memo's length in ms. */
    fun stop(): Long {
        running = false
        thread?.join(3_000)
        return ms
    }

    private fun run(c: MediaCodec, out: FileOutputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(BLOCK)
        try {
            while (running) {
                val n = mic.read(pcm, BLOCK)
                if (n < 0) { failed = "The microphone stopped"; break }
                if (n == 0) continue
                var high = 0
                for (i in 0 until n) { val v = abs(pcm[i].toInt()); if (v > high) high = v }
                peak.accumulateAndGet(high) { a, b -> maxOf(a, b) }
                feed(c, out, pcm, n)
                drain(c, out, toEnd = false)
            }
            // The end of the memo: whatever the encoder still holds goes to the file.
            var queued = false
            val give = System.nanoTime() + 1_000_000_000L
            while (!queued && System.nanoTime() < give) {
                val i = c.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    c.queueInputBuffer(i, 0, 0, taken * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else drain(c, out, toEnd = false)
            }
            if (queued) drain(c, out, toEnd = true)
        } catch (e: Exception) {
            Log.e(TAG, "recording", e)
            if (failed == null) failed = if (e is IOException) "The phone is out of space" else "Recording stopped"
        } finally {
            runCatching { flush(out) }.onFailure { if (failed == null) failed = "The phone is out of space" }
            runCatching { out.fd.sync() }
            runCatching { out.close() }
            runCatching { c.stop() }
            runCatching { c.release() }
            mic.close()
            running = false
        }
    }

    /** Hands [n] samples to the encoder, over as many of its buffers as it takes. */
    private fun feed(c: MediaCodec, out: FileOutputStream, pcm: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val i = c.dequeueInputBuffer(TIMEOUT_US)
            if (i < 0) { drain(c, out, toEnd = false); continue }
            val buf = c.getInputBuffer(i) ?: continue
            buf.clear()
            val take = minOf(buf.remaining() / 2, n - off)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, take)
            c.queueInputBuffer(i, 0, take * 2, taken * 1_000_000L / RATE, 0)
            off += take
            taken += take
        }
    }

    /** Takes what the encoder has finished and writes it out as frames. [toEnd]: until the last one, a second at most. */
    private fun drain(c: MediaCodec, out: FileOutputStream, toEnd: Boolean) {
        val give = System.nanoTime() + 1_000_000_000L
        while (true) {
            val o = c.dequeueOutputBuffer(info, if (toEnd) TIMEOUT_US else 0L)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!toEnd || System.nanoTime() > give) return
                continue
            }
            if (o < 0) continue // a new output format: ADTS needs nothing from it
            val buf = c.getOutputBuffer(o)
            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!config && info.size > 0 && buf != null) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                if (frame.size < info.size) frame = ByteArray(info.size)
                buf.get(frame, 0, info.size)
                pending.write(Adts.header(info.size, RATE, 1, head))
                pending.write(frame, 0, info.size)
                if (pending.size() >= FLUSH_BYTES) flush(out)
            }
            c.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        }
    }

    private fun flush(out: FileOutputStream) {
        if (pending.size() == 0) return
        pending.writeTo(out)
        pending.reset()
    }

    companion object {
        private const val TAG = "Memo"
        const val RATE = 48_000
        /** Mono AAC at 96 kbit/s: about 43 MB an hour, plenty for a voice. */
        const val BITRATE = 96_000
        private const val BLOCK = 1_024
        private const val TIMEOUT_US = 10_000L
        /** About a second of sound. */
        private const val FLUSH_BYTES = 12_000
    }
}
