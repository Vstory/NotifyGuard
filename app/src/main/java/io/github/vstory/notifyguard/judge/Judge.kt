package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.ai.ModelHolder
import io.github.vstory.notifyguard.core.ModuleLogger
import java.util.Locale

/**
 * 判定链（设计方案.md §6）。
 *
 * 任一步「放行」即短路；配置取一次快照传入，避免热更新落在两步之间。
 * [Decision.wouldBlock] 与 [Decision.block] 分开：观察模式下判定照跑、原因照出，只有真正拦截被压掉。
 *
 * AI 段（第 8–9 步）取的是 [ModelHolder] 的快照，判定里不做任何 IO；打分异常一律放行——
 * 打分跑在 system_server 的通知入队路径上，异常穿透的代价是通知直接发不出来。
 */
object Judge {

    const val SELF_PKG = "io.github.vstory.notifyguard"

    /** 过短的文本不进 AI 段。[SpamTuner] 复用同一个数：标了却拟合不出特征的样本是噪声。 */
    internal const val MIN_AI_LEN = 4

    data class Decision(
        val block: Boolean,
        val reason: String,
        val wouldBlock: Boolean = block,
        val ruleId: String? = null,
        val score: Double? = null,
    )

    fun decide(s: NotifySnapshot?, cfg: Config): Decision {
        if (s == null || s.pkg.isNullOrEmpty()) return pass("bad_args")
        if (!cfg.enabled) return pass("disabled")
        if (s.pkg == SELF_PKG) return pass("self_pkg")
        if (s.isGroupSummary) return pass("group_summary")
        if (s.hasNoText()) return pass("empty_text")

        ProtectGuard.protectedType(s, cfg.protect)?.let { return pass(ProtectGuard.REASON_PREFIX + it) }

        val raw = s.judgeText()
        val lowered = raw.lowercase()
        RuleMatcher.firstHit(cfg.compiledRules, s.pkg, lowered, raw)
            ?.let { return intercept(cfg, "rule:$it", it) }

        if (lowered.trim().length < MIN_AI_LEN) return pass("text_too_short")
        if (ProtectGuard.hasHardWord(lowered)) return pass("hard_word")

        // 白名单只跳过 AI 段，不影响规则
        if (s.pkg in cfg.whitelist) return pass("whitelisted")
        if (!cfg.spamEnabled) return pass("ai_off")
        val scorer = ModelHolder.current ?: return pass("no_model")
        val score = runCatching { scorer.score(raw) }.getOrElse { t ->
            ModuleLogger.error("ai.score_failed", t, "action=pass")
            return pass("ai_error")
        }
        // 分数写进 reason：观察模式下没有第二个地方能看到分数分布，标定阈值全靠它
        if (score >= cfg.threshold) {
            return Decision(
                block = !cfg.observe,
                reason = "ai:${fmt(score)}",
                wouldBlock = true,
                score = score,
            )
        }
        return Decision(false, "below_threshold:${fmt(score)}", score = score)
    }

    private fun fmt(score: Double): String = String.format(Locale.ROOT, "%.2f", score)

    private fun pass(reason: String) = Decision(false, reason)

    private fun intercept(cfg: Config, reason: String, ruleId: String) =
        Decision(block = !cfg.observe, reason = reason, wouldBlock = true, ruleId = ruleId)
}
