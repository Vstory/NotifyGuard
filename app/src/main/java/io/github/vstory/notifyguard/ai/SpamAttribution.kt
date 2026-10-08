package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.judge.SpamFeatures

/**
 * 贡献归因：算出「这条文本里哪些片段把分数推高了」，供学习屏画近似高亮。
 *
 * **为什么只能到片段、到不了词**：特征把 1–3 gram 哈希进 [SpamFeatures.BUCKETS] 个桶，桶里只有权重，
 * 反查不出词表。所以这里做的是「逐段算贡献」，不是词典式的关键词解释 —— 数据结构上就没有词。
 *
 * **归因必须与判定同源**：权重由调用方给（[SpamModel] 基权重叠加当前已下发的 delta），且全部分摊之和
 * 恰好等于打分前的 `z` 减 bias。读不到 delta 时调用方降级为 base-only 并在界面明示：高亮与实际拦截
 * 依据不一致，比没有高亮更坏。
 *
 * **分摊而不是逐位置重算**：重算等于每个位置删掉再打一次分，长文本要跑上百次；分摊是一次遍历，
 * 且每个出现的分摊之和严格等于打分里该桶那一项 `w[k]·c/norm`。
 */
object SpamAttribution {

    /** 高亮片段：原文的 UTF-16 码元区间，左闭右开。 */
    data class Span(val start: Int, val end: Int)

    sealed interface Result {
        /**
         * @param spans 按原文下标升序、互不相接；空列表 = 没有明显推高分数的片段
         * @param contribution 全部分摊之和（含负权重，不含 bias）。测试拿它与打分对账：
         *   `contribution + bias` 必须等于 [SpamModel.score] 打分前的 `z`
         */
        data class Ok(val spans: List<Span>, val contribution: Double) : Result

        /** 归一化后没有特征（空文本 / 纯符号 / 纯数字）：没有特征也就没有贡献。 */
        data object NoFeatures : Result

        /** 原文与归一化串不再一一对应（小写改了码元数），高亮落不到正确的字上。 */
        data object Unmappable : Result
    }

    /**
     * 片段门槛：占本条最大片段贡献的比例。
     *
     * 取**相对值**不取绝对值：base 与 delta 的量级不同，绝对值在换模型或首次下发 delta 之后会整体失效，
     * 表现是「全亮」或「全不亮」。
     */
    const val SPAN_RATIO = 0.6

    /**
     * @param weightOf 桶下标 → 该桶权重。调用方负责保证它与判定用的是同一份权重（base + 已下发的 delta）。
     */
    fun attribute(
        text: String,
        buckets: Int,
        ngramMin: Int,
        ngramMax: Int,
        weightOf: (Int) -> Double,
    ): Result {
        val mapped = SpamFeatures.normalizeMapped(text)
        if (!mapped.reliable) return Result.Unmappable
        val chars = mapped.text.toCharArray()
        if (chars.isEmpty()) return Result.NoFeatures

        // 一遍枚举：记下每个 gram 的位置与桶，同时统计各桶出现次数（打分用的 norm 由它算）
        val grams = ArrayList<Gram>()
        val occurrences = HashMap<Int, Int>()
        for (g in ngramMin..ngramMax) {
            if (g > chars.size) break
            for (s in 0..chars.size - g) {
                val bucket = SpamFeatures.bucketOf(chars, s, g, buckets)
                grams.add(Gram(s, g, bucket))
                occurrences[bucket] = (occurrences[bucket] ?: 0) + 1
            }
        }
        var sq = 0L
        for (c in occurrences.values) sq += c.toLong() * c
        val norm = Math.sqrt(sq.toDouble())
        if (norm == 0.0) return Result.NoFeatures

        // 逐**原文**码元累加。枚举按归一化位置升序，而映射单调不减 ⇒ 累加顺序本身就是原文下标升序；
        // 顺序不固定时浮点和的末位会抖，片段边界看起来像「模型不稳定」，实际是累加顺序问题
        val contrib = DoubleArray(text.length)
        for (gram in grams) {
            // 按**字符**数分摊而不是码元数：代理对占两个码元却只是一个字符，按码元分会让该 gram 的总和
            // 短掉一截，`contribution + bias = z` 这条对账在含 emoji 的文本上就不成立了
            val share = weightOf(gram.bucket) / norm / charCount(mapped, gram)
            if (share == 0.0) continue
            for (i in gram.start until gram.start + gram.len) {
                // 同一字符的第二个码元（代理对）指回同一段原文，跳过 —— 重复加会让它翻倍
                if (i > gram.start && mapped.sourceStart[i] == mapped.sourceStart[i - 1]) continue
                val from = mapped.sourceStart[i]
                val to = mapped.sourceEnd[i]
                val each = share / (to - from)
                for (p in from until to) contrib[p] += each
            }
        }

        var sum = 0.0
        var peak = 0.0
        for (v in contrib) {
            sum += v
            if (v > peak) peak = v
        }
        // 只有正贡献算「推高分数」；负的归因画出来是「这段在压低分数」，与这一屏要说的事相反
        if (peak <= 0.0) return Result.Ok(emptyList(), sum)

        val threshold = peak * SPAN_RATIO
        val spans = ArrayList<Span>()
        var i = 0
        while (i < contrib.size) {
            if (contrib[i] > 0.0 && contrib[i] >= threshold) {
                val from = i
                while (i < contrib.size && contrib[i] > 0.0 && contrib[i] >= threshold) i++
                spans.add(Span(from, i))
            } else {
                i++
            }
        }
        return Result.Ok(spans, sum)
    }

    /**
     * gram 覆盖的**字符**数（代理对计一个）。判据与上面累加时的跳过条件同源：映射里两个码元指向同一段
     * 原文，就说明它们属于同一个字符 —— 两处各写一套判据时，一方改了另一方不会红。
     */
    private fun charCount(mapped: SpamFeatures.Mapped, gram: Gram): Int {
        var n = 0
        for (i in gram.start until gram.start + gram.len) {
            if (i > gram.start && mapped.sourceStart[i] == mapped.sourceStart[i - 1]) continue
            n++
        }
        return n
    }

    private class Gram(val start: Int, val len: Int, val bucket: Int)
}
