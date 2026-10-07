package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1 判定链：结构性放行、保护类、规则拦截、观察模式与总开关。 */
class JudgeTest {

    // Notification.FLAG_GROUP_SUMMARY / FLAG_FOREGROUND_SERVICE / CATEGORY_*（编译期常量，用字面量免得引 android 类）
    private val flagGroupSummary = 0x200
    private val flagForegroundService = 0x40
    private val categoryCall = "call"
    private val categoryTransport = "transport"

    private fun snap(
        pkg: String? = "com.example.app",
        title: String? = "标题",
        text: String? = "正文",
        bigText: String? = null,
        flags: Int = 0,
        category: String? = null,
        messaging: Boolean = false,
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
        category = category,
        hasMessagingStyle = messaging,
    )

    private fun rule(vararg keywords: String) = Rule(id = "r1", keywords = keywords.toList())

    private val observing = Config(observe = true)
    private val blocking = Config(observe = false)

    @Test
    fun nullSnapshotIsBadArgs() {
        assertEquals("bad_args", Judge.decide(null, blocking).reason)
    }

    @Test
    fun emptyPkgIsBadArgs() {
        assertEquals("bad_args", Judge.decide(snap(pkg = ""), blocking).reason)
        assertEquals("bad_args", Judge.decide(snap(pkg = null), blocking).reason)
    }

    @Test
    fun selfPkgIsSkipped() {
        assertEquals("self_pkg", Judge.decide(snap(pkg = Judge.SELF_PKG), blocking).reason)
    }

    @Test
    fun groupSummaryIsSkipped() {
        assertEquals("group_summary", Judge.decide(snap(flags = flagGroupSummary), blocking).reason)
    }

    @Test
    fun emptyTextIsSkipped() {
        assertEquals("empty_text", Judge.decide(snap(title = null, text = null), blocking).reason)
        assertEquals("empty_text", Judge.decide(snap(title = " ", text = "\n"), blocking).reason)
    }

    @Test
    fun bigTextCountsAsText() {
        val s = snap(title = null, text = null, bigText = "长文正文")
        assertEquals("no_model", Judge.decide(s, blocking).reason)
        assertEquals(4, s.textLength)
    }

    @Test
    fun disabledConfigPassesBeforeAnythingElse() {
        val cfg = Config(enabled = false, observe = false, rules = listOf(rule("正文")))
        assertEquals("disabled", Judge.decide(snap(), cfg).reason)
        assertFalse(Judge.decide(snap(), cfg).wouldBlock)
    }

    // ===== 保护类型（先于规则） =====

    @Test
    fun protectedCategoriesPassEvenWhenRuleHits() {
        val cfg = Config(observe = false, rules = listOf(rule("正文")))
        assertEquals("protect_call", Judge.decide(snap(category = categoryCall), cfg).reason)
        assertEquals("protect_media", Judge.decide(snap(category = categoryTransport), cfg).reason)
        assertEquals("protect_fgs", Judge.decide(snap(flags = flagForegroundService), cfg).reason)
        assertEquals("protect_conversation", Judge.decide(snap(messaging = true), cfg).reason)
    }

    @Test
    fun protectSwitchesCanBeTurnedOff() {
        val cfg = Config(
            observe = false,
            protect = ProtectSwitches(call = false),
            rules = listOf(rule("正文")),
        )
        assertEquals("rule:r1", Judge.decide(snap(category = categoryCall), cfg).reason)
    }

    // ===== 规则 =====

    @Test
    fun ruleHitBlocksWhenNotObserving() {
        val cfg = Config(observe = false, rules = listOf(rule("正文")))
        val d = Judge.decide(snap(), cfg)
        assertTrue(d.block)
        assertTrue(d.wouldBlock)
        assertEquals("rule:r1", d.reason)
        assertEquals("r1", d.ruleId)
    }

    @Test
    fun observeModeKeepsDecisionButNotBlocking() {
        val cfg = Config(observe = true, rules = listOf(rule("正文")))
        val d = Judge.decide(snap(), cfg)
        assertFalse(d.block)
        assertTrue(d.wouldBlock)
        assertEquals("rule:r1", d.reason)
    }

    @Test
    fun missFallsThroughToNoModel() {
        val cfg = Config(observe = false, rules = listOf(rule("不存在的词")))
        assertEquals("no_model", Judge.decide(snap(), cfg).reason)
    }

    @Test
    fun packagesLimitedRuleOnlyAppliesToItsApps() {
        val cfg = Config(
            observe = false,
            rules = listOf(Rule(id = "r1", keywords = listOf("正文"), packages = setOf("com.other.app"))),
        )
        assertEquals("no_model", Judge.decide(snap(pkg = "com.example.app"), cfg).reason)
        assertEquals("rule:r1", Judge.decide(snap(pkg = "com.other.app"), cfg).reason)
    }

    // ===== 硬保护词 / 极短文本 / 白名单 =====

    @Test
    fun hardWordPassesWhenNoRuleMatches() {
        val cfg = Config(observe = false, rules = listOf(rule("不存在的词")))
        assertEquals("hard_word", Judge.decide(snap(text = "您的验证码是 1234"), cfg).reason)
    }

    @Test
    fun ruleBeatsHardWord() {
        // 顺序：规则在第 5 步、硬保护词在第 7 步 —— 用户把「验证码」写进规则就是要拦它
        val cfg = Config(observe = false, rules = listOf(rule("验证码")))
        assertEquals("rule:r1", Judge.decide(snap(text = "您的验证码是 1234"), cfg).reason)
    }

    @Test
    fun tooShortTextPasses() {
        val cfg = Config(observe = false, rules = listOf(rule("不存在的词")))
        assertEquals("text_too_short", Judge.decide(snap(title = "ab", text = null), cfg).reason)
    }

    @Test
    fun whitelistSkipsAiSegmentOnly() {
        val cfg = Config(
            observe = false,
            whitelist = setOf("com.example.app"),
            rules = listOf(rule("不存在的词")),
        )
        assertEquals("whitelisted", Judge.decide(snap(), cfg).reason)
    }

    @Test
    fun judgeTextDeduplicatesAndJoins() {
        val s = snap(title = "同文", text = "同文", bigText = "长文")
        assertEquals("同文\n长文", s.judgeText())
    }

    @Test
    fun onlyRuleHitsCanBeBlocked() {
        val cfg = Config(observe = false, rules = listOf(rule("正文")))
        assertNull(Judge.decide(snap(title = null, text = null), cfg).ruleId)
    }
}
