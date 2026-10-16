package io.github.vstory.notifyguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配明细的格式与裁剪口径。
 *
 * 格式（知识库《hook 点汇总日志格式规范》的写法）：`[OK]/[SKIP]/[FAIL] kind 类#方法 …`；
 * 裁剪：release 只列 skip/fail（`ok=N` 汇总已经回答了成功），debug 全列。热重载的判据不能看 `okCount`
 * （配置与模型先于钩子 markOk，恒 ≥ 2），要判 `isJudgingReady()`。
 */
class InstallReportTest {

    private fun report() = EntryHook.InstallReport().apply {
        markOk("funnel", "com.example.Foo#bar", "params=12")
        markSkip("ext", "reason=nms_ext_absent")
        markFail("nms_clinit", "reason=resolve_after_clinit", IllegalStateException("boom"))
    }

    @Test
    fun detailLinesKeepsOnlyFailuresWhenOkIsExcluded() {
        val r = report()

        assertEquals(1, r.okCount)
        assertEquals(1, r.skipCount)
        assertEquals(1, r.failCount)
        assertEquals(3, r.detailLines().size)
        assertEquals("[OK]   funnel com.example.Foo#bar params=12", r.detailLines().first())

        val kept = r.detailLines(includeOk = false)
        assertEquals(
            listOf(
                "[SKIP] ext reason=nms_ext_absent",
                "[FAIL] nms_clinit reason=resolve_after_clinit err=IllegalStateException: boom",
            ),
            kept,
        )
    }

    @Test
    fun emitDetailsRoutesFailuresToTheErrorChannel() {
        val info = mutableListOf<String>()
        val error = mutableListOf<String>()

        report().emitDetails(includeOk = true, info = info::add, error = error::add)

        assertEquals(2, info.size)
        assertTrue(info.first().startsWith("[OK]   funnel"))
        assertEquals(1, error.size)
        assertTrue(error.first().startsWith("[FAIL] nms_clinit"))
    }

    @Test
    fun judgingIsNotReadyBeforeAnySlotIsTaken() {
        EntryHook.reset()

        assertFalse(EntryHook.isJudgingReady())
        assertEquals(EntryHook.Slot.NONE, EntryHook.currentSlot())
    }
}
