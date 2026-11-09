package com.slowpixels.memo

import com.slowpixels.memo.store.Memos
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carried over from the Kompakt's Memo: numbering, file names, renaming, a tool stopped mid-memo, a full phone. */
class MemosTest {
    private val root: File = Files.createTempDirectory("memos").toFile()
    private val now = 1_794_148_860_000L

    @After fun clean() { root.deleteRecursively() }

    private fun store(measure: (File) -> Long = { 60_000L }, mend: (File) -> Unit = {}) =
        Memos(File(root, "shared/memos"), File(root, "memos.json"), measure, mend)

    private fun record(s: Memos, at: Long = now, length: Long = 5_000L): Int {
        val m = s.begin(at)
        s.file(m).writeBytes(ByteArray(4_096))
        s.finish(m.number, length)
        return m.number
    }

    @Test fun numbersAreNeverReused() {
        val s = store()
        val a = record(s)
        val b = record(s, now + 1_000)
        s.delete(setOf(b))
        val c = record(s, now + 2_000)
        assertEquals(listOf(1, 3), s.list.value.map { it.number })
        assertEquals(1, a)
        assertEquals(3, c)
        // And after the tool opens again.
        val again = store().apply { load() }
        assertEquals(4, record(again, now + 3_000))
    }

    @Test fun aSlipOfTheFingerGivesItsNumberBack() {
        val s = store()
        record(s)
        val slip = s.begin(now + 1_000)
        s.discard(slip.number)
        assertEquals(2, record(s, now + 2_000))
        // But not once a later memo has its own number.
        val a = s.begin(now + 3_000)
        val b = s.begin(now + 4_000)
        s.discard(a.number)
        assertEquals(b.number + 1, record(s, now + 5_000))
    }

    @Test fun filesSortByDateAndSayWhatTheyAre() {
        val s = store()
        val n = record(s)
        val m = s.list.value.single()
        assertTrue(m.file, m.file.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}\\.\\d{2}\\.\\d{2} Memo $n\\.aac")))
        assertEquals("Memo $n", m.title)
        assertEquals(5_000L, m.length)
        assertFalse(m.open)
    }

    @Test fun memosLiveWhereTheToolManagerLooks() {
        val s = store()
        record(s)
        assertEquals(File(root, "shared/memos"), s.file(s.list.value.single()).parentFile)
    }

    @Test fun renamingRenamesTheFileSafely() {
        val s = store()
        val n = record(s)
        s.rename(n, "  Call: the bank / card?  ")
        val m = s.list.value.single()
        assertEquals("Call: the bank / card?", m.title)
        assertTrue(m.file, m.file.endsWith(" Call- the bank - card-.aac"))
        assertTrue(s.file(m).exists())
        assertEquals(1, s.folder.listFiles()!!.size)
        // Blank gives back the number.
        s.rename(n, " ")
        assertEquals("Memo $n", s.list.value.single().title)
        assertTrue(s.list.value.single().file.endsWith(" Memo $n.aac"))
    }

    @Test fun twoMemosInOneSecondKeepTheirOwnFiles() {
        val s = store()
        record(s)
        record(s)
        s.rename(1, "Same")
        s.rename(2, "Same")
        val files = s.list.value.map { it.file }
        assertEquals(2, files.toSet().size)
        assertTrue(files.all { File(s.folder, it).exists() })
    }

    @Test fun theListComesBackAsItWas() {
        val s = store()
        val n = record(s, length = 83_000L)
        s.rename(n, "Groceries")
        val again = store().apply { load() }
        val m = again.list.value.single()
        assertEquals("Groceries", m.name)
        assertEquals(83_000L, m.length)
        assertEquals(now, m.made)
    }

    @Test fun tidyTakesInFilesCopiedOverAndDropsOnesTakenAway() {
        val s = store()
        val gone = record(s)
        s.file(s.list.value.single()).delete()
        File(s.folder, "Interview.m4a").writeBytes(ByteArray(10_000))
        File(s.folder, "notes.txt").writeText("not audio")
        File(s.folder, ".hidden.aac").writeBytes(ByteArray(10_000))
        s.tidy()
        val m = s.list.value.single()
        assertEquals("Interview", m.title)
        assertEquals(60_000L, m.length)
        assertTrue(m.number > gone)
    }

    @Test fun aMemoCutOffByTheToolStoppingIsMendedAndKept() {
        val s = store()
        val m = s.begin(now)
        s.file(m).writeBytes(ByteArray(50_000))
        // The tool stops here, mid-memo. Next time:
        val mended = ArrayList<String>()
        val again = store(measure = { 42_000L }, mend = { mended += it.name }).apply { load() }
        assertTrue(again.list.value.single().open)
        again.tidy()
        val kept = again.list.value.single()
        assertFalse(kept.open)
        assertEquals(42_000L, kept.length)
        assertEquals(listOf(m.file), mended)
    }

    @Test fun anEmptyCutOffMemoGoes() {
        val s = store()
        val m = s.begin(now)
        s.file(m).writeBytes(ByteArray(100))
        val again = store(measure = { 0L }).apply { load() }
        again.tidy()
        assertTrue(again.list.value.isEmpty())
        assertFalse(again.file(m).exists())
    }

    @Test fun tidyLeavesTheMemoBeingRecordedAlone() {
        val s = store()
        s.load()
        // Recording has begun, but the recorder hasn't made its file yet.
        val m = s.begin(now)
        s.tidy()
        assertEquals(listOf(m.number), s.list.value.map { it.number })
        assertTrue(s.list.value.single().open)
    }

    @Test fun aFullPhoneKeepsTheOldList() {
        val s = store()
        record(s)
        val index = File(root, "memos.json")
        val before = index.readText()
        // A folder where the new list would go stands in for a write that fails.
        File(index.path + ".new").mkdirs()
        record(s, now + 1_000)
        assertEquals(before, index.readText())
        assertEquals(2, s.list.value.size)
        File(index.path + ".new").delete()
        record(s, now + 2_000)
        assertEquals(3, store().apply { load() }.list.value.size)
    }

    @Test fun aDamagedListIsReadAsNone() {
        File(root, "memos.json").writeText("{not json")
        val s = store().apply { load() }
        assertTrue(s.list.value.isEmpty())
        assertEquals(1, s.nextNumber)
    }

    @Test fun namesFromFiles() {
        assertEquals("", Memos.nameFrom("2026-11-08 14.41.07 Memo 13.aac"))
        assertEquals("Groceries", Memos.nameFrom("2026-11-08 14.41.07 Groceries.aac"))
        assertEquals("Interview", Memos.nameFrom("Interview.m4a"))
        assertNull(Memos.madeFrom("Interview.m4a"))
        assertEquals(now / 1_000 * 1_000, Memos.madeFrom(Memos.stamp(now) + " Memo 1.aac"))
    }
}
