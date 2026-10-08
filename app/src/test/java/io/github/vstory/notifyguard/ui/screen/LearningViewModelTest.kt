package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习屏的清单口径（M4h）。
 *
 * 锁三件最容易说错的事：**待处理只收「被拦且未标注」**（观察模式下拦为空、它就该是空的）、
 * **有 AI 判定只认 score 字段**（解析 reason 前缀会在 reason 格式变动时静默漏掉整卡）、
 * **排序不随输入顺序漂**（同一份数据两次进屏给出两种顺序）。
 */
class LearningViewModelTest {

    /** 判定链的不变量：`block` ⇒ `would`。 */
    private fun rec(
        ts: Long = 1_000L,
        pkg: String = "a",
        block: Boolean = false,
        would: Boolean = false,
        score: Double? = null,
        count: Int = 1,
        aiText: String? = null,
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = null,
        text = "text",
        reason = score?.let { "ai:$it" } ?: "below_threshold:0.20",
        would = would || block,
        block = block,
        slot = null,
        lastTs = ts,
        count = count,
        score = score,
        aiText = aiText,
    )

    private fun label(r: LogRecord, spam: Boolean = true, at: Long = 1L) = LabelRecord(
        key = LabelRecord.keyOf(r),
        ts = r.ts,
        pkg = r.pkg,
        text = "text",
        spam = spam,
        at = at,
        modelVersion = 1,
    )

    private fun keys(records: List<LogRecord>) = records.map { LabelRecord.keyOf(it) }

    @Test
    fun pendingKeepsOnlyBlockedAndUnlabelled() {
        val blocked = rec(ts = 1_000L, block = true)
        val passed = rec(ts = 2_000L)
        val observeOnly = rec(ts = 3_000L, would = true)
        val labelled = rec(ts = 4_000L, block = true)
        val records = listOf(blocked, passed, observeOnly, labelled)
        val marks = LabelRecord.marksOf(records, listOf(label(labelled)))

        assertEquals(listOf(LabelRecord.keyOf(blocked)), keys(LearningViewModel.pendingOf(records, marks)))
    }

    /** 一条记录是一组通知，排序取最近一次（[LogRecord.lastTs]），否则一直在刷的组会沉到最底。 */
    @Test
    fun pendingIsNewestFirst() {
        val old = rec(ts = 1_000L, block = true)
        val mid = rec(ts = 2_000L, block = true)
        val new = rec(ts = 3_000L, block = true)
        val records = listOf(mid, old, new)

        assertEquals(
            listOf(LabelRecord.keyOf(new), LabelRecord.keyOf(mid), LabelRecord.keyOf(old)),
            keys(LearningViewModel.pendingOf(records, emptyMap())),
        )
    }

    @Test
    fun emptyMarksMeansEverythingIsPending() {
        val a = rec(ts = 1_000L, block = true)
        val b = rec(ts = 2_000L, block = true)
        val records = listOf(a, b)

        assertEquals(2, LearningViewModel.pendingOf(records, LabelRecord.marksOf(records, emptyList())).size)
    }

    @Test
    fun aiRowsKeepOnlyRecordsWithAScore() {
        val scored = rec(ts = 1_000L, score = 0.91)
        val newerUnscored = rec(ts = 5_000L)
        val olderScored = rec(ts = 500L, score = 0.12)
        val records = listOf(scored, newerUnscored, olderScored)

        assertTrue(LearningViewModel.hasAiVerdict(scored))
        assertEquals(
            listOf(LabelRecord.keyOf(scored), LabelRecord.keyOf(olderScored)),
            keys(LearningViewModel.aiRowsOf(records)),
        )
    }

    @Test
    fun orphansAreLabelsWhoseRecordLeftTheWindow() {
        val kept = rec(ts = 1_000L)
        val dropped = rec(ts = 2_000L)
        val records = listOf(kept)

        val orphans = LearningViewModel.orphansOf(records, listOf(label(kept), label(dropped, at = 9L)))

        assertEquals(listOf(LabelRecord.keyOf(dropped)), orphans.map { it.key })
    }

