package io.github.vstory.notifyguard.judge

/**
 * 一条用户标注（纯数据，两端共用）。
 *
 * [key] 用 `"$ts:$pkg"`，[ts] 取一组记录的**首见**时间：聚合后它恒定（组内后续通知只动 `lastTs`/`count`），
 * 所以标注天然跟着组走，而不必给 [LogRecord] 加 id 字段。
 *
 * [text] 自带训练文本、不引用流水：记录是 500 组滚动窗口，标注产生后流水随时可能被裁掉（判据 J1）。
 */
data class LabelRecord(
    val key: String,
    val ts: Long,
    val pkg: String,
    val text: String,
    val spam: Boolean,
    /** 标注时间。淘汰依据是它，不是 [ts] —— 被裁的该是标得最早的，不是通知最早发生的。 */
    val at: Long,
    /** 标注时的 base 指纹（模型文件 CRC32）。换模型后可据此筛出旧样本重标。 */
    val modelVersion: Int,
) {

    companion object {

        fun keyOf(r: LogRecord): String = "${r.ts}:${r.pkg}"

        fun of(r: LogRecord, spam: Boolean, at: Long, modelVersion: Int): LabelRecord = LabelRecord(
            key = keyOf(r),
            ts = r.ts,
            pkg = r.pkg,
            text = trainingText(r),
            spam = spam,
            at = at,
            modelVersion = modelVersion,
        )

        /**
         * 取 [LogRecord.aiText]（判定实际用过的文本，与推理同分布）；它缺失时回退标题+正文 ——
         * 该字段是 M2 后期才加的，早期记录没有。
         */
        private fun trainingText(r: LogRecord): String =
            r.aiText?.takeIf { it.isNotBlank() } ?: listOfNotNull(r.title, r.text).joinToString("\n")
    }
}
