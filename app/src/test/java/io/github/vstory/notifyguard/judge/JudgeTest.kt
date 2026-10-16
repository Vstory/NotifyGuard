package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.ai.ModelHolder
import io.github.vstory.notifyguard.ai.SpamScorer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判定链：结构性放行、保护类、规则拦截、总开关、AI 段。 */
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
        subText: String? = null,
        infoText: String? = null,
        textLines: List<String> = emptyList(),
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
        subText = subText,
        infoText = infoText,
        textLines = textLines,
    )

    private fun rule(vararg keywords: String) = Rule(id = "r1", keywords = keywords.toList())

    private val blocking = Config()

    /** 模型是全局状态，用例之间必须清干净，否则「无模型」的断言会随执行顺序飘。 */
    @After
    fun clearModel() = ModelHolder.replace(null)

    private fun aiCfg(threshold: Double = 0.7) =
        Config(spamEnabled = true, threshold = threshold)

    @Test
    fun nullSnapshotIsBadArgs() {
        assertEquals("bad_args", Judge.decide(null, blocking).reason)
    }

    /** InboxStyle 的正文在 textLines 里、三段留空是推广通知的常见形态，不能当成「无文本」放行。 */
    @Test
    fun inboxStyleLinesCountAsText() {
        val s = snap(text = null, textLines = listOf("限时特惠", "立即领取"))
        assertFalse(Judge.decide(s, blocking).reason == "empty_text")
    }

    /** 折叠摘要行同理：extras 里三段可能全空，内容只在 subText。 */
    @Test
    fun subTextCountsAsText() {
        val s = snap(text = null, subText = "3 条新消息")
        assertFalse(Judge.decide(s, blocking).reason == "empty_text")
    }

    /** 三段、补充字段、多行全空才真的是「无文本」。 */
    @Test
    fun noTextAtAllIsEmptyText() {
        val s = snap(title = null, text = null, textLines = emptyList())
        assertEquals("empty_text", Judge.decide(s, blocking).reason)
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
        assertEquals("ai_off", Judge.decide(s, blocking).reason)
        assertEquals(4, s.textLength)
    }

    @Test
    fun disabledConfigPassesBeforeAnythingElse() {
        val cfg = Config(enabled = false, rules = listOf(rule("正文")))
        assertEquals("disabled", Judge.decide(snap(), cfg).reason)
    }

    // ===== 保护类型（先于规则） =====

    @Test
    fun protectedCategoriesPassEvenWhenRuleHits() {
        val cfg = Config(rules = listOf(rule("正文")))
        assertEquals("protect_call", Judge.decide(snap(category = categoryCall), cfg).reason)
        assertEquals("protect_media", Judge.decide(snap(category = categoryTransport), cfg).reason)
        assertEquals("protect_fgs", Judge.decide(snap(flags = flagForegroundService), cfg).reason)
        assertEquals("protect_conversation", Judge.decide(snap(messaging = true), cfg).reason)
    }

    @Test
    fun protectSwitchesCanBeTurnedOff() {
        val cfg = Config(
            protect = ProtectSwitches(call = false),
            rules = listOf(rule("正文")),
        )
        assertEquals("rule:r1", Judge.decide(snap(category = categoryCall), cfg).reason)
    }

    // ===== 规则 =====

    @Test
    fun ruleHitBlocks() {
        val cfg = Config(rules = listOf(rule("正文")))
        val d = Judge.decide(snap(), cfg)
        assertTrue(d.block)
        assertEquals("rule:r1", d.reason)
        assertEquals("r1", d.ruleId)
    }


    @Test
    fun ruleMissFallsThroughToAiSegment() {
        val cfg = Config(spamEnabled = true, rules = listOf(rule("不存在的词")))
        assertEquals("no_model", Judge.decide(snap(), cfg).reason)
    }

    @Test
    fun packagesLimitedRuleOnlyAppliesToItsApps() {
        val cfg = Config(
            rules = listOf(Rule(id = "r1", keywords = listOf("正文"), packages = setOf("com.other.app"))),
        )
        assertEquals("ai_off", Judge.decide(snap(pkg = "com.example.app"), cfg).reason)
        assertEquals("rule:r1", Judge.decide(snap(pkg = "com.other.app"), cfg).reason)
    }

    // ===== 硬保护词 / 极短文本 / 白名单 =====

    @Test
    fun hardWordPassesWhenNoRuleMatches() {
        val cfg = Config(rules = listOf(rule("不存在的词")))
        assertEquals("hard_word", Judge.decide(snap(text = "您的验证码是 1234"), cfg).reason)
    }

    @Test
    fun ruleBeatsHardWord() {
        // 顺序：规则在第 5 步、硬保护词在第 7 步 —— 用户把「验证码」写进规则就是要拦它
        val cfg = Config(rules = listOf(rule("验证码")))
        assertEquals("rule:r1", Judge.decide(snap(text = "您的验证码是 1234"), cfg).reason)
    }

    @Test
    fun tooShortTextPasses() {
        val cfg = Config(rules = listOf(rule("不存在的词")))
        assertEquals("text_too_short", Judge.decide(snap(title = "ab", text = null), cfg).reason)
    }

    @Test
    fun whitelistSkipsAiSegmentOnly() {
        val cfg = Config(
            spamEnabled = true,
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
        val cfg = Config(rules = listOf(rule("正文")))
        assertNull(Judge.decide(snap(title = null, text = null), cfg).ruleId)
    }

    // ===== AI 段 =====

    @Test
    fun spamDisabledPassesWithAiOff() {
        ModelHolder.replace(SpamScorer { 1.0 })
        assertEquals("ai_off", Judge.decide(snap(), blocking).reason)
    }

    @Test
    fun missingModelPasses() {
        assertEquals("no_model", Judge.decide(snap(), aiCfg()).reason)
    }

    @Test
    fun scoreAboveThresholdBlocks() {
        ModelHolder.replace(SpamScorer { 0.91 })
        val d = Judge.decide(snap(), aiCfg(threshold = 0.9))
        assertTrue(d.block)
        assertEquals("ai:0.91", d.reason)
        assertEquals(0.91, d.score!!, 1e-9)
        assertNull(d.ruleId)
    }

    @Test
    fun thresholdIsInclusive() {
        ModelHolder.replace(SpamScorer { 0.9 })
        assertTrue(Judge.decide(snap(), aiCfg(threshold = 0.9)).block)
    }

    @Test
    fun scoreBelowThresholdPassesButKeepsScore() {
        ModelHolder.replace(SpamScorer { 0.42 })
        val d = Judge.decide(snap(), aiCfg(threshold = 0.9))
        assertFalse(d.block)
        assertEquals("below_threshold:0.42", d.reason)
        // 分数必须随记录带出来：界面上「严格程度」的档位与排障都看它，放行那条也要有
        assertEquals(0.42, d.score!!, 1e-9)
    }


    /** 打分跑在 system_server 的通知入队路径上：异常穿透等于通知发不出来。 */
    @Test
    fun scorerFailurePasses() {
        ModelHolder.replace(SpamScorer { throw IllegalStateException("boom") })
        val d = Judge.decide(snap(), aiCfg())
        assertFalse(d.block)
        assertEquals("ai_error", d.reason)
    }

    @Test
    fun scorerReceivesJudgeText() {
        val seen = ArrayList<String>()
        ModelHolder.replace(SpamScorer { seen.add(it); 0.1 })
        Judge.decide(snap(title = "标题", text = "正文", bigText = "长文"), aiCfg())
        assertEquals(listOf("标题\n正文\n长文"), seen)
    }

    @Test
    fun whitelistedPackageNeverReachesScorer() {
        var called = false
        ModelHolder.replace(SpamScorer { called = true; 1.0 })
        val cfg = Config(spamEnabled = true, whitelist = setOf("com.example.app"))
        assertEquals("whitelisted", Judge.decide(snap(), cfg).reason)
        assertFalse(called)
    }

    @Test
    fun bundledModelIsUsable() {
        // 资源链断了（模型没打进 APK / classpath）时在这里红，而不是到真机上才发现
        assertNotNull("classpath 里没有 ${io.github.vstory.notifyguard.ai.SpamModel.RESOURCE}", io.github.vstory.notifyguard.ai.SpamModel.bundled())
    }
}
