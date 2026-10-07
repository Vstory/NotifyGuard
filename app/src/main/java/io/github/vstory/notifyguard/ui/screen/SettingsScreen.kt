package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.sync.ConfigCodec

/**
 * 设置屏（M4）：判定开关、AI 开关与阈值、六类保护开关、只读的生效配置。
 *
 * 提交口径沿用 M1f —— 开关拨动即下发、阈值松手下发（见 [SettingsViewModel]）。
 *
 * 关键词与白名单归规则屏；屏内说明文字**刻意不重复**被产物门禁断言的字面量（见 build-ci.yml ③h、③i）：
 * 说明文字顺带覆盖了断言词，那条断言就变成「被两处同时撑着」，任一处单独失效都发现不了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }
    // 展开状态跨旋转存活：配置 JSON 是排查时才看的，转一下屏要重新展开很烦
    var showConfig by rememberSaveable { mutableStateOf(false) }

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
                Note("开关拨动即下发、阈值松手下发，模块端立刻生效 —— 这一屏没有「保存」按钮。")
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
                Note("分数 ≥ 阈值即拦。关掉上面的开关时本滑杆置灰：这个值不参与判定。调低更激进，0 等于全拦。")
            }

            GroupCard("保护类型") {
                Note("命中即放行，不进入规则与 AI：被误拦的代价远大于漏掉一条广告。")
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

            GroupCard("已生效配置") {
                Note("模块端此刻实际读到的那份配置（只读）。界面上的开关就是按它渲染的，排查「改了没生效」时先看这里。")
                OutlinedButton(
                    onClick = { showConfig = !showConfig },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (showConfig) "收起配置 JSON" else "展开配置 JSON")
                }
                if (showConfig) {
                    Text(
                        text = cfg?.let { ConfigCodec.encode(it) } ?: "（未连接，读不到生效配置）",
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
