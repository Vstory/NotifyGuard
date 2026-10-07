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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.ui.text

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.records_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh(ctx) }, enabled = !state.fetching) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.records_refresh))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.records_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_clear_records)) },
                            onClick = {
                                menuOpen = false
                                viewModel.clearRecords(ctx)
                            },
                        )
                        // 与「清空记录」拆成两项、且只有它二次确认：记录是流水（丢了无所谓），
                        // 标注是手工劳动（清空不可恢复），两个破坏性动作的误触代价不对等
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.records_clear_labels)) },
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
            title = { Text(stringResource(R.string.records_clear_labels_title)) },
            text = { Text(stringResource(R.string.records_clear_labels_body, n)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearLabels(ctx) }) {
                    Text(stringResource(R.string.records_clear_labels_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearLabels() }) {
                    Text(stringResource(R.string.records_clear_labels_cancel))
                }
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
                text = stringResource(
                    R.string.records_summary,
                    state.groups,
                    state.rawCount,
                    LogStore.MAX_RECORDS,
                    RecordsViewModel.RECENT_LIMIT,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.fetching) {
                Text(
                    text = stringResource(R.string.records_fetching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.fetchError?.let {
                Text(
                    text = it.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = state.labelError?.text()
                    ?: stringResource(R.string.records_label_summary, state.labelCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.labelError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = fitLine(state.fit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.records_label_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.records_empty),
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
                        text = stringResource(R.string.records_count_badge, row.count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                row.marked?.let { spam ->
                    Text(
                        text = stringResource(
                            if (spam) R.string.records_marked_spam else R.string.records_marked_ham
                        ),
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
                    text = stringResource(R.string.records_no_text),
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
                ) { Text(stringResource(R.string.action_mark_spam)) }
                TextButton(
                    onClick = { onMark(false) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && row.marked != false,
                ) { Text(stringResource(R.string.action_mark_ham)) }
                TextButton(
                    onClick = onUndo,
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && row.marked != null,
                ) { Text(stringResource(R.string.action_undo)) }
            }
        }
    }
}

@Composable
private fun verdictLabel(verdict: RecordsViewModel.Verdict): String = stringResource(
    when (verdict) {
        RecordsViewModel.Verdict.Block -> R.string.verdict_block
        RecordsViewModel.Verdict.Would -> R.string.verdict_would
        RecordsViewModel.Verdict.Pass -> R.string.verdict_pass
    }
)

@Composable
private fun verdictColor(verdict: RecordsViewModel.Verdict) = when (verdict) {
    RecordsViewModel.Verdict.Block -> MaterialTheme.colorScheme.error
    RecordsViewModel.Verdict.Would -> MaterialTheme.colorScheme.tertiary
    RecordsViewModel.Verdict.Pass -> MaterialTheme.colorScheme.onSurfaceVariant
}
