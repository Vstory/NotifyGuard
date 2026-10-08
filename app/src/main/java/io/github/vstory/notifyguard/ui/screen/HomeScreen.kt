package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.ui.text

/**
 * 首页（M4g）：**只读**概览 —— 拦截统计与命中排行。
 *
 * 不放开关是有意的：开关一放进来就得连带着处理「未连接时置灰、拨动即下发、失败回滚」，那是设置屏
 * 已经有一套的生效值口径，两处各有一套时分歧的表现是「首页显示开着、判定却按关着跑」。
 *
 * 判定分布按三类展示而不是只报「已拦截」：观察模式下 `block` 恒为 false，只报 0 会让用户以为
 * 模块没工作，而此时真正有意义的是「本应被拦」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRecords: (RecordsViewModel.Filter) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val ctx = LocalContext.current.applicationContext
    val state = viewModel.state

    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    LaunchedEffect(Unit) { viewModel.refresh(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    TooltippedIconButton(
                        tooltip = stringResource(R.string.home_refresh),
                        onClick = { viewModel.refresh(ctx) },
                        enabled = !state.fetching,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_refresh))
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusCard(state.serviceText, state.connected, state.cfg != null)

            // 总开关关着时判定链不跑，这条提示就是假话（M4g 已有「未知不等于开着」的口径，同理）
            if (state.cfg?.let { it.enabled && it.observe } == true) {
                GroupCard(stringResource(R.string.home_observe_group)) {
                    Note(stringResource(R.string.home_observe_note))
                }
            }

            val stats = state.stats
            GroupCard(stringResource(R.string.home_stats_group)) {
                Note(stringResource(R.string.home_stats_note, LogStore.MAX_RECORDS))
                StatLine(stringResource(R.string.home_stat_blocked, stats.blocked)) {
                    onOpenRecords(RecordsViewModel.Filter.Block)
                }
                StatLine(stringResource(R.string.home_stat_would, stats.would)) {
                    onOpenRecords(RecordsViewModel.Filter.Would)
                }
                StatLine(stringResource(R.string.home_stat_pass, stats.pass)) {
                    onOpenRecords(RecordsViewModel.Filter.Pass)
                }
                Note(stringResource(R.string.home_stat_scope, stats.groups, stats.events))
                // 整行可点但没有任何可点的样子，不写一句就没人会去点
                Note(stringResource(R.string.home_stats_tap_hint))
            }

            GroupCard(stringResource(R.string.home_top_group)) {
                if (stats.top.isEmpty()) {
                    Note(stringResource(R.string.home_top_empty))
                } else {
                    val max = stats.top.first().events
                    stats.top.forEach {
                        RankRow(
                            pkg = it.pkg,
                            countText = stringResource(R.string.records_count_badge, it.events),
                            fraction = if (max > 0) it.events.toFloat() / max else 0f,
                        )
                    }
                    if (stats.otherApps > 0) {
                        Note(stringResource(R.string.home_top_other, stats.otherApps, stats.otherEvents))
                    }
                }
            }
        }
    }
}

/**
 * 整行可点：这三行是「哪些通知被拦了」的入口，只报数不给出路时用户看到「已拦截 12」也无从下钻。
 * 触控目标按无障碍口径撑到 48dp（文本本身远低于此）。
 */
@Composable
private fun StatLine(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
