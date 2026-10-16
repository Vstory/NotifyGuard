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

    /**
     * 样本身份只认**内容**：同文本不同时间、不同包名、不同拦截状态都是同一条样本。
     *
     * 用户的规矩是「垃圾就是垃圾内容」—— 按包名或时间判同，等于同一份垃圾换个 App 出现就得重标
     * 一遍；而同一段文案先被拦、后被放（或用户改主意）本该覆盖成一条，不是并成两条方向相反的样本。
     */
    @Test
    fun keyIgnoresTimePackageAndVerdict() {
        val base = LabelRecord.keyOf(rec(ts = 100))
        assertEquals(base, LabelRecord.keyOf(rec(ts = 101)))
        assertEquals(base, LabelRecord.keyOf(rec(pkg = "com.y")))
        assertEquals(base, LabelRecord.keyOf(rec(block = true)))
        assertEquals(base, LabelRecord.keyOf(rec()))
    }

    /** 内容不同必须分开；大小写差异按归一化后判同（与判定打分喂给模型的是同一份文本）。 */
    @Test
    fun keySeparatesDifferentTexts() {
        val base = LabelRecord.keyOf(rec(ts = 100))
        assertNotEquals(base, LabelRecord.keyOf(rec(ts = 100, text = "C")))
        // title 为 null 与为 "" 在记录聚合里是两个组，样本键同样分得开（少了标题就是另一段内容）
        assertNotEquals(base, LabelRecord.keyOf(rec(ts = 100, title = null)))
        assertEquals(base, LabelRecord.keyOf(rec(ts = 100, title = "t", text = "b")))
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
        // 三条不同内容：默认文本相同的话会落到同一个键上（键只认内容）
        val spam = rec(ts = 100, text = "甲")
        val ham = rec(ts = 200, text = "乙")
        val unlabeled = rec(ts = 300, text = "丙")
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
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = title,
        text = text,
        reason = "pass",
        block = block,
        slot = "FUNNEL",
        lastTs = lastTs,
        count = count,
        aiText = aiText,
    )
}
