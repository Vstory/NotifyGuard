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
        text = "正文${word(ts)}",
        reason = score?.let { "ai:$it" } ?: "below_threshold:0.20",
        would = would || block,
        block = block,
        slot = null,
        lastTs = ts,
        count = count,
        score = score,
        aiText = aiText,
    )

    /**
     * 文本必须与记录同源：键由归一化后的文本算出来，`text` 与 `key` 对不上时，`LabelStore` 载入
     * 时会按 `text` 重算键，测试里看到的匹配关系就与线上不是一回事。
     *
     * 另外键只认内容 ⇒ 造数据不能用固定文本，否则所有记录会落到同一个键上。
     */
    private fun label(r: LogRecord, spam: Boolean = true, at: Long = 1L) = LabelRecord(
        key = LabelRecord.keyOf(r),
        ts = r.ts,
        pkg = r.pkg,
        text = r.text.orEmpty(),
        spam = spam,
        at = at,
        modelVersion = 1,
    )

    private fun keys(records: List<LogRecord>) = records.map { LabelRecord.keyOf(it) }

    /** 归一化会滤掉数字与字母 x（与 Python 训练端同口径），所以把数字逐位映射成字母来造不同的文本。 */
    private fun word(n: Long): String = n.toString().map { 'a' + (it - '0') }.joinToString("")

    @Test
    fun pendingKeepsOnlyBlockedAndUnlabelled() {
        val blocked = rec(ts = 1_000L, block = true)
        val passed = rec(ts = 2_000L)
        val observeOnly = rec(ts = 3_000L, would = true)
        val labelled = rec(ts = 4_000L, block = true)
        val records = listOf(blocked, passed, observeOnly, labelled)
        val marks = LabelRecord.marksOf(records, listOf(label(labelled)))

        assertEquals(listOf(LabelRecord.keyOf(blocked)), keys(LearningViewModel.pendingOf(records, marks, 0L)))
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
            keys(LearningViewModel.pendingOf(records, emptyMap(), 0L)),
        )
    }

    @Test
    fun emptyMarksMeansEverythingIsPending() {
        val a = rec(ts = 1_000L, block = true)
        val b = rec(ts = 2_000L, block = true)
        val records = listOf(a, b)

        assertEquals(2, LearningViewModel.pendingOf(records, LabelRecord.marksOf(records, emptyList()), 0L).size)
    }

    /**
     * 被拦而没表态的默认就是「拦得对」：本次开机之前的不再占用待办卡，用户不必逐条确认。
     * 清空界限取开机而不是超时 —— 超时会让隔夜没看的误杀再也找不回来。
     */
    @Test
    fun pendingDropsEntriesFromBeforeBoot() {
        val before = rec(ts = 1_000L, block = true)
        val after = rec(ts = 9_000L, block = true)
        val records = listOf(before, after)

        assertEquals(
            listOf(LabelRecord.keyOf(after)),
            keys(LearningViewModel.pendingOf(records, emptyMap(), since = 5_000L)),
        )
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

    /**
     * 分组：按来源 App 收组、组间按条数降序、组内按**标注时间**倒序。
     *
     * 组间按条数是这一栏的意义所在 —— 哪个 App 在灌，不看内容先看得出来。
     */
    @Test
    fun groupsBySourceAppWithCountAndLabelTimeOrder() {
        val a1 = rec(ts = 1_000L, pkg = "com.a")
        val a2 = rec(ts = 2_000L, pkg = "com.a")
        val b1 = rec(ts = 3_000L, pkg = "com.b")
        val labels = listOf(label(a1, at = 10L), label(a2, at = 20L), label(b1, at = 30L))

        val groups = LearningViewModel.libGroupsOf(labels, LearningViewModel.LibFilter.All, "") { it }

        assertEquals(listOf("com.a", "com.b"), groups.map { it.pkg })
        assertEquals(listOf(2, 1), groups.map { it.rows.size })
        assertEquals(
            listOf(LabelRecord.keyOf(a2), LabelRecord.keyOf(a1)),
            groups[0].rows.map { it.key },
        )
    }

    /** 筛选与搜索都在分组**之前**：先收组再筛，组头上的条数会和实际展开的内容对不上。 */
    @Test
    fun filtersAndSearchesBeforeGrouping() {
        val spamRec = rec(ts = 1_000L, pkg = "com.a")
        val hamRec = rec(ts = 2_000L, pkg = "com.b")
        val labels = listOf(label(spamRec, spam = true), label(hamRec, spam = false))

        val spamOnly = LearningViewModel.libGroupsOf(labels, LearningViewModel.LibFilter.Spam, "") { it }
        assertEquals(listOf("com.a"), spamOnly.map { it.pkg })
        assertEquals(listOf(1), spamOnly.map { it.rows.size })

        // 搜索命中 App 名：组名与搜索同源，按名字搜不到会很怪（两条分属不同的 App，所以是两组）
        val byName = LearningViewModel.libGroupsOf(labels, LearningViewModel.LibFilter.All, "商店") { "应用商店" }
        assertEquals(listOf("com.a", "com.b"), byName.map { it.pkg })
    }

    /** 空搜索（含只有空白）匹配全部，否则用户清空输入框时会看到空列表。 */
    @Test
    fun blankSearchMatchesEverything() {
        val r = rec(ts = 1_000L)

        assertEquals(1, LearningViewModel.libGroupsOf(listOf(label(r)), LearningViewModel.LibFilter.All, "  ") { it }.size)
    }
}
