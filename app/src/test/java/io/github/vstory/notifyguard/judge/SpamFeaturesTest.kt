package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 特征化：归一化各条规则、gram 计数口径、码元切分。与 `ml/features.py` 的一致性由 parity 测试守。 */
class SpamFeaturesTest {

    private val buckets = 1 shl 18

    private fun counts(text: String, buckets: Int = this.buckets) =
        SpamFeatures.counts(text, buckets, SpamFeatures.NGRAM_MIN, SpamFeatures.NGRAM_MAX)

    @Test
    fun normalizeLowercases() {
        assertEquals("abc", SpamFeatures.normalize("ABC"))
        assertEquals("ｆｕｌｌ", SpamFeatures.normalize("ＦＵＬＬ"))
    }

    @Test
    fun normalizeDropsSpacesDigitsAndX() {
        assertEquals("", SpamFeatures.normalize(" x X 9"))
        // 全角与阿拉伯-印度数字都属 Nd
        assertEquals("", SpamFeatures.normalize("１２３"))
        assertEquals("", SpamFeatures.normalize("١٢٣"))
    }

    /** U+00A0 是「我们当空白、Java 的 isWhitespace 不当空白」的那一类，用显式集合才能与 Python 对齐。 */
    @Test
    fun normalizeUsesExplicitSpaceSet() {
        assertEquals("ab", SpamFeatures.normalize("a\u00a0b"))
        assertEquals("ab", SpamFeatures.normalize("a\u3000b"))
        assertEquals("ab", SpamFeatures.normalize("a\u2028b"))
    }

    @Test
    fun normalizeKeepsOtherCharacters() {
        assertEquals("你好，世界！", SpamFeatures.normalize("你好， 世界！"))
        assertEquals("😀", SpamFeatures.normalize("😀"))
    }

    @Test
    fun emptyTextHasNoBuckets() {
        assertTrue(counts("").isEmpty())
        assertTrue(counts(" 123 x").isEmpty())
    }

    @Test
    fun bucketCountsSumEqualsGramCount() {
        // 4 个码元 ⇒ 4 个 1-gram + 3 个 2-gram + 2 个 3-gram
        assertEquals(9, counts("abcd").values.sum())
        assertEquals(1, counts("a").values.sum())
    }

    /** 代理对按 **码元** 切：😀 是两个码元，故 1-gram 两个 + 2-gram 一个。 */
    @Test
    fun surrogatePairsCountAsTwoUnits() {
        assertEquals(3, counts("😀").values.sum())
    }

    @Test
    fun bucketIdsStayWithinRange() {
        val small = counts("限时特惠，点击领取红包", buckets = 1 shl 8)
        assertTrue(small.isNotEmpty())
        small.keys.forEach { assertTrue("桶下标越界: $it", it in 0 until (1 shl 8)) }
    }
}
