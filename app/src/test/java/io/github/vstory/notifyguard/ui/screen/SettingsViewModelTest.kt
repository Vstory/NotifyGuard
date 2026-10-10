package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.DeltaStamp
import io.github.vstory.notifyguard.ai.SpamTuner
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.StatusReport
import io.github.vstory.notifyguard.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置屏与微调状态行的「事实 → 文案」映射。
 *
 * 这几段最容易说错话：熔断却显示「判定生效中」、没响应却显示「检查中」、草稿与生效值同档却提示
 * 有未保存改动。它们在界面里分支多、真机上又只在特定状态下才看得到，所以把映射抽成纯函数在这里
 * 逐条锁住（渲染成文本的那一步在 Composable 里，测不了也不需要测）。
 *
 * 断言的是资源号与参数，**不是文案**：改文案不该让测试红，改错分支才该红。
 */
class SettingsViewModelTest {

    /**
     * 自检：单测里的 R 字段必须是真实资源号。若 AGP 给单测的 R 全是 0，下面所有断言都会退化成
     * 「0 == 0」而恒过 —— 那样这套测试就成了摆设，所以先证明不同的键值确实不同。
     */
    @Test
    fun resourceIdsAreDistinctInUnitTests() {
        assertNotEquals(R.string.module_line_safe_mode, R.string.module_line_checking)
        assertNotEquals(R.string.threshold_note_ai_off, R.string.threshold_note_effective)
    }

    private fun report(
        judging: Boolean = false,
        slot: String = "",
        safeMode: Boolean = false,
        stopReason: String = "",
    ) = StatusReport(judging = judging, slot = slot, safeMode = safeMode, stopReason = stopReason)

    /**
     * 时间参数只判格式，不判字面量：格式串里的时刻随设备时区变（CI 跑在 UTC、本机在 +08:00），
     * 写死一个时刻会让这条测试在其中一个环境里必红。
     */
    private fun assertTimeArg(arg: Any?) {
        assertTrue("期望 MM-dd HH:mm 形式的时间串，实际是 $arg", arg is String && TIME_RE.matches(arg))
    }

    @Test
    fun moduleLineSeparatesNotFetchedFromNoReply() {
        assertEquals(UiText.Res(R.string.module_line_checking), SettingsViewModel.moduleLine(null, fetched = false))
        assertEquals(UiText.Res(R.string.module_line_no_reply), SettingsViewModel.moduleLine(null, fetched = true))
    }

    /** 熔断优先于其它一切：此时判定其实没在跑，说成「判定生效中」是最误导的一句。 */
    @Test
    fun safeModeWinsOverJudging() {
        val line = SettingsViewModel.moduleLine(report(judging = true, slot = "EXT_SLOT", safeMode = true), true)
        assertEquals(UiText.Res(R.string.module_line_safe_mode), line)
    }

    @Test
    fun judgingLineCarriesTheSlotLabel() {
        assertEquals(
            UiText.Res(R.string.module_line_judging, listOf(UiText.Res(R.string.module_slot_ext))),
            SettingsViewModel.moduleLine(report(judging = true, slot = "EXT_SLOT"), true),
        )
        assertEquals(
            UiText.Res(R.string.module_line_judging, listOf(UiText.Res(R.string.module_slot_funnel))),
            SettingsViewModel.moduleLine(report(judging = true, slot = "FUNNEL"), true),
        )
        // 认不出的槽位原样带出（模块端加了新槽时不许把它吞掉）
        assertEquals(
            UiText.Res(R.string.module_line_judging, listOf(UiText.Raw("EXT_SLOT_V2"))),
            SettingsViewModel.moduleLine(report(judging = true, slot = "EXT_SLOT_V2"), true),
        )
    }

    @Test
    fun stoppedJudgingReportsTheReasonVerbatim() {
        assertEquals(
            UiText.Res(R.string.module_line_stopped, listOf(UiText.Raw("异常风暴"))),
            SettingsViewModel.moduleLine(report(stopReason = "异常风暴"), true),
        )
    }

    @Test
    fun emptyStatusFieldsGiveNullLinesNotEmptyOnes() {
        assertNull(SettingsViewModel.countersText(null))
        assertNull(SettingsViewModel.detailLine(null))
        assertNull(SettingsViewModel.assemblySummary(null))
        assertNull(SettingsViewModel.safeModeLine(null))
    }

