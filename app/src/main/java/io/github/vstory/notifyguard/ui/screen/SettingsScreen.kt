package io.github.vstory.notifyguard.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.ui.ConfigActivity

/**
 * 设置屏（M4）：判定开关、AI 开关与阈值、六类保护开关。
 *
 * 提交口径沿用 M1f —— 开关拨动即下发、阈值松手下发（见 [SettingsViewModel]）。
 *
 * 关键词规则与白名单仍只在旧配置页可改（规则屏未落地），故本屏底部保留过渡入口；
 * 「安全模式（熔断状态）」需要模块端回传状态，随状态通道另立一片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state = viewModel.state
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 订阅只在屏活着时有效。切 tab 回来会重新订阅并立刻拿到当前状态，不需要额外的进屏刷新
    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it.text)
            viewModel.noticeShown(it)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cfg = state.cfg
            val connected = state.connected

            StatusCard(state.serviceText, connected, cfg != null)

            GroupCard("拦截") {
                SwitchRow(
                    label = "启用拦截判定",
                    note = "关闭后判定链直接放行（记录照写）",
                    checked = cfg?.enabled ?: false,
                    enabled = connected,
                    onCheckedChange = viewModel::setEnabled,
                )
                SwitchRow(
                    label = "观察模式",
                    note = "只记录、不拦截：判定照跑，reason 里照出 AI 分数与命中规则",
                    checked = cfg?.observe ?: false,
                    enabled = connected,
                    onCheckedChange = viewModel::setObserve,
                )
            }

            GroupCard("AI 识别") {
                SwitchRow(
                    label = "识别垃圾通知",
                    note = "端侧哈希 n-gram + 逻辑回归，不联网",
                    checked = cfg?.spamEnabled ?: false,
                    enabled = connected,
                    onCheckedChange = viewModel::setSpam,
                )
                Text(
                    text = viewModel.thresholdText(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = state.draftThreshold
                        ?: (cfg?.threshold?.toFloat() ?: Config.DEFAULT_THRESHOLD.toFloat()),
                    onValueChange = viewModel::dragThreshold,
                    onValueChangeFinished = viewModel::commitThreshold,
                    valueRange = 0f..1f,
                    steps = SettingsViewModel.THRESHOLD_STEPS - 1,
                    enabled = connected && cfg?.spamEnabled == true,
                )
                Text(
                    text = "分数 ≥ 阈值即拦，松手才下发（拖动途中的中间值会被当场拿去判定）。" +
                        "关掉上面的开关时本滑杆置灰：这个值不参与判定。调低更激进，0 等于全拦。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GroupCard("保护类型") {
                Text(
                    text = "命中即放行，不进入规则与 AI：被误拦的代价远大于漏掉一条广告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsViewModel.ProtectItem.entries.forEach { item ->
                    SwitchRow(
                        label = item.label,
                        note = item.note,
                        checked = cfg?.protect?.let { SettingsViewModel.isProtected(it, item) } ?: true,
                        enabled = connected,
                        onCheckedChange = { viewModel.setProtect(item, it) },
                    )
                }
            }

            GroupCard("关键词 / 白名单") {
                Text(
                    text = "这两项还没有新入口（规则屏未落地），暂时仍在旧配置页改：" +
                        "关键词命中即拦；白名单中的应用只跳过 AI 段，用户规则照常生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(ctx, ConfigActivity::class.java)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("打开旧配置页（临时）")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(serviceText: String, connected: Boolean, loaded: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = serviceText,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (connected && !loaded) {
                Text(
                    text = "连上了框架服务，但读不出生效配置（配置内容损坏？）—— 下面显示的是默认值，改动仍可下发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "开关拨动即下发、阈值松手下发，模块端立刻生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun SwitchRow(
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
