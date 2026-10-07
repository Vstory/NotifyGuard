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
