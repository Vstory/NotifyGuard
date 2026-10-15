package io.github.vstory.notifyguard.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ui.AppIcon
import io.github.vstory.notifyguard.ui.text



/**
 * 学习屏（M4h）：端侧学习这条链路的可解释面 —— 待表态的误杀、全部样本、拟合走到哪一步。
 *
 * 与记录屏的分工是有意的：记录屏回答「刚才发生了什么」（滚动流水 + 标注入口），这一屏回答
 * 「我标的东西被用上了吗、这东西到底有没有被纠过来」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(viewModel: LearningViewModel = viewModel()) {
    val ctx = LocalContext.current.applicationContext
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        val unbind = viewModel.bind()
        onDispose { unbind() }
    }
    LaunchedEffect(Unit) { viewModel.refresh(ctx) }
    // 文案必须在 Composable 上下文里解析：LaunchedEffect 的 block 不是 @Composable
    val notice = state.notice
    val noticeText = notice?.text?.text()
    LaunchedEffect(notice) {
        if (notice != null && noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.noticeShown(notice)
        }
    }

    // 搜索态只属于样本库：它占顶栏，与记录屏共用同一份 SearchTopBar
    BackHandler(enabled = state.libSearchOpen) { viewModel.setLibSearchOpen(false) }

    Scaffold(
        topBar = {
            if (state.libSearchOpen) {
                SearchTopBar(
                    query = state.libQuery,
                    onQueryChange = viewModel::setLibQuery,
                    onClose = { viewModel.setLibSearchOpen(false) },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.learning_title)) },
                    modifier = topBarScrollToTop(listState),
                    actions = {
                        if (state.tab == LearningViewModel.Tab.Library) {
                            TooltippedIconButton(
                                tooltip = stringResource(R.string.learning_search_samples),
                                onClick = { viewModel.setLibSearchOpen(true) },
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.learning_search_samples),
                                )
                            }
                        }
                        TooltippedIconButton(
                            tooltip = stringResource(R.string.learning_refresh),
                            onClick = { viewModel.refresh(ctx) },
                            enabled = !state.fetching,
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.learning_refresh),
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StatusCard(state.serviceText, state.connected, state.cfgLoaded) }
            item {
                LearningTabs(
                    tab = state.tab,
                    pending = state.pending.size,
                    total = state.labelTotal,
                    onSelect = viewModel::setTab,
                )
            }
            when (state.tab) {
                LearningViewModel.Tab.Pending -> item {
                    PendingCard(
                        state = state,
                        onMark = { key, spam -> viewModel.mark(ctx, key, spam) },
                        onExplain = { viewModel.askExplain(it) },
                    )
                }

                LearningViewModel.Tab.Library -> {
                    item {
                        LibraryBar(
                            filter = state.libFilter,
                            spam = state.libSpam,
                            ham = state.labelTotal - state.libSpam,
                            total = state.labelTotal,
                            shown = state.libGroups.sumOf { it.rows.size },
                            query = state.libQuery,
                            onFilter = viewModel::setLibFilter,
                        )
                    }
                    if (state.libGroups.isEmpty()) {
                        item { Note(stringResource(R.string.learning_lib_empty)) }
                    }
                    items(state.libGroups, key = { it.pkg }) { group ->
                        SampleGroup(
                            group = group,
                            expanded = group.pkg in state.openGroups,
                            shown = state.groupShown[group.pkg] ?: LearningViewModel.GROUP_PAGE,
                            busy = state.busy,
                            onToggle = { viewModel.toggleGroup(group.pkg) },
                            onMore = { viewModel.showMoreInGroup(group.pkg) },
                            onFlip = { key, spam -> viewModel.mark(ctx, key, spam) },
                            onDelete = { viewModel.deleteSample(ctx, it) },
                        )
                    }
                }

                LearningViewModel.Tab.Fit -> item {
                    FitCard(
                        state = state,
                        onResend = { viewModel.resendFit(ctx) },
                    )
                }
            }
        }
    }

    // 判定说明与记录屏同一份：同一条记录在两屏点开必须是同一段话
    state.explanation?.let { ex ->
        ReasonDialog(ex, null, null, viewModel::dismissExplain)
    }
}

/**
 * 拟合状态卡：**门槛进度用的是拟合真正会用的那份样本数**（凑不出特征的样本不计入），不是标注条数 ——
 * 显示标注条数会让用户看到「标了 12 条，界面说差 3 条」。状态行复用记录屏的 [fitLine]，两处各拼一遍
 * 迟早会拼出两种说法。
 */