    @Test
    fun safeModeLineIsNullUnlessActuallyInSafeMode() {
        assertNull(SettingsViewModel.safeModeLine(report(judging = true)))
    }

    @Test
    fun safeModeLineCarriesTimeReasonAndTail() {
        val line = SettingsViewModel.safeModeLine(
            StatusReport(safeMode = true, safeModeAt = 1_700_000_000_000L, safeModeReason = "崩溃环路")
        ) as UiText.Res
        assertEquals(R.string.module_safe_mode_line, line.id)
        assertEquals(R.string.module_safe_mode_tail_no_watch, (line.args[2] as UiText.Res).id)
        assertEquals(UiText.Raw("崩溃环路"), line.args[1])
        val at = line.args[0] as UiText.Res
        assertEquals(R.string.module_safe_mode_at, at.id)
        assertTimeArg(at.args[0])
    }

    /** 时间与原因没记下来时不许留空，否则熔断那一行会变成「熔断触发：；。」。 */
    @Test
    fun safeModeLineFillsInMissingTimeAndReason() {
        val line = SettingsViewModel.safeModeLine(StatusReport(safeMode = true)) as UiText.Res
        assertEquals(UiText.Res(R.string.module_safe_mode_at_unknown), line.args[0])
        assertEquals(UiText.Res(R.string.module_safe_mode_reason_unknown), line.args[1])
    }

    /** 标志监听可用与否决定「清除后要不要重启系统框架」，这一句不能两态说反。 */
    @Test
    fun safeModeTailFollowsWatcherAvailability() {
        fun tail(autoRecover: Boolean): UiText {
            val line = SettingsViewModel.safeModeLine(
                StatusReport(safeMode = true, autoRecover = autoRecover)
            ) as UiText.Res
            return line.args[2] as UiText
        }
        assertEquals(UiText.Res(R.string.module_safe_mode_tail_watch), tail(true))
        assertEquals(UiText.Res(R.string.module_safe_mode_tail_no_watch), tail(false))
    }

    @Test
    fun clearNoticeFollowsTheReplyNotTheRequest() {
        assertEquals(UiText.Res(R.string.notice_clear_no_reply), SettingsViewModel.clearNotice(null, false, false))
        assertEquals(UiText.Res(R.string.notice_clear_still_safe), SettingsViewModel.clearNotice(true, false, true))
        assertEquals(UiText.Res(R.string.notice_clear_resumed), SettingsViewModel.clearNotice(false, true, false))
        assertEquals(UiText.Res(R.string.notice_clear_reinstalling), SettingsViewModel.clearNotice(false, false, true))
        assertEquals(UiText.Res(R.string.notice_clear_no_watcher), SettingsViewModel.clearNotice(false, false, false))
    }

    @Test
    fun thresholdLineWithoutLiveConfigDoesNotShowDefaults() {
        assertEquals(UiText.Res(R.string.threshold_line_no_config), SettingsViewModel.thresholdText(null, null))
    }

    @Test
    fun thresholdNoteTracksDraftAndSwitchState() {
        val cfg = Config(spamEnabled = true, threshold = 0.8)
        // 没在拖：只说「当前生效」
        assertEquals(
            UiText.Res(R.string.threshold_line, listOf("0.80", UiText.Res(R.string.threshold_note_effective))),
            SettingsViewModel.thresholdText(cfg, null),
        )
        // 拖到同档（拖动控件松手也会回调一次）：仍算没改动
        assertEquals(
            UiText.Res(R.string.threshold_line, listOf("0.80", UiText.Res(R.string.threshold_note_effective))),
            SettingsViewModel.thresholdText(cfg, 0.8f),
        )
        // 真改动了：显示草稿值，并写明松手才下发、当前生效值是多少
        assertEquals(
            UiText.Res(
                R.string.threshold_line,
                listOf("0.55", UiText.Res(R.string.threshold_note_dirty, listOf("0.80"))),
            ),
            SettingsViewModel.thresholdText(cfg, 0.55f),
        )
        // AI 关着时这个值不参与判定，优先说这件事
        assertEquals(
            UiText.Res(R.string.threshold_line, listOf("0.80", UiText.Res(R.string.threshold_note_ai_off))),
            SettingsViewModel.thresholdText(Config(spamEnabled = false, threshold = 0.8), null),
        )
    }

