package io.github.vstory.notifyguard.ui.screen

import androidx.compose.runtime.Composable
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.SpamTuner
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.ui.UiText
import io.github.vstory.notifyguard.ui.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微调状态行的渲染。
 *
 * 记录屏的状态卡与「刷新」后的提示共用这一个函数：同一份事实两处各拼一遍，迟早会拼出两种说法
 * （改文案只改到一处，另一处就成了假话）。
 *
 * 「事实 → 资源号 + 参数」这段是不带 Compose 的纯函数，解析成文本只发生在 [fitLine]：
 * 分支判断因此可以单测，而 Composable 里测不了。
 */
internal fun fitText(state: DeltaFitter.State?): UiText = when (state) {
    // null = 还没拉过，与「拉到了但没下发」不是一回事
    null -> UiText.Res(R.string.records_fit_pending)

    is DeltaFitter.State.None ->
        UiText.Res(R.string.fit_none, listOf(SpamTuner.MIN_LABELS, SpamTuner.MIN_PER_CLASS))

    is DeltaFitter.State.NotEnough -> UiText.Res(
        R.string.fit_not_enough,
        listOf(
            state.readiness.usable, SpamTuner.MIN_LABELS,
            state.readiness.spam, SpamTuner.MIN_PER_CLASS,
            state.readiness.ham, SpamTuner.MIN_PER_CLASS,
        ),
    )

    is DeltaFitter.State.Unavailable ->
        UiText.Res(R.string.fit_unavailable_line, listOf(fitReason(state.reason)))

    is DeltaFitter.State.Sent -> if (state.weights >= 0) {
        UiText.Res(R.string.fit_sent, listOf(fitTime(state.version), state.weights))
    } else {
        UiText.Res(R.string.fit_sent_no_file, listOf(fitTime(state.version)))
    }
}

internal fun fitReason(reason: DeltaFitter.Reason): UiText = when (reason) {
    DeltaFitter.Reason.ModelUnavailable -> UiText.Res(R.string.fit_reason_model)
    DeltaFitter.Reason.ModuleDisconnected -> UiText.Res(R.string.fit_reason_module)
    DeltaFitter.Reason.DeltaWriteFailed -> UiText.Res(R.string.fit_reason_write)
    DeltaFitter.Reason.ConfigReadFailed -> UiText.Res(R.string.fit_reason_config)
    DeltaFitter.Reason.VersionWriteFailed -> UiText.Res(R.string.fit_reason_version)
    is DeltaFitter.Reason.FitError -> UiText.Res(R.string.fit_error, listOf(reason.detail))
    is DeltaFitter.Reason.StateError -> UiText.Res(R.string.fit_state_error, listOf(reason.detail))
}

@Composable
internal fun fitLine(state: DeltaFitter.State?): String = fitText(state).text()

private val FIT_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** `version` 是下发时刻的毫秒时间戳，展示成分钟粒度就够（它同时是版本号本身）。 */
private fun fitTime(version: Long): String = FIT_TIME.format(Date(version))
