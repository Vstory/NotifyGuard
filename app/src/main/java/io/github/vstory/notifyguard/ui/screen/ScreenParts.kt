package io.github.vstory.notifyguard.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.text.NumberFormat
import java.util.Locale


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
 * 分区标题：M3 的 Lists 用标题把列表分段，而不是把每一段装进一张卡片（Card 是 Containment 类的
 * 独立内容块，只有状态卡那种自成一块的内容才归它）。
 */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * 屏内间距（4dp 网格）：区块之间 24dp、屏边距 16dp、标题与首条内容 8dp。
 *
 * 三个数各自有出处（docs/ui-rework.md 第 7 节）：改小会让区块连成一片，改大则一屏装不下两件事。
 */
internal val ScreenPadding = 16.dp
internal val SectionGap = 24.dp
internal val ItemGap = 8.dp

/**
 * 区块标题（不带内边距的那版，供 [SectionGap] 布局的屏用；[SectionTitle] 是旧 8dp 间距布局的）。
 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 可点条目：进子屏（[expanded] 传 null，尾图标是右箭头）或就地展开（true/false，上下箭头）。
 *
 * 展开原先用 48dp 的 `OutlinedButton`：同屏的开关行是列表项，展开按钮是大圆角按钮，两种形态混在一起
 * 分不清哪个是「看一眼更多」、哪个是「提交」。列表里的这两种语义都归列表项。
 */
@Composable
internal fun ActionRow(
    label: String,
    onClick: () -> Unit,
    expanded: Boolean? = null,
    enabled: Boolean = true,
    note: String? = null,
) {
    val icon = when (expanded) {
        null -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        true -> Icons.Filled.KeyboardArrowUp
        false -> Icons.Filled.KeyboardArrowDown
    }
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
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
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        // 不按 note 是否为空决定传不传 composable：那样每处都要处理「空 lambda」，而这里
        // 判空只多占一点高度（官方列表项高度由最高元素决定，空 supporting 不留位不算数）
        supportingContent = {
            note?.let {
                Text(
                    text = richText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
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

/**
 * 顶栏点一下回到顶部。记录 / 学习 / 规则三屏共用同一套手感 —— 一处带动画、另一处瞬移，用户是会察觉的。
 *
 * 挂在 TopAppBar 的 modifier 上不会抢掉 actions：顶栏里的按钮是子节点，Main pass 里子先拿到事件。
 */
@Composable
internal fun topBarScrollToTop(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.scroll_to_top)
    return Modifier.clickable(onClickLabel = label) { scope.launch { state.animateScrollToItem(0) } }
}

internal data class RichPart(val text: String, val bold: Boolean)

/**
 * 把 `**重点**` 标记切成若干段。
 *
 * 说明文字里真正要人读的是「模块端累计」「过零点归零」这类口径词；整段同一个灰字字重时，关键词
 * 与铺垫一样轻，一眼扫过去分不出轻重。为一条说明引整套 Markdown 太重，只认 `**`：落单的 `**`
 * 原样输出 —— 宁可让人看到两个星号，也不能把该读的字吞掉。
 */
internal fun richParts(text: String): List<RichPart> {
    val out = ArrayList<RichPart>()
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("**", i)
        val close = if (open < 0) -1 else text.indexOf("**", open + 2)
        if (close < 0) {
            out.add(RichPart(text.substring(i), false))
            break
        }
        if (open > i) out.add(RichPart(text.substring(i, open), false))
        // `****` 不是标记，原样吐回去
        if (close == open + 2) out.add(RichPart("****", false)) else out.add(RichPart(text.substring(open + 2, close), true))
        i = close + 2
    }
    return out
}

/**
 * 把 `**重点**` 渲染成带样式的文本：粗体段加粗并提到正文色 [MaterialTheme.colorScheme.onSurface]。
 *
 * 灰字里的重点只加粗还是一片灰，提色才拉得开层次。凡是读 `**` 的说明文字都走这一个出口 ——
 * 否则会出现「这屏认标记、那屏把星号原样吐给用户」。
 *
 * 注意：`SnackbarHostState.showSnackbar(String)` 收的是纯文本，一次性提示的文案不能带标记。
 */
@Composable
internal fun richText(text: String): AnnotatedString {
    val strong = MaterialTheme.colorScheme.onSurface
    val parts = remember(text, strong) { richParts(text) }
    return buildAnnotatedString {
        for (p in parts) {
            if (p.bold) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strong)) { append(p.text) }
            } else {
                append(p.text)
            }
        }
    }
}

