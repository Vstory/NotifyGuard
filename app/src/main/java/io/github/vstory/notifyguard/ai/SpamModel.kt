package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.judge.SpamFeatures
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * base 模型：`NSPM` 二进制（与 `ml/export.py` 同源）。
 *
 * 内置只读——端侧自学习产生的是 delta，叠在内存副本上（M3），文件本身从不改写。
 * 打分口径与 `ml/export.py::score` 逐位对齐，`SpamModelParityTest` 拿 `ml/parity.json` 逐条比。
 */
class SpamModel private constructor(
    val buckets: Int,
    val ngramMin: Int,
    val ngramMax: Int,
    val bias: Float,
    val scale: Float,
    private val weights: ByteArray,
    /** 文件字节的 CRC32：delta 拿它校验「基于哪一版 base 拟合」（M3）。 */
    val fingerprint: Int,
) : SpamScorer {

    override fun score(text: String): Double {
        val counts = SpamFeatures.counts(text, buckets, ngramMin, ngramMax)
        if (counts.isEmpty()) return sigmoidOf(bias.toDouble())
        var sq = 0L
        for (c in counts.values) sq += c.toLong() * c
        val norm = Math.sqrt(sq.toDouble())
        var z = bias.toDouble()
        for ((k, c) in counts) {
            // 与 Python 同形：int8 × float32 先按单精度舍入，再升 double 累加（顺序已由升序 key 固定）
            z += (weights[k] * scale).toDouble() * (c / norm)
        }
        return sigmoidOf(z)
    }

    /**
     * 单桶的基权重（已反量化）。给 [TunedScorer] 与 [SpamTuner] 用：它们要的是「base 的那一项」，
     * 而不是「base 的最终分数」，所以不能靠调 [score] 绕。
     */
    internal fun baseWeight(k: Int): Float = weights[k] * scale

    fun fingerprintHex(): String = "%08x".format(fingerprint)

    /** 文件 CRC32 的**无符号**形式。delta 头里存的是它，用 Int 装负值再比会两边都自认为一致不了。 */
    val fingerprintU32: Long get() = fingerprint.toLong() and 0xFFFFFFFFL

    companion object {

        internal fun sigmoidOf(z: Double): Double {
            if (z >= 0) return 1.0 / (1.0 + Math.exp(-z))
            val e = Math.exp(z)
            return e / (1.0 + e)
        }


        const val RESOURCE = "model/model.bin"

        private const val MAGIC = 0x4E53504D // "NSPM"
        private const val VERSION = 1
        private const val HEADER_BYTES = 28

        /** 任何不合格式的输入都返回 null（判定链降级为放行），不抛。 */
        fun parse(bytes: ByteArray): SpamModel? {
            if (bytes.size < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes)
            if (buf.int != MAGIC) return null
            if (buf.int != VERSION) return null
            val buckets = buf.int
            val ngramMin = buf.int
            val ngramMax = buf.int
            val bias = buf.float
            val scale = buf.float
            // 桶数必须是 2 的幂：打分用 (hash & (buckets-1)) 取桶下标
            if (buckets <= 0 || buckets and (buckets - 1) != 0) return null
            if (ngramMin < 1 || ngramMax < ngramMin) return null
            // 长度按「恰好一个桶一字节」校验，截断的文件不能靠补零蒙过去
            if (bytes.size != HEADER_BYTES + buckets) return null
            val weights = ByteArray(buckets)
            buf.get(weights)
            val crc = CRC32().apply { update(bytes) }
            return SpamModel(buckets, ngramMin, ngramMax, bias, scale, weights, crc.value.toInt())
        }

        /**
         * 从 classpath 读内置模型。
         *
         * 走 java 资源而非 `assets/`：判定进程是 system_server，没有模块的 `AssetManager`，
         * 而模块入口类是用模块自己的 ClassLoader 加载的，`getResourceAsStream` 在那边可用。
         */
        fun bundled(): SpamModel? {
            val bytes = runCatching {
                SpamModel::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            }.getOrNull() ?: return null
            val model = parse(bytes)
            if (model == null) {
                ModuleLogger.error("内置模型解析失败（${bytes.size} 字节）：格式不符 ⇒ AI 段放行")
            }
            return model
        }
    }
}
