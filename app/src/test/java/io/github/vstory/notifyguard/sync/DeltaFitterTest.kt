package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拟合的样本集里「自动正样本」那半边。
 *
 * 学习屏只给「标正常」，正类样本由被拦记录供上 —— 这块错了不会有任何界面提示，
 * 只会让 delta 静默偏掉，所以要能单测。
 */
class DeltaFitterTest {

    /** 被拦的记录就是「模型判它为垃圾」的事实，直接当正样本；放过的不是。 */
    @Test
    fun blockedRecordsBecomeSpamSamples() {
        val blocked = rec(1_000L, block = true, score = 0.9)
        val passed = rec(2_000L, block = false)

        val samples = DeltaFitter.autoSpamOf(listOf(blocked, passed), emptyList())

        assertEquals(listOf(LabelRecord.keyOf(blocked)), samples.map { it.key })
        assertTrue(samples[0].spam)
    }

    /** 用户表过态的键以用户为准：否则同一条通知会同时留下方向相反的两条样本，互相抵消。 */
    @Test
    fun userDecidedKeysWin() {
        val blocked = rec(1_000L, block = true, score = 0.9)
        val other = rec(2_000L, block = true, score = 0.8)
        val labelled = LabelRecord.of(blocked, spam = false, at = 0L, modelVersion = 0)

        val samples = DeltaFitter.autoSpamOf(listOf(blocked, other), listOf(labelled))

        assertEquals(listOf(LabelRecord.keyOf(other)), samples.map { it.key })
    }

    /**
     * 只取分数最高的若干条：被拦记录最多几百组，不设上限会以压倒性数量淹没用户标的几条误杀，
     * 拟合出的 delta 就只往 spam 方向偏 —— 那正是旧版的失衡来源。
     */
    @Test
    fun cappedByScore() {
        val records = (1..DeltaFitter.AUTO_SPAM_MAX + 10).map { i ->
            rec(ts = i.toLong(), block = true, score = i / 100.0)
        }

        val samples = DeltaFitter.autoSpamOf(records, emptyList())

        assertEquals(DeltaFitter.AUTO_SPAM_MAX, samples.size)
        // 掉出去的必须是最低分那批
        assertTrue(samples.none { it.key == LabelRecord.keyOf(records.first()) })
    }

    /** 训练文本用判定实际打分的 [LogRecord.aiText]，缺省才回退标题+正文（与手标样本同口径）。 */
    @Test
    fun usesAiTextAsTrainingText() {
        val r = rec(1_000L, block = true, score = 0.9).copy(aiText = "judged text")

        assertEquals("judged text", DeltaFitter.autoSpamOf(listOf(r), emptyList())[0].text)
    }

    /** 归一化会滤掉数字与字母 x（与 Python 训练端同口径），所以把数字逐位映射成字母来造不同的文本。 */
    private fun word(n: Long): String = n.toString().map { 'a' + (it - '0') }.joinToString("")

    private fun rec(ts: Long, block: Boolean = false, score: Double? = null) = LogRecord(
        ts = ts,
        pkg = "com.example",
        title = "标题${word(ts)}",
        text = "正文${word(ts)}",
        reason = "",
        block = block,
        slot = null,
        score = score,
    )
}
