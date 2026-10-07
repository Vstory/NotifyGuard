package io.github.vstory.notifyguard.judge

/**
 * 判定链（设计方案.md §6）。
 *
 * 任一步「放行」即短路；配置取一次快照传入，避免热更新落在两步之间。
 * [Decision.wouldBlock] 与 [Decision.block] 分开：观察模式下判定照跑、原因照出，只有真正拦截被压掉。
 *
 * 模型相关两步（打分与阈值）属 M2，M1 在 [no_model] 处短路放行。
 */
object Judge {

    const val SELF_PKG = "io.github.vstory.notifyguard"

    private const val MIN_AI_LEN = 4

    data class Decision(
        val block: Boolean,
        val reason: String,
        val wouldBlock: Boolean = block,
        val ruleId: String? = null,
    )

    fun decide(s: NotifySnapshot?, cfg: Config): Decision {
        if (s == null || s.pkg.isNullOrEmpty()) return pass("bad_args")
        if (!cfg.enabled) return pass("disabled")
        if (s.pkg == SELF_PKG) return pass("self_pkg")
        if (s.isGroupSummary) return pass("group_summary")
        if (s.hasNoText()) return pass("empty_text")

        ProtectGuard.protectedType(s, cfg.protect)?.let { return pass("protect_$it") }

        val raw = s.judgeText()
        val lowered = raw.lowercase()
        RuleMatcher.firstHit(cfg.compiledRules, s.pkg, lowered, raw)
            ?.let { return intercept(cfg, "rule:$it", it) }

        if (lowered.trim().length < MIN_AI_LEN) return pass("text_too_short")
        if (ProtectGuard.hasHardWord(lowered)) return pass("hard_word")

        // 白名单只跳过 AI 段，不影响规则；M2 在下一行接 SpamModel 打分与阈值比较
        if (s.pkg in cfg.whitelist) return pass("whitelisted")
        return pass("no_model")
    }

    private fun pass(reason: String) = Decision(false, reason)

    private fun intercept(cfg: Config, reason: String, ruleId: String) =
        Decision(block = !cfg.observe, reason = reason, wouldBlock = true, ruleId = ruleId)
}
