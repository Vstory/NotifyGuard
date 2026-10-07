package io.github.vstory.notifyguard.ai

import io.github.vstory.notifyguard.judge.SpamFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 合成打分器。
 *
 * 两条要守的边界：空 delta 时它必须与 base **逐位相同**（否则「退回纯 base」与「叠一个空 delta」
 * 会给出不同的分数，用户看到的就是分数无故跳动）；非空时增量必须真的落在对应桶上。
 */
class TunedScorerTest {

    private val text = "限时抢购 全场一折 加微信"

    private fun model(buckets: Int = 1 shl 10): SpamModel {
        val buf = ByteBuffer.allocate(28 + buckets)
        buf.putInt(0x4E53504D).putInt(1).putInt(buckets).putInt(1).putInt(3).putFloat(0.2f).putFloat(0.05f)
        buf.put(ByteArray(buckets) { ((it % 11) - 5).toByte() })
        return SpamModel.parse(buf.array())!!
    }

    private fun deltaOf(model: SpamModel, pairs: List<Pair<Int, Float>>) = SpamDelta(
        buckets = model.buckets,
        baseFingerprint = model.fingerprintU32,
        indices = pairs.map { it.first }.toIntArray(),
        values = pairs.map { it.second }.toFloatArray(),
    )

    @Test
    fun emptyDeltaScoresExactlyLikeBase() {
        val base = model()
        val tuned = TunedScorer(base, deltaOf(base, emptyList()))
        assertEquals(base.score(text), tuned.score(text), 0.0)
        assertEquals(base.score(""), tuned.score(""), 0.0)
    }

    @Test
    fun positiveDeltaOnUsedBucketsRaisesScore() {
        val base = model()
        val buckets = SpamFeatures.counts(text, base.buckets, base.ngramMin, base.ngramMax).keys
        val up = deltaOf(base, buckets.map { it to 5f })
        assertTrue(TunedScorer(base, up).score(text) > base.score(text))
    }

    @Test
    fun negativeDeltaLowersScore() {
        val base = model()
        val buckets = SpamFeatures.counts(text, base.buckets, base.ngramMin, base.ngramMax).keys
        val down = deltaOf(base, buckets.map { it to -5f })
        assertTrue(TunedScorer(base, down).score(text) < base.score(text))
    }

    /** 落在别的桶上的增量不该影响这段文本：桶下标是 hash 值，不校验就成了「改哪都变」。 */
    @Test
    fun deltaOnUnusedBucketsDoesNotMoveScore() {
        val base = model()
        val used = SpamFeatures.counts(text, base.buckets, base.ngramMin, base.ngramMax).keys.toSet()
        val unused = (0 until base.buckets).first { it !in used }
        val unrelated = deltaOf(base, listOf(unused to 99f))
        assertEquals(base.score(text), TunedScorer(base, unrelated).score(text), 1e-12)
    }

    @Test
    fun textWithoutFeaturesScoresBiasOnly() {
        val base = model()
        val tuned = TunedScorer(base, deltaOf(base, listOf(0 to 9f)))
        assertEquals(base.score("1 2 x"), tuned.score("1 2 x"), 0.0)
    }
}
