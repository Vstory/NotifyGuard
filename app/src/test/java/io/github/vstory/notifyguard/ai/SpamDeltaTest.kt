package io.github.vstory.notifyguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** delta 编解码：五类非法输入各自被拒且**理由可辨认**（日志里要能看出是哪一档挡下的），坏输入绝不抛。 */
class SpamDeltaTest {

    private val base = model()

    private fun model(buckets: Int = 8, weights: ByteArray = ByteArray(buckets)): SpamModel {
        val buf = ByteBuffer.allocate(28 + weights.size)
        buf.putInt(0x4E53504D).putInt(1).putInt(buckets).putInt(1).putInt(3).putFloat(0f).putFloat(1f)
        buf.put(weights)
        return SpamModel.parse(buf.array())!!
    }

    private fun bytes(
        magic: String = "NSGD",
        version: Int = 1,
        buckets: Int = 8,
        fingerprint: Long = base.fingerprintU32,
        items: List<Pair<Int, Float>> = emptyList(),
        declaredCount: Int = items.size,
        tail: ByteArray = ByteArray(0),
    ): ByteArray {
        val buf = ByteBuffer.allocate(24 + items.size * 8 + tail.size)
        buf.put(magic.toByteArray(Charsets.US_ASCII))
        buf.putInt(version)
        buf.putInt(buckets)
        buf.putLong(fingerprint)
        buf.putInt(declaredCount)
        for ((i, v) in items) {
            buf.putInt(i)
            buf.putFloat(v)
        }
        buf.put(tail)
        return buf.array()
    }

    private fun parse(b: ByteArray) = SpamDelta.parse(b, base.buckets, base.fingerprintU32)

    private fun rejected(b: ByteArray): String {
        val r = parse(b)
        assertTrue("期望被拒，实际通过", r is SpamDelta.Parse.Rejected)
        return (r as SpamDelta.Parse.Rejected).reason
    }

    private fun ok(b: ByteArray): SpamDelta {
        val r = parse(b)
        assertTrue("期望通过，实际被拒：$r", r is SpamDelta.Parse.Ok)
        return (r as SpamDelta.Parse.Ok).delta
    }

    @Test
    fun roundTripsThroughEncodeAndParse() {
        val delta = SpamDelta(8, base.fingerprintU32, intArrayOf(0, 3, 7), floatArrayOf(0.5f, -1.25f, 2f))
        val back = ok(delta.encode())
        assertEquals(8, back.buckets)
        assertEquals(base.fingerprintU32, back.baseFingerprint)
        assertTrue(back.indices.contentEquals(delta.indices))
        assertTrue(back.values.contentEquals(delta.values))
    }

    /** 清空标注后下发的就是它，必须合法而不是被当成坏文件（否则模块端会一直退避重试）。 */
    @Test
    fun zeroItemsIsLegal() {
        val empty = ok(bytes())
        assertTrue(empty.isEmpty)
        assertTrue(empty.dense().all { it == 0f })
    }

    @Test
    fun rejectsForeignMagic() {
        // Notice 用的 NSPD：布局不同，误当同格式解码会让分数静默跑偏
        assertTrue(rejected(bytes(magic = "NSPD")).contains("magic"))
    }

    @Test
    fun rejectsUnknownVersion() {
        assertTrue(rejected(bytes(version = 2)).contains("版本"))
    }

    @Test
    fun rejectsBucketCountMismatch() {
        assertTrue(rejected(bytes(buckets = 16)).contains("桶数"))
    }

    /** 换过 base（重训 / 换设备恢复标注）时最该挡住的正是这一档。 */
    @Test
    fun rejectsBaseFingerprintMismatch() {
        assertTrue(rejected(bytes(fingerprint = base.fingerprintU32 + 1)).contains("指纹"))
    }

    @Test
    fun rejectsIndexOutOfRange() {
        assertTrue(rejected(bytes(items = listOf(3 to 1f, 8 to 1f))).contains("下标"))
    }

    @Test
    fun rejectsLengthMismatch() {
        assertTrue(rejected(bytes(items = listOf(1 to 1f), declaredCount = 2)).contains("长度"))
        assertTrue(rejected(bytes(items = listOf(1 to 1f), tail = ByteArray(8))).contains("长度"))
    }

    @Test
    fun rejectsTooShortAndAbsurdCount() {
        assertTrue(rejected(ByteArray(10)).contains("过短"))
        assertTrue(rejected(bytes(declaredCount = -1)).contains("项数"))
        assertTrue(rejected(bytes(declaredCount = 9)).contains("项数"))
    }

    /** 长度校验先于按 count 分配数组：损坏文件不该有机会让解析侧按它声明的项数要内存。 */
    @Test
    fun doesNotAllocateFromDeclaredCount() {
        val huge = bytes(items = listOf(1 to 1f), declaredCount = Int.MAX_VALUE)
        assertTrue(rejected(huge).contains("项数"))
    }

    /** 指纹按 u32 存进 long：高位为 1 的 CRC32（负 Int）必须原样往返，否则两端永远比不相等。 */
    @Test
    fun fingerprintInHeaderIsUnsignedWide() {
        val negativeCrc = 0x8CDC1683L
        val r = SpamDelta.parse(bytes(fingerprint = negativeCrc), base.buckets, negativeCrc)
        assertTrue("期望通过，实际：$r", r is SpamDelta.Parse.Ok)
        assertEquals(negativeCrc, (r as SpamDelta.Parse.Ok).delta.baseFingerprint)
    }

    /** 未微调的 delta 与「有微调」在打分上必须能区分：否则模块端判断不出该退回 base。 */
    @Test
    fun emptyAndNonEmptyAreDistinguishable() {
        assertFalse(ok(bytes(items = listOf(0 to 1f))).isEmpty)
    }
}
