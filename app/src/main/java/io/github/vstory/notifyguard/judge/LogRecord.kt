package io.github.vstory.notifyguard.judge

/**
 * 回流给 App 的一条判定记录（纯数据，两端共用）。
 *
 * 一条记录代表**一组内容相同、判定相同的通知**，不是一次事件：[ts] 是本组首见、[lastTs] 最近一次、
 * [count] 累计次数。展示与排序一律用 [lastTs]，否则一个首见早、一直在刷的组会沉到列表最底。
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
    val lastTs: Long = ts,
    val count: Int = 1,
    val ruleId: String? = null,
    val score: Double? = null,
    val aiText: String? = null,
    /**
     * 通知身份（`tag#id`，包名已进聚合键）。空 = 这条记录不带身份：M2 之前落盘的老记录，
     * 或 `id=0` 无 tag 这种被 App 当默认值用的取值（见 [notifKey]）。
     */
    val nkey: String? = null,
) {

    /**
     * 是否属同一组（聚合键见 M2b实施方案.md §二）。
     *
     * [block]/[would] 必须一起比：同文本「拦过」与「放过」是两个事实，合并会把前者吞掉。
     * reason 不能进键——它带分数，纳入即等于放弃聚合。
     *
     * 带身份时**只看身份**：同一个 `tag#id` 在系统里就是同一条通知被更新覆盖，正文里的数值
     * 每变一次就新开一组会让记录页被同一个 App 刷屏（电量、网速、步数这类刷新通知都如此）。
     * 无身份时退回文本键，行为与 M2 一致。
     */
    fun sameGroup(o: LogRecord): Boolean {
        if (pkg != o.pkg || block != o.block || would != o.would) return false
        // 两边都带身份时身份说了算：同一个 tag#id 就是系统里那条通知（正文数值变了也还是它），
        // 身份不同则是两条不同通知。只有一边缺身份（老记录）才退回文本键。
        if (nkey != null && o.nkey != null) return nkey == o.nkey
        return title == o.title && text == o.text
    }

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
            nkey = notifKey(s),
        )

        private fun clip(v: String?): String? = v?.take(TEXT_MAX)

        /**
         * 通知身份 `tag#id`。
         *
         * `id=0` 且无 tag 时不给身份：简单的那种 `notify(0, …)` 被大量 App 当默认值用（每次推的
         * 是不同内容），拿它当身份会把一个 App 的全部通知合成一条。
         */
        private fun notifKey(s: NotifySnapshot?): String? {
            val id = s?.id ?: return null
            val tag = s.tag
            if (id <= 0 && tag.isNullOrEmpty()) return null
            return "${tag.orEmpty()}#$id"
        }
    }
}