    @Test
    fun detailLineReportsModelAndDeltaAvailability() {
        val line = SettingsViewModel.detailLine(
            StatusReport(
                modelReady = true,
                deltaVersion = 1_700_000_000_000L,
                deltaWeights = 3,
                deltaDigest = "3f9a1c2b",
                recordsPersisted = 30L,
                recordsDropped = 2L,
            )
        ) as UiText.Res
        assertEquals(R.string.module_detail, line.id)
        val args = line.args
        assertEquals(UiText.Res(R.string.module_detail_ai_ready), args[0])
        // 微调那一行报「时刻 + 内容摘要」：版本号本体是毫秒时间戳，摘要才是「这是哪一份文件」的判据
        val delta = args[1] as UiText.Res
        assertEquals(R.string.module_detail_delta_on, delta.id)
        assertEquals(DeltaStamp.of(1_700_000_000_000L, "3f9a1c2b"), delta.args[0])
        assertEquals(3, delta.args[1])
        assertEquals(30L, args[2])
        assertEquals(2L, args[3])
    }

    /** 没有微调时不能报权重数：那会让「微调未启用」看起来像启用着一份空 delta。 */
    @Test
    fun detailLineHidesDeltaWeightsWhenNotTuned() {
        val line = SettingsViewModel.detailLine(StatusReport(deltaVersion = 0L, deltaWeights = 0)) as UiText.Res
        assertEquals(UiText.Res(R.string.module_detail_delta_off), line.args[1])
    }

    /**
     * 「点了按钮之后的回执」：内容有变化、内容一致、样本不够、模块没连上四种结局各一句 ——
     * 用户分不清「没反应」与「内容一致所以没重发」时，会一直点下去。
     */
    @Test
    fun fitNoticeReportsWhatResendDid() {
        assertEquals(
            UiText.Res(R.string.notice_fit_skipped),
            fitNotice(DeltaFitter.State.Sent(1L, 3, delivery = DeltaFitter.Delivery.SKIPPED)),
        )
        assertEquals(UiText.Res(R.string.notice_fit_sent), fitNotice(DeltaFitter.State.Sent(1L, 3)))
        assertEquals(
            UiText.Res(R.string.notice_fit_failed, listOf(UiText.Res(R.string.fit_reason_module))),
            fitNotice(DeltaFitter.State.Unavailable(DeltaFitter.Reason.ModuleDisconnected)),
        )
        assertEquals(
            UiText.Res(R.string.notice_fit_not_enough, listOf(7, SpamTuner.MIN_LABELS)),
            fitNotice(DeltaFitter.State.NotEnough(SpamTuner.Readiness(usable = 7, spam = 2, ham = 5))),
        )
        assertNull(fitNotice(DeltaFitter.State.None))
    }

    @Test
    fun fitTextSeparatesNotPulledYetFromNotDelivered() {
        // null = 还没拉；None = 拉到了，但版本号为 0（还没够样本）
        assertEquals(UiText.Res(R.string.records_fit_pending), fitText(null))
        assertEquals(
            UiText.Res(R.string.fit_none, listOf(SpamTuner.MIN_LABELS, SpamTuner.MIN_PER_CLASS)),
            fitText(DeltaFitter.State.None),
        )
    }

    @Test
    fun fitTextPassesTheReadinessNumbersThrough() {
        val readiness = SpamTuner.Readiness(usable = 7, spam = 2, ham = 5)
        assertEquals(
            UiText.Res(
                R.string.fit_not_enough,
                listOf(7, SpamTuner.MIN_LABELS, 2, SpamTuner.MIN_PER_CLASS, 5, SpamTuner.MIN_PER_CLASS),
            ),
            fitText(DeltaFitter.State.NotEnough(readiness)),
        )
    }

