package io.github.vstory.notifyguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 模型解析：坏输入一律返回 null（判定链降级放行），绝不抛。 */
class SpamModelTest {

    private fun bytes(
        magic: Int = 0x4E53504D,
        version: Int = 1,
        declaredBuckets: Int = 8,
        weightBytes: Int = 8,
        ngramMin: Int = 1,
        ngramMax: Int = 3,
        bias: Float = 0f,
        scale: Float = 1f,
        weights: ByteArray = ByteArray(weightBytes),
    ): ByteArray {
        val buf = ByteBuffer.allocate(28 + weightBytes)
        buf.putInt(magic).putInt(version).putInt(declaredBuckets)
        buf.putInt(ngramMin).putInt(ngramMax).putFloat(bias).putFloat(scale)
        buf.put(weights)
        return buf.array()
    }

    @Test
    fun parsesValidModel() {
        val m = SpamModel.parse(bytes())
        assertNotNull(m)
        assertEquals(8, m!!.buckets)
        assertEquals(1, m.ngramMin)
        assertEquals(3, m.ngramMax)
    }

    @Test
    fun rejectsBadMagic() {
        assertNull(SpamModel.parse(bytes(magic = 0x4E535058)))
    }

    @Test
    fun rejectsUnknownVersion() {
        assertNull(SpamModel.parse(bytes(version = 2)))
    }

    @Test
    fun rejectsBucketsThatAreNotPowerOfTwo() {
        assertNull(SpamModel.parse(bytes(declaredBuckets = 6, weightBytes = 6)))
    }

    @Test
    fun rejectsTruncatedFile() {
        assertNull(SpamModel.parse(bytes(declaredBuckets = 8, weightBytes = 7)))
    }

    @Test
    fun rejectsTooShortFile() {
        assertNull(SpamModel.parse(ByteArray(10)))
    }

    @Test
    fun rejectsInvertedGramRange() {
        assertNull(SpamModel.parse(bytes(ngramMin = 3, ngramMax = 1)))
        assertNull(SpamModel.parse(bytes(ngramMin = 0, ngramMax = 3)))
    }

    @Test
    fun emptyTextScoresBiasOnly() {
        val m = SpamModel.parse(bytes(bias = 0f))!!
        assertEquals(0.5, m.score(""), 1e-9)
        assertEquals(0.5, m.score("1 2 x"), 1e-9)
    }

    @Test
    fun biasMovesScore() {
        val high = SpamModel.parse(bytes(bias = 10f))!!
        val low = SpamModel.parse(bytes(bias = -10f))!!
        assertTrue(high.score("广告") > 0.99)
        assertTrue(low.score("广告") < 0.01)
    }

    /** 权重方向要能被读对（int8 负数不是无符号）。 */
    @Test
    fun weightsShiftScoreBothWays() {
        val positive = SpamModel.parse(bytes(weights = ByteArray(8) { 127 }))!!
        val negative = SpamModel.parse(bytes(weights = ByteArray(8) { -128 }))!!
        assertTrue(positive.score("限时抢购") > 0.5)
        assertTrue(negative.score("限时抢购") < 0.5)
    }

    @Test
    fun fingerprintIsStableHex() {
        val a = SpamModel.parse(bytes())!!
        val b = SpamModel.parse(bytes())!!
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(8, a.fingerprintHex().length)
        assertTrue(a.fingerprintHex().all { it in "0123456789abcdef" })
    }
}