/** 说明文字。`**…**` 之间加粗提色（见 [richText]）；`\n` 分行，用于把几件事分开说。 */
@Composable
internal fun Note(text: String) {
    Text(
        text = richText(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 数值加千分位。
 *
 * `81496` 得逐位读才知道量级，`81,496` 一眼就是八万；分组符交由系统区域决定，不替用户拍板。
 */
internal fun countText(value: Long, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(value)

/**
 * 需要注意的状态行（某项在当前配置下不生效之类）。
 *
 * 与 [Note] 同字号但换 [MaterialTheme.colorScheme.error]：灰字是背景说明、扫过去不读也不影响操作，
 * 这条说的是「你看到的开关为什么拨不动」，混在灰字里就等于没说。
 */
@Composable
internal fun AlertNote(text: String) {
    Text(
        text = richText(text),
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
    // 先在 composable 上下文里把每段求出来再拼：joinToString 的 transform 不是 inline，
    // 在里面调 @Composable 的 text() 编译器不认（map 是 inline，可以）。
    val line = meta.map { it.text() }.joinToString(" · ")
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            text = line,
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
 *
 * 归因（哪几个片段把分数顶上去的）放在这里，且**点了才算**：它要遍历整条文本的 n-gram，
 * 进屏或开弹窗就全算是白烧电；算完之前只给一个按钮。
 */
@Composable
internal fun ReasonDialog(
    ex: ReasonExplanation,
    attribution: AttributionState?,
    /** `null` = 这一屏不给归因入口（归因统一放在记录屏的说明弹窗里）。 */
    onAskAttribution: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ex.reason.short.text()) },
        text = {
            // 叠上高亮原文之后内容会超过弹窗高度，让它自己滚
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = richText(ex.reason.why.text()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                RawLine(R.string.reason_raw_reason, ex.reason.raw)
                ex.slot?.let { RawLine(R.string.reason_raw_slot, it) }
                if (onAskAttribution != null) {
                    if (attribution == null && ex.aiText != null) {
                        TextButton(onClick = onAskAttribution) {
                            Text(stringResource(R.string.attribution_show))
                        }
                    } else {
                        AttributionBlock(ex.aiText, attribution)
                    }
                }
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

    /**
     * 用户能补的方向只有一个：被拦下的条目只可能是「误拦」（模型已经判它是垃圾，再标一次垃圾等于
     * 把判定抄一遍，没有信息量），放行与观察模式的条目只可能是「漏拦」。两个方向都摆出来，用户
     * 就得先想「这条该点哪个」，而其中一个按构造永远是无意义的。
     */
    val markSpamAllowed: Boolean get() = this != Block
    val markHamAllowed: Boolean get() = this == Block

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
 *
 * 只说「连上没连上」：框架名与版本是排障信息，归诊断屏（docs/ui-rework.md 第 6.1 节）。
 */
internal fun serviceTextOf(svc: XposedService?): UiText = UiText.Res(
    if (svc == null) R.string.service_disconnected else R.string.service_connected
)

/**
 * 搜索态顶栏。记录屏与学习屏的样本库共用一份 —— 两处各写一遍，迟早有一处只改了清空按钮的位置。
 *
 * 进屏即抢焦点（用户点搜索就是为了打字），文案沿用记录屏那套 `records_search_*`：同一种交互
 * 在两屏说两套话没有意义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.records_search_close),
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.records_search_hint)) },
                // 顶栏里不要输入框自己的容器与下划线：它已经是顶栏的一部分，带边框会叠出两层
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.records_search_clear),
                            )
                        }
                    }
                },
            )
        },
    )
}
