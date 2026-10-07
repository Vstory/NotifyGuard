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
 * 单文件 JSON + 内存列表，上限 [MAX_RECORDS] **组** —— 内容与判定都相同的通知并成一组（见 M2b实施方案.md）。
 * 查询面只有「按最近活跃倒序取最近 N 条」，为难得的筛选需求引入 schema 迁移成本不划算。
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
            list.forEach(::mergeInto)
            trim()
            return persist()
        }
    }

    /** App 侧拉取结果的覆盖式写入：模块端是权威源，聚合已在那边做过。 */
    fun replaceAll(list: List<LogRecord>): Boolean = synchronized(lock) {
        ensureLoaded()
        records.clear()
        records.addAll(list)
        trim()
        persist()
    }

    fun recent(limit: Int): List<LogRecord> = synchronized(lock) {
        ensureLoaded()
        records.sortedByDescending { it.lastTs }.take(limit)
    }

    fun size(): Int = synchronized(lock) {
        ensureLoaded()
        records.size
    }

    /** 各组 [LogRecord.count] 之和：记录页用它显示聚合前实际发生过多少次。 */
    fun rawCount(): Int = synchronized(lock) {
        ensureLoaded()
        records.sumOf { it.count }
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
                arr.optJSONObject(i)?.let { o -> LogCodec.fromJson(o)?.let(::mergeInto) }
            }
        }
    }

    /**
     * 并入同组记录（键见 [LogRecord.sameGroup]）。载入已有文件也走这里，所以老文件不必清空重来就会当场压实。
     *
     * 不建哈希索引：上限 500 组、每次 flush 至多 50 条，线性查重可忽略，
     * 而索引会多出一份必须与裁剪保持同步的状态。
     */
    private fun mergeInto(r: LogRecord) {
        val i = records.indexOfFirst { it.sameGroup(r) }
        if (i < 0) {
            records.add(r)
            return
        }
        val old = records[i]
        // lastTs 取较大者：同一批记录不保证按时间递增
        records[i] = old.copy(count = old.count + r.count, lastTs = maxOf(old.lastTs, r.lastTs))
    }

    /** 超限淘汰最久未活跃者，而不是最早插入者：首见早但持续在刷的组恰恰是最该留的。 */
    private fun trim() {
        while (records.size > MAX_RECORDS) {
            records.removeAt(records.indices.minByOrNull { records[it].lastTs } ?: 0)
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

        /** 上限是**组数**不是事件数：同一容量下聚合后能覆盖的时间窗长得多。 */
        const val MAX_RECORDS = 500

        const val FILE_NAME = "logs.json"

        @Volatile private var instance: LogStore? = null

        /**
         * App 侧界面与拉取器共用一份内存列表，避免各自读盘。
         *
         * 单例是进程级的 ⇒ 一律取 applicationContext：传 Activity 进来会把它一直留到进程结束，
         * 而这里只需要 filesDir。
         */
        fun get(ctx: Context): LogStore = instance ?: synchronized(this) {
            val app = ctx.applicationContext ?: ctx
            instance ?: LogStore(File(app.filesDir, FILE_NAME)).also { instance = it }
        }
    }
}
