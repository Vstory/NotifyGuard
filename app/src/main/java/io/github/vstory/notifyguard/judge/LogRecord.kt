package io.github.vstory.notifyguard.judge

/**
 * 回流给 App 的一条判定记录（纯数据，两端共用）。
 *
 * 文本各截断到 [TEXT_MAX]：记录页只做展示，长正文通知不该让回流的传输与落盘体积失控。
 */
data class LogRecord(
    val ts: Long,
    val pkg: String,
    val title: String?,
    val text: String?,
    val reason: String,
    val would: Boolean,
    val block: Boolean,
    val slot: String?,
    val ruleId: String? = null,
    val score: Double? = null,
) {

    companion object {

        const val TEXT_MAX = 200

        fun from(s: NotifySnapshot?, d: Judge.Decision, slot: String, ts: Long): LogRecord = LogRecord(
            ts = ts,
            pkg = s?.pkg.orEmpty(),
            title = clip(s?.title),
            // 正文优先 text，为空才退到 bigText（长文通知里 text 常常只是摘要）
            text = clip(s?.text?.takeIf { it.isNotBlank() } ?: s?.bigText),
            reason = d.reason,
            // would/block 分开记：观察模式下 would=true, block=false，这一对就是切拦截模式的依据
            would = d.wouldBlock,
            block = d.block,
            slot = slot,
            ruleId = d.ruleId,
        )

        private fun clip(v: String?): String? = v?.take(TEXT_MAX)
    }
}
