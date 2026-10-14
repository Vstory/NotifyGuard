package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.SpamAttribution
import io.github.vstory.notifyguard.ai.SpamDelta
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.DeltaWriter

/**
 * 归因结果的界面状态。
 *
 * 分三态而不是 `Ready?`：`null` 会同时表示「还没算」与「算不出来」，界面只能二选一地说，
 * 两种说法在另一种情形下都是错的（说「正在算」会一直转，说「算不了」则是假话）。
 */
sealed interface AttributionState {
    data object Computing : AttributionState

    /** 内置模型读不出（资源缺失 / 解析失败）：没有权重就没有归因，只能明说。 */
    data object NoModel : AttributionState

    data class Ready(
        val text: String,
        /** 权重是否含已下发的微调量；false = 读不到 delta，降级为纯 base（界面必须说出来）。 */
        val tuned: Boolean,
        val result: SpamAttribution.Result,
    ) : AttributionState
}

/**
 * 算一条文本的归因。
 *
 * 权重与判定同源：base 基权重叠加当前已下发的 delta（与 [io.github.vstory.notifyguard.ai.TunedScorer]
 * 同构）。读不到 delta 时退 base-only —— 界面据此明示降级，绝不假装与实际判定一致。
 *
 * 冷路径：要遍历整条文本的 n-gram，只能在用户点开时算，进屏就全算是白烧电。
 */
internal fun attributeOf(text: String): AttributionState {
    val model = DeltaFitter.baseModel() ?: return AttributionState.NoModel
    val delta = (DeltaWriter.read(model)?.parsed as? SpamDelta.Parse.Ok)?.delta
    // 稀疏表：归因只碰文本里出现过的桶，摊成 2^18 长的稠密数组是白分配 1 MB
    val extra: HashMap<Int, Float>? = delta?.let { d ->
        HashMap<Int, Float>(d.indices.size).also { m ->
            for (i in d.indices.indices) m[d.indices[i]] = d.values[i]
        }
    }
    val weightOf: (Int) -> Double = { k ->
        model.baseWeight(k).toDouble() + (extra?.get(k)?.toDouble() ?: 0.0)
    }
    return AttributionState.Ready(
        text = text,
        tuned = extra != null,
        result = SpamAttribution.attribute(text, model.buckets, model.ngramMin, model.ngramMax, weightOf),
    )
}

/**
 * 把归因结果说清。三种「算不出」分别有各自的说明 —— 一律画成空白的话，用户会把
 * 「这条不是 AI 判的」「原文对不上」「没有突出片段」当成同一个故障。
 *
 * [aiText] 是判定实际用过的文本（[io.github.vstory.notifyguard.judge.LogRecord.aiText]），
 * 拿被截断到两百字的展示文本去高亮，画的是一段判定根本没用过的文字。
 */
@Composable
internal fun AttributionBlock(aiText: String?, state: AttributionState?) {
    when {
        aiText == null -> Note(stringResource(R.string.attribution_no_text))

        state == null || state is AttributionState.Computing ->
            Note(stringResource(R.string.attribution_computing))

        state is AttributionState.NoModel -> Note(stringResource(R.string.attribution_no_model))

        state is AttributionState.Ready -> {
            Note(
                stringResource(
                    if (state.tuned) R.string.attribution_tuned else R.string.attribution_base_only
                )
            )
            when (val result = state.result) {
                is SpamAttribution.Result.NoFeatures -> Note(stringResource(R.string.attribution_no_features))

                is SpamAttribution.Result.Unmappable -> Note(stringResource(R.string.attribution_unmappable))

                is SpamAttribution.Result.Ok -> if (result.spans.isEmpty()) {
                    Note(stringResource(R.string.attribution_no_span))
                } else {
                    Note(stringResource(R.string.attribution_span_note))
                    HighlightedText(state.text, result.spans)
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
        modifier = Modifier.fillMaxWidth(),
    )
}
