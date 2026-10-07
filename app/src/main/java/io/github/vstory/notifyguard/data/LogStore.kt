package io.github.vstory.notifyguard.data

import android.content.Context
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.LogCodec
import org.json.JSONArray
import java.io.File

/**
 * App 侧记录落盘（设计方案.md §7.3：首版不用 Room）。
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

    /** 批量入口：模块端一次 flush 对应这里一次写盘，不是每条记录各写一次。 */
    fun addAll(list: List<LogRecord>) {
        if (list.isEmpty()) return
        synchronized(lock) {
            ensureLoaded()
            records.addAll(list)
            while (records.size > MAX_RECORDS) records.removeAt(0)
            persist()
        }
    }

    fun recent(limit: Int): List<LogRecord> = synchronized(lock) {
        ensureLoaded()
        records.asReversed().take(limit)
    }

    fun size(): Int = synchronized(lock) {
        ensureLoaded()
        records.size
    }

    fun clear() = synchronized(lock) {
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

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            records.forEach { arr.put(LogCodec.toJson(it)) }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        }
    }

    companion object {

        const val MAX_RECORDS = 500
        private const val FILE_NAME = "logs.json"

        @Volatile private var instance: LogStore? = null

        /** provider 与界面同进程共用一份内存列表，避免各自读盘。 */
        fun get(ctx: Context): LogStore = instance ?: synchronized(this) {
            instance ?: LogStore(File(ctx.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
