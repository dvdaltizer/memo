package com.slowpixels.memo.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One recording. [number] is given when it's recorded and never reused, so "Memo 13" stays Memo 13 after others are
 * erased. [file] is its name in the memos folder; [made] is when recording began; [length] is in ms. [open] means it
 * was still being recorded when the list was last saved.
 */
data class Memo(
    val number: Int,
    val file: String,
    val name: String = "",
    val made: Long,
    val length: Long,
    val open: Boolean = false,
) {
    val title: String get() = name.ifBlank { "Memo $number" }
}

@Serializable
private class Saved(val next: Int = 1, val memos: List<Entry> = emptyList())

@Serializable
private class Entry(val n: Int, val f: String, val name: String = "", val made: Long, val ms: Long = 0L, val open: Boolean = false)

/**
 * The memos: sound files in [folder] (the folder Light's Tool Manager shows, so they can be copied to a computer and
 * backed up), and a small list beside the tool's own files ([index]) that keeps their numbers, names and lengths. The
 * folder is the truth: a file put there from a computer joins the list, and one taken away leaves it.
 *
 * Saving survives a full phone: the list is written to a new file and then moved over the old one, so the old one stays
 * whole if the write fails, and the next change tries again. [measure] gives a file's length in ms; [mend] tidies a
 * file the tool stopped in the middle of recording.
 */
