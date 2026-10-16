package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.data.ModuleDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 累计账本：三类判定各记一笔、`block` 必须同时记进 `would`、进程重启后从文件读回而不是从记录重算
 * （记录已被裁剪，重算出来的是「窗口内一个数」，那正是这个账本要摆脱的东西）。
 */
class TallyTest {

    private fun tmpFile(): File =
        File(Files.createTempDirectory("tally").toFile(), ModuleDir.FILE_TALLY)

    @Test
    fun countsEachVerdictOnceAndBlockImpliesWould() {
        Tally.resetForTest(tmpFile())
        Tally.add(block = true, would = true, pkg = "com.example.app")
        Tally.add(block = false, would = true, pkg = "com.example.app")
        Tally.add(block = false, would = false, pkg = "com.example.app")
        assertEquals(1L, Tally.blockedTotal())
        assertEquals(2L, Tally.wouldTotal())
        assertEquals(1L, Tally.passTotal())
        assertTrue(Tally.sinceAt() > 0L)
        Tally.resetForTest(null)
    }

    @Test
    fun survivesRestartByReloadingTheFile() {
        val f = tmpFile()
        Tally.resetForTest(f)
        Tally.add(block = true, would = true, pkg = "com.example.app")
        Tally.add(block = false, would = false, pkg = "com.example.app")
        Tally.flushForTest()

        // 模拟模块端进程重启：内存清零，只剩文件
        Tally.resetForTest(f)
        assertEquals(1L, Tally.blockedTotal())
        assertEquals(1L, Tally.wouldTotal())
        assertEquals(1L, Tally.passTotal())
        assertTrue(Tally.sinceAt() > 0L)
        Tally.resetForTest(null)
    }

    @Test
    fun emptyFileYieldsZeros() {
        Tally.resetForTest(tmpFile())
        assertEquals(0L, Tally.blockedTotal())
        assertEquals(0L, Tally.wouldTotal())
        assertEquals(0L, Tally.passTotal())
        assertEquals(0L, Tally.sinceAt())
        Tally.resetForTest(null)
    }

    /** 今日拦截按本地日期走：同一天累加，跨过零点归零，而累计总量不受影响。 */
    @Test
    fun todayBlockedResetsOnDayRollover() {
        val f = tmpFile()
        Tally.resetForTest(f)
        val noon = 1_800_000_000_000L
        Tally.clock = { noon }
        Tally.add(block = true, would = true, pkg = "com.example.app")
        Tally.add(block = true, would = true, pkg = "com.example.app")
        Tally.add(block = false, would = false, pkg = "com.example.app")
        assertEquals(2L, Tally.todayBlockedTotal())

        // 跨到第二天：读的时候就得按新的一天算（不能等下一次判定）
        Tally.clock = { noon + 86_400_000L }
        assertEquals(0L, Tally.todayBlockedTotal())
        Tally.add(block = true, would = true, pkg = "com.example.app")
        assertEquals(1L, Tally.todayBlockedTotal())
        assertEquals(3L, Tally.blockedTotal())
        Tally.resetForTest(null)
    }

    /** 今日**按包名**的计数：只记真拦截、按包名分开、跨零点清零。 */
    @Test
    fun todayByPkgCountsBlocksPerPackageAndResetsOnRollover() {
        Tally.resetForTest(tmpFile())
        val noon = 1_800_000_000_000L
        Tally.clock = { noon }
        Tally.add(block = true, would = true, pkg = "com.a")
        Tally.add(block = true, would = true, pkg = "com.a")
        Tally.add(block = true, would = true, pkg = "com.b")
        // 没拦的不进排行（建议拦截的那条同理：观察模式已经去掉，这条现在只可能是异常路径）
        Tally.add(block = false, would = true, pkg = "com.c")
        Tally.add(block = false, would = false, pkg = "com.a")
        assertEquals(mapOf("com.a" to 2L, "com.b" to 1L), Tally.todayBlockedByPkg())

        Tally.clock = { noon + 86_400_000L }
        assertTrue(Tally.todayBlockedByPkg().isEmpty())
        Tally.resetForTest(null)
    }

    /** 包名缺失（拿不到 snapshot）时不能把整条判定算崩。 */
    @Test
    fun todayByPkgToleratesMissingPackage() {
        Tally.resetForTest(tmpFile())
        Tally.clock = { 1_800_000_000_000L }
        Tally.add(block = true, would = true, pkg = null)
        Tally.add(block = true, would = true, pkg = "")
        assertEquals(2L, Tally.todayBlockedTotal())
        assertTrue(Tally.todayBlockedByPkg().isEmpty())
        Tally.resetForTest(null)
    }

    /** 重启后今天还是同一天 ⇒ 今日计数接着算（按包名那份也一样）。 */
    @Test
    fun todayByPkgSurvivesRestartWithinTheSameDay() {
        val f = tmpFile()
        val noon = 1_800_000_000_000L
        Tally.resetForTest(f)
        Tally.clock = { noon }
        Tally.add(block = true, would = true, pkg = "com.a")
        Tally.flushForTest()

        Tally.resetForTest(f)
        Tally.clock = { noon + 3_600_000L }
        assertEquals(mapOf("com.a" to 1L), Tally.todayBlockedByPkg())
        Tally.resetForTest(null)
    }

    /** 重启后今天还是同一天 ⇒ 今日计数接着算。 */
    @Test
    fun todayBlockedSurvivesRestartWithinTheSameDay() {
        val f = tmpFile()
        val noon = 1_800_000_000_000L
        Tally.resetForTest(f)
        Tally.clock = { noon }
        Tally.add(block = true, would = true, pkg = "com.example.app")
        Tally.flushForTest()

        Tally.resetForTest(f)
        Tally.clock = { noon + 3_600_000L }
        assertEquals(1L, Tally.todayBlockedTotal())
        Tally.resetForTest(null)
    }
}
