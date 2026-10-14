package io.github.vstory.notifyguard.judge

/**
 * 一条用户标注（纯数据，两端共用）。
 *
 * [key] 由**领域处理后的文本**决定（见 [keyOf]）：同内容只有一条样本，改判即覆盖。
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

        fun keyOf(r: LogRecord): String = contentKey(trainingText(r))

        /**
         * 样本身份 = 归一化后的文本。**不含包名、时间、拦截状态**（用户定的规则：「垃圾就是垃圾内容」）。
         *
         * 不含包名的依据：模型打分只看 `normalize(文本)`，看不见包名 —— 同一段文案换个 App 出现时
         * 特征完全相同，按包名判同只会把同一份垃圾人为碎成多条样本，等于换个 App 重训一遍。
         *
         * 不含时间与拦截状态的后果是有意的：同一段文本先被拦、后被放，或用户改主意，都落在同一个
         * 键上，`upsert` 用最新表态覆盖旧的那条 —— 改判不必先去手删旧样本。
         */
        internal fun contentKey(text: String): String =
            "c:" + SpamFeatures.normalize(text).hashCode().toUInt().toString(16)

        /**
         * 旧库迁移：M4 期的键是 `ts:pkg:摘要`（含首见时间与拦截状态）⇒ 同文本改一次标就留下两条样本。
         * 读入时一律按新判据重算，同内容只留**最后一次表态**那条（`at` 最大；同刻按 key 定，保证两次
         * 载入得到同一份结果）。
         */
        fun migrate(list: List<LabelRecord>): List<LabelRecord> =
            list.filter { SpamFeatures.normalize(it.text).isNotEmpty() }
                .groupBy { contentKey(it.text) }
                .map { (k, g) ->
                    g.maxWith(compareBy<LabelRecord> { it.at }.thenBy { it.key }).copy(key = k)
                }
                .sortedBy { it.key }

        /**
         * 记录页用：这批记录各自已标注成什么，**缺失即未标注**（`null`）。
         *
         * 必须走 [keyOf] —— 另写一套拼法（比如拿 `lastTs` 或包名去拼）会让「已标注」永远显示成
         * 未标注，用户于是重复标注。
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
         *
         * 对自动 spam 样本（[DeltaFitter] 拿被拦记录当正样本）同样要用这个 —— 两处各写一份，
         * 训练文本就会分叉：用户手标的样本与自动样本落在不同的分布上，拟合出来互相抵消。
         */
        internal fun trainingText(r: LogRecord): String =
            r.aiText?.takeIf { it.isNotBlank() } ?: listOfNotNull(r.title, r.text).joinToString("\n")
    }
}
