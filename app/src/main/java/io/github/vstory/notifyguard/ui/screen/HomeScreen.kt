package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ui.text

/**
 * 首页（M4g）：**只读**概览 —— 拦截统计与命中排行。
 *
 * 不放开关是有意的：开关一放进来就得连带着处理「未连接时置灰、拨动即下发、失败回滚」，那是设置屏
 * 已经有一套的生效值口径，两处各有一套时分歧的表现是「首页显示开着、判定却按关着跑」。
 *
 * 每个数都跟着写口径：今日拦截与排行是**本地日期**口径（模块端过零点归零），总拦截是模块端累计
 * （清空记录、重启系统框架都不归零）。三个数不同源，不写清就会被当成一套。
 *
 * **这一屏不用 [ListItem]**：M3 的列表项是为「可点的行」设计的，自带 72/88dp 的最小高度与触摸
 * 目标；这里统计与排行都不可交互，套上去只会把行撑高、空隙变大（真机上表现为「胖」）。不可交互的
 * 数据行按排版 token 自排即可，聚拢交给卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
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

            val totals = state.totals
            GroupCard(stringResource(R.string.home_stats_group)) {
                // 今日排最前：进这一屏问的是「今天拦了多少」，总量是背景
                // -1 = 旧版模块端没上报这个字段，不能拿它当「今天拦了 0 条」
                val today = totals?.today ?: -1L
                StatRow(
                    label = stringResource(R.string.home_stat_today),
                    value = if (today >= 0) countText(today) else stringResource(R.string.home_stat_no_value),
                    note = if (today >= 0) {
                        stringResource(R.string.home_stat_today_note)
                    } else {
                        stringResource(R.string.home_stat_today_unknown)
                    },
                )
                // totals 为 null 时不显示 0，也不退回别的口径：那会把「没读到」画成「拦了 0 条」
                StatRow(
                    label = stringResource(R.string.home_stat_blocked),
                    value = totals?.let { countText(it.blocked) } ?: stringResource(R.string.home_stat_no_value),
                    note = stringResource(R.string.home_stat_total_note),
                )
            }

            val rank = HomeViewModel.rank(totals?.todayByPkg ?: emptyMap())
            GroupCard(stringResource(R.string.home_top_group)) {
                when {
                    totals == null -> Note(stringResource(R.string.home_top_no_report))
                    rank.top.isEmpty() -> Note(stringResource(R.string.home_top_empty))
                    else -> {
                        val max = rank.top.first().events
                        rank.top.forEach {
                            RankRow(
                                pkg = it.pkg,
                                countText = stringResource(R.string.records_count_badge, it.events),
                                fraction = if (max > 0) it.events.toFloat() / max else 0f,
                            )
                        }
                        if (rank.otherApps > 0) {
                            Note(stringResource(R.string.home_top_other, rank.otherApps, rank.otherEvents))
                        }
                        Note(stringResource(R.string.home_top_scope))
                    }
                }
            }
        }
    }
}

/**
 * 统计里的一行：左侧口径标签、右侧数值（大号加粗），下面一行小字说这一行的口径。
 *
 * 原先标签与数值同字号拼在一行，最重的是括号里的「（累计）」而不是那个数 —— 用户是来看数的。
 * 只读（整行可点跳记录屏的行为按用户要求取消）。
 */
@Composable
private fun StatRow(label: String, value: String, note: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Note(note)
    }
}
