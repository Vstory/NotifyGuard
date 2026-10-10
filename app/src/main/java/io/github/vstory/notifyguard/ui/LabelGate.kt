package io.github.vstory.notifyguard.ui

import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.judge.ProtectGuard
import io.github.vstory.notifyguard.judge.ProtectSwitches

/**
 * 标注闸门：命中保护类型、且该保护仍开着 ⇒ 不许标。
 *
 * 判定在 [ProtectGuard]、文案在 [ReasonInfo]，两个屏（记录 / 学习）各自拼一遍就会某天只改一处；
 * 所以判据与提示收在这一个入口。
 *
 * 开关读不到（未连接模块端）时**不拦**：与「未知不等于开着」同口径 —— 拿不到真实开关就当没开，
 * 宁可让用户标一条会被保护放行的样本，也不要在他什么都不知道的情况下把按钮锁死。
 */
object LabelGate {

    /** 非空 = 禁止标注，值是保护类型的人话（用于提示）；null = 可以标。 */
    fun blockedLabel(r: LogRecord, protect: ProtectSwitches?): UiText? =
        protect
            ?.let { ProtectGuard.labelBlockedType(r.reason, it) }
            ?.let { ReasonInfo.protectTypeLabel(it) }
}
