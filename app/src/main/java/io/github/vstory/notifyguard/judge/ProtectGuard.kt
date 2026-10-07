package io.github.vstory.notifyguard.judge

import android.app.Notification

/**
 * 保护类判定：命中即放行，不进入规则与 AI（设计方案.md §6 第 3、7 步）。
 *
 * 第 3 步（保护类型）排在规则之前 —— 通话 / 闹钟 / 导航这类通知被误拦的代价远大于漏掉一条广告；
 * 第 7 步（硬保护词）排在规则之后 —— 用户把「验证码」显式写进规则关键词时，那是明确要求拦它。
 */
object ProtectGuard {

    /** 硬保护词按小写匹配（调用方传入的文本已小写）。 */
    private val HARD_WORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "安全码", "登录码", "短信验证", "身份验证",
        "otp", "verification code", "verification", "one-time password", "one time password",
    )

    fun protectedType(s: NotifySnapshot, p: ProtectSwitches): String? = when {
        p.call && s.category == Notification.CATEGORY_CALL -> "call"
        p.alarm && s.category == Notification.CATEGORY_ALARM -> "alarm"
        p.navigation && s.category == Notification.CATEGORY_NAVIGATION -> "navigation"
        p.media && s.category == Notification.CATEGORY_TRANSPORT -> "media"
        p.foregroundService && s.isForegroundService -> "fgs"
        p.conversation && s.hasMessagingStyle -> "conversation"
        else -> null
    }

    fun hasHardWord(loweredText: String): Boolean = HARD_WORDS.any { loweredText.contains(it) }
}
