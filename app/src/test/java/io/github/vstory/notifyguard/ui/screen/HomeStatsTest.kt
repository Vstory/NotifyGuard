package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页统计的纯函数（M4g）。
 *
 * 锁的是四件最容易说错的事：**次数与组数不能混用**（500 组 ≠ 500 条）、观察模式下真拦截恒为 0
 * 且「建议拦截」要有值、排行顺序不能随输入顺序漂（否则同一份数据两次进屏显示两种顺序）、
 * **排行按今日而窗口数按全量**（排行跟着窗口走时，窗口里留着昨天的记录就会与「今日拦截」对不上）。
 */
class HomeStatsTest {

    /** 本地日期零点（真机上由 [io.github.vstory.notifyguard.judge.Tally.startOfDay] 算出）；排行按它分日。 */
    private val TODAY = 1_700_000_000_000L
    private val TODAY_NOON = TODAY + 12 * 60 * 60 * 1000L

    /** 判定链的不变量：`block` ⇒ `would`（拦下来的必然是建议拦截的）。 */
    private fun rec(
        pkg: String,
        block: Boolean = false,
        would: Boolean = false,
        count: Int = 1,
        ts: Long = TODAY_NOON,
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = null,
        text = "text",
        reason = if (block) "ai:0.91" else "below_threshold:0.20",
        would = would || block,
        block = block,
        slot = null,
        count = count,
    )

    @Test
    fun emptyListYieldsZeros() {
        val s = HomeViewModel.Stats.of(emptyList(), TODAY)
        assertEquals(0, s.groups)
        assertEquals(0, s.events)
        assertEquals(0, s.blocked)
        assertEquals(0, s.would)
        assertEquals(0, s.pass)
        assertTrue(s.top.isEmpty())
        assertEquals(0, s.otherApps)
        assertEquals(0, s.otherEvents)
    }

    @Test
    fun passedOnlyLeavesBlockedZero() {
        val s = HomeViewModel.Stats.of(listOf(rec("a"), rec("b", count = 3)), TODAY)
        assertEquals(2, s.groups)
        assertEquals(4, s.events)
        assertEquals(0, s.blocked)
        assertEquals(0, s.would)
        assertEquals(4, s.pass)
        assertTrue(s.top.isEmpty())
    }

    @Test
    fun observeModeCountsWouldNotBlocked() {
        val s = HomeViewModel.Stats.of(
            listOf(rec("a", would = true, count = 2), rec("b", would = true, count = 5)),
            TODAY,
        )
        assertEquals(0, s.blocked)
        assertEquals(7, s.would)
        assertEquals(0, s.pass)
        // 观察模式下的排行只统计真拦截 ⇒ 全空，这是有意的：这里的数不能当成「命中」
        assertTrue(s.top.isEmpty())
    }

    @Test
    fun countsEventsNotGroups() {
        val s = HomeViewModel.Stats.of(
            listOf(
                rec("a", block = true, count = 4),
                rec("a", block = true, count = 6),
                rec("b", block = true, count = 1),
                rec("c"),
            ),
            TODAY,
        )
        assertEquals(4, s.groups)
        assertEquals(12, s.events)
        assertEquals(11, s.blocked)
        assertEquals(1, s.pass)
        assertEquals(2, s.top.size)
        assertEquals("a", s.top[0].pkg)
        assertEquals(10, s.top[0].events)
    }

    @Test
    fun rankingIsStableOnTies() {
        val ties = listOf("c", "a", "b").map { rec(it, block = true, count = 2) }
        val s = HomeViewModel.Stats.of(ties, TODAY)
        assertEquals(listOf("a", "b", "c"), s.top.map { it.pkg })
        // 输入顺序反过来，结果必须一样
        val reversed = HomeViewModel.Stats.of(ties.reversed(), TODAY)
        assertEquals(s.top.map { it.pkg }, reversed.top.map { it.pkg })
    }

    @Test
    fun rankingCapsAtTopNAndReportsTheRest() {
        val many = (1..13).map { rec("pkg$it", block = true, count = it) }
        val s = HomeViewModel.Stats.of(many, TODAY)
        assertEquals(HomeViewModel.Stats.TOP_N, s.top.size)
        assertEquals(13, s.groups)
        assertEquals(3, s.otherApps)
        // 被截掉的是最小的三个：1 + 2 + 3
        assertEquals(6, s.otherEvents)
        assertEquals(91, s.blocked)
    }

    @Test
    fun rankingCountsTodayOnly() {
        val rows = listOf(
            rec("a", block = true, count = 2),
            // 零点前一毫秒：不进排行，但仍进窗口口径的 blocked
            rec("b", block = true, count = 5, ts = TODAY - 1),
            // 恰好零点算今天
            rec("c", block = true, count = 3, ts = TODAY),
        )
        val s = HomeViewModel.Stats.of(rows, TODAY)
        // 次数降序：c(3) 在 a(2) 前面
        assertEquals(listOf("c", "a"), s.top.map { it.pkg })
        assertEquals(5, s.top.sumOf { it.events })
        assertEquals(3, s.groups)
        assertEquals(10, s.blocked)
    }

    @Test
    fun rankingResetsAfterMidnight() {
        val yesterdayOnly = listOf(rec("a", block = true, count = 9, ts = TODAY - 1))
        val s = HomeViewModel.Stats.of(yesterdayOnly, TODAY)
        // 过零点清零：排行空，但窗口口径的总数还在（两行数各说各的口径）
        assertTrue(s.top.isEmpty())
        assertEquals(0, s.otherApps)
        assertEquals(0, s.otherEvents)
        assertEquals(9, s.blocked)
        assertEquals(1, s.groups)
    }

}
