package com.slowpixels.memo.deck

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.slowpixels.memo.audio.FeedMic
import com.slowpixels.memo.audio.Holder
import com.slowpixels.memo.audio.PhoneMic
import com.slowpixels.memo.audio.Recorder
import com.slowpixels.memo.store.Memo
import com.slowpixels.memo.store.Memos
import com.slowpixels.memo.store.Settings
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the deck is doing. */
enum class Mode { Stopped, Playing, Recording }

/**
 * What the recorder's display shows. [listening] is the mode switch: Listen (true) or Record (false). [memo] is the memo
 * on the deck (its number): the one being recorded, or the one to listen to. [at] is how far into it, in ms: played so
 * far, or recorded so far. [note] is a problem in a few words, until the next key. [room] is how much more fits on the
 * phone. [saved] is the memo just recorded, for the display to say so until the next. [keepPitch] off plays memos as
 * tape does: slower is lower, faster is higher.
 */
data class DeckState(
    val mode: Mode = Mode.Stopped,
    val listening: Boolean = false,
    val memo: Int? = null,
    val at: Long = 0L,
    val note: String = "",
    val speed: Float = 1f,
    val room: Long = Long.MAX_VALUE,
    val saved: Int? = null,
    val keepPitch: Boolean = true,
)

/**
 * The recorder, with a mode switch as some pocket recorders have: Record or Listen. In Record there's one big key: it
 * starts a new memo, and pressed again stops and keeps it, so recording is on or off and nothing between. In Listen,
 * one memo sits on the deck: ▶ plays it, ■ stops it where it is, ◀◀ and ▶▶ skip within it while playing and go to the
 * memo before or after while stopped. Moving the switch never starts anything: to Record it stops playing, to Listen it
 * keeps the memo being recorded.
 *
 * On the Light Phone a tool may only use the mic while it's on screen, so a recording stops and is kept when Memo goes
 * into the background ([paused]). Playing goes on with the phone locked: [Holder] keeps Light's player going for it.
 * Memos are AAC in ADTS frames, which stand on their own: if the phone dies mid-memo, everything up to then plays.
 */
class Deck(private val memos: Memos, private val holder: Holder, private val settings: Settings, private val feed: () -> File?) {
    private val _state = MutableStateFlow(
        DeckState(
            memo = settings.int(KEY_MEMO, 0).takeIf { it > 0 }, at = settings.long(KEY_AT, 0L),
            speed = settings.float(KEY_SPEED, 1f).takeIf { it in SPEEDS } ?: 1f, keepPitch = settings.bool(KEY_PITCH, true),
        )
    )
    val state: StateFlow<DeckState> = _state
    private val _level = MutableStateFlow(0)
    /** The level meter while recording: lit cells, 0 to [LEVELS]. Jumps up at once and falls back slowly, as a meter does. */
    val level: StateFlow<Int> = _level
    private val s get() = _state.value
    private fun set(change: DeckState.() -> DeckState) { _state.value = _state.value.change() }

    private val main = Handler(Looper.getMainLooper())
    private val speech = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var watching = false

    // Recording.
    private var recorder: Recorder? = null
    private var recording: Int? = null

    // Playing.
    private var player: MediaPlayer? = null
    private var loaded: Int? = null
    private var preparing = false

    fun memoOf(number: Int?): Memo? = memos.list.value.firstOrNull { it.number == number }

    /** The number the memo being recorded has, or the next one will get. */
    val recordNumber: Int get() = recording ?: memos.nextNumber

    /** The face is on screen ([on]) or not: the display's second-by-second changes only run while it is. */
    fun watching(on: Boolean) {
        watching = on
        if (!on) { if (s.mode != Mode.Recording) main.removeCallbacks(tick); return }
        when (s.mode) {
            Mode.Recording -> recorder?.let { r -> set { copy(at = r.ms, room = room()) } }
            Mode.Playing -> player?.let { p -> set { copy(at = position(p)) } }
            Mode.Stopped -> set { copy(room = room()) }
        }
        tickSoon()
    }

