package io.github.vstory.notifyguard.ai

import java.nio.ByteBuffer

/**
 * 端侧微调量：叠在 base（[SpamModel]）之上的**稀疏权重增量**。
 *
 * 格式 `NSGD` v1（大端）。magic 刻意与 Notice 的 `NSPD` 不同：那边把 delta 直接加到 float 权重上，
 * 这边加在「反量化后的基权重」上，两者混用不会报错、只会让分数静默跑偏。
 *
 * 布局：magic(4B) | version(i32) | buckets(i32) | baseFingerprint(i64) | count(i32) | count×(index:i32, value:f32)
 *
 * [baseFingerprint] 是拟合时 base 文件的 CRC32（装成无符号）：它是对抗「旧 delta 套新 base」的唯一凭据
 * ——模型换了而用户标注没重标的场景必然发生（重训 base、换设备恢复标注）。
 */
class SpamDelta(
    val buckets: Int,
    val baseFingerprint: Long,
    val indices: IntArray,
    val values: FloatArray,
) {

    init {
        require(indices.size == values.size) { "indices ${indices.size} != values ${values.size}" }
    }

    val isEmpty: Boolean get() = indices.isEmpty()

    /**
     * 稀疏项 → 稠密数组（[buckets] 长，2^18 即 1 MB）。
     *
     * 打分的桶下标是 hash 值：稀疏表在热路径上要么 hash 查找、要么二分，两者都在通知入队同步路径上。
     * 稠密数组把它降成一次数组读，是这一层唯一能承受的形式。
     */
    fun dense(): FloatArray {
        val out = FloatArray(buckets)
        for (i in indices.indices) out[indices[i]] = values[i]
        return out
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEAD_BYTES + indices.size * ITEM_BYTES)
        buf.put(MAGIC)
        buf.putInt(VERSION)
        buf.putInt(buckets)
        buf.putLong(baseFingerprint)
        buf.putInt(indices.size)
        for (i in indices.indices) {
            buf.putInt(indices[i])
            buf.putFloat(values[i])
        }
        return buf.array()
    }

    /** 解析结果。非法输入是**预期内的降级路径**（丢弃 delta、按纯 base 打分），故用返回值而非异常。 */
    sealed interface Parse {
        data class Ok(val delta: SpamDelta) : Parse

        data class Rejected(val reason: String) : Parse
    }

    companion object {

        /**
         * libxposed remote file 名。App 侧（`XposedService.openRemoteFile`）写、模块侧
         * （`XposedInterface.openRemoteFile`）读——**两个方向的语义不对称**：App 侧不存在则创建，
         * 模块侧只读、文件不在直接抛 FileNotFoundException。
         */
        const val REMOTE_FILE = "spam_delta.bin"

        private const val VERSION = 1

        /** 写成字面量而不是逐字节 `byteArrayOf('N'.code…)`：dex 里留住 "NSGD" 才能被产物门禁断言到。 */
        private val MAGIC = "NSGD".toByteArray(Charsets.US_ASCII)

        private const val HEAD_BYTES = 4 + 4 + 4 + 8 + 4
        private const val ITEM_BYTES = 8

        /**
         * 校验顺序：magic → version → buckets → fingerprint → count/长度 → index 范围。
         * 前一档不过就不看后一档：`count` 是从文件读来的数，先拿它去分配数组等于让损坏文件决定内存。
         *
         * `count = 0` 是**合法状态**（未微调 / 已清空标注），不是错误。
         */
        fun parse(bytes: ByteArray, expectBuckets: Int, expectFingerprint: Long): Parse {
            if (bytes.size < HEAD_BYTES) return Parse.Rejected("文件过短（${bytes.size} 字节 < $HEAD_BYTES）")
            val buf = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) return Parse.Rejected("magic 不符")
            val version = buf.int
            if (version != VERSION) return Parse.Rejected("不支持的版本 $version")
            val buckets = buf.int
            if (buckets != expectBuckets) return Parse.Rejected("桶数 $buckets != base 的 $expectBuckets")
            val fingerprint = buf.long
            if (fingerprint != expectFingerprint) {
                return Parse.Rejected("base 指纹不符（delta=%08x base=%08x）".format(fingerprint, expectFingerprint))
            }
            val count = buf.int
            if (count < 0 || count > buckets) return Parse.Rejected("项数非法 $count（桶数 $buckets）")
            val expectSize = HEAD_BYTES + count * ITEM_BYTES
            if (bytes.size != expectSize) {
                return Parse.Rejected("长度不符（${bytes.size} 字节 != 头声明的 $expectSize）")
            }
            val indices = IntArray(count)
            val values = FloatArray(count)
            for (i in 0 until count) {
                val index = buf.int
                if (index < 0 || index >= buckets) return Parse.Rejected("第 $i 项下标越界 $index")
                indices[i] = index
                values[i] = buf.float
            }
            return Parse.Ok(SpamDelta(buckets, fingerprint, indices, values))
        }
    }
}
