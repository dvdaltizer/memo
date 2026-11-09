package com.slowpixels.memo

import android.media.MediaMetadataRetriever
import com.slowpixels.memo.audio.Adts
import com.slowpixels.memo.audio.Holder
import com.slowpixels.memo.deck.Deck
import com.slowpixels.memo.store.Memos
import com.slowpixels.memo.store.Settings
import com.slowpixels.memo.ui.Notice
import com.thelightphone.sdk.audio.LightAudio
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The recorder for the life of the process, so it outlives the screens: LightOS can drop and reopen a screen whenever
 * it likes, and a memo goes on playing with the phone locked.
 */
object Hub {
    /** The folder under the tool's shared files that Light's Tool Manager shows as "Memos". */
    const val MEMOS = "memos"
    /** In a debug build, raw sound read in place of the mic (the simulator has none). */
    const val FEED = "test-feed.pcm"

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var memos: Memos
        private set
    lateinit var deck: Deck
        private set
    private val holder = Holder(scope)
    private var filesDir: File? = null

    /** Whether LightOS lets Memo use the mic: null until asked. */
    val micOk = MutableStateFlow<Boolean?>(null)
    /** Set so the list screens close themselves down to the one tagged with it ("deck" closes them all). */
    var closeUntil: String? = null

    /** Reads the memos, once. */
    fun start(dir: File) {
        if (filesDir != null) return
        filesDir = dir
        // Light's file share and Tool Manager see the tool's files/shared folder; the list of names stays private.
        memos = Memos(File(dir, "shared/$MEMOS"), File(dir, "memos.json"), ::lengthOf, { Adts.mend(it) })
        memos.load()
        deck = Deck(memos, holder, Settings(File(dir, "settings.properties")), ::feed)
        holder.onLost = deck::lost
        holder.onAsked = deck::asked
        refresh()
        scope.launch { memos.list.collect { deck.settle() } }
    }

    /** The deck screen is up ([by] is its view model): Light's player is joined for it. */
    fun attach(by: Any, audio: LightAudio, dir: File) {
        start(dir)
        holder.attach(by, audio, dir)
    }

    /** Backed out of the tool, which ends the screen Light's player hangs from: the deck stops, keeping any recording. */
    fun leave(by: Any) {
        if (holder.owns(by)) deck.quietAll()
        holder.leave(by)
    }

    /** Squares the list with the memos folder, as when a file was added or taken away with the Tool Manager. */
    fun refresh() {
        if (filesDir == null) return
        scope.launch(Dispatchers.IO) { memos.tidy() }
    }

    /** A few words along the foot of the screen for a moment. */
    fun say(text: String) = Notice.say(text)

    private fun feed(): File? = if (BuildConfig.DEBUG) filesDir?.let { File(it, FEED) }?.takeIf { it.isFile } else null
}

/** How long a sound file plays, in ms; 0 if it can't be read. Memo's own files are measured frame by frame. */
fun lengthOf(file: File): Long {
    if (file.extension.equals("aac", ignoreCase = true)) {
        val walked = runCatching { Adts.walk(file).ms }.getOrDefault(0L)
        if (walked > 0) return walked
    }
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(file.path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        runCatching { r.release() }
    }
}
