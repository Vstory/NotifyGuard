package io.github.vstory.notifyguard.data

import android.content.Context
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.LogCodec
import org.json.JSONArray
import java.io.File

/**
 * 记录落盘（设计方案.md §7.3：首版不用 Room）。两端同一份实现、只换了文件路径：
 * - system_server 侧：`/data/misc/notifyguard/logs.json`（记录权威源）
 * - App 侧：`filesDir/logs.json`（拉取结果的本地缓存，打开即显示，不依赖模块端可用）
 *
 * 单文件 JSON + 内存列表，上限 [MAX_RECORDS]，超出裁掉最旧。查询面只有「按时间倒序取最近 N 条」，
 * 为难得的筛选需求引入 schema 迁移成本不划算。
 *
 * 构造收 [File] 而不是 Context：单测直接喂临时目录，不需要 Android 环境。
 */
class LogStore(private val file: File) {

    private val lock = Any()

    @Volatile private var loaded = false
    private val records = ArrayList<LogRecord>()

    /** 批量入口：模块端一次 flush 对应这里一次写盘，不是每条记录各写一次。返回是否真的落盘了。 */
    fun addAll(list: List<LogRecord>): Boolean {
        if (list.isEmpty()) return true
        synchronized(lock) {
            ensureLoaded()
            records.addAll(list)
            while (records.size > MAX_RECORDS) records.removeAt(0)
            return persist()
        }
    }

    /** App 侧拉取结果的覆盖式写入：模块端是权威源，不做合并去重。 */
    fun replaceAll(list: List<LogRecord>): Boolean = synchronized(lock) {
        ensureLoaded()
        records.clear()
        records.addAll(list)
        while (records.size > MAX_RECORDS) records.removeAt(0)
        persist()
    }

    fun recent(limit: Int): List<LogRecord> = synchronized(lock) {
        ensureLoaded()
        records.asReversed().take(limit)
    }

    fun size(): Int = synchronized(lock) {
        ensureLoaded()
        records.size
    }

    fun clear(): Boolean = synchronized(lock) {
        ensureLoaded()
        records.clear()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return@runCatching
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> LogCodec.fromJson(o)?.let(records::add) }
            }
        }
    }

    /** 返回写盘结果：模块端要靠它决定「记录出不出缓冲」（写失败不能当成功）。 */
    private fun persist(): Boolean = runCatching {
        val arr = JSONArray()
        records.forEach { arr.put(LogCodec.toJson(it)) }
        file.parentFile?.mkdirs()
        val json = arr.toString()
        // 写 tmp 再 rename：单文件全量重写下，进程被杀会留下半截 JSON（下一次读直接得到空列表）
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(json)
        }
    }.isSuccess

    companion object {

        const val MAX_RECORDS = 500
        const val FILE_NAME = "logs.json"

        @Volatile private var instance: LogStore? = null

        /** App 侧界面与拉取器共用一份内存列表，避免各自读盘。 */
        fun get(ctx: Context): LogStore = instance ?: synchronized(this) {
            instance ?: LogStore(File(ctx.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
