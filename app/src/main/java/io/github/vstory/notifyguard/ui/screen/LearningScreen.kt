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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.SpamAttribution
import io.github.vstory.notifyguard.ui.text

/**
 * 学习屏（M4h）：端侧学习这条链路的可解释面 —— 拟合走到哪一步、被拦的待表态、AI 判定的归因、孤儿标注。
 *
 * 与记录屏的分工是有意的：记录屏回答「刚才发生了什么」（滚动流水 + 标注入口），这一屏回答
 * 「我标的东西被用上了吗、这条为什么被拦」。两块有 AI 判定的卡都能展开看高亮，高亮片段按贡献排序取
 * 峰值的一部分 —— 哈希 n-gram 的桶里没有词表，所以只能到片段、到不了词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(viewModel: LearningViewModel = viewModel()) {
    val ctx = LocalContext.current.applicationContext
    val state = viewModel.state
    val snackbar = remember { SnackbarHostState() }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.learning_title)) },
                actions = {
                    TooltippedIconButton(
                        tooltip = stringResource(R.string.learning_refresh),
                        onClick = { viewModel.refresh(ctx) },
                        enabled = !state.fetching,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.learning_refresh))
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
            item { StatusCard(state.serviceText, state.connected, state.cfgLoaded) }
            item { FitCard(state) }
            item {
                PendingCard(
                    state = state,
                    onMark = { key, spam -> viewModel.mark(ctx, key, spam) },
                )
            }
            item {
                AiCard(
                    state = state,
                    onMark = { key, spam -> viewModel.mark(ctx, key, spam) },
                    onUndo = { viewModel.undo(ctx, it) },
                    onToggle = { viewModel.toggleDetail(it) },
                )
            }
            item {
                OrphanCard(
                    state = state,
                    onUndo = { viewModel.undo(ctx, it) },
                )
            }
        }
    }
}

/**
 * 拟合状态卡：**门槛进度用的是拟合真正会用的那份样本数**（凑不出特征的样本不计入），不是标注条数 ——
 * 显示标注条数会让用户看到「标了 12 条，界面说差 3 条」。状态行复用记录屏的 [fitLine]，两处各拼一遍
 * 迟早会拼出两种说法。
 */
@Composable
private fun FitCard(state: LearningViewModel.UiState) {
    GroupCard(stringResource(R.string.learning_fit_group)) {
        Note(stringResource(R.string.learning_fit_note))
        Text(
            text = fitLine(state.fit),
            style = MaterialTheme.typography.bodyMedium,
        )
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
 * 这里只给「标垃圾 / 标正常」两个动作、不给撤销：按构造这些条必然未标注，撤销没有对象。
 */
@Composable
private fun PendingCard(state: LearningViewModel.UiState, onMark: (String, Boolean) -> Unit) {
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
                    RowBody(row)
                    MarkActions(row, state.busy, undoable = false, onMark = onMark, onUndo = { _ -> })
                }
            }
        }
    }
}

/** 有 AI 判定的记录卡：可展开看归因。 */
@Composable
private fun AiCard(
    state: LearningViewModel.UiState,
    onMark: (String, Boolean) -> Unit,
    onUndo: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    GroupCard(stringResource(R.string.learning_ai_group)) {
        Note(stringResource(R.string.learning_ai_note))
        if (state.aiRows.isEmpty()) {
            Note(stringResource(R.string.learning_ai_empty))
        } else {
            state.aiRows.forEach { row ->
                val open = state.openKey == row.key
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RowBody(row)
                    if (open) {
                        DetailBlock(row, state.detail)
                    }
                    MarkActions(row, state.busy, undoable = true, onMark = onMark, onUndo = onUndo)
                    // 展开用独立按钮而不是整行可点：行里已经有三个动作按钮，整行可点会让它们的
                    // 点按区域互相盖住，误触的后果是标注被改掉（不可感知）
                    TextButton(
                        onClick = { onToggle(row.key) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (open) R.string.learning_detail_hide else R.string.learning_detail_show
                            )
                        )
                    }
                }
            }
        }
    }
}

/** 孤儿标注卡：记录窗口里已经找不到的标注。 */
@Composable
private fun OrphanCard(state: LearningViewModel.UiState, onUndo: (String) -> Unit) {
    GroupCard(stringResource(R.string.learning_orphan_group)) {
        Note(stringResource(R.string.learning_orphan_note))
        if (state.orphans.isEmpty()) {
            Note(stringResource(R.string.learning_orphan_empty))
        } else {
            state.orphans.forEach { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RowHead(row.time, row.spam)
                    Text(
                        text = row.pkg,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { onUndo(row.key) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.action_undo)) }
                }
            }
        }
    }
}

