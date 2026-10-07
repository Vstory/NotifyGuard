package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRecordTest {

    @Test
    fun clipsLongText() {
        val r = LogRecord.from(snap(title = "t".repeat(500)), Judge.Decision(false, "pass"), "FUNNEL", 1L)
        assertEquals(LogRecord.TEXT_MAX, r.title!!.length)
    }

    @Test
    fun fallsBackToBigText() {
        val r = LogRecord.from(snap(text = "", bigText = "长文正文"), Judge.Decision(false, "pass"), "FUNNEL", 1L)
        assertEquals("长文正文", r.text)
    }

    @Test
    fun observeModeKeepsWouldWithoutBlock() {
        val d = Judge.Decision(block = false, reason = "rule:r1", wouldBlock = true, ruleId = "r1")
        val r = LogRecord.from(snap(title = "推广"), d, "EXT_SLOT", 5L)
        assertEquals(true, r.would)
        assertEquals(false, r.block)
        assertEquals("EXT_SLOT", r.slot)
        assertEquals("r1", r.ruleId)
    }

    @Test
    fun nullSnapshotStillProducesReason() {
        val r = LogRecord.from(null, Judge.Decision(false, "bad_args"), "FUNNEL", 9L)
        assertEquals("", r.pkg)
        assertEquals("bad_args", r.reason)
    }

    private fun snap(title: String? = null, text: String? = null, bigText: String? = null) = NotifySnapshot(
        pkg = "com.x",
        opPkg = null,
        uid = 1,
        pid = 2,
        tag = null,
        id = 3,
        userId = 0,
        channelId = null,
        title = title,
        text = text,
        bigText = bigText,
        flags = 0,
        group = null,
        category = null,
        hasMessagingStyle = false,
    )
}
