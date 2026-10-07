package io.github.vstory.notifyguard.judge

/**
 * 判定链 —— M0 只落「结构性放行」（设计方案.md §6 的第 1、2 步），且**恒不拦**。
 *
 * 保护类型 / 白名单 / 规则 / 模型 / 阈值属 M1、M2。[Decision.block] 字段现在就留，是为了 M1 改判定链时
 * 不动 hooker 里的调用点。
 */
object Judge {

    const val SELF_PKG = "io.github.vstory.notifyguard"

    data class Decision(val block: Boolean, val reason: String)

    fun decide(s: NotifySnapshot?): Decision {
        if (s == null || s.pkg.isNullOrEmpty()) return Decision(false, "bad_args")
        if (s.pkg == SELF_PKG) return Decision(false, "self_pkg")
        if (s.isGroupSummary) return Decision(false, "group_summary")
        if (s.hasNoText()) return Decision(false, "empty_text")

        return Decision(false, "observe_pass")
    }
}