    /** `at` 相同的标注按 key 兜底排序：只按时间排会让同刻标注的顺序随输入顺序漂。 */
    @Test
    fun orphanOrderIsStableOnTies() {
        val r1 = rec(ts = 1_000L, pkg = "a")
        val r2 = rec(ts = 2_000L, pkg = "b")
        val r3 = rec(ts = 3_000L, pkg = "c")
        val labels = listOf(label(r2, at = 5L), label(r1, at = 5L), label(r3, at = 5L))

        val first = LearningViewModel.orphansOf(emptyList(), labels).map { it.key }
        val reversed = LearningViewModel.orphansOf(emptyList(), labels.reversed()).map { it.key }

        assertEquals(first, reversed)
        assertEquals(listOf(LabelRecord.keyOf(r1), LabelRecord.keyOf(r2), LabelRecord.keyOf(r3)), first)
    }

    @Test
    fun noOrphansWhenEveryLabelStillHasItsRecord() {
        val r1 = rec(ts = 1_000L, pkg = "a")
        val r2 = rec(ts = 2_000L, pkg = "b")

        assertTrue(LearningViewModel.orphansOf(listOf(r1, r2), listOf(label(r1), label(r2))).isEmpty())
    }

    /** 记录还在窗口里时，标注按记录派生：文本取判定实际用过的 `aiText`，而不是库里那份。 */
    @Test
    fun labelForUsesTheRecordWhileItIsStillInTheWindow() {
        val r = rec(ts = 1_000L, block = true, aiText = "fresh")
        val stale = label(r, spam = false).copy(text = "stale")

        val out = LearningViewModel.labelFor(listOf(r), listOf(stale), LabelRecord.keyOf(r), true, 99L, 7)

        assertEquals("fresh", out?.text)
        assertEquals(true, out?.spam)
        assertEquals(99L, out?.at)
        assertEquals(7, out?.modelVersion)
    }

    /**
     * 记录已离开窗口时改判：只翻方向，身份四件（key / ts / pkg / text）逐字保留。
     *
     * 翻方向若顺手重算 key，库里会同时留下方向相反的两条样本（而界面只看得到一条）。
     */
    @Test
    fun flipKeepsIdentityAndOnlyChangesTheDirection() {
        val gone = rec(ts = 1_000L, pkg = "a")
        val orphan = label(gone, spam = true, at = 5L).copy(text = "kept-text", modelVersion = 1)

        val out = LearningViewModel.labelFor(emptyList(), listOf(orphan), orphan.key, false, 42L, 9)

        assertEquals(orphan.key, out?.key)
        assertEquals(orphan.ts, out?.ts)
        assertEquals(orphan.pkg, out?.pkg)
        assertEquals("kept-text", out?.text)
        assertEquals(false, out?.spam)
        assertEquals(42L, out?.at)
        assertEquals(9, out?.modelVersion)
    }

    /** 库里没有这个 key（记录与标注都没有）时不给目标：界面不该发出这条指令。 */
    @Test
    fun noTargetMeansNoLabel() {
        val r = rec(ts = 1_000L)

        assertEquals(null, LearningViewModel.labelFor(listOf(r), listOf(label(r)), "1:a:deadbeef", true, 1L, 1))
    }

    /** 改判刷新 `at` ⇒ 这条排到孤儿列表最前（淘汰依据也是 `at`，刚表过态的不该还在队首等淘汰）。 */
    @Test
    fun flippedOrphanMovesToTheFront() {
        val a = rec(ts = 1_000L, pkg = "a")
        val b = rec(ts = 2_000L, pkg = "b")
        val labels = listOf(label(a, at = 5L), label(b, at = 9L))

        val flipped = LearningViewModel.labelFor(emptyList(), labels, LabelRecord.keyOf(a), false, 20L, 1)
        val after = labels.map { if (it.key == flipped?.key) flipped!! else it }

        assertEquals(
            listOf(LabelRecord.keyOf(a), LabelRecord.keyOf(b)),
            LearningViewModel.orphansOf(emptyList(), after).map { it.key },
        )
    }
}
