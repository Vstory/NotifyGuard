package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.judge.SpamFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贡献归因（M4h）。
 *
 * 主战场是**对账**：全部分摊之和 + bias 必须等于打分前的 `z`。这条一旦不成立，学习屏画出来的高亮
 * 就是在说一件与实际拦截依据无关的事 —— 比没有高亮更坏。所以对账用三份权重各跑一遍（恒 1、恒 -1、
 * 内置模型真权重），并单独覆盖代理对（emoji）与同桶多次出现这两种最容易算错的情形。
 */
class SpamAttributionTest {

    private fun model(): SpamModel {
        val m = SpamModel.bundled()
        assertNotNull("classpath 里没有 ${SpamModel.RESOURCE}（跑 training/train.py 生成）", m)
        return m!!
    }

    /** 桶数与 gram 范围取内置模型那份（与判定同源），权重由用例给。 */
    private fun attribute(text: String, weightOf: (Int) -> Double): SpamAttribution.Result {
        val m = model()
        return SpamAttribution.attribute(text, m.buckets, m.ngramMin, m.ngramMax, weightOf)
    }

    private fun ok(result: SpamAttribution.Result): SpamAttribution.Result.Ok {
        assertTrue("期望 Ok，实际 ${result::class.simpleName}", result is SpamAttribution.Result.Ok)
        return result as SpamAttribution.Result.Ok
    }

    /** `weightOf` 恒为 1 时，`z - bias` 就是 `Σ c_k / norm` —— 从 counts 直接推，不必复刻打分实现。 */
    private fun expectedUniformContribution(text: String): Double {
        val m = model()
        val counts = SpamFeatures.counts(text, m.buckets, m.ngramMin, m.ngramMax)
        assertTrue("用例文本没有特征，对账是空转", counts.isNotEmpty())
        var sq = 0.0
        for (c in counts.values) sq += c.toDouble() * c
        val norm = Math.sqrt(sq)
        var z = 0.0
        for (c in counts.values) z += c / norm
        return z
    }

    private fun logit(p: Double): Double = Math.log(p / (1.0 - p))

    @Test
    fun noFeaturesWhenNothingSurvivesNormalization() {
        for (text in listOf("", "   ", "123456", "xxx", " 9 8 7 ")) {
            assertTrue(
                "「$text」归一化后没有特征，应返回 NoFeatures",
                attribute(text) { 1.0 } is SpamAttribution.Result.NoFeatures,
            )
        }
    }

    /**
     * 归一化会删掉空格、数字与字母 x，所以**归一化下标不能当原文下标用**。判据不看具体片段（那随权重
     * 变），只看「被删掉的位置绝不高亮」——映射搬运错了，高亮就会落在空格或数字上。
     */
    @Test
    fun spansNeverLandOnCharactersNormalizationDrops() {
        for (text in listOf(" 限时 5 折 领取 ", "限时5折x领取")) {
            val result = ok(attribute(text) { 1.0 })
            val covered = BooleanArray(text.length)
            for (span in result.spans) {
                assertTrue("片段 $span 越界（文本 ${text.length} 码元）", span.start in text.indices && span.end in 1..text.length)
                for (i in span.start until span.end) covered[i] = true
            }
            assertTrue("一个片段都没有，这条断言成了空转：「$text」", covered.any { it })
            for (i in text.indices) {
                val kept = SpamFeatures.normalize(text.substring(i, i + 1)).isNotEmpty()
                assertFalse(
                    "第 $i 位「${text[i]}」是归一化删掉的字符，不该高亮（下标搬运错了）",
                    !kept && covered[i],
                )
            }
        }
    }

    /**
     * 同桶在文本里出现 `c` 次时，各位置的分摊之和必须等于打分里那一项 `w·c/norm`。
     * 断言的是**总和**而不是逐位置值：逐位置值本来就是按分摊规则定的，拿它当期望等于自证。
     */
    @Test
    fun occurrencesOfOneBucketShareExactlyItsContribution() {
        val text = "aaa"
        val r = ok(attribute(text) { 1.0 })
        assertEquals(expectedUniformContribution(text), r.contribution, 1e-9)
    }

