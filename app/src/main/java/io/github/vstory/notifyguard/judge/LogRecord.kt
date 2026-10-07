package io.github.vstory.notifyguard.judge

/**
 * 回流给 App 的一条判定记录（纯数据，两端共用）。
 *
 * [title]/[text] 各截到 [TEXT_MAX]：记录页只做展示，长正文通知不该让回流的传输与落盘体积失控。
 * [aiText] 是另一份上限（[AI_TEXT_MAX]）的同一口径文本，专供 M3 的端侧标注训练用。
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
    val aiText: String? = null,
) {

    companion object {

        const val TEXT_MAX = 200

        /**
         * 标注训练文本的上限，比展示用的大：标注要拿「判定时一样的文本」去拟合 delta，
         * 用截断到 200 字的展示文本训练，学到的就是被截断后的偏样本。
         */
        const val AI_TEXT_MAX = 500

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
            score = d.score,
            // 与判定同一个 judgeText()：标注文本必须与推理时的输入同分布
            aiText = s?.judgeText()?.take(AI_TEXT_MAX)?.takeIf { it.isNotEmpty() },
        )

        private fun clip(v: String?): String? = v?.take(TEXT_MAX)
    }
}