    @Test
    fun fitTextMapsEveryUnavailableReason() {
        fun reason(r: DeltaFitter.Reason) = (fitText(DeltaFitter.State.Unavailable(r)) as UiText.Res).args[0]
        assertEquals(UiText.Res(R.string.fit_reason_model), reason(DeltaFitter.Reason.ModelUnavailable))
        assertEquals(UiText.Res(R.string.fit_reason_module), reason(DeltaFitter.Reason.ModuleDisconnected))
        assertEquals(UiText.Res(R.string.fit_reason_write), reason(DeltaFitter.Reason.DeltaWriteFailed))
        assertEquals(UiText.Res(R.string.fit_reason_config), reason(DeltaFitter.Reason.ConfigReadFailed))
        assertEquals(UiText.Res(R.string.fit_reason_version), reason(DeltaFitter.Reason.VersionWriteFailed))
        assertEquals(
            UiText.Res(R.string.fit_error, listOf("OutOfMemoryError: null")),
            reason(DeltaFitter.Reason.FitError("OutOfMemoryError: null")),
        )
        assertEquals(
            UiText.Res(R.string.fit_state_error, listOf("IllegalStateException: x")),
            reason(DeltaFitter.Reason.StateError("IllegalStateException: x")),
        )
    }

    /** 版本号写了但文件读不到时要单独说：否则用户以为微调已经在起作用。 */
    @Test
    fun fitTextSaysWhenTheDeltaFileIsUnreadable() {
        val sent = fitText(
            DeltaFitter.State.Sent(
                version = 1_700_000_000_000L,
                weights = 128,
                digest = "3f9a1c2b",
                fittedAt = 1_699_999_000_000L,
            )
        ) as UiText.Res
        assertEquals(R.string.fit_sent, sent.id)
        assertEquals(DeltaStamp.of(1_700_000_000_000L, "3f9a1c2b"), sent.args[0])
        assertEquals(128, sent.args[1])
        // 拟合完成时间与下发时间是两个：跳过下发时只有前者会前进
        assertEquals(DeltaStamp.timeOf(1_699_999_000_000L), sent.args[2])

        val noFitTime = fitText(
            DeltaFitter.State.Sent(version = 1_700_000_000_000L, weights = 128, digest = "3f9a1c2b")
        ) as UiText.Res
        assertEquals("没有拟合时间的老记录不该拿下发时间冒充", R.string.fit_sent_no_fit_time, noFitTime.id)

        val broken = fitText(DeltaFitter.State.Sent(version = 1_700_000_000_000L, weights = -1)) as UiText.Res
        assertEquals(R.string.fit_sent_no_file, broken.id)
        // 文件读不到就没有摘要可报，只报时刻（不带一个空的 `+`）
        assertEquals(DeltaStamp.timeOf(1_700_000_000_000L), broken.args[0])
    }

    /** 内容与模块端一致 ⇒ 跳过下发，文案必须区别于「已下发」（否则用户以为又推了一次）。 */
    @Test
    fun fitTextSaysWhenTheDeltaWasSkipped() {
        val skipped = fitText(
            DeltaFitter.State.Sent(
                version = 1_700_000_000_000L,
                weights = 524,
                digest = "e1ae973d",
                fittedAt = 1_699_999_000_000L,
                delivery = DeltaFitter.Delivery.SKIPPED,
            )
        ) as UiText.Res
        assertEquals(R.string.fit_skipped, skipped.id)
        assertEquals(DeltaStamp.of(1_700_000_000_000L, "e1ae973d"), skipped.args[0])
        assertEquals(524, skipped.args[1])
        assertEquals(DeltaStamp.timeOf(1_699_999_000_000L), skipped.args[2])
    }

    /** 进度行要说「还差几条」；达标后换成「已达标」而不是消失 —— 那时 fitText 里已经没有门槛数了。 */
    @Test
    fun progressLineReportsTheShortfallAndSwitchesToMet() {
        val short = fitProgressText(SpamTuner.Readiness(8, 4, 4)) as UiText.Res
        assertEquals(R.string.learning_fit_progress_short, short.id)
        assertEquals(2, short.args[0])
        assertEquals(8, short.args[1])
        assertEquals(SpamTuner.MIN_LABELS, short.args[2])

        val met = fitProgressText(SpamTuner.Readiness(22, 12, 10)) as UiText.Res
        assertEquals(R.string.learning_fit_progress_ready, met.id)
        assertEquals(22, met.args[0])
    }

    /** 还没拉过标注、或内置 base 不可用时不显示 —— 而不是显示一行假的「0 条」。 */
    @Test
    fun progressLineIsAbsentWithoutData() {
        assertNull(fitProgressText(null))
    }

    private companion object {
        val TIME_RE = Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")
    }
}
