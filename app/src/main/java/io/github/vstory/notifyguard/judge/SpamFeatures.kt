package io.github.vstory.notifyguard.judge

import java.util.SortedMap
import java.util.TreeMap

/**
 * 垃圾文本特征。**必须与 `training/features.py` 逐位一致**（`SpamModelParityTest` 守着这条一致性）。
 *
 * 归一化去掉空白、十进制数字与字母 x：语料里正常样本被去过空格、垃圾样本把数字与人名掩码成连续 x，
 * 留着它们，模型学到的是「有空格=正常」「有 xxx=垃圾」这类语料的加工痕迹而非词本身。
 *
 * gram 按 **UTF-16 码元** 切。Kotlin 的 String 天然如此；Python 侧必须先 `encode("utf-16-le")`
 * 再按 2 字节切，否则 emoji 一类增补平面字符会切出不同的 gram。
 */
object SpamFeatures {

    const val BUCKETS = 1 shl 18
    const val NGRAM_MIN = 1
    const val NGRAM_MAX = 3

    private const val FNV_OFFSET = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193

    private val NO_INDEX = IntArray(0)

    /**
     * 归一化串 + 原文映射，给贡献归因（`ai/SpamAttribution.kt`）用。
     *
     * 归因要在**原文**上画高亮，而归一化删掉了空白、数字与 x：「归一化串的下标」不等于「原文下标」。
     * [sourceStart]/[sourceEnd] 就是两者之间的搬运表，下标是归一化码元、值是该码元所属字符的原文码元区间。
     *
     * [reliable] 为 false 表示小写改变了码元数（如 U+0130 展开成两个码点），两个串不再一一对应。
     * 那时映射整体不可信（不能只丢几个条目：后面全部错位），调用方据此降级为不画高亮。
     */
    class Mapped internal constructor(
        val text: String,
        val sourceStart: IntArray,
        val sourceEnd: IntArray,
        val reliable: Boolean,
    )

    /**
     * 与 `features.py` 的 SPACE_CHARS 同一集合。不用 `Character.isWhitespace`：
     * 它把 U+00A0 之外的一批字符判法不同（如 U+200B），两端一旦不一致就是静默分叉。
     */
    private fun isSpace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680 -> true
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    /** 纯归一化串。判定打分的热路径，每次判定都要跑，故不产出映射表。 */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        filter(text, sb, null, null)
        return sb.toString()
    }

    /** 归一化 + 原文映射。归因用，属按需的冷路径。 */
    internal fun normalizeMapped(text: String): Mapped {
        val sb = StringBuilder(text.length)
        val starts = ArrayList<Int>(text.length)
        val ends = ArrayList<Int>(text.length)
        val reliable = filter(text, sb, starts, ends)
        return Mapped(
            text = sb.toString(),
            sourceStart = if (reliable) starts.toIntArray() else NO_INDEX,
            sourceEnd = if (reliable) ends.toIntArray() else NO_INDEX,
            reliable = reliable,
        )
    }

    /**
     * 归一化的唯一一份实现（[normalize] 与 [normalizeMapped] 共用）：两处各写一遍循环，
     * 改一处忘另一处就是静默分叉。
     *
     * 先整体小写再过滤：小写可能改变码元数（如 U+0130 → 两个码点），顺序反了会与 Python 分叉。
     * 返回 false 表示小写改变了码元数 —— 此时映射里的下标是小写串的下标、不是原文下标，调用方须丢弃。
     */
    private fun filter(
        text: String,
        out: StringBuilder,
        starts: MutableList<Int>?,
        ends: MutableList<Int>?,
    ): Boolean {
        val lower = text.lowercase()
        val reliable = lower.length == text.length
        var i = 0
        while (i < lower.length) {
            val from = i
            val cp = lower.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 'x'.code || isSpace(cp)) continue
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) continue
            out.appendCodePoint(cp)
            // 一个码点可能占两个 UTF-16 码元（增补平面）：两个码元都记同一段原文区间，
            // 归因据此知道「这个字符在原文里占哪一段」，也就不会把代理对切一半
            if (reliable && starts != null && ends != null) {
                repeat(i - from) {
                    starts.add(from)
                    ends.add(i)
                }
            }
        }
        return reliable
    }

    /**
     * 桶下标 → 出现次数。**按 key 升序**返回：打分是浮点累加，两端迭代顺序一致才守得住 parity 容差。
     */
    fun counts(text: String, buckets: Int, ngramMin: Int, ngramMax: Int): SortedMap<Int, Int> {
        val map = TreeMap<Int, Int>()
        val chars = normalize(text).toCharArray()
        if (chars.isEmpty()) return map
        val mask = buckets - 1
        for (g in ngramMin..ngramMax) {
            if (g > chars.size) break
            for (start in 0..chars.size - g) {
                val k = hash(chars, start, g) and mask
                map[k] = (map[k] ?: 0) + 1
            }
        }
        return map
    }

    /**
     * 位置级 gram 的桶下标。归因要「哪个位置的 gram 进了哪个桶」，而 [counts] 是桶级聚合 ——
     * 位置信息在聚合时就丢掉了。两者共用的是同一个 [hash] 与同一套 n-gram 范围，不是同一个函数。
     */
    internal fun bucketOf(chars: CharArray, start: Int, len: Int, buckets: Int): Int =
        hash(chars, start, len) and (buckets - 1)

    /** FNV-1a 32，输入是 gram 的 UTF-16-LE 字节（低位在前）。 */
    private fun hash(chars: CharArray, start: Int, len: Int): Int {
        var h = FNV_OFFSET
        for (i in start until start + len) {
            val c = chars[i].code
            h = (h xor (c and 0xFF)) * FNV_PRIME
            h = (h xor (c ushr 8)) * FNV_PRIME
        }
        return h
    }
}