    /** Keeps a memo on the deck: the one there if it still exists, or else the newest. */
    fun settle() {
        if (s.mode != Mode.Stopped || preparing) return
        val list = memos.list.value
        if (list.any { it.number == s.memo }) return
        release()
        set { copy(memo = list.lastOrNull()?.number, at = 0L) }
        remember()
    }

    // The mode switch.

    /**
     * Listen ([on]) or Record. Leaving Record in the middle of a memo stops it and keeps it, as sliding a recorder's
     * switch off REC does; leaving Listen stops playing where it is. Neither starts anything.
     */
    fun listen(on: Boolean) {
        clearNote()
        if (on == s.listening) return
        if (on) {
            if (s.mode == Mode.Recording) stopRecording()
            set { copy(listening = true, saved = null) }
        } else {
            stopPlaying(release = false)
            set { copy(listening = false, room = room()) }
        }
    }

    // The keys.

    /** Record's one key: starts a new memo, or stops and keeps the one recording. */
    fun record() {
        clearNote()
        if (s.listening) return
        when (s.mode) {
            Mode.Recording -> stopRecording()
            Mode.Stopped, Mode.Playing -> startRecording()
        }
    }

    /** ■, in Listen: stops playing where it is. It also keeps a memo being recorded, should one be. */
    fun stop() {
        clearNote()
        when (s.mode) {
            Mode.Recording -> stopRecording()
            Mode.Playing -> stopPlaying(release = false)
            Mode.Stopped -> if (preparing) { release(); quiet() }
        }
    }

    /** ▶, in Listen: plays the memo on the deck from where it stands. */
    fun play() {
        clearNote()
        if (!s.listening || s.mode != Mode.Stopped || preparing) return
        val m = memoOf(s.memo) ?: return
        val p = player
        if (p != null && loaded == m.number) {
            if (abs(position(p) - s.at) > 300) runCatching { p.seekTo(s.at, MediaPlayer.SEEK_CLOSEST) }
            start(p)
            return
        }
        release()
        val np = MediaPlayer()
        np.setAudioAttributes(speech)
        try {
            np.setDataSource(memos.file(m).path)
        } catch (e: Exception) {
            np.release()
            quiet()
            set { copy(note = "Can't play this memo") }
            return
        }
        np.setOnPreparedListener {
            if (player !== np) return@setOnPreparedListener
            preparing = false
            val length = np.duration.toLong()
            if (length > 0) memos.setLength(m.number, length)
            if (s.at in 1 until length - 250) runCatching { np.seekTo(s.at, MediaPlayer.SEEK_CLOSEST) }
            start(np)
        }
        np.setOnCompletionListener { if (player === np) ended() }
        np.setOnErrorListener { _, _, _ ->
            if (player === np) {
                release()
                quiet()
                set { copy(mode = if (mode == Mode.Playing) Mode.Stopped else mode, note = "Can't play this memo") }
            }
            true
        }
        np.addOnRoutingChangedListener(routing, main)
        player = np
        loaded = m.number
        preparing = true
        np.prepareAsync()
    }

    /** ◀◀: back a little while playing; while stopped, to the start of the memo, then to the memo before. [held] counts repeats. */
    fun back(held: Int = 0) {
        clearNote()
        if (!s.listening) return
        when (s.mode) {
            Mode.Playing -> jump(-step(held))
            Mode.Stopped -> if (s.at >= 1_000) moveTo(0L) else memoOf(s.memo)?.let { m ->
                val list = memos.list.value
                val i = list.indexOf(m)
                if (i > 0) select(list[i - 1].number)
            }
            else -> {}
        }
    }

    /** ▶▶: on a little while playing; while stopped, to the memo after. */
    fun ahead(held: Int = 0) {
        clearNote()
        if (!s.listening) return
        when (s.mode) {
            Mode.Playing -> jump(step(held))
            Mode.Stopped -> {
                val list = memos.list.value
                val i = list.indexOfFirst { it.number == s.memo }
                if (i in 0 until list.lastIndex) select(list[i + 1].number)
            }
            else -> {}
        }
    }

    /** Puts memo [number] on the deck in Listen, and plays it if [andPlay]. Not while recording. */
    fun select(number: Int, andPlay: Boolean = false) {
        if (s.mode == Mode.Recording) return
        if (!s.listening) set { copy(listening = true, saved = null) }
        if (number != s.memo) {
            stopPlaying(release = true)
            set { copy(memo = number, at = 0L, note = "") }
            remember()
        }
        if (andPlay && s.mode == Mode.Stopped) play()
    }

