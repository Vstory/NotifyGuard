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

    /** 保护出口的原因码前缀：判定侧拼串（[Judge]）与 App 侧解析都引用这里，避免各写一份。 */
    const val REASON_PREFIX = "protect_"

    /** 判定侧的类型名（与设置页的资源键不同源：前台服务在判定侧是 `fgs`）。 */
    private val TYPES = setOf("call", "alarm", "navigation", "media", "fgs", "conversation")

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

    /** 原因码 → 保护类型名；不是保护出口、或类型认不出（新版模块端加了类型）都返回 null。 */
    fun typeOfReason(reason: String): String? {
        if (!reason.startsWith(REASON_PREFIX)) return null
        return reason.removePrefix(REASON_PREFIX).takeIf { it in TYPES }
    }

    /** 该类型在当前开关下是否仍受保护。 */
    fun isEnabled(type: String, p: ProtectSwitches): Boolean = when (type) {
        "call" -> p.call
        "alarm" -> p.alarm
        "navigation" -> p.navigation
        "media" -> p.media
        "fgs" -> p.foregroundService
        "conversation" -> p.conversation
        else -> false
    }

    /**
     * 该条能否标注：命中保护类型**且该保护仍开着**就不能 —— 这种通知在保护这一步就放行了，
     * 不进规则也不进 AI，标了等于给微调喂噪音。用户在设置里关掉对应保护后即可标注。
     * 返回保护类型名；null = 可以标。
     */
    fun labelBlockedType(reason: String, p: ProtectSwitches): String? =
        typeOfReason(reason)?.takeIf { isEnabled(it, p) }
}
