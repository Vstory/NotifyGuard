package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.judge.Judge
import io.github.vstory.notifyguard.judge.SpamFeatures
import java.util.TreeMap
import kotlin.math.exp

/**
 * 端侧拟合：从 Δ=0 出发，在**冻结的 base** 上做确定性 SGD，最小化
 * `Σ logloss(σ(z0 + Δ·x)) + (l2/2)·‖Δ‖²`。
 *
 * 拟合只在 App 进程跑（纯 CPU 活，不该占 system_server），但类放在同一个 APK 里：
 * 判定侧要用同一份实现，两端各写一份就是分叉的开始。
 *
 * 「同标注必得同 delta」由三件事保证，缺一个就不可复现、也就不可单测：
 * 样本按 [Sample.key] 升序、固定轮数、输出按桶下标升序。迭代顺序不能依赖 Map 实现。
 */
object SpamTuner {

    data class Sample(val key: String, val text: String, val spam: Boolean)

    const val EPOCHS = 60
    const val LR = 1.0
    const val L2 = 0.005

    /** 门槛（判据 J8）：样本总量与两类各自的量都不够时返回 null，调用方据此不下发、提示用户再标几条。 */
    const val MIN_LABELS = 10
    const val MIN_PER_CLASS = 2

    private class Prepared(val z0: Double, val y: Double, val keys: IntArray, val x: DoubleArray)

    /** 可训练样本的盘点（门槛判定与 UI 提示共用同一口径）。 */
    data class Readiness(val usable: Int, val spam: Int, val ham: Int) {
        val ready: Boolean get() = usable >= MIN_LABELS && spam >= MIN_PER_CLASS && ham >= MIN_PER_CLASS

        /**
         * 离过门槛还差几条标注（0 = 已过）。取三类缺口里最大的那个 —— 这是「新标的每条都能用」的乐观估计：
         * 界面要的是一个照着标就能达标的数字，而「新那条也可能凑不出特征」只有拟合时才揭晓。
         */
        val shortfall: Int
            get() = maxOf(MIN_LABELS - usable, MIN_PER_CLASS - spam, MIN_PER_CLASS - ham, 0)
    }

    sealed interface Fit {
        data class Ok(val delta: SpamDelta) : Fit

        data class NotReady(val readiness: Readiness) : Fit
    }

    /**
     * 门槛按**过滤后**的样本判：标注了 12 条但 5 条是纯符号凑不出 gram 时，实际参与拟合的只有 7 条，
     * 按 12 条放行等于用 7 条样本去动全量权重。
     *
     * `Readiness.usable` 与 [fit] 内部用的是同一份过滤结果（同一个 [readinessOf]），不存在
     * 「界面说够了、拟合却空手而归」。
     */
    fun fit(base: SpamModel, samples: List<Sample>): Fit {
        val prepared = preparedOf(base, samples)
        val readiness = readinessOf(prepared)
        if (!readiness.ready) return Fit.NotReady(readiness)

        val delta = HashMap<Int, Double>()
        repeat(EPOCHS) {
            for (s in prepared) {
                var z = s.z0
                for (i in s.keys.indices) z += (delta[s.keys[i]] ?: 0.0) * s.x[i]
                val g = sigmoid(z) - s.y
                for (i in s.keys.indices) {
                    val k = s.keys[i]
                    val cur = delta[k] ?: 0.0
                    delta[k] = cur - LR * (g * s.x[i] + L2 * cur)
                }
            }
        }
        val keys = delta.keys.filter { delta[it] != 0.0 }.sorted()
        return Fit.Ok(
            SpamDelta(
                buckets = base.buckets,
                baseFingerprint = base.fingerprintU32,
                indices = keys.toIntArray(),
                values = FloatArray(keys.size) { delta[keys[it]]!!.toFloat() },
            )
        )
    }

    /**
     * 冻结点：预先算好 base 给出的 `z0` 与 L2 归一化后的样本向量，之后每轮只碰 delta。
     * 样本过滤口径与判定侧对齐（过短文本判定链根本不进 AI 段，那种样本拟合出来是噪声）。
     */
    /**
     * 只算门槛进度、不拟合：界面要**随时**回答「还差几条」，而拟合成功之后就没有 `Fit.NotReady` 可拿了。
     */
    fun readiness(base: SpamModel, samples: List<Sample>): Readiness = readinessOf(preparedOf(base, samples))

    private fun preparedOf(base: SpamModel, samples: List<Sample>): List<Prepared> =
        samples.sortedBy { it.key }.mapNotNull { prepare(base, it) }

    private fun readinessOf(prepared: List<Prepared>): Readiness {
        val spam = prepared.count { it.y == 1.0 }
        return Readiness(prepared.size, spam, prepared.size - spam)
    }

    private fun prepare(base: SpamModel, sample: Sample): Prepared? {
        val text = sample.text
        if (text.lowercase().trim().length < Judge.MIN_AI_LEN) return null
        val counts = SpamFeatures.counts(text, base.buckets, base.ngramMin, base.ngramMax)
        if (counts.isEmpty()) return null
        // 判据：字典序升序遍历（TreeMap）——与打分侧同一顺序，z0 才能与推理时逐位一致
        val sorted = TreeMap<Int, Int>(counts)
        var sq = 0L
        for (c in sorted.values) sq += c.toLong() * c
        val norm = Math.sqrt(sq.toDouble())
        val keys = IntArray(sorted.size)
        val x = DoubleArray(sorted.size)
        var z0 = base.bias.toDouble()
        var i = 0
        for ((k, c) in sorted) {
            keys[i] = k
            x[i] = c / norm
            z0 += base.baseWeight(k).toDouble() * x[i]
            i++
        }
        return Prepared(z0, if (sample.spam) 1.0 else 0.0, keys, x)
    }

    private fun sigmoid(z: Double): Double =
        if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }
}