class Memos(
    val folder: File,
    private val index: File,
    private val measure: (File) -> Long = { 0L },
    private val mend: (File) -> Unit = {},
) {
    private val _list = MutableStateFlow<List<Memo>>(emptyList())
    /** Oldest first, as a recorder numbers them. */
    val list: StateFlow<List<Memo>> = _list
    private var next = 1
    private val lock = Any()
    /** Memos the saved list had as still recording: the tool stopped while they were. None of this run's own. */
    private val leftOpen = HashSet<Int>()

    /** Reads the saved list. Quick: no files are opened. [tidy] then squares it with the folder. */
    fun load() = synchronized(lock) {
        folder.mkdirs()
        val text = runCatching { index.readText() }.getOrNull() ?: return@synchronized
        runCatching {
            val saved = JSON.decodeFromString(Saved.serializer(), text)
            next = saved.next
            val read = saved.memos.map { Memo(it.n, it.f, it.name, it.made, it.ms, it.open) }
            _list.value = read.sortedWith(ORDER)
            leftOpen += read.filter { it.open }.map { it.number }
        }
    }

    /**
     * Squares the list with the folder: memos whose file is gone leave the list, sound files that aren't on it join it,
     * and a memo the tool stopped in the middle of recording is measured and kept, since what was recorded up to then is
     * in its file. A memo this run is recording is left alone. Opens files, so not on the main thread.
     */
    fun tidy() {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in AUDIO } ?: emptyList()
        synchronized(lock) {
            val names = files.mapTo(HashSet()) { it.name }
            // One being recorded now may not have its file yet.
            val kept = _list.value.filter { it.file in names || (it.open && it.number !in leftOpen) }.toMutableList()
            val known = kept.mapTo(HashSet()) { it.file }
            for (f in files.sortedBy { it.lastModified() }) if (f.name !in known) {
                kept += Memo(next++, f.name, nameFrom(f.name), madeFrom(f.name) ?: f.lastModified(), 0L)
            }
            _list.value = kept.sortedWith(ORDER)
        }
        // Lengths are measured outside the lock: a long file takes a moment.
        val unmeasured = synchronized(lock) { _list.value.filter { it.number in leftOpen || (!it.open && it.length <= 0L) } }
        for (m in unmeasured) {
            val f = File(folder, m.file)
            if (m.number in leftOpen) runCatching { mend(f) }
            val ms = runCatching { measure(f) }.getOrDefault(0L)
            synchronized(lock) {
                if (m.number in leftOpen && (ms <= 0L || f.length() < MIN_BYTES)) {
                    // Nothing usable was recorded before the tool stopped.
                    f.delete()
                    _list.value = _list.value.filter { it.number != m.number }
                } else replace(m.number) { it.copy(length = maxOf(ms, it.length), open = false) }
                leftOpen -= m.number
            }
        }
        synchronized(lock) { save() }
    }

    fun file(m: Memo) = File(folder, m.file)

    /** The number the next memo will get. */
    val nextNumber: Int get() = synchronized(lock) { next }

    /** A new memo, about to be recorded: its number, and a file name that sorts by date on a computer. */
    fun begin(now: Long): Memo = synchronized(lock) {
        folder.mkdirs()
        val n = next++
        val m = Memo(n, freeName(stamp(now), "Memo $n"), made = now, length = 0L, open = true)
        _list.value = (_list.value + m).sortedWith(ORDER)
        save()
        m
    }

    /** The memo [number] has finished recording, [length] ms long. */
    fun finish(number: Int, length: Long) = synchronized(lock) {
        replace(number) { it.copy(length = length, open = false) }
        save()
    }

    /** What the player found the length to be, where it differs from the recorder's count. */
    fun setLength(number: Int, length: Long) = synchronized(lock) {
        if (_list.value.firstOrNull { it.number == number }?.length == length) return@synchronized
        replace(number) { it.copy(length = length) }
        save()
    }

    /**
     * Calls memo [number] [name] (blank for its number again). The file is renamed to match, so it reads the same on a
     * computer; if that fails, the name is kept in the list alone.
     */
    fun rename(number: Int, name: String) = synchronized(lock) {
        val m = _list.value.firstOrNull { it.number == number } ?: return@synchronized
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        if (clean == m.name) return@synchronized
        val wanted = freeName(m.file.take(STAMP_LENGTH).takeIf { madeFrom(m.file) != null } ?: stamp(m.made), clean.ifBlank { "Memo $number" }, m.file)
        val moved = wanted == m.file || File(folder, m.file).renameTo(File(folder, wanted))
        // A backup goes by when a file last changed, so the new name is backed up too.
        if (moved && wanted != m.file) File(folder, wanted).setLastModified(System.currentTimeMillis())
        replace(number) { it.copy(name = clean, file = if (moved) wanted else it.file) }
        save()
    }

    /** Erases the memos [numbers], files and all. */
    fun delete(numbers: Set<Int>) = synchronized(lock) {
        for (m in _list.value) if (m.number in numbers) File(folder, m.file).delete()
        _list.value = _list.value.filter { it.number !in numbers }
        leftOpen -= numbers
        save()
    }

    /**
     * Throws away memo [number], just begun and not worth keeping (a slip of the finger, a recording that never
     * started). If nothing came after it, its number is given back, so the next memo doesn't skip one.
     */
    fun discard(number: Int) = synchronized(lock) {
        for (m in _list.value) if (m.number == number) File(folder, m.file).delete()
        _list.value = _list.value.filter { it.number != number }
        leftOpen -= number
        if (number == next - 1) next--
        save()
    }

    private inline fun replace(number: Int, change: (Memo) -> Memo) {
        _list.value = _list.value.map { if (it.number == number) change(it) else it }
    }

    /** A file name not yet taken: the date and time it was made, then its name ("2026-11-08 14.41.07 Groceries.aac"). */
    private fun freeName(stamp: String, title: String, own: String? = null): String {
        val base = "$stamp ${safe(title)}".trim()
        var name = "$base.aac"
        var i = 2
        while (name != own && File(folder, name).exists()) name = "$base ${i++}.aac"
        return name
    }

    private fun save() {
        val saved = Saved(next, _list.value.map { Entry(it.number, it.file, it.name, it.made, it.length, it.open) })
        val text = JSON.encodeToString(Saved.serializer(), saved)
        // A new file moved over the old one: a failed write (a full phone) leaves the old list whole. The next change
        // writes again.
        runCatching {
            index.parentFile?.mkdirs()
            val tmp = File(index.path + ".new")
            tmp.writeText(text)
            if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        }
    }

    companion object {
        /** What the folder may hold: what Memo records, and sound copied in that Android can play. */
        val AUDIO = setOf("aac", "m4a", "mp3", "wav", "ogg", "opus", "amr", "3gp", "flac")
        private val ORDER = compareBy<Memo>({ it.made }, { it.number })
        private val JSON = Json { ignoreUnknownKeys = true }
        const val MAX_NAME = 60
        /** Smaller than this is a file with no sound in it: a header and little else. */
        const val MIN_BYTES = 1_024L
        private const val STAMP_LENGTH = 19

        fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(ms))

        /** When the file was made, from a name Memo gave it. */
        fun madeFrom(name: String): Long? {
            if (name.length < STAMP_LENGTH) return null
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).apply { isLenient = false }.parse(name.take(STAMP_LENGTH))?.time
            }.getOrNull()
        }

        /** The name in a file name, without the date, the extension or a default "Memo 4". */
        fun nameFrom(file: String): String {
            var n = file.substringBeforeLast('.')
            if (madeFrom(n) != null) n = n.drop(STAMP_LENGTH).trim()
            return if (Regex("Memo \\d+( \\d+)?").matches(n)) "" else n.take(MAX_NAME)
        }

        /** A name that's safe in a file name on a phone, a Mac or a PC. */
        fun safe(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "-").trim().trimEnd('.').take(MAX_NAME)
    }
}