    /** Moves to [fraction] of the way through the memo on the deck (a touch on the display's bar). */
    fun seek(fraction: Float) {
        if (!s.listening) return
        val m = memoOf(s.memo) ?: return
        if (m.length <= 0L) return
        moveTo((m.length * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Erases [numbers]; the deck moves on to the memo after (or before) when its own goes. Never the one recording. */
    fun erase(numbers: Set<Int>) {
        clearNote()
        if (recording in numbers) return
        val list = memos.list.value
        if (s.memo in numbers) {
            stopPlaying(release = true)
            val i = list.indexOfFirst { it.number == s.memo }
            val after = list.drop(i + 1).firstOrNull { it.number !in numbers } ?: list.take(maxOf(i, 0)).lastOrNull { it.number !in numbers }
            set { copy(memo = after?.number, at = 0L) }
        }
        memos.delete(numbers)
        remember()
    }

    fun setSpeed(speed: Float) {
        settings.put(KEY_SPEED to speed)
        set { copy(speed = speed) }
        retune()
    }

    /** Keep pitch: on, a memo sounds the same at any speed; off, it goes lower and higher with the speed, as tape does. */
    fun setKeepPitch(on: Boolean) {
        settings.put(KEY_PITCH to on)
        set { copy(keepPitch = on) }
        retune()
    }

    // The rest of the phone.

    /** The phone took the sound (a call, an alarm, the lock screen's pause): a recording stops and is kept, playing stops where it is. */
    fun lost() {
        when (s.mode) {
            Mode.Recording -> stopRecording()
            Mode.Playing -> stopPlaying(release = false)
            Mode.Stopped -> {}
        }
    }

    /** Play from the lock screen: the memo on the deck plays, if there's one to play in Listen. */
    fun asked(): Boolean {
        if (s.mode == Mode.Playing || preparing) return true
        if (s.mode != Mode.Stopped || !s.listening || memoOf(s.memo) == null) return false
        play()
        return s.mode == Mode.Playing || preparing
    }

    /** Memo went into the background. The mic can't be used unseen, so a recording stops here and is kept; playing goes on. */
    fun paused() {
        val number = recording ?: return
        stopRecording(note = "Memo $number saved. Memo only records while it's open.")
    }

    /** A few words on the display's foot line for a moment, or until the next key. */
    fun say(text: String) {
        set { copy(note = text) }
        main.postDelayed({ if (s.note == text) set { copy(note = "") } }, 3_000)
    }

    /** Backed out of the tool: everything stops, and a recording is kept. */
    fun quietAll() {
        if (s.mode == Mode.Recording) stopRecording()
        stopPlaying(release = true)
        release()
        quiet()
    }

    /**
     * Puts a new speed or pitch into the memo playing. Only while it plays: MediaPlayer takes new settings given to a
     * stopped player as ▶, so a stopped memo gets them when ▶ starts it.
     */
    private fun retune() {
        val p = player
        if (s.mode == Mode.Playing && p != null) {
            tune(p)
            tickSoon()
        }
    }

    /**
     * Sets [p]'s speed and pitch. A pitch the phone won't take keeps the speed, and a speed it won't take plays at
     * normal speed, rather than not at all.
     */
    private fun tune(p: MediaPlayer) {
        val pitch = pitchFor(s.speed, s.keepPitch)
        if (runCatching { p.playbackParams = p.playbackParams.setSpeed(s.speed).setPitch(pitch) }.isFailure && pitch != 1f)
            runCatching { p.playbackParams = p.playbackParams.setSpeed(s.speed).setPitch(1f) }
    }

    // Recording.

    private fun startRecording() {
        stopPlaying(release = true)
        release()
        if (freeBytes() < MIN_FREE) { set { copy(note = "The phone is out of space") }; return }
        val mic = feed()?.let { FeedMic(it) } ?: PhoneMic.open()
        if (mic == null) { set { copy(note = "The microphone won't open") }; return }
        val memo = memos.begin(System.currentTimeMillis())
        val r = Recorder(memos.file(memo), mic)
        if (!r.start()) {
            memos.discard(memo.number)
            set { copy(note = "Couldn't record") }
            return
        }
        recorder = r
        recording = memo.number
        _level.value = 0
        holder.take()
        set { copy(mode = Mode.Recording, listening = false, memo = memo.number, at = 0L, note = "", room = room(), saved = null) }
        // If the tool stops mid-memo, it opens on this memo, kept up to then.
        remember()
        tickSoon()
    }

    private fun stopRecording(note: String = "") {
        val r = recorder ?: return
        val number = recording ?: return
        recorder = null
        recording = null
        val ms = r.stop()
        val failed = r.failed
        main.removeCallbacks(tick)
        _level.value = 0
        holder.drop()
        val file = memoOf(number)?.let { memos.file(it) }
        if (ms < MIN_MEMO || (file?.length() ?: 0L) < Memos.MIN_BYTES) {
            memos.discard(number)
            set {
                copy(mode = Mode.Stopped, memo = memos.list.value.lastOrNull()?.number, at = 0L, saved = null, room = room(),
                    note = if (ms < MIN_MEMO) "Too short to keep" else failed ?: "Nothing was recorded")
            }
        } else {
            memos.finish(number, ms)
            set { copy(mode = Mode.Stopped, memo = number, at = 0L, saved = number, room = room(), note = failed ?: note) }
        }
        remember()
    }

    private fun freeBytes(): Long = runCatching { StatFs(memos.folder.apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE / 2)

    /** How long the phone can go on recording, in ms. */
    fun room(): Long = maxOf(0L, freeBytes() - RESERVE) * 8_000L / Recorder.BITRATE

    // Playing.

    private fun start(p: MediaPlayer) {
        holder.take()
        tune(p)
        try {
            p.start()
        } catch (e: Exception) {
            release()
            quiet()
            set { copy(mode = Mode.Stopped, note = "Can't play this memo") }
            return
        }
        set { copy(mode = Mode.Playing, at = position(p), note = "") }
        tickSoon()
    }

    private fun stopPlaying(release: Boolean) {
        if (preparing) { release(); quiet(); return }
        val p = player
        if (s.mode == Mode.Playing && p != null) {
            runCatching { p.pause() }
            val at = position(p)
            set { copy(mode = Mode.Stopped, at = at) }
            quiet()
            remember()
        }
        if (release) release()
    }

    private fun ended() {
        if (s.mode != Mode.Playing) return
        set { copy(mode = Mode.Stopped, at = 0L) }
        quiet()
        remember()
    }

    /** Moves the playing or stopped memo to [to] ms. */
    private fun moveTo(to: Long) {
        val p = player
        when (s.mode) {
            Mode.Playing -> if (p != null) {
                runCatching { p.seekTo(to, MediaPlayer.SEEK_CLOSEST) }
                set { copy(at = to) }
                tickSoon()
            }
            Mode.Stopped -> {
                if (p != null && loaded == s.memo && !preparing) runCatching { p.seekTo(to, MediaPlayer.SEEK_CLOSEST) }
                set { copy(at = to) }
                remember()
            }
            else -> {}
        }
    }

    private fun jump(delta: Long) {
        val p = player ?: return
        val length = runCatching { p.duration.toLong() }.getOrDefault(0L)
        val to = position(p) + delta
        // Past the end, the memo's done, as if it had played out.
        if (length > 0 && to >= length - 250) { runCatching { p.pause() }; ended(); return }
        moveTo(maxOf(0L, to))
    }

    private fun position(p: MediaPlayer): Long = runCatching { p.currentPosition.toLong() }.getOrDefault(s.at)

    private fun release() {
        player?.let { runCatching { it.removeOnRoutingChangedListener(routing) }; it.release() }
        player = null
        loaded = null
        preparing = false
        lastOut = 0
    }

    /** Lets go of what playing needed: the ticks and the sound. */
    private fun quiet() {
        main.removeCallbacks(tick)
        holder.drop()
    }

    // Headphones taken off while playing stop it, rather than playing on out loud. A tool can't hear Android's own
    // "becoming noisy" news, but the player says where its sound goes.

    private var lastOut = 0
    private val routing = AudioRouting.OnRoutingChangedListener { router ->
        val type = router.routedDevice?.type ?: return@OnRoutingChangedListener
        val was = lastOut
        lastOut = type
        if (s.mode == Mode.Playing && was in PRIVATE && type !in PRIVATE) stopPlaying(release = false)
    }

    // The display's ticks: the meter and counter while recording, the counter on the second while playing and on screen.

    private val tick = Runnable { onTick() }

    private fun tickSoon() {
        main.removeCallbacks(tick)
        when (s.mode) {
            Mode.Recording -> main.postDelayed(tick, METER_MS)
            Mode.Playing -> if (watching) player?.let { p ->
                val pos = position(p)
                main.postDelayed(tick, ((1_000 - pos % 1_000) / s.speed).toLong() + 15)
            }
            else -> {}
        }
    }

    private fun onTick() {
        when (s.mode) {
            Mode.Recording -> {
                val r = recorder ?: return
                if (!r.alive) { stopRecording(); return }
                // The loudest moment since the last look, shown at once, falling back slowly.
                val shown = meterStep(_level.value, levelOf(r.takePeak()))
                if (shown != _level.value) _level.value = shown
                val at = r.ms
                if (at / 1_000 != s.at / 1_000) {
                    val room = room()
                    set { copy(at = at, room = room) }
                    if (room <= 0L) { stopRecording(note = "The phone is out of space"); return }
                }
                main.postDelayed(tick, METER_MS)
            }
            Mode.Playing -> {
                val p = player ?: return
                val pos = position(p)
                if (pos / 1_000 != s.at / 1_000) set { copy(at = pos) }
                if (watching) main.postDelayed(tick, ((1_000 - pos % 1_000) / s.speed).toLong() + 15)
            }
            else -> {}
        }
    }

    private fun clearNote() { if (s.note.isNotEmpty()) set { copy(note = "") } }

    /** Where the deck stands, for next time the tool opens. */
    private fun remember() { settings.put(KEY_MEMO to (s.memo ?: 0), KEY_AT to s.at) }

    companion object {
        /** Cells in the level meter. */
        const val LEVELS = 20
        /** The meter's pace: about sixteen looks a second. */
        private const val METER_MS = 60L
        /** How many cells the meter falls back each look, so a word stays readable for a moment. */
        private const val FALL = 1
        /** Shorter than this is a slip of the finger, not a memo. */
        const val MIN_MEMO = 1_000L
        /** Left free on the phone when a memo stops for room. */
        private const val RESERVE = 50L * 1024 * 1024
        private const val MIN_FREE = RESERVE + 10L * 1024 * 1024
        private const val KEY_MEMO = "memo"
        private const val KEY_AT = "at"
        private const val KEY_SPEED = "speed"
        private const val KEY_PITCH = "keep pitch"
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** Where the sound is only for the listener: taking these off shouldn't let a memo play on out loud. */
        private val PRIVATE = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        /**
         * The pitch a memo plays at [speed]: its own when [keepPitch], or else the speed's, as a tape's goes. Android makes
         * a pitch by playing the sound faster or slower, so a pitch that matches the speed is a tape's sound exactly.
         */
        fun pitchFor(speed: Float, keepPitch: Boolean): Float = if (keepPitch) 1f else speed

        /** How far a skip goes: 5 s at a tap, more the longer ◀◀ or ▶▶ is held. */
        fun step(held: Int): Long = when {
            held < 4 -> 5_000L
            held < 12 -> 15_000L
            else -> 60_000L
        }

        /** The meter's next reading: up at once to what was [heard], down at most [FALL] cells from what was [shown]. */
        fun meterStep(shown: Int, heard: Int): Int = maxOf(heard, shown - FALL, 0)

        /** The mic's loudest sample (0 to 32767) as lit cells: 48 dB from nothing to full. */
        fun levelOf(amplitude: Int): Int {
            if (amplitude <= 0) return 0
            val db = 20 * log10(amplitude / 32_767.0)
            return ((db + 48) / 48 * LEVELS).roundToInt().coerceIn(0, LEVELS)
        }
    }
}
