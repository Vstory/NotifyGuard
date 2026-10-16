package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.ui.UiText
import io.github.vstory.notifyguard.ui.text

/**
 * 设置屏：用户偏好 —— 拦截总开关、只看不拦、内容识别与严格程度、保护类型。
 *
 * 开发信息（模块状态、装配明细、生效配置 JSON、框架与模块版本）已搬到 [DiagnosticScreen]：这一屏的
 * 读者是最终用户，那些内容他既看不懂、也不需要（docs/ui-rework.md 第 3.2 / 5.1 节）。
 *
 * 提交口径沿用 M1f —— 开关拨动即下发、阈值松手下发（见 [SettingsViewModel]）。
 *
 * 关键词与白名单归规则屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDiagnostic: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }

    // 订阅只在屏活着时有效。切 tab 回来会重新订阅并立刻拿到当前状态，不需要额外的进屏刷新
    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    // 文案必须在 Composable 上下文里解析：LaunchedEffect 的 block 不是 @Composable，
    // 里面调不了 stringResource
    val notice = state.notice
    val noticeText = notice?.text?.text()
    LaunchedEffect(notice) {
        if (notice != null && noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.noticeShown(notice)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            val cfg = state.cfg
            val connected = state.connected
            // README 判定链第 2 行：总开关关闭即整链短路（reason 落 `disabled`）。所以「只看不拦」
            // 与内容识别都是它的从属项 —— 总开关关着时判定根本不跑，这些项无处生效。
            // 不置灰就会出现「只看不拦显示为开、而它承诺的『照常判断』并没发生」这种自相矛盾的
            // 界面状态（官方 Settings 模式对父开关与从属控件的规定即：关闭父开关即禁用从属控件）。
            val judgeOn = connected && cfg?.enabled == true
            // 用 == false 而不是 != true：cfg 为 null（连上了但配置读不到）时原因不在总开关，
            // 此时挂一条「先打开总开关」会把用户指错方向
            val gated = connected && cfg?.enabled == false
            val spamOn = cfg?.spamEnabled == true

            StatusLine(state.serviceText, connected, cfg != null)

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.settings_block_group))
                SwitchRow(
                    label = stringResource(R.string.settings_enabled),
                    note = stringResource(R.string.settings_enabled_note),
                    checked = cfg?.enabled ?: false,
                    enabled = connected,
                    onCheckedChange = viewModel::setEnabled,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_observe),
                    note = stringResource(R.string.settings_observe_note),
                    checked = cfg?.observe ?: false,
                    enabled = judgeOn,
                    onCheckedChange = viewModel::setObserve,
                )
                if (gated) AlertNote(stringResource(R.string.settings_judge_gated))
            }

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.settings_ai_group))
                SwitchRow(
                    label = stringResource(R.string.settings_spam),
                    note = stringResource(R.string.settings_spam_note),
                    checked = spamOn,
                    enabled = judgeOn,
                    onCheckedChange = viewModel::setSpam,
                )
                if (gated) AlertNote(stringResource(R.string.settings_judge_gated))
                val threshold = state.draftThreshold
                    ?: (cfg?.threshold?.toFloat() ?: Config.DEFAULT_THRESHOLD.toFloat())
                StrictnessSlider(
                    // 滑杆右端是「更严格」= 阈值更低，所以整条轨道的方向与底层阈值相反
                    value = 1f - threshold,
                    enabled = judgeOn && spamOn,
                    onDrag = { viewModel.dragThreshold(1f - it) },
                    onCommit = viewModel::commitThreshold,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.settings_protect_group))
                SettingsViewModel.ProtectItem.entries.forEach { item ->
                    SwitchRow(
                        label = stringResource(item.labelRes),
                        checked = cfg?.protect?.let { SettingsViewModel.isProtected(it, item) } ?: true,
                        enabled = connected,
                        onCheckedChange = { viewModel.setProtect(item, it) },
                    )
                }
                Note(stringResource(R.string.settings_protect_note))
            }

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                HorizontalDivider()
                ActionRow(
                    label = stringResource(R.string.settings_diagnostic),
                    note = stringResource(R.string.settings_diagnostic_note),
                    onClick = onOpenDiagnostic,
                )
            }
        }
    }
}

/**
 * 顶部一行连接状态。
 *
 * 不做成卡片：卡片是 Containment，留给首页那种「自成一块」的统计块；这里只需要回答「有没有连上、
 * 没连上怎么办」，那就一行字。连上但读不到配置时另起一行（那是另一种故障，恢复方式也不同）。
 */
@Composable
private fun StatusLine(serviceText: UiText, connected: Boolean, loaded: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * 严格程度：滑杆 + 两端标签。
 *
 * 不显示阈值的原始数值（0.80 这种分数只对开发者有意义）—— 用户能判断的是「往哪边拨会怎样」，
 * 所以两端给「宽松 / 严格」，把数值留给诊断屏（docs/ui-rework.md 第 5.1 节）。
 */
@Composable
private fun StrictnessSlider(
    value: Float,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.settings_threshold_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = value,
            onValueChange = onDrag,
            onValueChangeFinished = onCommit,
            valueRange = 0f..1f,
            steps = SettingsViewModel.THRESHOLD_STEPS - 1,
            enabled = enabled,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_threshold_loose),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_threshold_strict),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Note(stringResource(R.string.settings_threshold_note))
    }
}
