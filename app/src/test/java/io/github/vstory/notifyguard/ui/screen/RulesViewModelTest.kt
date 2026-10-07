package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则屏里与 Android 无关的那几段逻辑（关键词归一、包名校验、白名单候选排序）。
 *
 * 屏上其余部分（读生效配置、下发、回执）要真机与框架服务才能跑，见知识库的真机验收清单 ——
 * 这里守的是「点几下就能改坏、坏了还看不出来」的部分：候选被截断后白名单项消失（用户取消不掉）、
 * 包名校验放行了非法串（下发一份错配置）。
 */
class RulesViewModelTest {

    @Test
    fun normalizeDropsBlankLinesAndTrimsEachLine() {
        assertEquals(listOf("a", "b"), RulesViewModel.normalize(" a \n\n  b\n"))
        assertTrue(RulesViewModel.normalize("  \n \n").isEmpty())
    }

    @Test
    fun pkgNameAcceptsOnlyDottedIdentifiers() {
        assertTrue(RulesViewModel.isPkgName("com.example.app"))
        assertTrue(RulesViewModel.isPkgName("io.github.vstory.notifyguard"))
        assertTrue(RulesViewModel.isPkgName("com.example_2.app"))
        for (bad in listOf("", "com", "com.", ".com.example", "com.exa mple", "com.exa-mple", "com.2app", "微信")) {
            assertFalse(bad, RulesViewModel.isPkgName(bad))
        }
    }

    @Test
    fun whitelistedCandidatesRankFirstEvenWhenRarelySeen() {
        val ranked = RulesViewModel.rankCandidates(
            whitelist = setOf("com.quiet.app"),
            counts = mapOf("com.noisy.app" to 99, "com.quiet.app" to 1),
        )
        assertEquals(listOf("com.quiet.app", "com.noisy.app"), ranked.map { it.pkg })
        assertEquals(listOf(true, false), ranked.map { it.whitelisted })
        assertEquals(listOf(1, 99), ranked.map { it.groups })
    }

    /** 记录里没出现过（groups=0）的白名单项也必须留在候选里，否则用户取消不掉它。 */
    @Test
    fun whitelistEntrySurvivesWithoutAnyRecord() {
        val ranked = RulesViewModel.rankCandidates(setOf("com.gone.app"), emptyMap())
        assertEquals(1, ranked.size)
        assertTrue(ranked[0].whitelisted)
        assertEquals(0, ranked[0].groups)
    }

    @Test
    fun truncationNeverDropsWhitelistedEntries() {
        val counts = (1..200).associate { "com.app$it" to it }
        // 按包名字典序它排在最后，靠「白名单优先」才能进截断线
        val ranked = RulesViewModel.rankCandidates(setOf("com.zzz.last"), counts)
        assertEquals(RulesViewModel.CANDIDATE_MAX, ranked.size)
        assertEquals("com.zzz.last", ranked.first().pkg)
    }

    @Test
    fun recordsOrderedByCountThenPackageName() {
        val ranked = RulesViewModel.rankCandidates(
            whitelist = emptySet(),
            counts = mapOf("b.app" to 5, "a.app" to 5, "c.app" to 9),
        )
        assertEquals(listOf("c.app", "a.app", "b.app"), ranked.map { it.pkg })
    }

    /** 有未保存改动时按钮必须自己说出来 —— 那一屏没有「保存」以外的反馈渠道。 */
    @Test
    fun saveLabelTellsWhetherThereAreUnsavedChanges() {
        assertEquals(
            UiText.Res(R.string.rules_save_keywords),
            RulesViewModel.saveLabel(dirty = false),
        )
        assertEquals(
            UiText.Res(R.string.rules_save_keywords_dirty),
            RulesViewModel.saveLabel(dirty = true),
        )
    }
}
