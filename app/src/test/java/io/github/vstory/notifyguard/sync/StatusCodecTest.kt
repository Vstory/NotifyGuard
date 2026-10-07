package io.github.vstory.notifyguard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCodecTest {

    private fun sample() = StatusReport(
        version = "1.7.0",
        assembly = "[OK] 漏斗\n[SKIP] 扩展槽",
        assemblyAt = 1_234L,
        okCount = 2,
        skipCount = 1,
        failCount = 0,
        judging = true,
        stopReason = "异常风暴",
        slot = "EXT_SLOT",
        safeMode = true,
        safeModeAt = 999L,
        safeModeReason = "SystemUI 在 30s 内死亡 >2 次",
        autoRecover = true,
        errorCount = 3,
        extHits = 10L,
        funnelJudgeHits = 4L,
        funnelPassHits = 5L,
        romBlocked = 6L,
        modelReady = true,
        deltaVersion = 7L,
        recordsPersisted = 8L,
        recordsDropped = 9L,
    )

    @Test
    fun roundTripKeepsEveryField() {
        val s = sample()
        assertEquals(s, StatusCodec.decode(StatusCodec.encode(s)))
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val d = StatusCodec.decode("""{"judging":true,"safeMode":false}""")
        assertEquals(true, d!!.judging)
        assertFalse(d.safeMode)
        assertEquals("", d.version)
        assertEquals("", d.safeModeReason)
        assertEquals(0L, d.deltaVersion)
        assertEquals(0, d.okCount)
        assertFalse(d.autoRecover)
    }

    /** 多行装配明细里含换行与引号，编码不能把它们吃掉（它是排查「为什么没装上」的唯一线索）。 */
    @Test
    fun assemblyTextSurvivesWithNewlines() {
        val d = StatusCodec.decode(StatusCodec.encode(sample()))!!
        assertTrue(d.assembly.contains("\n"))
        assertEquals("[OK] 漏斗", d.assembly.lines().first())
    }

    @Test
    fun brokenJsonIsUnreadable() {
        assertNull(StatusCodec.decode("{"))
        assertNull(StatusCodec.decode(null))
        assertNull(StatusCodec.decode(""))
        assertNull(StatusCodec.decode("   "))
    }
}
