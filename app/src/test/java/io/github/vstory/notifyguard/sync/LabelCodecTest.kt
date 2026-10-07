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

    /**
     * `parseList` 的三态是写入安全的开关：`null`（整体不可解析）与 `[]`（合法但为空）
     * 在 `LabelStore` 里对应完全不同的处置 —— 前者拒写，后者照写。
     */
    @Test
    fun parseListDistinguishesBrokenFromEmpty() {
        assertNull(LabelCodec.parseList("{ not an array"))
        assertNull(LabelCodec.parseList(null))
        assertNull(LabelCodec.parseList("   "))
        assertEquals(emptyList<LabelRecord>(), LabelCodec.parseList("[]"))
        assertEquals(listOf("1:com.x"), LabelCodec.parseList(LabelCodec.encodeList(listOf(label(key = "1:com.x"))))?.map { it.key })
    }

    /** 通道载荷是单条对象（不是数组）：解不出就得让调用方拒绝这次写入。 */
    @Test
    fun decodeOneHandlesSingleObjectOnly() {
        assertEquals(label(), LabelCodec.decodeOne(LabelCodec.toJson(label()).toString()))
        assertNull(LabelCodec.decodeOne("[]"))
        assertNull(LabelCodec.decodeOne("junk"))
        assertNull(LabelCodec.decodeOne(""))
        assertNull(LabelCodec.decodeOne(null))
        assertNull(LabelCodec.decodeOne(JSONObject().put("key", "").toString()))
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
