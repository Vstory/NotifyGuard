package io.github.vstory.notifyguard.data

import android.content.Context
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.sync.LabelCodec
import org.json.JSONArray
import java.io.File

/**
 * 标注落盘（与 [LogStore] 同构、只换文件路径）：
 * - system_server 侧：`/data/misc/notifyguard/labels.json`（权威源）
 * - App 侧：`filesDir/labels.json`（拉取结果的本地缓存）
 *
 * 与记录的关键差别在淘汰依据：记录按最近活跃裁，标注按 [LabelRecord.at] 裁 ——
 * 被裁的必须是标得最早的那条，而不是「通知最早发生」的那条。
 *
 * 构造收 [File] 而不是 Context：单测直接喂临时目录，不需要 Android 环境。
 */
class LabelStore(private val file: File) {

    private val lock = Any()

    @Volatile private var loaded = false

    /**
     * 「文件存在且非空，但整体不是 JSON 数组」。置位后拒绝一切增量写入 ——
     * 因为此时内存快照与权威源无关，写回等于拿空库覆盖用户标注（[LabelCodec.parseList] 的三态区分）。
     * 不重试读盘：文件已经坏了，再读还是坏的。
     */
    @Volatile private var loadFailed = false

    private val labels = HashMap<String, LabelRecord>()

    /** 单测注入点：真上限下逐条写 5000 次会退化成 O(n²) 序列化，用例改小它（同 [LogSink] 的做法）。 */
    internal var maxLabels = MAX_LABELS

    val isLoadFailed: Boolean get() = synchronized(lock) { ensureLoaded(); loadFailed }

    /** 同一 key 再标即用户改主意，只留最新一条。 */
    fun upsert(l: LabelRecord): Boolean = synchronized(lock) {
        ensureLoaded()
        if (loadFailed) return false
        labels[l.key] = l
        trim()
        persist()
    }

    /** 删不存在的 key 是成功：调用方不必先查存在性，也不会因此白写一次盘。 */
    fun delete(key: String): Boolean = synchronized(lock) {
        ensureLoaded()
        if (loadFailed) return false
        if (labels.remove(key) == null) true else persist()
    }

    fun clear(): Boolean = synchronized(lock) {
        ensureLoaded()
        if (loadFailed) return false
        labels.clear()
        persist()
    }

    /**
     * 覆盖式替换（App 侧缓存专用：权威源在模块端，回执里的全量就是唯一真相）。
     * **不受 [loadFailed] 限制**：它是显式覆盖语义，坏掉的本地缓存正该被它冲掉。
     */
    fun replaceAll(list: List<LabelRecord>): Boolean = synchronized(lock) {
        ensureLoaded()
        labels.clear()
        list.forEach { labels[it.key] = it }
        trim()
        loadFailed = false
        persist()
    }

    /**
     * 按 key 升序。只承诺顺序**确定**（拟合要固定顺序才可复现），不承诺时间序 ——
     * key 是字符串，字符串序与 [LabelRecord.ts] 序本来就不一致。
     */
    fun all(): List<LabelRecord> = synchronized(lock) {
        ensureLoaded()
        labels.values.sortedBy { it.key }
    }

    fun size(): Int = synchronized(lock) {
        ensureLoaded()
        labels.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        if (text.isNullOrBlank()) return
        val parsed = LabelCodec.parseList(text)
        if (parsed == null) {
            loadFailed = true
            return
        }
        parsed.forEach { labels[it.key] = it }
    }

    private fun trim() {
        while (labels.size > maxLabels) {
            val oldest = labels.values.minByOrNull { it.at } ?: return
            labels.remove(oldest.key)
        }
    }

    /** 返回写盘结果：模块端要靠它决定回不回执（回执的语义是「已持久化」，不是「收到了」）。 */
    private fun persist(): Boolean = runCatching {
        val arr = JSONArray()
        labels.values.forEach { arr.put(LabelCodec.toJson(it)) }
        file.parentFile?.mkdirs()
        val json = arr.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(json)
        }
    }.isSuccess

    companion object {

        /**
         * 比 [LogStore.MAX_RECORDS]（500）大一个数量级：两者淘汰的不是同类东西 ——
         * 记录是流水，过了就没价值；标注是用户手工劳动。5000 条 × ~500B ≈ 2.5 MB，单文件 JSON 仍可接受。
         */
        const val MAX_LABELS = 5000

        const val FILE_NAME = "labels.json"

        @Volatile private var instance: LabelStore? = null

        /** App 侧界面与拉取器共用一份内存列表，避免各自读盘。 */
        fun get(ctx: Context): LabelStore = instance ?: synchronized(this) {
            instance ?: LabelStore(File(ctx.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
