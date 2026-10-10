package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** 同一个 `tag#id` = 系统里同一条通知被更新：正文带数值的刷新通知靠它收敛成一组。 */
    @Test
    fun sameNotificationIdentityGroupsDifferentTexts() {
        val a = rec(text = "当前：-448 mA", nkey = "#305230424")
        val b = rec(text = "当前：-608 mA", nkey = "#305230424")
        assertTrue(a.sameGroup(b))
    }

    @Test
    fun differentIdentitiesStayApart() {
        val a = rec(text = "x", nkey = "null#1")
        val b = rec(text = "x", nkey = "null#2")
        assertFalse(a.sameGroup(b))
    }

    /** 拦截与放行仍是两个事实，身份相同也不合。 */
    @Test
    fun identityDoesNotMergeAcrossVerdicts() {
        val a = rec(text = "x", nkey = "null#1")
        val b = rec(text = "x", nkey = "null#1", block = true)
        assertFalse(a.sameGroup(b))
    }

    /** 不带身份（老记录 / id=0）时行为与 M2 一致：同文本才同组。 */
    @Test
    fun withoutIdentityTextStillDecides() {
        assertTrue(rec(text = "same").sameGroup(rec(text = "same")))
        assertFalse(rec(text = "a").sameGroup(rec(text = "b")))
    }

    /** 无身份时退回文本键：老记录与新记录同文本仍能并到一起。 */
    @Test
    fun missingIdentityFallsBackToText() {
        assertTrue(rec(text = "same").sameGroup(rec(text = "same", nkey = "null#1")))
    }

    /** `id=0` 无 tag 被大量 App 当默认值用，不能当身份。 */
    @Test
    fun zeroIdWithoutTagIsNotAnIdentity() {
        assertEquals(null, LogRecord.from(snap(id = 0), Judge.Decision(false, "pass"), "FUNNEL", 1L).nkey)
        assertEquals("#7", LogRecord.from(snap(id = 7), Judge.Decision(false, "pass"), "FUNNEL", 1L).nkey)
        assertEquals("t#0", LogRecord.from(snap(id = 0, tag = "t"), Judge.Decision(false, "pass"), "FUNNEL", 1L).nkey)
        assertEquals("tag#7", LogRecord.from(snap(id = 7, tag = "tag"), Judge.Decision(false, "pass"), "FUNNEL", 1L).nkey)
    }

    private fun rec(text: String?, nkey: String? = null, block: Boolean = false) = LogRecord(
        ts = 1L,
        pkg = "com.x",
        title = "t",
        text = text,
        reason = "pass",
        would = false,
        block = block,
        slot = "FUNNEL",
        nkey = nkey,
    )

    private fun snap(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        id: Int = 3,
        tag: String? = null,
    ) = NotifySnapshot(
        pkg = "com.x",
        opPkg = null,
        uid = 1,
        pid = 2,
        tag = tag,
        id = id,
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
