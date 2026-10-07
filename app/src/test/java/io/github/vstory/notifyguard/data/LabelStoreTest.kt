package io.github.vstory.notifyguard.data

import io.github.vstory.notifyguard.judge.LabelRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LabelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 同一 key 再标 = 用户改主意，覆盖而不是并存。 */
    @Test
    fun upsertSameKeyOverwrites() {
        val s = store()
        s.upsert(label("1:com.x", spam = true))
        s.upsert(label("1:com.x", spam = false))
        assertEquals(1, s.size())
        assertEquals(false, s.all().single().spam)
    }

    @Test
    fun deleteRemovesOne() {
        val s = store()
        s.upsert(label("1:com.x"))
        s.upsert(label("2:com.x"))
        assertTrue(s.delete("1:com.x"))
        assertEquals(listOf("2:com.x"), s.all().map { it.key })
    }

    /** 幂等：调用方不必先查存在性，也不该因为删了个不存在的而白写一次盘。 */
    @Test
    fun deleteMissingKeyIsSuccess() {
        val s = store()
        s.upsert(label("1:com.x"))
        assertTrue(s.delete("9:com.x"))
        assertEquals(1, s.size())
    }

    /** 被裁的必须是**标得最早**的，不是通知最早发生的（[LabelRecord.at] 而非 ts）。 */
    @Test
    fun trimsOldestByLabelTime() {
        val s = store(maxLabels = 3)
        s.upsert(label("a:com.x", at = 30))
        s.upsert(label("b:com.x", at = 10))
        s.upsert(label("c:com.x", at = 20))
        s.upsert(label("d:com.x", at = 40))
        assertEquals(listOf("a:com.x", "c:com.x", "d:com.x"), s.all().map { it.key })
    }

    @Test
    fun clearEmptiesMemoryAndDisk() {
        val s = store()
        s.upsert(label("1:com.x"))
        s.clear()
        assertEquals(0, s.size())
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun reloadKeepsLabels() {
        store().upsert(label("1:com.x"))
        assertEquals(listOf("1:com.x"), store().all().map { it.key })
    }

    @Test
    fun brokenFileIsTreatedAsEmpty() {
        file().writeText("{ not an array")
        assertEquals(0, store().size())
    }

    @Test
    fun persistLeavesNoTempFile() {
        store().upsert(label("1:com.x"))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /**
     * 顺序只承诺确定（拟合要固定入参顺序才可复现），且是**字典序**、不是时间序 ——
     * key 是字符串，`ts=10` 排在 `ts=9` 之前（`'0'` < `':'`）。
     */
    @Test
    fun allIsOrderedByKeyLexicographically() {
        val s = store()
        listOf("9:com.x", "10:com.x", "1:com.x").forEach { s.upsert(label(it)) }
        assertEquals(listOf("10:com.x", "1:com.x", "9:com.x"), s.all().map { it.key })
    }

    @Test
    fun dropsRecordsWithoutKeyOnLoad() {
        file().writeText("[{\"ts\":1,\"spam\":true},{\"key\":\"2:com.x\",\"spam\":true}]")
        assertEquals(listOf("2:com.x"), store().all().map { it.key })
    }

    private fun file(): File = File(tmp.root, "labels.json")

    private fun store(maxLabels: Int = LabelStore.MAX_LABELS) = LabelStore(file()).apply { this.maxLabels = maxLabels }

    private fun label(key: String = "1:com.x", spam: Boolean = true, at: Long = 500) =
        LabelRecord(key, ts = 100, pkg = "com.x", text = "t", spam = spam, at = at, modelVersion = 7)
}
