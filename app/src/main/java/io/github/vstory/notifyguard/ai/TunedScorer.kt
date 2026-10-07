package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.judge.SpamFeatures

/**
 * base + delta 的合成打分器（判定侧在微调生效后用的就是它）。
 *
 * 打分项 = `base 基权重 + delta[k]`，即 delta 是**未量化**的 float32 增量。这不影响 base 自身的
 * parity（[SpamModel.score] 一字未动）：delta 为空时调用方就该退回 base，而不是用一个等价实现兜着。
 *
 * 累加顺序与 [SpamModel.score] 相同（升序 key）：顺序一变浮点和的末位就变，跨端容差会随机失败。
 */
class TunedScorer(private val base: SpamModel, delta: SpamDelta) : SpamScorer {

    private val dense = delta.dense()

    override fun score(text: String): Double {
        val counts = SpamFeatures.counts(text, base.buckets, base.ngramMin, base.ngramMax)
        if (counts.isEmpty()) return SpamModel.sigmoidOf(base.bias.toDouble())
        var sq = 0L
        for (c in counts.values) sq += c.toLong() * c
        val norm = Math.sqrt(sq.toDouble())
        var z = base.bias.toDouble()
        for ((k, c) in counts) {
            z += (base.baseWeight(k) + dense[k]).toDouble() * (c / norm)
        }
        return SpamModel.sigmoidOf(z)
    }
}
