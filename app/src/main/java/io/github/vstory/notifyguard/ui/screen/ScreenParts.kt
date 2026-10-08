package io.github.vstory.notifyguard.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.ui.ReasonExplanation
import io.github.vstory.notifyguard.ui.UiText
import io.github.vstory.notifyguard.ui.text

/**
 * 多屏共用的最小零件（状态卡 / 分组卡 / 开关行）。
 *
 * 各屏的提交口径不同（开关即下发、文本按钮提交），但**渲染**是同一套 —— 分开写会漂成两种视觉，
 * 而这类漂移没人报错，只能靠肉眼在屏与屏之间对照。
 */

@Composable
internal fun StatusCard(serviceText: UiText, connected: Boolean, loaded: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = serviceText.text(),
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (connected && !loaded) {
                Text(
                    text = stringResource(R.string.status_cfg_unreadable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/**
 * 整行可点、`Switch` 自身不吃事件（`onCheckedChange = null`）：读屏会把这一行当一个带名字的开关念，
 * 而不是只念一个无标签的「开关」。
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 带 tooltip 的图标按钮：触屏长按弹出说明（Material 规范里 plain tooltip 的用途就是给无文字的
 * 图标按钮标注作用）。
 *
 * 锚点强制 Below：顶栏按钮贴着屏幕顶端，默认的 Above 只能出屏。
 *
 * 长按顺带给一次系统 `LONG_PRESS` 触感：Material3 的长按判定自己实现（全库无触感调用），而平台
 * 自己的 tooltip 走 `View` 的长按路径是会震的 —— 不补这一步，同一个「长按看说明」在系统控件上
 * 有触感、在这里没有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TooltippedIconButton(
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = rememberTooltipState(),
        tooltip = { PlainTooltip { Text(tooltip) } },
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.longPressHaptic(haptics)) {
            icon()
        }
    }
}

/**
 * 长按判定成功那一刻触发一次触感。只观察、不消费事件，也不看 `enabled` —— 库的提示无论子级是否
 * 可用都会弹（`TooltipBox` 不下传 `enabled`），触感得跟着提示走。
 *
 * 超时用 `AwaitPointerEventScope` 自己的那个 `withTimeout`（不是 kotlinx 的同名函数，M3 源码那份
 * import 就是没生效的）：只有它抛 [PointerEventTimeoutCancellationException]，捕获 kotlinx 的
 * `TimeoutCancellationException` 永远 catch 不到。
 */
private fun Modifier.longPressHaptic(haptics: HapticFeedback): Modifier = pointerInput(haptics) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // 不等抬手就会在按住期间反复判定；consume 由库负责（吞掉长按之后那次点击）。
            waitForUpOrCancellation(pass = PointerEventPass.Initial)
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 需要注意的状态行（某项在当前配置下不生效之类）。
 *
 * 与 [Note] 同字号但换 [MaterialTheme.colorScheme.error]：灰字是背景说明、扫过去不读也不影响操作，
 * 这条说的是「你看到的开关为什么拨不动」，混在灰字里就等于没说。
 */
@Composable
internal fun AlertNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 行内可点的判定元数据。记录屏与学习屏共用 —— 同一串在一屏能点开、到另一屏点了没反应，用户会
 * 以为那屏坏了。
 *
 * 图标承担「这儿能点」的提示：这行是小字元数据，用 primary 色会跟行里那几个真动作抢层级。
 */
@Composable
internal fun ReasonLine(meta: List<UiText>, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            text = meta.joinToString(" · ") { it.text() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * 判定说明弹窗（两屏共用）。
 *
 * 可读化不替代排障：人话下面并排给出原始原因码与判定槽，出问题时两边都要能拿到。
 */
@Composable
internal fun ReasonDialog(ex: ReasonExplanation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ex.reason.short.text()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ex.reason.why.text(), style = MaterialTheme.typography.bodyMedium)
                RawLine(R.string.reason_raw_reason, ex.reason.raw)
                ex.slot?.let { RawLine(R.string.reason_raw_slot, it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reason_close)) }
        },
    )
}

/** 「标签 + 等宽原文」一行。等宽是为了让技术串一眼认出不是给人读的话。 */
@Composable
private fun RawLine(@StringRes label: Int, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 判定三态（展示用）。记录屏与学习屏共用一套标签与配色：同一个量在两屏有两种视觉，就是两套口径。
 *
 * 从 [LogRecord] 的 `block` / `would` 派生，不解析 `reason` 前缀 —— 技术串的形态随判定链增删而变，字段不会
 * （与 `hasAiVerdict` 取 `score` 同一条口径）。
 */
enum class Verdict(@StringRes val labelRes: Int) {
    Block(R.string.verdict_block),
    Would(R.string.verdict_would),
    Pass(R.string.verdict_pass);

    companion object {
        fun of(record: LogRecord): Verdict = when {
            record.block -> Block
            record.would -> Would
            else -> Pass
        }
    }
}

@Composable
internal fun verdictColor(verdict: Verdict) = when (verdict) {
    Verdict.Block -> MaterialTheme.colorScheme.error
    Verdict.Would -> MaterialTheme.colorScheme.tertiary
    Verdict.Pass -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 服务状态行的「事实 → 文案」映射，用到状态卡的屏共用一份。
 *
 * 两处各拼一遍的代价不是重复而是**分歧**：改文案只改到一处时，另一处就成了假话（同 FitLine 的取舍）。
 */
internal fun serviceTextOf(svc: XposedService?): UiText = if (svc == null) {
    UiText.Res(R.string.service_disconnected)
} else {
    UiText.Res(R.string.service_connected, listOf(svc.frameworkName, svc.frameworkVersion))
}