/**
 * 行的头行：时间 +（右侧）标注状态。三块卡共用 —— 孤儿卡原先自己手写了一套时间行，于是把标注状态
 * 整个落下了，而那张卡正是唯一能撤销标注的地方（撤销前看不到标的是哪个方向）。
 */
@Composable
private fun RowHead(time: String, marked: Boolean?) {
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

/** 行的公共部分：头行 + 包名 + 文本 + 判定串与分数。 */
@Composable
private fun RowBody(row: LearningViewModel.Row) {
    RowHead(row.time, row.marked)
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
    // 分数单独取 score 字段而不是解析 reason：reason 里只有两位小数，是给日志看的技术串
    row.score?.let {
        Text(
            text = stringResource(R.string.learning_score, "%.2f".format(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 改判 / 撤销。已标注的那一侧置灰（同义指令按出来没有意义），在途时全部置灰（连点会并发发出多条，
 * 而先到的回执会盖掉后点那次的状态）。
 */
@Composable
private fun MarkActions(
    row: LearningViewModel.Row,
    busy: Boolean,
    /** AI 卡里是「改判」（可能已标注），待处理卡里是「表态」（按构造必然未标注，撤销没有对象）。 */
    undoable: Boolean,
    onMark: (String, Boolean) -> Unit,
    onUndo: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { onMark(row.key, true) },
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = !busy && row.marked != true,
        ) { Text(stringResource(R.string.action_mark_spam)) }
        TextButton(
            onClick = { onMark(row.key, false) },
            modifier = Modifier.heightIn(min = 48.dp),
            enabled = !busy && row.marked != false,
        ) { Text(stringResource(R.string.action_mark_ham)) }
        if (undoable) {
            TextButton(
                onClick = { onUndo(row.key) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !busy && row.marked != null,
            ) { Text(stringResource(R.string.action_undo)) }
        }
    }
}

/**
 * 展开块：把归因结果说清。三种「算不出」分别有各自的说明 —— 一律画成空白的话，用户会把
 * 「这条不是 AI 判的」「原文对不上」「没有突出片段」当成同一个故障。
 */
@Composable
private fun DetailBlock(row: LearningViewModel.Row, detail: LearningViewModel.Detail?) {
    val text = row.aiText
    when {
        text == null -> Note(stringResource(R.string.learning_detail_no_text))

        detail == null || detail is LearningViewModel.Detail.Computing ->
            Note(stringResource(R.string.learning_detail_computing))

        detail is LearningViewModel.Detail.NoModel -> Note(stringResource(R.string.learning_detail_no_model))

        detail is LearningViewModel.Detail.Ready -> {
            Note(
                stringResource(
                    if (detail.tuned) R.string.learning_detail_tuned else R.string.learning_detail_base_only
                )
            )
            when (val result = detail.result) {
                is SpamAttribution.Result.NoFeatures -> Note(stringResource(R.string.learning_detail_no_features))

                is SpamAttribution.Result.Unmappable -> Note(stringResource(R.string.learning_detail_unmappable))

                is SpamAttribution.Result.Ok -> if (result.spans.isEmpty()) {
                    Note(stringResource(R.string.learning_detail_no_span))
                } else {
                    Note(stringResource(R.string.learning_detail_span_note))
                    HighlightedText(detail.text, result.spans)
                }
            }
        }
    }
}

/**
 * 把片段画成高亮。
 *
 * 底色与文字色取同一对 *Container / on*Container 角色：只上底色而文字沿用 onSurface 时，深色下
 * 高亮区里的字会与底色糊在一起（这类对比度问题在代码里看不出来，只有真机能发现）。
 */
@Composable
private fun HighlightedText(text: String, spans: List<SpamAttribution.Span>) {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    val annotated = remember(text, spans, bg, fg) {
        buildAnnotatedString {
            var cursor = 0
            for (span in spans) {
                // 防御性收敛：片段越界时宁可少画一段，也不能让 substring 抛异常打掉整屏
                val from = span.start.coerceIn(0, text.length)
                val to = span.end.coerceIn(from, text.length)
                if (from > cursor) append(text.substring(cursor, from))
                if (to > from) {
                    withStyle(SpanStyle(background = bg, color = fg)) { append(text.substring(from, to)) }
                }
                cursor = to
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 12,
        overflow = TextOverflow.Ellipsis,
    )
}
