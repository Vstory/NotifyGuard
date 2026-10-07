package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.data.LogStore

/**
 * 记录屏（M4）：模块端回流记录的展示 + 就地标注。
 *
 * 判定的拦截/放行、AI 分数、命中规则全在每行的 `reason` 里 —— 观察模式下标定阈值靠的就是它，
 * 所以不做折叠、也不做筛选，一屏看全。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(viewModel: RecordsViewModel = viewModel()) {
    val ctx = LocalContext.current.applicationContext
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    // 进屏拉一次（切回本屏也算进屏）；超时不重试，刷新入口就在顶栏
    LaunchedEffect(Unit) { viewModel.refresh(ctx) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it.text)
            viewModel.noticeShown(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记录") },
                actions = {
                    IconButton(onClick = { viewModel.refresh(ctx) }, enabled = !state.fetching) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新记录")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("清空记录") },
                            onClick = {
                                menuOpen = false
                                viewModel.clearRecords(ctx)
                            },
                        )
                        // 与「清空记录」拆成两项、且只有它二次确认：记录是流水（丢了无所谓），
                        // 标注是手工劳动（清空不可恢复），两个破坏性动作的误触代价不对等
                        DropdownMenuItem(
                            text = { Text("清空标注") },
                            onClick = {
                                menuOpen = false
                                viewModel.askClearLabels(ctx)
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StatusCard(state) }
            if (state.rows.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(state.rows, key = { it.key }) { row ->
                    RecordCard(
                        row = row,
                        busy = state.busy,
                        onMark = { viewModel.mark(ctx, row.key, it) },
                        onUndo = { viewModel.undo(ctx, row.key) },
                    )
                }
            }
        }
    }

    state.confirmClearLabels?.let { n ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearLabels() },
            title = { Text("清空全部标注？") },
            text = { Text("$n 条标注会被删除且无法恢复。清空后模型退回纯内置模型，分数会立刻变回去。") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearLabels(ctx) }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearLabels() }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusCard(state: RecordsViewModel.UiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "共 ${state.groups} 组 / 累计 ${state.rawCount} 次" +
                    "（上限 ${LogStore.MAX_RECORDS} 组），下列最近 ${RecordsViewModel.RECENT_LIMIT} 组；权威源在模块端",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.fetching) {
                Text(
                    text = "正在从模块端拉取…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.fetchError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                text = state.labelError
                    ?: "标注 ${state.labelCount} 条 · 权威源在模块端的 labels.json（与记录一样，卸载重装 App 不会丢）",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.labelError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = state.fitText ?: "微调：尚未拉取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "标垃圾 / 标正常会把这条通知的文本交给端侧微调；累计到门槛自动拟合并下发给模块端" +
                    "（下次判定即生效），无需手动触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "（暂无记录。判定链是否在跑看框架日志；模块端记录落在 /data/misc/notifyguard/logs.json）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordCard(
    row: RecordsViewModel.RecordRow,
    busy: Boolean,
    onMark: (Boolean) -> Unit,
    onUndo: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = verdictLabel(row.verdict),
                    style = MaterialTheme.typography.labelMedium,
                    color = verdictColor(row.verdict),
                )
                if (row.count > 1) {
                    Text(
                        text = "×${row.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                row.marked?.let { spam ->
                    Text(
                        text = if (spam) "已标垃圾" else "已标正常",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (spam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = row.pkg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.title.isNullOrBlank() && row.text.isNullOrBlank()) {
                Text(
                    text = "(无文本)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                row.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                row.text?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                text = row.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 三个动作对已标注状态互斥收敛：已标垃圾时「标垃圾」置灰，免得按出一串同义指令。
            // 在途（busy）时全部置灰：连点会并发发出多条指令，而先到的回执会盖掉后点那次的状态
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onMark(true) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && row.marked != true,
                ) { Text("标垃圾") }
                TextButton(
                    onClick = { onMark(false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && row.marked != false,
                ) { Text("标正常") }
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && row.marked != null,
                ) { Text("撤销") }
            }
        }
    }
}

private fun verdictLabel(verdict: RecordsViewModel.Verdict): String = when (verdict) {
    RecordsViewModel.Verdict.Block -> "拦截"
    RecordsViewModel.Verdict.Would -> "本应拦"
    RecordsViewModel.Verdict.Pass -> "放行"
}

@Composable
private fun verdictColor(verdict: RecordsViewModel.Verdict) = when (verdict) {
    RecordsViewModel.Verdict.Block -> MaterialTheme.colorScheme.error
    RecordsViewModel.Verdict.Would -> MaterialTheme.colorScheme.tertiary
    RecordsViewModel.Verdict.Pass -> MaterialTheme.colorScheme.onSurfaceVariant
}
