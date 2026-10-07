package io.github.vstory.notifyguard.sync

import android.content.Context
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.data.RecordDir
import io.github.vstory.notifyguard.judge.LogRecord
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 模块端记录出口：worker 缓冲 → 批量落盘到 system_server 侧记录目录。
 *
 * 与 M1c 的 provider 通道相比，这里没有跨进程调用——每批记录直接写本地文件，不会「被 ROM 拦」，
 * 也就不需要「失败不出队 + 退避重试 + App 前台催送」那一套。代价是权威源挪到 system_server 侧，
 * App 侧要读得走 [LogChannel] 回传一次。
 *
 * 判定线程只投递任务：整批 JSON 写盘全在 [worker] 上，不占通知入队的热路径。
 * 队列满时丢最旧：记录是流水，新的比旧的有用。
 */
object LogSink {

    /** 单测注入点（与 ConfigReader.retryDelaysMs 同一套做法）。 */
    internal var flushThreshold = 50
    internal var flushDelayMs = 30_000L
    internal var maxPending = 500
    internal var storeOverride: LogStore? = null

    private const val FLUSH_NOW_TIMEOUT_MS = 3_000L

    /** 写盘失败后的静默期：本地写失败基本是权限/磁盘问题，不该被每条通知撞一次。 */
    private const val PERSIST_RETRY_MS = 30_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-log").apply { isDaemon = true }
    }

    // 以下各项只在 worker 线程上访问
    private val pending = ArrayDeque<LogRecord>()
    private var scheduled: ScheduledFuture<*>? = null
    private var store: LogStore? = null
    private var failureLogged = false
    private var retryAfter = 0L

    private val persisted = AtomicLong()
    private val dropped = AtomicLong()

    @Volatile private var bound = false

    fun bindContext(c: Context) {
        if (bound) return
        bound = true
        val dir = RecordDir.forSystemServer(c).resolve()
        if (dir == null) {
            ModuleLogger.error("记录落盘目录不可用（${RecordDir.BASE}）⇒ 记录不落盘（判定不受影响）")
            return
        }
        worker.execute {
            store = LogStore(java.io.File(dir, LogStore.FILE_NAME))
            ModuleLogger.info("记录落盘就绪（目录=${dir.absolutePath}）")
        }
        LogChannel.register(c)
    }

    fun submit(r: LogRecord) {
        worker.execute {
            pending.addLast(r)
            while (pending.size > maxPending) {
                pending.removeFirst()
                dropped.incrementAndGet()
            }
            if (pending.size >= flushThreshold) flush() else schedule()
        }
    }

    /** 拉取前把缓冲刷进文件：缓冲意味着最多 [flushDelayMs] 的记录还没落盘，不刷就看不到刚发生的通知。 */
    internal fun snapshotJson(): String {
        awaitFlush()
        val s = storeOverride ?: store ?: return "[]"
        return LogCodec.encodeList(s.recent(LogStore.MAX_RECORDS))
    }

    internal fun clearAll() {
        worker.execute { (storeOverride ?: store)?.clear() }
    }

    /** 回传等 IO 工作放 worker 上；广播回调（system_server 主线程）只投递任务。 */
    internal fun onWorker(block: () -> Unit) {
        worker.execute(block)
    }

    fun statsLine(): String = "已落盘=${persisted.get()} 丢弃=${dropped.get()}"

    internal fun awaitIdle() {
        worker.submit { }.get(5, TimeUnit.SECONDS)
    }

    internal fun resetForTest() {
        flushThreshold = 50
        flushDelayMs = 30_000L
        maxPending = 500
        storeOverride = null
        persisted.set(0)
        dropped.set(0)
        worker.execute {
            pending.clear()
            scheduled?.cancel(false)
            scheduled = null
            store = null
            failureLogged = false
            retryAfter = 0L
        }
        awaitIdle()
    }

    private fun awaitFlush() {
        runCatching { worker.submit { flush() }.get(FLUSH_NOW_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { ModuleLogger.error("等待记录落盘超时：${describe(it)}") }
    }

    private fun schedule() {
        if (scheduled != null || pending.isEmpty()) return
        scheduled = worker.schedule({ flush() }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        scheduled?.cancel(false)
        scheduled = null
        if (pending.isEmpty()) return
        // 目录还没解析出来（Context 未取到/不可写）：留在队列里等下一次 submit，不空转
        val s = storeOverride ?: store ?: return
        val now = System.currentTimeMillis()
        if (now < retryAfter) {
            schedule()
            return
        }
        val batch = pending.toList()
        val ok = runCatching { s.addAll(batch) }.getOrDefault(false)
        if (!ok) {
            // 失败不出队：记录还在（受 maxPending 约束），静默期过后再试
            retryAfter = now + PERSIST_RETRY_MS
            if (!failureLogged) {
                failureLogged = true
                ModuleLogger.error("记录落盘失败（写 ${s.javaClass.simpleName} 未成功）：记录留在缓冲里，${PERSIST_RETRY_MS / 1000}s 后重试")
            }
            schedule()
            return
        }
        pending.clear()
        persisted.addAndGet(batch.size.toLong())
        failureLogged = false
        retryAfter = 0L
        schedule()
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}"
}
