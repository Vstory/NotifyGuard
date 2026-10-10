package io.github.vstory.notifyguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 拟合的确定性与门槛。
 *
 * 确定性是这一层唯一不能让步的性质：它是「同标注必得同 delta」的前提，也是「重复拟合无害」的依据
 * （见 [io.github.vstory.notifyguard.sync.DeltaFitter]）——一旦不确定，重拟合就会让分数无端漂移。
 */
class SpamTunerTest {

    private val base = model()

    private fun model(buckets: Int = 1 shl 10, seed: Int = 0): SpamModel {
        val buf = ByteBuffer.allocate(28 + buckets)
        // 权重给一个非零初值：z0 全一样时梯度方向会被对称性掩盖，测不出「只动 delta」
        val weights = ByteArray(buckets) { ((it + seed) % 7).toByte() }
        buf.putInt(0x4E53504D).putInt(1).putInt(buckets).putInt(1).putInt(3).putFloat(-0.5f).putFloat(0.01f)
        buf.put(weights)
        return SpamModel.parse(buf.array())!!
    }

    private fun samples(spam: Int, ham: Int, tag: String = ""): List<SpamTuner.Sample> {
        val out = ArrayList<SpamTuner.Sample>()
        repeat(spam) { out.add(SpamTuner.Sample("t$tag$it:pkg", "急售低价二手手机加微信详聊 $it", true)) }
        repeat(ham) { out.add(SpamTuner.Sample("h$tag$it:pkg", "您的快递已放至丰巢柜，请及时取件 $it", false)) }
        return out
    }

    private fun ok(fit: SpamTuner.Fit): SpamDelta {
        assertTrue("期望拟合成功，实际：$fit", fit is SpamTuner.Fit.Ok)
        return (fit as SpamTuner.Fit.Ok).delta
    }

    private fun notReady(fit: SpamTuner.Fit): SpamTuner.Readiness {
        assertTrue("期望未达门槛，实际：$fit", fit is SpamTuner.Fit.NotReady)
        return (fit as SpamTuner.Fit.NotReady).readiness
    }

    /** 样本乱序传入必须得到同一个 delta：顺序由 key 决定，不靠调用方。 */
    @Test
    fun isDeterministicRegardlessOfInputOrder() {
        val a = ok(SpamTuner.fit(base, samples(6, 6)))
        val b = ok(SpamTuner.fit(base, samples(6, 6).reversed()))
        assertTrue(a.encode().contentEquals(b.encode()))
    }

    @Test
    fun encodesTheBaseFingerprint() {
        val delta = ok(SpamTuner.fit(base, samples(5, 5)))
        assertEquals(base.fingerprintU32, delta.baseFingerprint)
        assertEquals(base.buckets, delta.buckets)
    }

    /** 输出按桶下标升序：加载侧与单测都依赖它，顺序随 Map 实现漂移就不可复现。 */
    @Test
    fun indicesAreSortedAscendingAndUnique() {
        val delta = ok(SpamTuner.fit(base, samples(6, 6)))
        assertTrue(delta.indices.size > 0)
        assertEquals(delta.indices.toList(), delta.indices.toList().sorted().distinct())
    }

    @Test
    fun refusesBelowTotalThreshold() {
        val r = notReady(SpamTuner.fit(base, samples(4, 5)))
        assertEquals(9, r.usable)
        assertTrue(!r.ready)
    }

    @Test
    fun refusesWhenOneClassIsMissing() {
        // 全是垃圾样本：学不出「正常」那一侧，下发只会把阈值整体拉爆
        val r = notReady(SpamTuner.fit(base, samples(10, 0)))
        assertEquals(0, r.ham)
        val r2 = notReady(SpamTuner.fit(base, samples(1, 10)))
        assertEquals(1, r2.spam)
    }

    /** 门槛按**过滤后**的样本判：凑不出特征的标注不能算进「够了」。 */
    @Test
    fun unusableSamplesDoNotCountTowardThreshold() {
        val filler = List(6) { SpamTuner.Sample("f$it:pkg", "1 x", true) }
        val r = notReady(SpamTuner.fit(base, filler + samples(2, 2)))
        assertEquals(4, r.usable)
    }

    @Test
    fun ignoresSamplesWithoutFeatures() {
        val withNoise = ok(SpamTuner.fit(base, samples(6, 6) + SpamTuner.Sample("n:pkg", "。", true)))
        val without = ok(SpamTuner.fit(base, samples(6, 6)))
        assertTrue(withNoise.encode().contentEquals(without.encode()))
    }

    /** 行为面：标了垃圾之后，同类文本的分数必须真的被推上去 —— 否则整条链路等于没接。 */
    @Test
    fun raisesScoreOfLabelledSpam() {
        val text = "急售低价二手手机加微信详聊 0"
        val before = base.score(text)
        val delta = ok(SpamTuner.fit(base, samples(6, 6)))
        val after = TunedScorer(base, delta).score(text)
        assertTrue("标注后分数应上升：$before → $after", after > before)
    }

    @Test
    fun pullsDownScoreOfLabelledHam() {
        val text = "您的快递已放至丰巢柜，请及时取件 0"
        val before = base.score(text)
        val delta = ok(SpamTuner.fit(base, samples(6, 6)))
        val after = TunedScorer(base, delta).score(text)
        assertTrue("标为正常后分数应下降：$before → $after", after < before)
    }

    /** 换一版 base 就是另一个指纹 ⇒ 判定侧据此拒收旧 delta（R2 的可执行证据）。 */
    @Test
    fun deltaIsBoundToTheBaseItWasFittedOn() {
        val other = model(seed = 3)
        val d1 = ok(SpamTuner.fit(base, samples(5, 5)))
        val d2 = ok(SpamTuner.fit(other, samples(5, 5)))
        assertNotEquals(d1.baseFingerprint, d2.baseFingerprint)
        assertTrue(
            SpamDelta.parse(d1.encode(), other.buckets, other.fingerprintU32) is SpamDelta.Parse.Rejected
        )
    }

    /** 缺口取三类里最大的那个：某一类只有 1 条时，总数再多也不算达标。 */
    @Test
    fun shortfallTakesTheWidestOfTheThreeGaps() {
        assertEquals(0, SpamTuner.Readiness(20, 5, 15).shortfall)
        assertEquals(1, SpamTuner.Readiness(20, 1, 19).shortfall)
        assertEquals(5, SpamTuner.Readiness(5, 5, 0).shortfall)
    }

    /**
     * 界面用的进度与拟合的门槛判定必须同源：进度说「够了」，fit 就得真拟合得出来。
     * 两处各算一遍过滤，迟早出现「界面说够了、拟合却空手而归」。
     */
    @Test
    fun progressAgreesWithFitAboutCrossingTheThreshold() {
        val thin = samples(4, 5)
        assertEquals(9, SpamTuner.readiness(base, thin).usable)
        assertTrue(!SpamTuner.readiness(base, thin).ready)
        assertTrue(SpamTuner.fit(base, thin) is SpamTuner.Fit.NotReady)

        val enough = samples(5, 5)
        assertTrue(SpamTuner.readiness(base, enough).ready)
        assertTrue(SpamTuner.fit(base, enough) is SpamTuner.Fit.Ok)
    }

    /** 同一份标注 + 同一版 base，重复拟合必须落到同一串字节（重复触发才敢不设防）。 */
    @Test
    fun refittingTheSameInputsIsIdempotent() {
        val samples = samples(7, 5)
        val first = ok(SpamTuner.fit(base, samples)).encode()
        val second = ok(SpamTuner.fit(base, samples)).encode()
        assertTrue(first.contentEquals(second))
    }
}
