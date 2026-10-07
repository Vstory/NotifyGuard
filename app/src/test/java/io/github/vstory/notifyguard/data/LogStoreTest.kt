package io.github.vstory.notifyguard.data

import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun recentIsNewestFirst() {
        val s = store()
        s.addAll(listOf(rec(1), rec(2), rec(3)))
        assertEquals(listOf(3L, 2L, 1L), s.recent(10).map { it.ts })
    }

    @Test
    fun recentRespectsLimit() {
        val s = store()
        s.addAll((1..10).map { rec(it.toLong()) })
        assertEquals(listOf(10L, 9L), s.recent(2).map { it.ts })
    }

    @Test
    fun oldestDroppedBeyondCap() {
        val s = store()
        val over = LogStore.MAX_RECORDS + 5
        s.addAll((1..over).map { rec(it.toLong()) })
        assertEquals(LogStore.MAX_RECORDS, s.size())
        assertEquals(over.toLong(), s.recent(1).first().ts)
        assertEquals((over - LogStore.MAX_RECORDS + 1).toLong(), s.recent(LogStore.MAX_RECORDS).last().ts)
    }

    @Test
    fun persistsAndReloads() {
        store().addAll(listOf(rec(7), rec(8)))
        assertEquals(listOf(8L, 7L), store().recent(5).map { it.ts })
    }

    @Test
    fun clearEmptiesBothMemoryAndDisk() {
        val s = store()
        s.addAll(listOf(rec(1)))
        s.clear()
        assertEquals(0, s.size())
        assertTrue(store().recent(5).isEmpty())
    }

    @Test
    fun brokenFileIsTreatedAsEmpty() {
        file().writeText("{ not an array")
        assertEquals(0, store().size())
    }

    @Test
    fun replaceAllOverwritesMemoryAndDisk() {
        val s = store()
        s.addAll(listOf(rec(1), rec(2)))
        s.replaceAll(listOf(rec(9)))
        assertEquals(1, s.size())
        assertEquals(listOf(9L), store().recent(5).map { it.ts })
    }

    @Test
    fun persistLeavesNoTempFile() {
        store().addAll(listOf(rec(1)))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    private fun file(): File = File(tmp.root, "logs.json")

    private fun store() = LogStore(file())

    private fun rec(ts: Long) = LogRecord(
        ts = ts,
        pkg = "com.x",
        title = "t",
        text = null,
        reason = "pass",
        would = false,
        block = false,
        slot = "FUNNEL",
    )
}
