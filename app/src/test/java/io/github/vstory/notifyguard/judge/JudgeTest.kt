package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** M0 判定链：结构化放行分类正确，且**任何分支都不得拦**。 */
class JudgeTest {

    // Notification.FLAG_GROUP_SUMMARY（编译期常量，单测里用字面量免得引 android 类）
    private val flagGroupSummary = 0x200

    private fun snap(
        pkg: String? = "com.example.app",
        title: String? = "标题",
        text: String? = "正文",
        bigText: String? = null,
        flags: Int = 0,
    ) = NotifySnapshot(
        pkg = pkg,
        opPkg = null,
        uid = 10001,
        pid = 1234,
        tag = "tag",
        id = 1,
        userId = 0,
        channelId = "ch",
        title = title,
        text = text,
        bigText = bigText,
        flags = flags,
        group = null,
    )

    @Test
    fun nullSnapshotIsBadArgs() {
        assertEquals("bad_args", Judge.decide(null).reason)
    }

    @Test
    fun emptyPkgIsBadArgs() {
        assertEquals("bad_args", Judge.decide(snap(pkg = "")).reason)
        assertEquals("bad_args", Judge.decide(snap(pkg = null)).reason)
    }

    @Test
    fun selfPkgIsSkipped() {
        assertEquals("self_pkg", Judge.decide(snap(pkg = Judge.SELF_PKG)).reason)
    }

    @Test
    fun groupSummaryIsSkipped() {
        assertEquals("group_summary", Judge.decide(snap(flags = flagGroupSummary)).reason)
    }

    @Test
    fun emptyTextIsSkipped() {
        assertEquals("empty_text", Judge.decide(snap(title = null, text = null)).reason)
        assertEquals("empty_text", Judge.decide(snap(title = " ", text = "\n")).reason)
    }

    @Test
    fun bigTextCountsAsText() {
        val s = snap(title = null, text = null, bigText = "长文正文")
        assertEquals("observe_pass", Judge.decide(s).reason)
        assertEquals(4, s.textLength)
    }

    @Test
    fun normalNotificationFallsThroughToObservePass() {
        assertEquals("observe_pass", Judge.decide(snap()).reason)
    }

    @Test
    fun m0NeverBlocksAnyBranch() {
        val cases = listOf(
            null,
            snap(pkg = ""),
            snap(pkg = Judge.SELF_PKG),
            snap(flags = flagGroupSummary),
            snap(title = null, text = null),
            snap(),
        )
        for (s in cases) {
            assertFalse("M0 不得拦截：${Judge.decide(s).reason}", Judge.decide(s).block)
        }
    }
}
