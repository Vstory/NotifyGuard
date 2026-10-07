package io.github.vstory.notifyguard.judge

/**
 * 一条用户标注（纯数据，两端共用）。
 *
 * [key] 用 `"$ts:$pkg:$digest"`，[ts] 取一组记录的**首见**时间：聚合后它恒定（组内后续通知只动
 * `lastTs`/`count`），所以标注天然跟着组走，而不必给 [LogRecord] 加 id 字段。
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

        fun keyOf(r: LogRecord): String = "${r.ts}:${r.pkg}:${digestOf(r)}"

        /**
         * 同一毫秒、同一包名、内容不同的两条通知必须能分开。
         *
         * `ts` 由判定侧给 `System.currentTimeMillis()`（毫秒级），一个 App 在同一毫秒连推两条不同
         * 通知完全可能；那时两条记录组不同、而 `"$ts:$pkg"` 相同 ⇒ 标了其中一条，另一条在记录页也
         * 显示「已标注」，而库里只有一条 —— 用户以为自己标了两条。
         *
         * `block`/`would` 也进摘要，因为 [LogRecord.sameGroup] 含它们：同文本「拦过」与「放过」
         * 本来就是两个组。`hashCode` 的 32 位碰撞概率在此可忽略（只为把撞车降到「不会遇到」）。
         */
        private fun digestOf(r: LogRecord): String {
            val sb = StringBuilder()
            for (part in listOf(r.title, r.text)) {
                // 长度前缀而非分隔符：null 与空串必须摘出不同的值（聚合键里它们也是不同的组）
                sb.append(part?.length ?: -1).append(':').append(part.orEmpty()).append('|')
            }
            sb.append(r.block).append('|').append(r.would)
            return sb.toString().hashCode().toUInt().toString(16)
        }

        /**
         * 记录页用：这批记录各自已标注成什么，**缺失即未标注**（`null`）。
         *
         * 必须走 [keyOf] —— 拿 `lastTs` 拼 key（组内一条新通知就变）、或另写一套拼法，
         * 都会让「已标注」永远显示成未标注，用户于是重复标注。
         */
        fun marksOf(records: List<LogRecord>, labels: List<LabelRecord>): Map<String, Boolean> {
            val byKey = labels.associateBy { it.key }
            return records.mapNotNull { r ->
                val k = keyOf(r)
                byKey[k]?.let { k to it.spam }
            }.toMap()
        }

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
