package io.github.vstory.notifyguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配报告的两条安全边界：release 只列 skip/fail 明细；「判定链挂上了没」不能看 `okCount`。
 *
 * `install()` 里配置与模型先于钩子 `markOk`，`okCount` 恒 ≥ 2 —— 热重载若拿它判成功，
 * 钩子全没挂上的空壳也会踢掉旧代（旧钩子已卸、新钩子没上 = 模块停摆）。
 */
class InstallReportTest {

    @Test
    fun detailLinesKeepsOnlyFailuresWhenOkIsExcluded() {
        val r = EntryHook.InstallReport("hotreload")
        r.markOk("funnel", "cls=x", "role=fallback")
        r.markSkip("ext", "reason=nms_ext_absent")
        r.markFail("nms_clinit", "reason=resolve_after_clinit", IllegalStateException("boom"))

        assertEquals(1, r.okCount)
        assertEquals(1, r.skipCount)
        assertEquals(1, r.failCount)
        assertEquals(3, r.detailLines().size)

        val kept = r.detailLines(includeOk = false)
        assertEquals(2, kept.size)
        assertTrue(kept.none { it.startsWith("hotreload.hook ok ") })
        assertTrue(kept.any { it.startsWith("hotreload.hook skip kind=ext") })
        assertTrue(kept.any { it.endsWith("err=IllegalStateException: boom") })
    }

    @Test
    fun judgingIsNotReadyBeforeAnySlotIsTaken() {
        EntryHook.reset()

        assertFalse(EntryHook.isJudgingReady())
        assertEquals(EntryHook.Slot.NONE, EntryHook.currentSlot())
    }
}