    /**
     * 代理对（增广平面字符）占两个 UTF-16 码元却只是一个字符：按码元数分摊会让该 gram 的总贡献短掉
     * 一截，对账就在含 emoji 的文本上失败。这是实测踩到过的错，故单独立一条。
     */
    @Test
    fun surrogatePairsAreCountedAsOneCharacter() {
        val text = "🎁限时特惠🎁 全场五折"
        val r = ok(attribute(text) { 1.0 })
        assertEquals(expectedUniformContribution(text), r.contribution, 1e-9)
    }

    /** 真权重（内置模型）下与 `SpamModel.score` 对账：这是「归因与判定同源」的可执行证据。 */
    @Test
    fun contributionReconcilesWithTheModelScore() {
        val m = model()
        val text = "限时特惠！全场五折，点击立即领取优惠券"
        val z = logit(m.score(text))
        val r = ok(attribute(text) { m.baseWeight(it).toDouble() })
        // 容差不取 1e-9：两边累加顺序不同（打分按桶升序、归因按文本位置），末位必然有差
        assertEquals(z, r.contribution + m.bias.toDouble(), 1e-9)
    }

    @Test
    fun negativeWeightsProduceNoHighlight() {
        // 权重恒为负时每个位置都在压低分数，画出来的是「这段把分数压低了」，与本屏要说的事相反
        val r = ok(attribute("限时特惠！点击领取优惠券") { -1.0 })
        assertTrue("全负权重不该有高亮片段", r.spans.isEmpty())
        assertTrue("全负权重的总贡献应为负", r.contribution < 0.0)
    }

    /**
     * 门槛取**相对值**（占本条峰值的比例），所以权重整体放大一倍后片段必须一模一样：
     * 绝对值门槛在换 base 或首次下发 delta 之后会整体失效，表现是「全亮」或「全不亮」。
     * 权重乘 2 是精确缩放，逐项比较不受浮点末位影响。
     */
    @Test
    fun relativeThresholdSurvivesScaleChange() {
        val text = "【限时特惠】全场五折起，点击立即领取优惠券"
        val base = ok(attribute(text) { 1.0 })
        val scaled = ok(attribute(text) { 2.0 })
        assertTrue("基准权重下没有片段，这条用例失去意义", base.spans.isNotEmpty())
        assertEquals(base.spans, scaled.spans)
        assertEquals(2.0, scaled.contribution / base.contribution, 1e-9)
    }

    @Test
    fun spansAreAscendingAndWithinBounds() {
        val text = "【限时特惠】全场五折起，点击立即领取 100 元优惠券，仅限今天！"
        val r = ok(attribute(text) { 1.0 })
        var prev = 0
        for (span in r.spans) {
            assertTrue("片段 $span 与上一个重叠或乱序（上一个结束于 $prev）", span.start >= prev)
            assertTrue("片段 $span 越界", span.start < span.end && span.end <= text.length)
            prev = span.end
        }
    }

    /**
     * 小写改变码元数时原文与归一化串不再一一对应（此后全部错位），归因必须整体放弃高亮。
     * 前提也一并断言：这条用例若因 JDK 不再展开 U+0130 而空转，会在这里红，而不是静默通过。
     */
    @Test
    fun unmappableWhenLowercaseChangesCodeUnitCount() {
        val text = "\u0130限时特惠"
        assertEquals("前提不成立：该 JDK 的小写没把 U+0130 展开成两个码位", text.length + 1, text.lowercase().length)
        assertTrue(
            "码元数变了还给出 Ok，高亮会整体错位",
            attribute(text) { 1.0 } is SpamAttribution.Result.Unmappable,
        )
    }
}
