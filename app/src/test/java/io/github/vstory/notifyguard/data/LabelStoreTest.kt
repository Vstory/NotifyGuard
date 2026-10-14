package io.github.vstory.notifyguard.data

import io.github.vstory.notifyguard.judge.LabelRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        s.upsert(label("alpha", spam = true))
        s.upsert(label("alpha", spam = false))
        assertEquals(1, s.size())
        assertEquals(false, s.all().single().spam)
    }

    @Test
    fun deleteRemovesOne() {
        val s = store()
        s.upsert(label("alpha"))
        s.upsert(label("beta"))
        assertTrue(s.delete(LabelRecord.contentKey("alpha")))
        assertEquals(listOf(LabelRecord.contentKey("beta")), s.all().map { it.key })
    }

    /** 幂等：调用方不必先查存在性，也不该因为删了个不存在的而白写一次盘。 */
    @Test
    fun deleteMissingKeyIsSuccess() {
        val s = store()
        s.upsert(label("alpha"))
        assertTrue(s.delete(LabelRecord.contentKey("nope")))
        assertEquals(1, s.size())
    }

    /** 被裁的必须是**标得最早**的，不是通知最早发生的（[LabelRecord.at] 而非 ts）。 */
    @Test
    fun trimsOldestByLabelTime() {
        val s = store(maxLabels = 3)
        s.upsert(label("aa", at = 30))
        s.upsert(label("bb", at = 10))
        s.upsert(label("cc", at = 20))
        s.upsert(label("dd", at = 40))
        assertEquals(
            listOf("aa", "cc", "dd").map { LabelRecord.contentKey(it) },
            s.all().map { it.key },
        )
    }

    @Test
    fun clearEmptiesMemoryAndDisk() {
        val s = store()
        s.upsert(label("alpha"))
        s.clear()
        assertEquals(0, s.size())
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun reloadKeepsLabels() {
        store().upsert(label("alpha"))
        assertEquals(listOf(LabelRecord.contentKey("alpha")), store().all().map { it.key })
    }

    @Test
    fun brokenFileIsTreatedAsEmpty() {
        file().writeText("{ not an array")
        assertEquals(0, store().size())
    }

    /**
     * 坏文件必须**拒写**：此时内存快照与权威源无关，写回去就是拿空库盖掉用户已有标注。
     * 拒写的代价只是「这次改动没生效」，写进去的代价是「标注全没了」。
     */
    @Test
    fun brokenFileRejectsWritesAndKeepsTheFileIntact() {
        file().writeText("{ not an array")
        val s = store()
        assertTrue(s.isLoadFailed)
        assertFalse(s.upsert(label("alpha")))
        assertFalse(s.delete(LabelRecord.contentKey("alpha")))
        assertFalse(s.clear())
        assertEquals("{ not an array", file().readText())
    }

    /** 「不存在 / 空 / 合法空数组」都不是故障，别把正常空库也锁成只读。 */
    @Test
    fun missingOrEmptyFileIsNotALoadFailure() {
        assertFalse(store().isLoadFailed)
        file().writeText("")
        assertFalse(store().isLoadFailed)
        file().writeText("[]")
        assertFalse(store().isLoadFailed)
    }

    /** replaceAll 是显式覆盖语义（App 侧缓存专用），坏掉的缓存正该被它冲掉。 */
    @Test
    fun replaceAllRecoversABrokenCache() {
        file().writeText("{ not an array")
        assertTrue(store().replaceAll(listOf(label("alpha"))))
        assertEquals(listOf(LabelRecord.contentKey("alpha")), store().all().map { it.key })
        assertFalse(store().isLoadFailed)
    }

    /** 回执里的全量是唯一真相：替换而不是合并，否则模块端删掉的那条会一直留在缓存里。 */
    @Test
    fun replaceAllDropsWhatIsNoLongerThere() {
        val s = store()
        s.upsert(label("alpha"))
        s.upsert(label("beta"))
        s.replaceAll(listOf(label("beta")))
        assertEquals(listOf(LabelRecord.contentKey("beta")), s.all().map { it.key })
    }

    @Test
    fun replaceAllRespectsTheLimit() {
        val s = store(maxLabels = 2)
        s.replaceAll(listOf(label("aa", at = 10), label("bb", at = 20), label("cc", at = 30)))
        assertEquals(listOf("bb", "cc").map { LabelRecord.contentKey(it) }, s.all().map { it.key })
    }

    @Test
    fun persistLeavesNoTempFile() {
        store().upsert(label("alpha"))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /**
     * 顺序只承诺确定（拟合要固定入参顺序才可复现）：返回的就是按 key 排好的那份。
     * 不断言 key 之间的先后 —— key 是内容摘要，先后没有语义。
     */
    @Test
    fun allIsOrderedByKeyLexicographically() {
        val s = store()
        listOf("gamma", "alpha", "beta").forEach { s.upsert(label(it)) }
        assertEquals(s.all().map { it.key }.sorted(), s.all().map { it.key })
    }

    /**
     * 旧键（`ts:pkg:摘要`）在载入时按新判据重算：同内容的只留**最后表态**那条。
     * 这里是两条旧样本、文本相同（同一段文案先被当垃圾、后改判正常）⇒ 迁移后必须只剩一条。
     */
    @Test
    fun migratesLegacyKeysToContentKeys() {
        file().writeText(
            """
            [
              {"key":"1:com.x:ab","ts":1,"pkg":"com.x","text":"限时抢购","spam":true,"at":10,"modelVersion":7},
              {"key":"2:com.x:cd","ts":2,"pkg":"com.x","text":"限时抢购","spam":false,"at":20,"modelVersion":7},
              {"key":"3:com.y:ef","ts":3,"pkg":"com.y","text":"另有其事","spam":true,"at":30,"modelVersion":7}
            ]
            """.trimIndent()
        )
        val all = store().all()
        assertEquals(2, all.size)
        val merged = all.single { it.key == LabelRecord.contentKey("限时抢购") }
        assertEquals(false, merged.spam)
        assertEquals(20L, merged.at)
    }

    /**
     * 连文本都没有的残条算不出键 —— 丢掉，而不是让它落到一个空键上去盖掉别的样本。
     * （归一化后什么都不剩的同样丢掉：那种文本判定链根本不进 AI 段。）
     */
    @Test
    fun dropsEntriesWithoutUsableTextOnLoad() {
        file().writeText(
            """
            [
              {"ts":1,"spam":true,"text":""},
              {"ts":2,"spam":true,"text":"12 34"},
              {"key":"3:com.x","text":"保留","spam":true,"at":1,"modelVersion":0}
            ]
            """.trimIndent()
        )
        assertEquals(listOf(LabelRecord.contentKey("保留")), store().all().map { it.key })
    }

    private fun file(): File = File(tmp.root, "labels.json")

    private fun store(maxLabels: Int = LabelStore.MAX_LABELS) = LabelStore(file()).apply { this.maxLabels = maxLabels }

    private fun label(text: String = "alpha", spam: Boolean = true, at: Long = 500) =
        LabelRecord(
            key = LabelRecord.contentKey(text),
            ts = 100,
            pkg = "com.x",
            text = text,
            spam = spam,
            at = at,
            modelVersion = 7,
        )
}
