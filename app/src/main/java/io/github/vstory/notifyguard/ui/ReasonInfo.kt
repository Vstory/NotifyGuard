package io.github.vstory.notifyguard.ui

import androidx.annotation.StringRes
import io.github.vstory.notifyguard.R
import java.util.Locale

/**
 * 判定原因码 → 人话（判定链见 README 的「判定链」表）。
 *
 * 模块端回传的原因码是排障用的技术串：框架日志、回流、聚合键都靠它，所以**数据层一个字不改**，
 * 这里只负责把它讲明白 —— [raw] 会一路带进弹窗照原样显示，界面上的说法与日志里的串对得上。
 *
 * 认不出的原因码落 [ReasonKind.UNKNOWN] 并给出原文：判定链以后加码时，旧版 App 要诚实地说
 * 「这条我看不懂」，不能拿一个像模像样的短语糊过去。
 */
data class ReasonInfo(
    val kind: ReasonKind,
    /** 原始原因码（`disabled` / `ai:0.87` 这类）。 */
    val raw: String,
    /** 模板参数：保护类型 / 规则 id / 分数。 */
    val args: List<UiText> = emptyList(),
) {

    /** 列表行底部的短语。 */
    val short: UiText = text(kind.shortRes)

    /** 弹窗正文：为什么这么判、要去哪儿改。 */
    val why: UiText = text(kind.whyRes)

    private fun text(@StringRes id: Int): UiText =
        if (args.isEmpty()) UiText.Res(id) else UiText.Res(id, args)

    companion object {

        /**
         * 判定链上每条出口一档。链上加码时必须同步这张表 —— 漏了不会报错，
         * 只会让用户看到「未知原因」。
         */
        private val EXACT: Map<String, ReasonKind> = mapOf(
            "bad_args" to ReasonKind.BAD_ARGS,
            "disabled" to ReasonKind.DISABLED,
            "self_pkg" to ReasonKind.SELF_PKG,
            "group_summary" to ReasonKind.GROUP_SUMMARY,
            "empty_text" to ReasonKind.EMPTY_TEXT,
            "text_too_short" to ReasonKind.TEXT_TOO_SHORT,
            "hard_word" to ReasonKind.HARD_WORD,
            "whitelisted" to ReasonKind.WHITELISTED,
            "ai_off" to ReasonKind.AI_OFF,
            "no_model" to ReasonKind.NO_MODEL,
            "ai_error" to ReasonKind.AI_ERROR,
        )

        private const val PREFIX_PROTECT = "protect_"
        private const val PREFIX_RULE = "rule:"
        /** 带分数的两族：`ai_off` / `ai_error` 是完整串，只有带冒号的才是分数。 */
        private const val PREFIX_AI = "ai:"
        private const val PREFIX_BELOW = "below_threshold:"

        /**
         * 判定侧的类型名与设置页的键名不同源：前台服务在判定侧是 `fgs`，资源键是
         * `protect_foreground_service` —— 靠拼串（`"protect_" + 类型名`）在这里找不到键。
         */
        private val PROTECT_LABELS: Map<String, Int> = mapOf(
            "call" to R.string.protect_call,
            "alarm" to R.string.protect_alarm,
            "navigation" to R.string.protect_navigation,
            "media" to R.string.protect_media,
            "fgs" to R.string.protect_foreground_service,
            "conversation" to R.string.protect_conversation,
        )

        /**
         * @param score 记录里的结构化分数。带分数的原因优先用它，串只在字段缺失时兜底 ——
         *   字段是数据，串是给人看的那一份。
         */
        fun of(reason: String, score: Double? = null, ruleId: String? = null): ReasonInfo = when {
            reason.startsWith(PREFIX_PROTECT) -> ReasonInfo(
                ReasonKind.PROTECT, reason,
                listOf(protectLabel(reason.removePrefix(PREFIX_PROTECT))),
            )

            reason.startsWith(PREFIX_RULE) -> ReasonInfo(
                ReasonKind.RULE, reason,
                listOf(UiText.Raw(ruleId ?: reason.removePrefix(PREFIX_RULE))),
            )

            reason.startsWith(PREFIX_AI) -> ReasonInfo(
                ReasonKind.AI_HIT, reason,
                listOf(UiText.Raw(scoreText(score, reason, PREFIX_AI))),
            )

            reason.startsWith(PREFIX_BELOW) -> ReasonInfo(
                ReasonKind.BELOW_THRESHOLD, reason,
                listOf(UiText.Raw(scoreText(score, reason, PREFIX_BELOW))),
            )

            else -> EXACT[reason]?.let { ReasonInfo(it, reason) }
                ?: ReasonInfo(ReasonKind.UNKNOWN, reason, listOf(UiText.Raw(reason)))
        }

        private fun protectLabel(type: String): UiText =
            PROTECT_LABELS[type]?.let { UiText.Res(it) } ?: UiText.Raw(type)

        /** 与判定链 `%.2f` 同粒度（更粗就与设置页的阈值档位对不上数）。 */
        private fun scoreText(score: Double?, reason: String, prefix: String): String =
            score?.let { String.format(Locale.ROOT, "%.2f", it) } ?: reason.removePrefix(prefix)
    }
}

/** 判定链每条出口的文案槽位。 */
enum class ReasonKind(@StringRes val shortRes: Int, @StringRes val whyRes: Int) {
    BAD_ARGS(R.string.records_reason_short_bad_args, R.string.records_reason_why_bad_args),
    DISABLED(R.string.records_reason_short_disabled, R.string.records_reason_why_disabled),
    SELF_PKG(R.string.records_reason_short_self_pkg, R.string.records_reason_why_self_pkg),
    GROUP_SUMMARY(R.string.records_reason_short_group_summary, R.string.records_reason_why_group_summary),
    EMPTY_TEXT(R.string.records_reason_short_empty_text, R.string.records_reason_why_empty_text),
    PROTECT(R.string.records_reason_short_protect, R.string.records_reason_why_protect),
    RULE(R.string.records_reason_short_rule, R.string.records_reason_why_rule),
    TEXT_TOO_SHORT(R.string.records_reason_short_text_too_short, R.string.records_reason_why_text_too_short),
    HARD_WORD(R.string.records_reason_short_hard_word, R.string.records_reason_why_hard_word),
    WHITELISTED(R.string.records_reason_short_whitelisted, R.string.records_reason_why_whitelisted),
    AI_OFF(R.string.records_reason_short_ai_off, R.string.records_reason_why_ai_off),
    NO_MODEL(R.string.records_reason_short_no_model, R.string.records_reason_why_no_model),
    AI_ERROR(R.string.records_reason_short_ai_error, R.string.records_reason_why_ai_error),
    AI_HIT(R.string.records_reason_short_ai_hit, R.string.records_reason_why_ai_hit),
    BELOW_THRESHOLD(R.string.records_reason_short_below_threshold, R.string.records_reason_why_below_threshold),
    UNKNOWN(R.string.records_reason_short_unknown, R.string.records_reason_why_unknown),
}

/**
 * 判定槽 → 文案（设置页与记录页共用，同一概念在两屏必须是同一句话）。
 *
 * 认不出的槽值给原文而不是「未知」：那是个可拿去对日志的枚举名，抹掉等于把线索扔掉。
 */
fun slotLabel(slot: String): UiText = when (slot) {
    "EXT_SLOT" -> UiText.Res(R.string.module_slot_ext)
    "FUNNEL" -> UiText.Res(R.string.module_slot_funnel)
    else -> if (slot.isEmpty()) UiText.Res(R.string.module_slot_unknown) else UiText.Raw(slot)
}
