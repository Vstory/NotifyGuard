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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
 * 每行底部的判定元数据（M4k）出人话、点开给解释：早先直贴 `reason · ruleId · slot` 三段技术串，
 * 看不懂「这条为什么没被判」。原始串只留一份在弹窗里，排障仍对得上框架日志（那行与弹窗由
 * [ReasonLine] / [ReasonDialog] 提供，学习屏用同一份）。
 *
 * 档位（全部 / 拦截 / 建议拦截 / 放行）记在 [RecordsFilterMemory] 里：本屏的 ViewModel 会随导航被
 * 销毁，档位不能跟着丢。首页点统计行跳进来时走那条一次性通道，不会改掉「上次用的档」。
 *
 * 行不做折叠、屏上不做筛选：观察模式下标定阈值要的是「一屏看全每条的判定与分数」。
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
                    TooltippedIconButton(
                        tooltip = stringResource(R.string.records_refresh),
                        onClick = { viewModel.refresh(ctx) },
                        enabled = !state.fetching,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.records_refresh))
                    }
                    TooltippedIconButton(
                        tooltip = stringResource(R.string.records_more),
                        onClick = { menuOpen = true },
                    ) {
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
            item { FilterRow(state.filter) { viewModel.setFilter(ctx, it) } }
            // 命中数紧跟筛选行：切档后视线就在这里，把窗口口径写在筛选项旁边才有人读到
            if (state.filter != RecordsViewModel.Filter.All) {
                item {
                    Note(
                        stringResource(
                            R.string.records_filter_hit,
                            state.matched,
                            RecordsViewModel.LIST_LIMIT,
                        )
                    )
                }
            }
            if (state.rows.isEmpty()) {
                item { EmptyHint(state.filter) }
            } else {
                items(state.rows, key = { it.key }) { row ->
                    RecordCard(
                        row = row,
                        busy = state.busy,
                        onMark = { viewModel.mark(ctx, row.key, it) },
                        onUndo = { viewModel.undo(ctx, row.key) },
                        onExplain = { viewModel.askExplain(row.key) },
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

    state.explanation?.let { ex ->
        ReasonDialog(ex, viewModel::dismissExplain)
    }
}

/**
 * 判定筛选。官方对单选分段按钮的定位就是「从一组选项里选一个、切换视图、或排序」，
 * 与这里互斥的四档正好对齐，选中态与单语义不必自造。
 */
@Composable
private fun FilterRow(
    selected: RecordsViewModel.Filter,
    onSelect: (RecordsViewModel.Filter) -> Unit,
) {
    val filters = RecordsViewModel.Filter.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        filters.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size),
            ) {
                Text(stringResource(filter.labelRes))
            }
        }
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

/**
 * 空态两分：窗口真的为空（判定链可能没跑，要去查框架日志）与当前档没有命中（换一档就有）是两种事实，
 * 合成一句会把「没记录」说成「筛没了」，用户就不会去查日志了。
 */
@Composable
private fun EmptyHint(filter: RecordsViewModel.Filter) {
    Text(
        text = if (filter == RecordsViewModel.Filter.All) {
            stringResource(R.string.records_empty)
        } else {
            stringResource(R.string.records_filter_empty)
        },
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
    onExplain: () -> Unit,
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
                    text = stringResource(row.verdict.labelRes),
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
            // 这行原本直接贴模块端的技术串（`disabled · EXT_SLOT`），看不懂只能去翻代码。
            // 现在出人话、点开给解释；原始串挪进弹窗，排障时仍能与框架日志对上
            ReasonLine(row.meta, onExplain)
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
