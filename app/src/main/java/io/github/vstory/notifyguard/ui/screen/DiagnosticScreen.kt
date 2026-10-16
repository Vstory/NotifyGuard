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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.sync.ConfigCodec
import io.github.vstory.notifyguard.ui.text

/** 诊断屏的路由键。不放进 [io.github.vstory.notifyguard.ui.Destination]：那个枚举是五屏底栏的来源。 */
internal const val DIAGNOSTIC_ROUTE = "diagnostic"

/**
 * 诊断屏：模块状态、装配明细、生效配置、两边版本 —— 排障用的一屏。
 *
 * 这些内容原先在设置屏首屏：普通用户看不懂也不必看（docs/ui-rework.md 第 3.2 / 5.1 节）。收进来而不是
 * 删掉，是因为「装了没生效」这类问题在 App 内无解时会直接变成无处下手；入口做在设置屏底部、
 * 明面可见，不做连点隐藏（用户明确要求）。
 *
 * 屏内文案保持开发口径：这一屏的读者是排障的人，把「扩展槽命中」改成「用了几次」反而查不了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state = viewModel.state
    val ctx = LocalContext.current.applicationContext
    // 展开状态跨旋转存活：这两块都是排查时才看的，转一下屏要重新展开很烦
    var showAssembly by rememberSaveable { mutableStateOf(false) }
    var showConfig by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    // 模块端状态是异步拉的（模块没注入时 5s 超时），进屏拉一次，之后靠手动刷新
    LaunchedEffect(Unit) { viewModel.refreshStatus(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.diag_back),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.diag_connection_group))
                Text(
                    text = state.serviceText.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.connected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                state.frameworkText?.let {
                    Text(
                        text = it.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.diag_app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Note(stringResource(R.string.diag_note))
            }

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.diag_status_group))
                Note(stringResource(R.string.diag_status_note))
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
            }

            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                SectionLabel(stringResource(R.string.diag_config_group))
                Note(stringResource(R.string.settings_config_note))
                Text(
                    text = richText(viewModel.thresholdText().text()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActionRow(
                    label = stringResource(
                        if (showAssembly) R.string.settings_assembly_hide else R.string.settings_assembly_show
                    ),
                    expanded = showAssembly,
                    onClick = { showAssembly = !showAssembly },
                )
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
                ActionRow(
                    label = stringResource(
                        if (showConfig) R.string.settings_config_hide else R.string.settings_config_show
                    ),
                    expanded = showConfig,
                    onClick = { showConfig = !showConfig },
                )
                if (showConfig) {
                    Text(
                        text = state.cfg?.let { ConfigCodec.encode(it) }
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
