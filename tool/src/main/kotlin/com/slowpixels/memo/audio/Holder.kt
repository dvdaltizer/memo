package com.slowpixels.memo.audio

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Light's own player, playing silence, for what LightOS gives a player: audio focus (music pauses while you record or
 * listen, and a call stops you), a foreground service so a memo plays on with the phone locked, and the lock screen's
 * play and pause. A tool can't ask Android for any of these itself. The memo's own sound comes from the deck's
 * player, which follows this one.
 *
 * Its usage is Music, not Speech, on purpose: for speech Light's player pauses when a notification sounds, and that
 * would end a recording; for music it only turns down.
 */
class Holder(private val scope: CoroutineScope) {
    /** The phone took the sound away while the deck had it: a call, an alarm, the lock screen's pause. */
    var onLost: () -> Unit = {}
    /** Play was asked for from outside the tool (the lock screen): true if the deck took it up. */
    var onAsked: () -> Boolean = { false }

    private var player: LightAudioPlayer? = null
    private var owner: Any? = null
    private var watching: Job? = null
    private var want = false
    private var next = QUEUE

    /** A deck screen is up: a new handle on Light's player for it, joining the session if one is already playing. */
    fun attach(by: Any, audio: LightAudio, filesDir: File) {
        release()
        owner = by
        val p = runCatching { audio.newPlayer(LightPlayerSpec(usage = LightAudioUsage.Music, playback = LightAudioPlayback.Detached)) }
            .getOrNull() ?: return
        player = p
        watching = scope.launch {
            if (!p.awaitReady()) return@launch
            val silence = withContext(Dispatchers.IO) { silence(filesDir) }
            if (p.currentMediaItemIndex.value == NO_MEDIA_ITEM) p.setMediaQueue(List(QUEUE) { item(silence, it) })
            if (want) p.play()
            launch { p.isPlaying.collectLatest(::follow) }
            // Each minute the item that finished is swapped for a new one at the end, so the silence never runs out.
            launch {
                p.currentMediaItemIndex.collect { i ->
                    if (i > 0) { p.removeMediaItem(0); p.addMediaItem(item(silence, next++)) }
                }
            }
        }
    }

    fun owns(by: Any) = by === owner

    private suspend fun follow(playing: Boolean) {
        val p = player ?: return
        if (playing) {
            if (!want && !onAsked()) p.pause()
        } else {
            // Not for the blink between one item of silence and the next.
            delay(500)
            if (want) { want = false; onLost() }
        }
    }

    /** The deck is recording or playing: hold the sound for it. */
    fun take() {
        want = true
        player?.play()
    }

    /** The deck is quiet. Stopped, not paused: Light's player only gives the focus back when it stops. */
    fun drop() {
        want = false
        player?.stop()
    }

    /** The deck's screen is gone for good (backed out of the tool): stop, and let go of the player, if it's still ours. */
    fun leave(by: Any) {
        if (by !== owner) return
        want = false
        player?.stop()
        release()
    }

    private fun release() {
        watching?.cancel()
        watching = null
        player?.release()
        player = null
        owner = null
    }

    companion object {
        private const val RATE = 8_000
        private const val SECONDS = 60
        private const val QUEUE = 30

        /** A minute of 8-bit silence, written once: 480 KB. */
        fun silence(dir: File): File {
            val file = File(dir, "silence.wav")
            val data = RATE * SECONDS
            if (file.length() == 44L + data) return file
            DataOutputStream(file.outputStream().buffered()).use { out ->
                fun text(s: String) = out.writeBytes(s)
                fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
                fun short(v: Int) = out.writeShort(((v and 0xFF) shl 8) or ((v shr 8) and 0xFF))
                text("RIFF"); int(36 + data); text("WAVE")
                text("fmt "); int(16); short(1); short(1); int(RATE); int(RATE); short(1); short(8)
                text("data"); int(data)
                val quiet = ByteArray(RATE) { 0x80.toByte() }
                repeat(SECONDS) { out.write(quiet) }
            }
            return file
        }

        private fun item(file: File, n: Int) = LightAudioItem(
            source = LightAudioSource.FileSource(file),
            metadata = LightMediaMetadata(title = "Memo"),
            id = "silence-$n",
        )
    }
}
