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
        Tally.add(block = true, would = true)
        Tally.add(block = false, would = true)
        Tally.add(block = false, would = false)
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
        Tally.add(block = true, would = true)
        Tally.add(block = false, would = false)
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
}
