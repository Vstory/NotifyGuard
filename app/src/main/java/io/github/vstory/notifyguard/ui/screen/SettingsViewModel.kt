package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.ProtectSwitches
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.StatusClient
import io.github.vstory.notifyguard.sync.StatusReport
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
 */
class SettingsViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: String = "框架服务：检查中…",
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
    data class Notice(val id: Long, val text: String)

    /** 六类保护开关（判定链第 3 步）。标签是界面入口，`index` 与 [ProtectSwitches] 的字段一一对应。 */
    enum class ProtectItem(val label: String, val note: String) {
        Call("通话", "来电"),
        Alarm("闹钟", "闹钟"),
        Navigation("导航", "导航"),
        Media("媒体", "媒体播放"),
        ForegroundService("前台服务", "前台服务"),
        Conversation("对话", "即时通讯对话"),
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
            notify("框架服务未连接，改动没有下发")
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
            notify("下发失败，阈值已还原为生效值")
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
            notify(
                when {
                    r == null -> "清除请求没有回应（模块端没回执）"
                    r.safeMode -> "熔断标志还在：清除没成功，仍处熔断"
                    r.judging -> "已清除熔断标志，判定已恢复"
                    r.autoRecover -> "已清除熔断标志，正在重装拦截"
                    else -> "已清除熔断标志，但标志监听不可用：仍需重启系统框架才恢复判定"
                }
            )
        }
    }

    /** 模块状态主行：模块此刻在自己生效吗、走的哪条路。 */
    fun moduleLine(): String {
        val s = state.status ?: return if (state.statusFetched) {
            "模块未响应（LSPosed 里未启用本模块、装完还没重启系统框架，或状态通道没注册上）"
        } else {
            "检查中…"
        }
        return when {
            s.safeMode -> "已熔断：判定停用，通知全部放行"
            s.judging -> "判定生效中（路径：${slotLabel(s.slot)}）"
            s.stopReason.isNotEmpty() -> "判定已停用（${s.stopReason}）"
            else -> "未装拦截：见下方装配明细"
        }
    }

    fun countersText(): String {
        val s = state.status ?: return ""
        return "扩展槽命中 ${s.extHits} · 漏斗判定 ${s.funnelJudgeHits} · 漏斗直通 ${s.funnelPassHits} · " +
            "ROM 已拦 ${s.romBlocked} · 异常 ${s.errorCount}"
    }

    fun detailLine(): String {
        val s = state.status ?: return ""
        val ai = if (s.modelReady) "AI 模型就绪" else "AI 模型不可用（整段放行）"
        val delta = if (s.deltaVersion > 0) "微调 v${s.deltaVersion}" else "微调未启用"
        return "$ai · $delta · 记录已落盘 ${s.recordsPersisted} 条（缓冲丢弃 ${s.recordsDropped}）"
    }

    fun assemblySummary(): String {
        val s = state.status ?: return ""
        return "本代装配 OK ${s.okCount} / SKIP ${s.skipCount} / FAIL ${s.failCount}（模块版本 ${s.version}）"
    }

    /** 熔断详情；没熔断返回 null。 */
    fun safeModeLine(): String? {
        val s = state.status ?: return null
        if (!s.safeMode) return null
        val at = if (s.safeModeAt > 0) "时间 ${timeText(s.safeModeAt)}" else "时间未记录"
        val reason = s.safeModeReason.ifEmpty { "原因未记录" }
        val tail = if (s.autoRecover) "标志监听在跑，清除即可恢复判定。" else "标志监听不可用，清除后仍需重启系统框架。"
        return "熔断触发：$at；$reason。$tail"
    }

    private fun slotLabel(slot: String): String = when (slot) {
        "EXT_SLOT" -> "扩展槽（主路径）"
        "FUNNEL" -> "漏斗（兜底路径）"
        else -> slot.ifEmpty { "未知" }
    }

    private fun timeText(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(at))

    /** 阈值行的说明文字。在 ViewModel 里算好，界面只负责画。 */
    fun thresholdText(): String {
        val cfg = state.cfg ?: return "AI 分数阈值 未读到生效值（框架服务未连接）"
        val shown = state.draftThreshold ?: cfg.threshold.toFloat()
        val note = when {
            !cfg.spamEnabled -> "AI 未开启，不生效"
            isThresholdDirty() -> "松手下发，当前生效 ${fmt(cfg.threshold)}"
            else -> "当前生效"
        }
        return "AI 分数阈值 ${fmt(shown.toDouble())}（$note）"
    }

    private fun isThresholdDirty(): Boolean {
        val draft = state.draftThreshold ?: return false
        val base = state.cfg?.threshold ?: return false
        return valueOf(stepOf(draft.toDouble())) != base
    }

    /**
     * 拨动开关即刻下发：读回已生效配置，只替换这一个字段。
     *
     * 下发失败必须**回滚到生效值**并用提示说明 —— 停在「看起来开了」的状态比失败本身更糟：
     * 用户会以为拦截已经生效。
     */
    private fun push(change: (Config) -> Config) {
        val base = ConfigWriter.load()
        if (base == null) {
            notify("框架服务未连接，改动没有下发")
            repaint()
            return
        }
        val next = change(base)
        if (!ConfigWriter.save(next)) {
            notify("下发失败，开关已还原为生效值")
            repaint()
            return
        }
        state = state.copy(cfg = next, draftThreshold = null)
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = if (svc == null) {
                "框架服务：未连接（在 LSPosed 里启用本模块后重开本页；未连接时改动下发不了，控件已置灰）"
            } else {
                "框架服务：${svc.frameworkName} ${svc.frameworkVersion}"
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

    private fun notify(text: String) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /** 阈值档位数：0.00–1.00 步长 0.01，与判定链 reason 里 %.2f 的粒度对齐（更粗就对不上数）。 */
        const val THRESHOLD_STEPS = 100

        fun stepOf(v: Double): Int = (v * THRESHOLD_STEPS).roundToInt().coerceIn(0, THRESHOLD_STEPS)

        fun valueOf(step: Int): Double = step.toDouble() / THRESHOLD_STEPS

        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

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
