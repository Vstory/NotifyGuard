package io.github.vstory.notifyguard.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 0L

    @Before
    fun setUp() {
        dir = tmp.newFolder("safety")
        now = 1_000L
        CrashGuard.watchEnabled = false
        CrashGuard.dirOverride = dir
        CrashGuard.deathWindowMs = 30_000L
        CrashGuard.clock = { now }
        CrashGuard.onStorm = null
        CrashGuard.onTrip = null
        CrashGuard.onCleared = null
    }

    @After
    fun tearDown() {
        CrashGuard.reset()
        // trip 是异步写盘：先把标志删掉再同步，否则状态会渗到下个用例
        flag().delete()
        CrashGuard.syncFromDisk()
        CrashGuard.dirOverride = null
        CrashGuard.watchEnabled = true
        CrashGuard.clock = { System.currentTimeMillis() }
    }

    @Test
    fun twoDeathsDoNotTrip() {
        var trips = 0
        CrashGuard.onTrip = { trips++ }
        repeat(2) {
            now += 100
            CrashGuard.onSystemUiDied()
        }
        assertEquals(0, trips)
    }

    @Test
    fun thirdDeathInWindowTrips() {
        var trips = 0
        CrashGuard.onTrip = { trips++ }
        repeat(3) {
            now += 100
            CrashGuard.onSystemUiDied()
        }
        assertEquals(1, trips)
        assertTrue(waitUntil { flag().exists() })
        assertTrue(flag().readText().contains("SystemUI"))
    }

    @Test
    fun deathsOutsideWindowDoNotAccumulate() {
        var trips = 0
        CrashGuard.onTrip = { trips++ }
        repeat(3) {
            CrashGuard.onSystemUiDied()
            now += 31_000
        }
        assertEquals(0, trips)
    }

    @Test
    fun flagOnDiskMeansSafeMode() {
        flag().writeText("tripped_at=1\nreason=test\n")
        assertTrue(CrashGuard.syncFromDisk())
        assertTrue(CrashGuard.isSafeMode())
    }

    @Test
    fun clearingFlagLeavesSafeMode() {
        flag().writeText("x")
        assertTrue(CrashGuard.syncFromDisk())
        flag().delete()
        assertFalse(CrashGuard.syncFromDisk())
        assertFalse(CrashGuard.isSafeMode())
    }

    @Test
    fun deathAfterFlagIsClearedTripsAgain() {
        var trips = 0
        CrashGuard.onTrip = { trips++ }
        repeat(3) {
            now += 100
            CrashGuard.onSystemUiDied()
        }
        assertEquals(1, trips)
        // trip 是异步写盘：不等它落地就删，删的是还不存在的文件，随后的写会把它"复活"
        assertTrue(waitUntil { flag().exists() })

        // 用户删掉标志：窗口要重新起算，否则恢复瞬间会被上一次崩溃的残留记录再次触发
        flag().delete()
        assertFalse(CrashGuard.syncFromDisk())
        repeat(3) {
            now += 100
            CrashGuard.onSystemUiDied()
        }
        assertEquals(2, trips)
    }

    @Test
    fun errorStormStopsJudgingOnce() {
        var stops = 0
        CrashGuard.onStorm = { stops++ }
        repeat(60) { CrashGuard.noteError("test", RuntimeException("boom")) }
        assertEquals(1, stops)
    }

    private fun flag() = File(dir, "safe_mode")

    private fun waitUntil(cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }
}
