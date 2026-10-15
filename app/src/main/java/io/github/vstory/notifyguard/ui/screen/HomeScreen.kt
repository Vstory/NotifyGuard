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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight

/**
 * 首页（M4g）：**只读**概览 —— 拦截统计与命中排行。
 *
 * 不放开关是有意的：开关一放进来就得连带着处理「未连接时置灰、拨动即下发、失败回滚」，那是设置屏
 * 已经有一套的生效值口径，两处各有一套时分歧的表现是「首页显示开着、判定却按关着跑」。
 *
 * 判定分布按三类展示而不是只报「已拦截」：观察模式下 `block` 恒为 false，只报 0 会让用户以为
 * 模块没工作，而此时真正有意义的是「建议拦截」。
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

            // 总开关关着时判定链不跑，这条提示就是假话（M4g 已有「未知不等于开着」的口径，同理）
            if (state.cfg?.let { it.enabled && it.observe } == true) {
                GroupCard(stringResource(R.string.home_observe_group)) {
                    Note(stringResource(R.string.home_observe_note))
                }
            }

            val stats = state.stats
            val totals = state.totals
            GroupCard(stringResource(R.string.home_stats_group)) {
                // 口径逐行跟着数走，不在顶上写一段总结：三个数不是同一套口径，一句话说完必然有人读错
                // （同一个位置静默换数据源也一样 —— 读不到累计就把那一行标成窗口口径）
                val totalNote = stringResource(R.string.home_stat_total_note)
                val windowNote = stringResource(R.string.home_stat_window_note)
                val blockedNote = if (totals == null) windowNote else totalNote
                StatRow(
                    label = stringResource(R.string.home_stat_blocked),
                    value = countText(totals?.blocked ?: stats.blocked.toLong()),
                    note = blockedNote,
                )
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
                StatRow(
                    label = stringResource(R.string.home_stat_pass),
                    value = countText(totals?.pass ?: stats.pass.toLong()),
                    note = blockedNote,
                )
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
                Text(
                    text = stringResource(R.string.home_stat_window_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Note(
                    stringResource(
                        R.string.home_stat_scope,
                        countText(stats.groups.toLong()),
                        countText(stats.events.toLong()),
                    )
                )
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
                    // 上面那三个数已经是累计口径了，排行还是窗口口径 —— 不写这句，两个口径会被当成一套
                    Note(stringResource(R.string.home_top_scope))
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
