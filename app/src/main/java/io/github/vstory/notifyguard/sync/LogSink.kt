package io.github.vstory.notifyguard.sync

import android.content.ContentValues
import android.content.Context
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.judge.LogRecord
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 模块端记录出口：缓冲 → 批量 insert 到 App 的 [io.github.vstory.notifyguard.provider.LogProvider]。
 *
 * 判定线程只投递任务，跨进程调用全在 [worker] 上——insert 会等 App 进程（必要时还要拉起它），
 * 放在判定热路径上会直接拖慢通知入队。一次 flush 只发一条 binder（payload 是整批的 JSON 数组），
 * App 侧因此也只写一次盘。
 *
 * 队列满时丢最旧：记录是流水，新的比旧的有用。
 *
 * 不做「App 在不在运行」预检：insert 顺带拉起 App 是可接受的代价，为省一次进程启动而丢掉
 * 用户回看时要看的记录不划算。
 */
object LogSink {

    /** 单测注入点（与 ConfigReader.retryDelaysMs 同一套做法）。 */
    internal var flushThreshold = 50
    internal var flushDelayMs = 30_000L
    internal var maxPending = 500
    internal var deliverOverride: ((List<LogRecord>) -> Boolean)? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-log").apply { isDaemon = true }
    }

    // 以下三项只在 worker 线程上访问
    private val pending = ArrayDeque<LogRecord>()
    private var scheduled: ScheduledFuture<*>? = null
    private var failureLogged = false

    @Volatile private var ctx: Context? = null

    private val delivered = AtomicLong()
    private val failed = AtomicLong()

    fun bindContext(c: Context) {
        if (ctx == null) {
            ctx = c
            ModuleLogger.info("记录回流就绪（Context=${c.packageName}）")
        }
    }

    fun submit(r: LogRecord) {
        worker.execute {
            pending.addLast(r)
            while (pending.size > maxPending) pending.removeFirst()
            if (pending.size >= flushThreshold) flush() else schedule()
        }
    }

    fun statsLine(): String = "已回流=${delivered.get()} 回流失败=${failed.get()}"

    /** 单测用：等 worker 排空，避免用例之间互相串。 */
    internal fun awaitIdle() {
        worker.submit { }.get(5, TimeUnit.SECONDS)
    }

    internal fun resetForTest() {
        flushThreshold = 50
        flushDelayMs = 30_000L
        maxPending = 500
        deliverOverride = null
        ctx = null
        delivered.set(0)
        failed.set(0)
        worker.execute {
            pending.clear()
            scheduled?.cancel(false)
            scheduled = null
            failureLogged = false
        }
        awaitIdle()
    }

    private fun schedule() {
        if (scheduled != null) return
        scheduled = worker.schedule({ flush() }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        scheduled?.cancel(false)
        scheduled = null
        if (pending.isEmpty()) return
        // Context 尚未取到（漏斗还没被调用过）：留在队列里等下次 submit，不空转重试
        if (ctx == null && deliverOverride == null) return

        val batch = ArrayList<LogRecord>(minOf(pending.size, flushThreshold))
        while (pending.isNotEmpty() && batch.size < flushThreshold) batch.add(pending.removeFirst())

        val ok = runCatching { deliver(batch) }.getOrElse { false }
        if (ok) {
            delivered.addAndGet(batch.size.toLong())
        } else {
            failed.addAndGet(batch.size.toLong())
            if (!failureLogged) {
                failureLogged = true
                ModuleLogger.error("记录回流失败（App 未安装 / provider 未就绪）⇒ 本代只报这一次，后续见统计行")
            }
        }
    }

    private fun deliver(batch: List<LogRecord>): Boolean {
        deliverOverride?.let { return it(batch) }
        val c = ctx ?: return false
        val values = ContentValues().apply {
            put(LogContract.COL_PAYLOAD, LogCodec.encodeList(batch))
        }
        return c.contentResolver.insert(LogContract.CONTENT_URI, values) != null
    }
}
