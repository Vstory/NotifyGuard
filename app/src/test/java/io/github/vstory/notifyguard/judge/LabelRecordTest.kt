package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // 时间与包名仍留在 key 前缀里：排查时要能一眼看出这条标注是哪条通知的
        assertTrue(LabelRecord.keyOf(rec(ts = 100)).startsWith("100:com.x:"))
    }

    /**
     * ts 是毫秒级（判定侧取 `System.currentTimeMillis()`）：同一毫秒、同一 App 连推两条不同通知
     * 会各成一组。只按「时间:包名」定位会让它们共用一条标注 —— 标了其中一条，另一条也显示已标注。
     */
    @Test
    fun keySeparatesGroupsSharingTimestampAndPackage() {
        val base = LabelRecord.keyOf(rec(ts = 100))
        assertNotEquals(base, LabelRecord.keyOf(rec(ts = 100, text = "B2")))
        assertNotEquals(base, LabelRecord.keyOf(rec(ts = 100, block = true)))
        assertNotEquals(base, LabelRecord.keyOf(rec(ts = 100, would = true)))
        // title 为 null 与为 "" 在聚合键里是两个组，摘要也必须分得开（长度前缀而非分隔符的原因）
        assertNotEquals(
            LabelRecord.keyOf(rec(ts = 100, title = null)),
            LabelRecord.keyOf(rec(ts = 100, title = "")),
        )
    }

    @Test
    fun carriesIdentityAndVerdict() {
        val l = LabelRecord.of(rec(ts = 100), spam = true, at = 555, modelVersion = 42)
        assertEquals(LabelRecord.keyOf(rec(ts = 100)), l.key)
        assertEquals(100L, l.ts)
        assertEquals("com.x", l.pkg)
        assertEquals(555L, l.at)
        assertEquals(42, l.modelVersion)
        assertEquals(true, l.spam)
    }

    @Test
    fun marksOfMapsRecordsToTheirVerdict() {
        val spam = rec(ts = 100)
        val ham = rec(ts = 200)
        val unlabeled = rec(ts = 300)
        val labels = listOf(
            LabelRecord.of(spam, spam = true, at = 1, modelVersion = 0),
            LabelRecord.of(ham, spam = false, at = 2, modelVersion = 0),
        )
        val marks = LabelRecord.marksOf(listOf(spam, ham, unlabeled), labels)
        assertEquals(true, marks[LabelRecord.keyOf(spam)])
        assertEquals(false, marks[LabelRecord.keyOf(ham)])
        assertNull(marks[LabelRecord.keyOf(unlabeled)])
    }

    /** 组内新通知只动 lastTs：若拿它拼 key，已标注的行会显示成未标注，用户于是重复标一遍。 */
    @Test
    fun marksOfIgnoresLastTsWhenMatching() {
        val labeled = rec(ts = 100, lastTs = 100, count = 1)
        val refreshed = rec(ts = 100, lastTs = 900, count = 7)
        val labels = listOf(LabelRecord.of(labeled, spam = true, at = 1, modelVersion = 0))
        assertEquals(true, LabelRecord.marksOf(listOf(refreshed), labels)[LabelRecord.keyOf(refreshed)])
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
        block: Boolean = false,
        would: Boolean = false,
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = title,
        text = text,
        reason = "pass",
        would = would,
        block = block,
        slot = "FUNNEL",
        lastTs = lastTs,
        count = count,
        aiText = aiText,
    )
}
