package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.ProtectSwitches
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    /** 期望解析成功的用例都用这个取结果（org.junit 的 assertNotNull 返回 void，不能当值用）。 */
    private fun decode(json: String?): Config = requireNotNull(ConfigCodec.decode(json))

    @Test
    fun blankFallsBackToDefaults() {
        for (json in listOf(null, "", "   ")) {
            val c = decode(json)
            assertTrue(c.observe)
            assertTrue(c.enabled)
            assertTrue(c.rules.isEmpty())
            assertTrue(c.protect.call)
        }
    }

    @Test
    fun roundTripKeepsEveryField() {
        val src = Config(
            enabled = false,
            observe = false,
            protect = ProtectSwitches(call = false, media = false),
            whitelist = setOf("com.keep.me"),
            rules = listOf(
                Rule(
                    id = "r1",
                    name = "电商推广",
                    enabled = true,
                    type = RuleType.KEYWORD,
                    logic = RuleLogic.AND,
                    keywords = listOf("优惠券", "限时"),
                    packages = setOf("com.shop.app"),
                ),
                Rule(id = "r2", type = RuleType.REGEX, pattern = "(点击|戳).{0,8}领取"),
            ),
            threshold = 0.55,
        )
        val back = decode(ConfigCodec.encode(src))

        assertEquals(src.enabled, back.enabled)
        assertEquals(src.observe, back.observe)
        assertEquals(src.protect.call, back.protect.call)
        assertEquals(src.protect.media, back.protect.media)
        assertEquals(src.protect.navigation, back.protect.navigation)
        assertEquals(src.whitelist, back.whitelist)
        assertEquals(src.threshold, back.threshold, 0.0)
        assertEquals(2, back.rules.size)
        assertEquals(src.rules[0], back.rules[0])
        assertEquals(src.rules[1], back.rules[1])
    }

    /**
     * M1f：开关下发走「读回已生效配置 + 只替换一个字段」。这条用例锁住那个契约 ——
     * 单字段更新经编解码往返后，阈值 / 白名单 / 保护开关 / 规则一个都不能变（变一个就是把别的改动夹带下去了）。
     */
    @Test
    fun singleFieldUpdateCarriesNothingElse() {
        val base = decode(
            """{"schema":1,"observe":true,"threshold":0.83,"whitelist":["com.keep.me"],
                "protect":{"call":false},"spamEnabled":false,
                "rules":[{"id":"custom-keywords","keywords":["贷款"]}]}""",
        )
        val back = decode(ConfigCodec.encode(base.copy(spamEnabled = true)))

        assertTrue(back.spamEnabled)
        assertTrue(back.observe)
        assertEquals(0.83, back.threshold, 0.0)
        assertEquals(setOf("com.keep.me"), back.whitelist)
        assertFalse(back.protect.call)
        assertEquals(base.rules, back.rules)
    }

    @Test
    fun brokenJsonReturnsNull() {        assertNull(ConfigCodec.decode("{ 这不是 json"))
        assertNull(ConfigCodec.decode("[]"))
    }

    @Test
    fun unknownSchemaReturnsNull() {
        assertNull(ConfigCodec.decode("""{"schema":99}"""))
        assertNull(ConfigCodec.decode("""{"schema":0}"""))
    }

    @Test
    fun missingFieldsUseDefaults() {
        val c = decode("""{"schema":1}""")
        assertTrue(c.enabled)
        assertTrue(c.observe)
        assertTrue(c.rules.isEmpty())
        assertTrue(c.whitelist.isEmpty())
        assertEquals(Config.DEFAULT_THRESHOLD, c.threshold, 0.0)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val c = decode("""{"schema":1,"future":"x","nested":{"a":1}}""")
        assertTrue(c.enabled)
    }

    /**
     * 微调版本号挂在同一条「读回配置 + 只替换一个字段」的路径上（[io.github.vstory.notifyguard.sync.DeltaFitter]），
     * 所以它必须与阈值 / 开关 / 规则一起原样往返，否则一次拟合下发会把用户刚改的规则抹掉。
     */
    @Test
    fun deltaVersionRoundTripsAndCarriesNothingElse() {
        val base = decode(
            """{"schema":1,"observe":false,"threshold":0.83,"spamEnabled":true,
                "rules":[{"id":"custom-keywords","keywords":["贷款"]}]}""",
        )
        assertEquals(0L, base.deltaVersion)
        val back = decode(ConfigCodec.encode(base.copy(deltaVersion = 1759812345678L)))
        assertEquals(1759812345678L, back.deltaVersion)
        assertTrue(back.spamEnabled)
        assertFalse(back.observe)
        assertEquals(0.83, back.threshold, 0.0)
        assertEquals(base.rules, back.rules)
    }

    @Test
    fun ruleWithoutIdIsDropped() {
        assertTrue(decode("""{"schema":1,"rules":[{"keywords":["a"]}]}""").rules.isEmpty())
    }

    @Test
    fun unknownRuleTypeIsDropped() {
        assertTrue(decode("""{"schema":1,"rules":[{"id":"r1","type":"glob","keywords":["a"]}]}""").rules.isEmpty())
    }

    @Test
    fun keywordRuleWithoutKeywordsIsDropped() {
        val json = """{"schema":1,"rules":[
            {"id":"r1","type":"keyword","keywords":[]},
            {"id":"r2","type":"keyword","keywords":["   "]}
        ]}"""
        assertTrue(decode(json).rules.isEmpty())
    }

    @Test
    fun regexRuleWithoutPatternIsDropped() {
        assertTrue(decode("""{"schema":1,"rules":[{"id":"r1","type":"regex","pattern":"  "}]}""").rules.isEmpty())
    }

    @Test
    fun duplicateRuleIdKeepsFirst() {
        val json = """{"schema":1,"rules":[
            {"id":"r1","keywords":["first"]},
            {"id":"r1","keywords":["second"]}
        ]}"""
        val rules = decode(json).rules
        assertEquals(1, rules.size)
        assertEquals(listOf("first"), rules[0].keywords)
    }

    @Test
    fun disabledRuleSurvivesParsingButNotCompilation() {
        val cfg = decode("""{"schema":1,"rules":[{"id":"r1","enabled":false,"keywords":["a"]}]}""")
        assertEquals(1, cfg.rules.size)
        assertFalse(cfg.rules[0].enabled)
        assertTrue(cfg.compiledRules.isEmpty())
        assertEquals(0, cfg.droppedRules)
    }

    @Test
    fun invalidRegexCountsAsDroppedRule() {
        val cfg = decode("""{"schema":1,"rules":[{"id":"r1","type":"regex","pattern":"("}]}""")
        assertEquals(1, cfg.rules.size)
        assertTrue(cfg.compiledRules.isEmpty())
        assertEquals(1, cfg.droppedRules)
    }
}
