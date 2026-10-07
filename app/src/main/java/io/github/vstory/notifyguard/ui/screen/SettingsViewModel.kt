package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.ProtectSwitches
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.StatusClient
import io.github.vstory.notifyguard.sync.StatusReport
import io.github.vstory.notifyguard.ui.UiText
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置屏的状态与动作（M4）。
 *
 * 提交口径沿用 M1f：开关没有中间态，拨动即下发；阈值是连续量，拖动途中的中间值会被当场拿去拦通知
 * （`score >= threshold` 即拦），所以只在松手那一下下发。
 *
 * 两条路径都只替换自己负责的字段、且以**模块端读回的生效配置**为基准，绝不从界面收集全部字段整份重写 ——
 * 那会把另一方尚未刷新的改动一起带下去（同进程另有一个旧配置页可能在位）。
 *
 * M4e 起另有一条**读**通道（模块端状态回传）：它不回写配置，只是把熔断与装配情况显示出来。
 *
 * 界面文案一律以 [UiText] 回报（见 ui/UiText.kt）：在这里拼成品文本等于把语言钉在 ViewModel 里。
 */
class SettingsViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: UiText = UiText.Res(R.string.service_checking),
        /** 读回的生效配置；null = 未连接或读不出（界面按「未知」渲染，不要当默认值用）。 */
        val cfg: Config? = null,
        /** 阈值拖动中的草稿（null = 没在拖）。与生效值分开，松手前不下发。 */
        val draftThreshold: Float? = null,
        val notice: Notice? = null,
        /** 模块端状态：null 且 [statusFetched] 为真 = 拉过但没响应（不是「还没拉」）。 */
        val status: StatusReport? = null,
        val statusFetched: Boolean = false,
        val statusBusy: Boolean = false,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次也要各弹一次。 */
    data class Notice(val id: Long, val text: UiText)

    /** 六类保护开关（判定链第 3 步）。标签是界面入口，`index` 与 [ProtectSwitches] 的字段一一对应。 */
    enum class ProtectItem(@StringRes val labelRes: Int, @StringRes val noteRes: Int) {
        Call(R.string.protect_call, R.string.protect_call_note),
        Alarm(R.string.protect_alarm, R.string.protect_alarm_note),
        Navigation(R.string.protect_navigation, R.string.protect_navigation_note),
        Media(R.string.protect_media, R.string.protect_media_note),
        ForegroundService(R.string.protect_foreground_service, R.string.protect_foreground_service_note),
        Conversation(R.string.protect_conversation, R.string.protect_conversation_note),
    }

    var state by mutableStateOf(UiState())
        private set

    private var noticeSeq = 0L

    /** Binder 线程回调不能直接写 Compose 状态，一律转主线程（与旧配置页同一口径）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 订阅框架服务状态；返回注销句柄，界面离开时务必调用。 */
    fun bind(): () -> Unit = ConfigWriter.observe { svc -> main.post { onService(svc) } }

    fun setEnabled(value: Boolean) = push { it.copy(enabled = value) }

    fun setObserve(value: Boolean) = push { it.copy(observe = value) }

    fun setSpam(value: Boolean) = push { it.copy(spamEnabled = value) }

    fun setProtect(item: ProtectItem, value: Boolean) =
        push { it.copy(protect = it.protect.with(item, value)) }

    fun dragThreshold(value: Float) {
        state = state.copy(draftThreshold = value)
    }

    /**
     * 松手下发。与生效值同档时什么都不做 —— 拖动控件在松手瞬间也会回调一次，
     * 不比较就会把一次没改动的触碰变成一次写（写配置会触发模块端热更新）。
     */
    fun commitThreshold() {
        val draft = state.draftThreshold ?: return
        state = state.copy(draftThreshold = null)
        val base = ConfigWriter.load()
        if (base == null) {
            notify(UiText.Res(R.string.notice_service_disconnected))
            repaint()
            return
        }
        val value = valueOf(stepOf(draft.toDouble()))
        if (value == base.threshold) {
            repaint()
            return
        }
        val next = base.copy(threshold = value)
        if (!ConfigWriter.save(next)) {
            notify(UiText.Res(R.string.notice_delivery_failed_threshold))
            repaint()
            return
        }
        state = state.copy(cfg = next)
    }

    fun noticeShown(n: Notice) {
        if (state.notice == n) state = state.copy(notice = null)
    }

    // ===== 模块端状态（M4e）=====

    /** 拉一次模块端实时状态。回调已在主线程（[StatusClient] 保证），不用再 post。 */
    fun refreshStatus(ctx: Context) {
        if (state.statusBusy) return
        state = state.copy(statusBusy = true)
        StatusClient.fetch(ctx) { r ->
            state = state.copy(status = r, statusFetched = true, statusBusy = false)
        }
    }

    /**
     * 清除熔断标志。回执是清除后**现读的**状态 —— 所以这里不做「已恢复」的口头承诺，
     * 只按回执里的事实说（重装拦截是异步的，回执时可能还没走到）。
     */
    fun clearSafeMode(ctx: Context) {
        if (state.statusBusy) return
        state = state.copy(statusBusy = true)
        StatusClient.clearSafeMode(ctx) { r ->
            state = state.copy(status = r ?: state.status, statusFetched = true, statusBusy = false)
            notify(clearNotice(r?.safeMode, r?.judging == true, r?.autoRecover == true))
        }
    }

    // ===== 事实 → 文案的映射 =====
    // 映射本体放在 companion 里（纯函数、可单测），这里只把当前状态喂进去。
    // 这些分支是「模块端说什么，界面就说什么」的地方，最容易出现「熔断却显示正常」这类错话。

    /** 模块状态主行：模块此刻在自己生效吗、走的哪条路。 */
    fun moduleLine(): UiText = moduleLine(state.status, state.statusFetched)

    fun countersText(): UiText? = countersText(state.status)

    fun detailLine(): UiText? = detailLine(state.status)

    fun assemblySummary(): UiText? = assemblySummary(state.status)

    /** 熔断详情；没熔断返回 null。 */
    fun safeModeLine(): UiText? = safeModeLine(state.status)

    /** 阈值行的说明文字。界面只负责画。 */
    fun thresholdText(): UiText = thresholdText(state.cfg, state.draftThreshold)

    /**
     * 拨动开关即刻下发：读回已生效配置，只替换这一个字段。
     *
     * 下发失败必须**回滚到生效值**并用提示说明 —— 停在「看起来开了」的状态比失败本身更糟：
     * 用户会以为拦截已经生效。
     */
    private fun push(change: (Config) -> Config) {
        val base = ConfigWriter.load()
        if (base == null) {
            notify(UiText.Res(R.string.notice_service_disconnected))
            repaint()
            return
        }
        val next = change(base)
        if (!ConfigWriter.save(next)) {
            notify(UiText.Res(R.string.notice_delivery_failed_switch))
            repaint()
            return
        }
        state = state.copy(cfg = next, draftThreshold = null)
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = if (svc == null) {
                UiText.Res(R.string.service_disconnected)
            } else {
                UiText.Res(R.string.service_connected, listOf(svc.frameworkName, svc.frameworkVersion))
            },
            draftThreshold = null,
        )
        // 生效值只在连上时才有得读；连不上就保持 null，界面显示「未读到」而不是默认值
        repaint()
    }

    /** 以模块端读回的配置为准重绘（回滚、失败、服务状态变化都走这里）。 */
    private fun repaint() {
        state = state.copy(
            cfg = if (state.connected) ConfigWriter.load() else null,
            draftThreshold = null,
        )
    }

    private fun notify(text: UiText) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /** 阈值档位数：0.00–1.00 步长 0.01，与判定链 reason 里 %.2f 的粒度对齐（更粗就对不上数）。 */
        const val THRESHOLD_STEPS = 100

        fun stepOf(v: Double): Int = (v * THRESHOLD_STEPS).roundToInt().coerceIn(0, THRESHOLD_STEPS)

        fun valueOf(step: Int): Double = step.toDouble() / THRESHOLD_STEPS

        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

        /** 模块状态主行。分支顺序即优先级：熔断 > 判定中 > 已停用 > 未装拦截。 */
        fun moduleLine(s: StatusReport?, fetched: Boolean): UiText {
            if (s == null) {
                // 没响应与还没拉是两件事：前者要让用户去查 LSPosed，后者只该说「检查中」
                return UiText.Res(if (fetched) R.string.module_line_no_reply else R.string.module_line_checking)
            }
            return when {
                s.safeMode -> UiText.Res(R.string.module_line_safe_mode)
                s.judging -> UiText.Res(R.string.module_line_judging, listOf(slotLabel(s.slot)))
                s.stopReason.isNotEmpty() -> UiText.Res(R.string.module_line_stopped, listOf(UiText.Raw(s.stopReason)))
                else -> UiText.Res(R.string.module_line_not_installed)
            }
        }

        fun countersText(s: StatusReport?): UiText? = s?.let {
            UiText.Res(
                R.string.module_counters,
                listOf(it.extHits, it.funnelJudgeHits, it.funnelPassHits, it.romBlocked, it.errorCount),
            )
        }

        fun detailLine(s: StatusReport?): UiText? = s?.let {
            val ai = UiText.Res(
                if (it.modelReady) R.string.module_detail_ai_ready else R.string.module_detail_ai_unavailable
            )
            val delta = if (it.deltaVersion > 0) {
                UiText.Res(R.string.module_detail_delta_on, listOf(it.deltaVersion))
            } else {
                UiText.Res(R.string.module_detail_delta_off)
            }
            UiText.Res(R.string.module_detail, listOf(ai, delta, it.recordsPersisted, it.recordsDropped))
        }

        fun assemblySummary(s: StatusReport?): UiText? = s?.let {
            UiText.Res(R.string.module_assembly, listOf(it.okCount, it.skipCount, it.failCount, it.version))
        }

        fun safeModeLine(s: StatusReport?): UiText? {
            if (s == null || !s.safeMode) return null
            val at = if (s.safeModeAt > 0) {
                UiText.Res(R.string.module_safe_mode_at, listOf(timeText(s.safeModeAt)))
            } else {
                UiText.Res(R.string.module_safe_mode_at_unknown)
            }
            // 熔断原因是模块端写的原文（可能含类名、异常），不翻译
            val reason = if (s.safeModeReason.isEmpty()) {
                UiText.Res(R.string.module_safe_mode_reason_unknown)
            } else {
                UiText.Raw(s.safeModeReason)
            }
            val tail = UiText.Res(
                if (s.autoRecover) R.string.module_safe_mode_tail_watch else R.string.module_safe_mode_tail_no_watch
            )
            return UiText.Res(R.string.module_safe_mode_line, listOf(at, reason, tail))
        }

        /** 清除熔断的回执文案。`null` 表示没有回执；其余按回执里的事实说，不做口头承诺。 */
        fun clearNotice(safeMode: Boolean?, judging: Boolean, autoRecover: Boolean): UiText = UiText.Res(
            when {
                safeMode == null -> R.string.notice_clear_no_reply
                safeMode -> R.string.notice_clear_still_safe
                judging -> R.string.notice_clear_resumed
                autoRecover -> R.string.notice_clear_reinstalling
                else -> R.string.notice_clear_no_watcher
            }
        )

        fun thresholdText(cfg: Config?, draft: Float?): UiText {
            if (cfg == null) return UiText.Res(R.string.threshold_line_no_config)
            val shown = draft ?: cfg.threshold.toFloat()
            val note = when {
                !cfg.spamEnabled -> UiText.Res(R.string.threshold_note_ai_off)
                // 草稿与生效值同档不算改动（拖动控件松手也会回调一次）
                draft != null && valueOf(stepOf(draft.toDouble())) != cfg.threshold ->
                    UiText.Res(R.string.threshold_note_dirty, listOf(fmt(cfg.threshold)))

                else -> UiText.Res(R.string.threshold_note_effective)
            }
            return UiText.Res(R.string.threshold_line, listOf(fmt(shown.toDouble()), note))
        }

        fun slotLabel(slot: String): UiText = when (slot) {
            "EXT_SLOT" -> UiText.Res(R.string.module_slot_ext)
            "FUNNEL" -> UiText.Res(R.string.module_slot_funnel)
            else -> if (slot.isEmpty()) UiText.Res(R.string.module_slot_unknown) else UiText.Raw(slot)
        }

        private fun timeText(at: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(at))

        fun isProtected(p: ProtectSwitches, item: ProtectItem): Boolean = when (item) {
            ProtectItem.Call -> p.call
            ProtectItem.Alarm -> p.alarm
            ProtectItem.Navigation -> p.navigation
            ProtectItem.Media -> p.media
            ProtectItem.ForegroundService -> p.foregroundService
            ProtectItem.Conversation -> p.conversation
        }

        private fun ProtectSwitches.with(item: ProtectItem, value: Boolean): ProtectSwitches = when (item) {
            ProtectItem.Call -> copy(call = value)
            ProtectItem.Alarm -> copy(alarm = value)
            ProtectItem.Navigation -> copy(navigation = value)
            ProtectItem.Media -> copy(media = value)
            ProtectItem.ForegroundService -> copy(foregroundService = value)
            ProtectItem.Conversation -> copy(conversation = value)
        }
    }
}
