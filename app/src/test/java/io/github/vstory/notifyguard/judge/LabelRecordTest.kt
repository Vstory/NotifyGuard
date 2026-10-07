package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LabelRecordTest {

    /** 聚合后同一组的 ts 恒定（后续通知只动 count/lastTs）⇒ 标注必须跟着组走，key 不能变。 */
    @Test
    fun keyIsStableForTheSameGroup() {
        assertEquals(
            LabelRecord.keyOf(rec(ts = 100, count = 1, lastTs = 100)),
            LabelRecord.keyOf(rec(ts = 100, count = 7, lastTs = 900)),
        )
    }

    @Test
    fun keySeparatesDifferentGroups() {
        assertNotEquals(LabelRecord.keyOf(rec(ts = 100)), LabelRecord.keyOf(rec(ts = 101)))
        assertNotEquals(LabelRecord.keyOf(rec(ts = 100)), LabelRecord.keyOf(rec(pkg = "com.y")))
        assertEquals("100:com.x", LabelRecord.keyOf(rec(ts = 100)))
    }

    @Test
    fun carriesIdentityAndVerdict() {
        val l = LabelRecord.of(rec(ts = 100), spam = true, at = 555, modelVersion = 42)
        assertEquals("100:com.x", l.key)
        assertEquals(100L, l.ts)
        assertEquals("com.x", l.pkg)
        assertEquals(555L, l.at)
        assertEquals(42, l.modelVersion)
        assertEquals(true, l.spam)
    }

    @Test
    fun textPrefersAiText() {
        assertEquals("ai", LabelRecord.of(rec(aiText = "ai"), true, 1, 0).text)
    }

    /** aiText 是 M2 后期才加的字段：早期记录没有，回退标题+正文而不是存一条没有文本的标注。 */
    @Test
    fun textFallsBackToTitleAndBody() {
        assertEquals("T\nB", LabelRecord.of(rec(aiText = null), true, 1, 0).text)
    }

    @Test
    fun textFallsBackWhenAiTextIsBlank() {
        assertEquals("T\nB", LabelRecord.of(rec(aiText = "   "), true, 1, 0).text)
    }

    @Test
    fun textToleratesMissingTitleOrBody() {
        assertEquals("B", LabelRecord.of(rec(title = null, aiText = null), true, 1, 0).text)
        assertEquals("T", LabelRecord.of(rec(text = null, aiText = null), true, 1, 0).text)
    }

    private fun rec(
        ts: Long = 100,
        pkg: String = "com.x",
        title: String? = "T",
        text: String? = "B",
        aiText: String? = null,
        count: Int = 1,
        lastTs: Long = ts,
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = title,
        text = text,
        reason = "pass",
        would = false,
        block = false,
        slot = "FUNNEL",
        lastTs = lastTs,
        count = count,
        aiText = aiText,
    )
}
