package io.github.vstory.notifyguard.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页今日排行的纯函数。
 *
 * 数据源是模块端按包名维护的今日拦截次数（过零点由模块端清零），所以这里只锁三件事：次数降序、
 * 同数按包名稳定排序（同一份数据两次进屏不能两种顺序）、只到前十且其余要报出来。
 */
class HomeRankTest {

    @Test
    fun emptyCountsYieldEmptyRanking() {
        val r = HomeViewModel.rank(emptyMap())
        assertTrue(r.top.isEmpty())
        assertEquals(0, r.otherApps)
        assertEquals(0L, r.otherEvents)
    }

    @Test
    fun ordersByCountDescending() {
        val r = HomeViewModel.rank(mapOf("a" to 2L, "b" to 7L, "c" to 5L))
        assertEquals(listOf("b", "c", "a"), r.top.map { it.pkg })
        assertEquals(listOf(7L, 5L, 2L), r.top.map { it.events })
        assertEquals(0, r.otherApps)
    }

    @Test
    fun tiesAreStableOnPackageName() {
        val r = HomeViewModel.rank(mapOf("c" to 2L, "a" to 2L, "b" to 2L))
        assertEquals(listOf("a", "b", "c"), r.top.map { it.pkg })
    }

    @Test
    fun capsAtTopNAndReportsTheRest() {
        val counts = (1..13).associate { "pkg$it" to it.toLong() }
        val r = HomeViewModel.rank(counts)
        assertEquals(HomeViewModel.TOP_N, r.top.size)
        assertEquals(13L, r.top.first().events)
        assertEquals(3, r.otherApps)
        // 被截掉的是最小的三个：1 + 2 + 3
        assertEquals(6L, r.otherEvents)
    }

    @Test
    fun zeroCountsAreIgnored() {
        // 模块端的 map 里不该出现 0，但真出现时不能占掉一个排行位
        val r = HomeViewModel.rank(mapOf("a" to 0L, "b" to 3L))
        assertEquals(listOf("b"), r.top.map { it.pkg })
        assertEquals(0, r.otherApps)
    }
}
