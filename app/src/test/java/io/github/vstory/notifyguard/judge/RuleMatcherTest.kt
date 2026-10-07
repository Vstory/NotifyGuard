package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {

    private fun hit(text: String = "限时抢购 优惠券", pkg: String? = "com.example.app", rules: List<Rule>): String? {
        val compiled = RuleMatcher.compile(rules)
        return RuleMatcher.firstHit(compiled, pkg, text.lowercase(), text)
    }

    @Test
    fun keywordOrHitsOnAnyWord() {
        val rules = listOf(Rule(id = "r1", keywords = listOf("不存在的词", "优惠券")))
        assertEquals("r1", hit(rules = rules))
    }

    @Test
    fun keywordAndNeedsAllWords() {
        val both = listOf(Rule(id = "r1", logic = RuleLogic.AND, keywords = listOf("限时", "优惠券")))
        val one = listOf(Rule(id = "r1", logic = RuleLogic.AND, keywords = listOf("限时", "不存在的词")))
        assertEquals("r1", hit(rules = both))
        assertNull(hit(rules = one))
    }

    @Test
    fun keywordMatchIsCaseInsensitive() {
        val rules = listOf(Rule(id = "r1", keywords = listOf("SALE")))
        assertEquals("r1", hit(text = "Big sale now", rules = rules))
    }

    @Test
    fun regexUsesFindNotMatches() {
        val rules = listOf(Rule(id = "r1", type = RuleType.REGEX, pattern = "领取"))
        assertEquals("r1", hit(text = "点击此处领取好礼", rules = rules))
        assertNull(hit(text = "此处没有那个词", rules = rules))
    }

    @Test
    fun packagesLimitScope() {
        val rules = listOf(Rule(id = "r1", keywords = listOf("优惠券"), packages = setOf("com.shop.app")))
        assertEquals("r1", hit(pkg = "com.shop.app", rules = rules))
        assertNull(hit(pkg = "com.other.app", rules = rules))
        assertNull(hit(pkg = null, rules = rules))
    }

    @Test
    fun disabledAndInvalidRulesAreNotCompiled() {
        val rules = listOf(
            Rule(id = "off", enabled = false, keywords = listOf("优惠券")),
            Rule(id = "empty", keywords = emptyList()),
            Rule(id = "bad-regex", type = RuleType.REGEX, pattern = "("),
            Rule(id = "regex-no-pattern", type = RuleType.REGEX),
            Rule(id = "ok", keywords = listOf("优惠券")),
        )
        assertEquals(1, RuleMatcher.compile(rules).size)
        assertEquals("ok", hit(rules = rules))
    }

    @Test
    fun firstMatchingRuleWins() {
        val rules = listOf(
            Rule(id = "r1", keywords = listOf("优惠券")),
            Rule(id = "r2", keywords = listOf("限时")),
        )
        assertEquals("r1", hit(rules = rules))
    }

    @Test
    fun emptyRuleSetNeverHits() {
        assertNull(hit(rules = emptyList()))
    }
}
