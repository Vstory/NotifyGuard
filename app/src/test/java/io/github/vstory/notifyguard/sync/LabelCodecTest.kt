package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LabelRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelCodecTest {

    @Test
    fun roundTripKeepsEveryField() {
        val l = label(spam = true)
        val back = LabelCodec.fromJson(LabelCodec.toJson(l))
        assertEquals(l, back)
    }

    /** 缺省必须落在无害侧：漏标一条垃圾只是放过，反过来会让正常通知被拦。 */
    @Test
    fun missingSpamDefaultsToNormal() {
        val o = LabelCodec.toJson(label(spam = true)).apply { remove("spam") }
        assertEquals(false, LabelCodec.fromJson(o)?.spam)
    }

    @Test
    fun missingOptionalFieldsTakeDefaults() {
        val back = LabelCodec.fromJson(JSONObject().put("key", "1:com.x"))
        assertEquals(label(key = "1:com.x", ts = 0, pkg = "", text = "", spam = false, at = 0, modelVersion = 0), back)
    }

    /** 没有主键的标注无处安放：整条判废，而不是塞一个空 key 进去。 */
    @Test
    fun missingOrEmptyKeyDropsTheRecord() {
        assertNull(LabelCodec.fromJson(LabelCodec.toJson(label()).apply { remove("key") }))
        assertNull(LabelCodec.fromJson(JSONObject().put("key", "")))
    }

    @Test
    fun brokenJsonYieldsEmptyList() {
        assertTrue(LabelCodec.decodeList("{ not an array").isEmpty())
        assertTrue(LabelCodec.decodeList("").isEmpty())
        assertTrue(LabelCodec.decodeList(null).isEmpty())
    }

    /** 一条坏标注不该毁掉整批 —— 与 LogCodec 同一口径。 */
    @Test
    fun brokenItemDoesNotKillTheBatch() {
        val json = "[${LabelCodec.toJson(label(key = "1:com.x"))},\"junk\"," +
            "${JSONObject().put("key", "")},${LabelCodec.toJson(label(key = "2:com.x"))}]"
        assertEquals(listOf("1:com.x", "2:com.x"), LabelCodec.decodeList(json).map { it.key })
    }

    @Test
    fun encodeDecodeListRoundTrips() {
        val list = listOf(label(key = "1:com.x"), label(key = "2:com.y", spam = false))
        assertEquals(list, LabelCodec.decodeList(LabelCodec.encodeList(list)))
    }

    @Test
    fun textSurvivesRoundTrip() {
        val text = "换行\n与\"引号\""
        assertEquals(text, LabelCodec.fromJson(LabelCodec.toJson(label(text = text)))?.text)
    }

    private fun label(
        key: String = "100:com.x",
        ts: Long = 100,
        pkg: String = "com.x",
        text: String = "t",
        spam: Boolean = true,
        at: Long = 500,
        modelVersion: Int = 7,
    ) = LabelRecord(key, ts, pkg, text, spam, at, modelVersion)
}