@Composable
private fun FitCard(
    state: LearningViewModel.UiState,
    onResend: () -> Unit,
) {
    GroupCard(stringResource(R.string.learning_fit_group)) {
        Note(stringResource(R.string.learning_fit_note))
        fitProgressText(state.fitProgress)?.let { Note(it.text()) }
        Text(
            text = fitLine(state.fit),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        // 操作按钮独占一行：状态串里带时间戳与摘要（`2026-10-10T04:16:47Z+c63df84c`），
        // 与按钮挤同一行时会在时间戳中间折行，看起来像串坏了
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            // 重算 → 与模块端手上那份比对：内容一样就不发（再发一次写的是同样的字节）
            TextButton(onClick = onResend, enabled = !state.fitBusy) {
                Text(stringResource(R.string.learning_fit_resend))
            }
        }
        Note(stringResource(R.string.learning_fit_resend_hint))
        Note(stringResource(R.string.learning_labels_line, state.labelTotal))
        state.fetchError?.let {
            Text(
                text = it.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.labelError?.let {
            Text(
                text = it.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 待处理卡：被拦下但还没表态的条目，也就是误杀回溯的入口。
 *
 * 这张卡只给「标正常」一个动作：条目都是已被拦下的（模型已判它为垃圾），用户能补的信息只有
 * 「拦错了」那一个方向 —— 再点一次「标垃圾」等于把模型的判定抄一遍，没有信息量，而模型的
 * 正类样本由 [io.github.vstory.notifyguard.sync.DeltaFitter] 直接取被拦记录供上。也不给撤销：
 * 按构造这些条必然未标注，撤销没有对象。
 */
@Composable
private fun PendingCard(
    state: LearningViewModel.UiState,
    onMark: (String, Boolean) -> Unit,
    onExplain: (String) -> Unit,
) {
    GroupCard(stringResource(R.string.learning_pending_group)) {
        Note(stringResource(R.string.learning_pending_note))
        if (state.pending.isEmpty()) {
            Note(stringResource(R.string.learning_pending_empty))
        } else {
            state.pending.forEach { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RowBody(row, onExplain)
                    MarkActions(
                        row.key, row.marked, state.busy,
                        undoable = false, spamAllowed = false,
                        onMark = onMark, onUndo = { _ -> },
                    )
                }
            }
        }
    }
}
/**
 * 栏切换：待处理 / 样本库 / 微调。三栏各自滚动，不并成一条长页 —— 样本库几千条，和待处理挤在
 * 同一个滚动容器里就是滚到死。
 */
@Composable
private fun LearningTabs(
    tab: LearningViewModel.Tab,
    pending: Int,
    total: Int,
    onSelect: (LearningViewModel.Tab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabChip(
            stringResource(R.string.learning_tab_pending, pending),
            tab == LearningViewModel.Tab.Pending,
        ) { onSelect(LearningViewModel.Tab.Pending) }
        TabChip(
            stringResource(R.string.learning_tab_library, total),
            tab == LearningViewModel.Tab.Library,
        ) { onSelect(LearningViewModel.Tab.Library) }
        TabChip(stringResource(R.string.learning_tab_fit), tab == LearningViewModel.Tab.Fit) {
            onSelect(LearningViewModel.Tab.Fit)
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 样本库的筛选与计数。
 *
 * 两组数字不同源，别混：按钮上的是**全库分方向总计**（选中「正常」时也要看得到「垃圾 116」才知道
 * 另一边有多少），下面那行是**当前筛选与搜索之后**的条数。
 */
@Composable
private fun LibraryBar(
    filter: LearningViewModel.LibFilter,
    spam: Int,
    ham: Int,
    total: Int,
    shown: Int,
    query: String,
    onFilter: (LearningViewModel.LibFilter) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip(
                stringResource(R.string.learning_lib_filter_all, total),
                filter == LearningViewModel.LibFilter.All,
            ) { onFilter(LearningViewModel.LibFilter.All) }
            TabChip(
                stringResource(R.string.learning_lib_filter_spam, spam),
                filter == LearningViewModel.LibFilter.Spam,
            ) { onFilter(LearningViewModel.LibFilter.Spam) }
            TabChip(
                stringResource(R.string.learning_lib_filter_ham, ham),
                filter == LearningViewModel.LibFilter.Ham,
            ) { onFilter(LearningViewModel.LibFilter.Ham) }
        }
        Note(
            if (query.isEmpty()) {
                stringResource(R.string.learning_lib_count, shown)
            } else {
                stringResource(R.string.learning_lib_hit, shown)
            }
        )
    }
}

/**
 * 一组样本（同一个来源 App）。
 *
 * 折叠是这栏的核心：同一个 App 的垃圾往往几十上百条，平铺只能一条条滚过去；收起时只留一行
 * 「组名 + 条数」，一眼看出是哪个 App 在灌。
 */
@Composable
private fun SampleGroup(
    group: LearningViewModel.Group,
    expanded: Boolean,
    shown: Int,
    busy: Boolean,
    onToggle: () -> Unit,
    onMore: () -> Unit,
    onFlip: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 官方 ListItem：leading 是应用图标（一眼认出是哪个 App），条数与折叠箭头都在 trailing ——
        // 「次要动作与信息放右侧」是 Material 列表控件的规定位置
        ListItem(
            headlineContent = {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { AppIcon(group.pkg) },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.learning_lib_group_count, group.rows.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.clickable(onClick = onToggle),
        )
        if (expanded) {
            val visible = group.rows.take(shown)
            visible.forEach { row -> SampleLine(row, busy, onFlip, onDelete) }
            val rest = group.rows.size - visible.size
            if (rest > 0) {
                TextButton(onClick = onMore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.learning_lib_group_more, rest))
                }
            }
        }
    }
}

/**
 * 一条样本。文本默认折两行 —— 一条营销文案能占半屏，全展开就没人看得见列表。
 * `remember(row.key)`：列表重绘（改判、别处删除）时展开态跟着那条走，而不是挪到邻居身上。
 */
@Composable
private fun SampleLine(
    row: LearningViewModel.SampleRow,
    busy: Boolean,
    onFlip: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember(row.key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (row.spam) R.string.learning_sample_spam else R.string.learning_sample_ham
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.spam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = row.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { onFlip(row.key, !row.spam) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy,
            ) {
                Text(
                    stringResource(
                        if (row.spam) R.string.learning_sample_to_ham else R.string.learning_sample_to_spam
                    )
                )
            }
            TextButton(
                onClick = { onDelete(row.key) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy,
            ) { Text(stringResource(R.string.learning_sample_delete)) }
        }
    }
}

/**
 * 行的头行：时间 +（紧随）判定 +（右端）标注状态。
 *
 * 判定为 `null` 时只画时间：没有对应记录的条目给不出判定。
 */
@Composable
private fun RowHead(time: String, verdict: Verdict?, marked: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        verdict?.let {
            Text(
                text = stringResource(it.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = verdictColor(it),
            )
        }
        Spacer(Modifier.weight(1f))
        marked?.let { spam ->
            Text(
                text = stringResource(
                    if (spam) R.string.records_marked_spam else R.string.records_marked_ham
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (spam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 行的公共部分：头行 + 包名 + 文本 + 判定元数据。
 *
 * 那行元数据与它的弹窗由 [ReasonLine] / [ReasonDialog] 提供（记录屏用同一份）：分数、规则 id 都已在
 * 短语里，紧挨着再写一行「分数 0.87」是同一个数说两遍。
 */
@Composable
private fun RowBody(row: LearningViewModel.Row, onExplain: (String) -> Unit) {
    RowHead(row.time, row.verdict, row.marked)
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
    ReasonLine(row.meta) { onExplain(row.key) }
}

/**
 * 改判 / 撤销。已标注的那一侧置灰（同义指令按出来没有意义），在途时全部置灰（连点会并发发出多条，
 * 而先到的回执会盖掉后点那次的状态）。
 *
 * 收 key 与当前方向而不是整个行模型：三张卡的行模型不同（[LearningViewModel.Row] 与
 * [LearningViewModel.SampleRow]），而动作需要的只有这两样 —— 各卡自己写一份动作行，迟早有一处把
 * 「已经是当前方向」的那侧画成可点。
 */
@Composable
private fun MarkActions(
    key: String,
    /** 当前方向；`null` = 未标注。 */
    marked: Boolean?,
    busy: Boolean,
    /** AI 卡与孤儿卡里是「改判」（可能已标注），待处理卡里是「表态」（按构造必然未标注，撤销没有对象）。 */
    undoable: Boolean,
    /** 给哪一侧的按钮，由条目的判定三态决定（见 `Verdict.markSpamAllowed`）。 */
    spamAllowed: Boolean = true,
    hamAllowed: Boolean = true,
    onMark: (String, Boolean) -> Unit,
    onUndo: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (spamAllowed) {
            TextButton(
                onClick = { onMark(key, true) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy && marked != true,
            ) { Text(stringResource(R.string.action_mark_spam)) }
        }
        if (hamAllowed) {
            TextButton(
                onClick = { onMark(key, false) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy && marked != false,
            ) { Text(stringResource(R.string.action_mark_ham)) }
        }
        if (undoable) {
            TextButton(
                onClick = { onUndo(key) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy && marked != null,
            ) { Text(stringResource(R.string.action_undo)) }
        }
    }
}
