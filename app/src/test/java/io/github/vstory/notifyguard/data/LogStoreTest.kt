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

    // ---- 内容聚合（M2b）----

    /** 一条记录是一组通知：同文本同判定只留一组，[LogRecord.count]/[LogRecord.lastTs] 记住它的量。 */
    @Test
    fun repeatedTextCollapsesIntoOneGroup() {
        val s = store()
        s.addAll(listOf(dup(1), dup(2), dup(3)))
        assertEquals(1, s.size())
        val r = s.recent(1).first()
        assertEquals(1L, r.ts)
        assertEquals(3L, r.lastTs)
        assertEquals(3, r.count)
        assertEquals(3, s.rawCount())
    }

    @Test
    fun packageTextOrVerdictDifferenceKeepsGroupsApart() {
        val s = store()
        s.addAll(
            listOf(
                dup(1, text = "a"),
                dup(2, text = "b"),
                dup(3, text = "a", pkg = "com.y"),
                dup(4, text = "a", block = true),
                dup(5, text = "a", would = true),
            ),
        )
        assertEquals(5, s.size())
    }

    @Test
    fun mergeSurvivesReload() {
        store().addAll(listOf(dup(1), dup(2)))
        val r = store().recent(1).first()
        assertEquals(2, r.count)
        assertEquals(2L, r.lastTs)
    }

    /**
     * 组内原因取**最近一次**判定，与展示用的 lastTs 同源。
     *
     * 首见那条可能落在另一次配置下（总开关/阈值/模型改过）：保留它就会显示「刚发生 + 总开关关闭」，
     * 而实际最近一次判的是 below_threshold。
     */
    @Test
    fun mergeKeepsReasonOfLatestJudgment() {
        val s = store()
        s.addAll(listOf(dup(1).copy(reason = "disabled", score = null)))
        s.addAll(listOf(dup(2).copy(reason = "below_threshold:0.19", score = 0.19)))
        val r = s.recent(1).first()
        assertEquals(1L, r.ts)
        assertEquals(2L, r.lastTs)
        assertEquals(2, r.count)
        assertEquals("below_threshold:0.19", r.reason)
        assertEquals(0.19, r.score!!, 1e-9)
    }

    /** 同一批记录不保证按时间递增：基底要按 lastTs 选，不能按并入顺序。 */
    @Test
    fun mergePicksLatestByLastTsNotByOrder() {
        val s = store()
        s.addAll(listOf(dup(9).copy(reason = "disabled"), dup(3).copy(reason = "below_threshold:0.19")))
        val r = s.recent(1).first()
        assertEquals(3L, r.ts)
        assertEquals(9L, r.lastTs)
        assertEquals(2, r.count)
        assertEquals("disabled", r.reason)
    }

    /**
     * 刷新型通知（同一 App 同一条通知被更新、正文里的数值一直在变）只留一条：显示最新内容，
     * 次数与首见时间照旧累计 —— 否则每次刷新都开一个新组，记录页被同一个 App 刷屏。
     */
    @Test
    fun refreshingNotificationCollapsesToItsLatestContent() {
        val s = store()
        s.addAll(listOf(accu(100, "-448 mA"), accu(101, "-608 mA"), accu(102, "-465 mA")))
        assertEquals(1, s.size())
        val r = s.recent(1).first()
        assertEquals(100L, r.ts)
        assertEquals(102L, r.lastTs)
        assertEquals(3, r.count)
        assertEquals("-465 mA 平均：-622 mA", r.text)
    }

    /** 身份不同（不同通知 id）的同类文本不该被并到一起。 */
    @Test
    fun distinctNotificationIdentitiesStayApart() {
        val s = store()
        s.addAll(listOf(accu(1, "-448 mA", nkey = "#1"), accu(2, "-448 mA", nkey = "#2")))
        assertEquals(2, s.size())
    }

    private fun accu(ts: Long, current: String, nkey: String = "#305230424") = LogRecord(
        ts = ts,
        pkg = "com.digibites.accubattery",
        title = "电池",
        text = "$current 平均：-622 mA",
        reason = "below_threshold:0.14",
        would = false,
        block = false,
        slot = "EXT_SLOT",
        nkey = nkey,
    )

    /** M2 之前的文件没有 count/lastTs：载入时就地压实，用户不需要清空重来。 */
    @Test
    fun legacyFileIsCompactedOnLoad() {
        val one = "{\"ts\":%d,\"pkg\":\"com.x\",\"title\":\"t\",\"text\":\"same\"," +
            "\"reason\":\"no_model\",\"would\":false,\"block\":false,\"slot\":\"EXT_SLOT\"}"
        file().writeText("[${one.format(1)},${one.format(2)}]")
        val s = store()
        assertEquals(1, s.size())
        assertEquals(2, s.recent(1).first().count)
    }

    /** 裁剪淘汰最久未活跃者：首见很早但刚被刷新的组必须留下，被淘汰的是别的。 */
    @Test
    fun trimDropsLeastRecentlySeen() {
        val s = store()
        s.addAll((1..LogStore.MAX_RECORDS).map { dup(it.toLong(), text = "u$it") })
        s.addAll(listOf(dup(501L, text = "u1")))
        s.addAll(listOf(dup(502L, text = "u501")))
        assertEquals(LogStore.MAX_RECORDS, s.size())
        assertEquals(listOf("u501", "u1"), s.recent(2).map { it.text })
        assertTrue(s.recent(LogStore.MAX_RECORDS).none { it.text == "u2" })
    }

    /** 排序看最近活跃而非首见：否则一直在刷的组会沉到列表最底。 */
    @Test
    fun recentOrdersByLastSeenNotFirstSeen() {
        val s = store()
        s.addAll(listOf(dup(1, text = "a"), dup(2, text = "b")))
        s.addAll(listOf(dup(3, text = "a")))
        assertEquals(listOf("a", "b"), s.recent(2).map { it.text })
    }

    private fun file(): File = File(tmp.root, "logs.json")
    private fun store() = LogStore(file())

    private fun rec(ts: Long) = LogRecord(
        ts = ts,
        pkg = "com.x",
        title = "t",
        // 每条一个正文：同文本会被聚合，而这些用例要的是逐条独立
        text = "t$ts",
        reason = "pass",
        would = false,
        block = false,
        slot = "FUNNEL",
    )

    private fun dup(
        ts: Long,
        text: String = "same",
        pkg: String = "com.x",
        would: Boolean = false,
        block: Boolean = false,
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = "t",
        text = text,
        reason = "no_model",
        would = would,
        block = block,
        slot = "EXT_SLOT",
    )
}
