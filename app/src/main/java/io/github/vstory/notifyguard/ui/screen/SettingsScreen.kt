package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.sync.ConfigCodec
import io.github.vstory.notifyguard.ui.text

/**
 * 设置屏（M4）：判定开关、AI 开关与阈值、六类保护开关、模块端状态、只读的生效配置。
 *
 * 提交口径沿用 M1f —— 开关拨动即下发、阈值松手下发（见 [SettingsViewModel]）。
 *
 * 关键词与白名单归规则屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current.applicationContext
    // 展开状态跨旋转存活：这两块都是排查时才看的，转一下屏要重新展开很烦
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var showAssembly by rememberSaveable { mutableStateOf(false) }

    // 订阅只在屏活着时有效。切 tab 回来会重新订阅并立刻拿到当前状态，不需要额外的进屏刷新
    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    // 模块端状态是异步拉的（模块没注入时 5s 超时），进屏拉一次，之后靠手动刷新
    LaunchedEffect(Unit) { viewModel.refreshStatus(ctx) }
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cfg = state.cfg
            val connected = state.connected

            StatusCard(state.serviceText, connected, cfg != null)

            GroupCard(stringResource(R.string.settings_status_group)) {
                Note(stringResource(R.string.settings_status_note))
                Text(viewModel.moduleLine().text(), style = MaterialTheme.typography.bodyMedium)
                viewModel.countersText()?.let {
                    Text(
                        text = it.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                viewModel.detailLine()?.let {
                    Text(
                        text = it.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                viewModel.safeModeLine()?.let {
                    Text(
                        text = it.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.refreshStatus(ctx) },
                        enabled = !state.statusBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.settings_refresh_status))
                    }
                    if (state.status?.safeMode == true) {
                        Button(
                            onClick = { viewModel.clearSafeMode(ctx) },
                            enabled = !state.statusBusy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.settings_clear_safe_mode))
                        }
                    }
                }
                viewModel.assemblySummary()?.let {
                    Text(
                        text = it.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { showAssembly = !showAssembly },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (showAssembly) R.string.settings_assembly_hide else R.string.settings_assembly_show
                        )
                    )
                }
                if (showAssembly) {
                    Text(
                        text = state.status?.assembly?.takeIf { it.isNotEmpty() }
                            ?: stringResource(R.string.settings_assembly_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                    )
                }
            }

            GroupCard(stringResource(R.string.settings_block_group)) {
                Note(stringResource(R.string.settings_block_note))
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
                    enabled = connected,
                    onCheckedChange = viewModel::setObserve,
                )
            }

            GroupCard(stringResource(R.string.settings_ai_group)) {
                SwitchRow(
                    label = stringResource(R.string.settings_spam),
                    note = stringResource(R.string.settings_spam_note),
                    checked = cfg?.spamEnabled ?: false,
                    enabled = connected,
                    onCheckedChange = viewModel::setSpam,
                )
                Text(
                    text = viewModel.thresholdText().text(),
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
                Note(stringResource(R.string.settings_threshold_note))
            }

            GroupCard(stringResource(R.string.settings_protect_group)) {
                Note(stringResource(R.string.settings_protect_note))
                SettingsViewModel.ProtectItem.entries.forEach { item ->
                    SwitchRow(
                        label = stringResource(item.labelRes),
                        note = stringResource(item.noteRes),
                        checked = cfg?.protect?.let { SettingsViewModel.isProtected(it, item) } ?: true,
                        enabled = connected,
                        onCheckedChange = { viewModel.setProtect(item, it) },
                    )
                }
            }

            GroupCard(stringResource(R.string.settings_config_group)) {
                Note(stringResource(R.string.settings_config_note))
                OutlinedButton(
                    onClick = { showConfig = !showConfig },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (showConfig) R.string.settings_config_hide else R.string.settings_config_show
                        )
                    )
                }
                if (showConfig) {
                    Text(
                        text = cfg?.let { ConfigCodec.encode(it) }
                            ?: stringResource(R.string.settings_config_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
