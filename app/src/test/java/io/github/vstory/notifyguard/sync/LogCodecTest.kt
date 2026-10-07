package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LogRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCodecTest {

    @Test
    fun roundTripKeepsFields() {
        val r = rec()
        assertEquals(r, LogCodec.fromJson(LogCodec.toJson(r)))
    }

    @Test
    fun scoreRoundTrips() {
        val r = rec().copy(score = 0.8125)
        assertEquals(0.8125, LogCodec.fromJson(LogCodec.toJson(r))!!.score!!, 1e-9)
    }

    @Test
    fun missingTsIsRejected() {
        assertNull(LogCodec.fromJson(JSONObject("{\"pkg\":\"com.x\"}")))
    }

    @Test
    fun listDropsBrokenItems() {
        val json = "[{\"ts\":1,\"pkg\":\"a\"},{\"pkg\":\"b\"},{\"ts\":2,\"pkg\":\"c\"}]"
        assertEquals(listOf("a", "c"), LogCodec.decodeList(json).map { it.pkg })
    }

    @Test
    fun blankOrBrokenPayloadIsEmpty() {
        assertTrue(LogCodec.decodeList(null).isEmpty())
        assertTrue(LogCodec.decodeList("  ").isEmpty())
        assertTrue(LogCodec.decodeList("not json").isEmpty())
    }

    @Test
    fun optionalFieldsStayNull() {
        val back = LogCodec.fromJson(JSONObject("{\"ts\":1,\"pkg\":\"a\",\"reason\":\"pass\"}"))!!
        assertNull(back.title)
        assertNull(back.text)
        assertNull(back.slot)
        assertNull(back.ruleId)
        assertNull(back.score)
        assertEquals(false, back.would)
    }

    @Test
    fun mergedFieldsRoundTrip() {
        val r = rec().copy(lastTs = 1_500L, count = 7)
        val back = LogCodec.fromJson(LogCodec.toJson(r))!!
        assertEquals(1_500L, back.lastTs)
        assertEquals(7, back.count)
    }

    /** 只出现一次的记录不写这两个键：字节格式与 M2 一致，旧版本读新文件也不会多解析。 */
    @Test
    fun singleRecordOmitsMergedFields() {
        val json = LogCodec.toJson(rec())
        assertTrue(!json.has("count") && !json.has("lastTs"))
    }

    @Test
    fun omittedMergedFieldsDefaultToOne() {
        val back = LogCodec.fromJson(JSONObject("{\"ts\":9,\"pkg\":\"a\",\"reason\":\"pass\"}"))!!
        assertEquals(9L, back.lastTs)
        assertEquals(1, back.count)
    }

    private fun rec() = LogRecord(
        ts = 1_000L,
        pkg = "com.x",
        title = "标题",
        text = "正文",
        reason = "rule:r1",
        would = true,
        block = false,
        slot = "EXT_SLOT",
        ruleId = "r1",
    )
}
