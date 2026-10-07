package io.github.vstory.notifyguard.judge

import java.util.SortedMap
import java.util.TreeMap

/**
 * 垃圾文本特征。**必须与 `ml/features.py` 逐位一致**（`SpamModelParityTest` 守着这条一致性）。
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

    /** 先整体小写再过滤：小写可能改变码点数（如 U+0130 → 两个码点），顺序反了会与 Python 分叉。 */
    fun normalize(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 'x'.code || isSpace(cp)) continue
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
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
