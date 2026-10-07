package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
 * **投递失败不出队**（ColorOS 的 OplusAppStartupManager 会拦「system_server 经 provider 拉起 App」，
 * 失败是常态而非异常）：记录留在队列里按 [retryDelayMs] 起步退避重试，App 一旦活着就补齐。
 * 失败也从不静默——异常类型要进日志，否则「被 ROM 拦启动」会被误读成「App 未安装」。
 *
 * 不做「App 在不在运行」预检：insert 顺带拉起 App 是可接受代价，为省一次进程启动而丢掉
 * 用户回看时要看的记录不划算。
 */
object LogSink {

    /** 单测注入点（与 ConfigReader.retryDelaysMs 同一套做法）。 */
    internal var flushThreshold = 50
    internal var flushDelayMs = 30_000L
    internal var retryDelayMs = 5_000L
    internal var retryMaxDelayMs = 30_000L
    internal var maxPending = 500
    internal var deliverOverride: ((List<LogRecord>) -> Boolean)? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-log").apply { isDaemon = true }
    }

    // 以下各项只在 worker 线程上访问
    private val pending = ArrayDeque<LogRecord>()
    private var scheduled: ScheduledFuture<*>? = null
    private var failureLogged = false
    private var consecutiveFailures = 0
    private var nextAttemptAt = 0L
    private var receiverRegistered = false

    @Volatile private var ctx: Context? = null

    private val delivered = AtomicLong()
    private val failed = AtomicLong()

    fun bindContext(c: Context) {
        if (ctx == null) {
            ctx = c
            ModuleLogger.info("记录回流就绪（Context=${c.packageName}）")
        }
        worker.execute { ensureFlushReceiver(c) }
    }

    fun submit(r: LogRecord) {
        worker.execute {
            pending.addLast(r)
            while (pending.size > maxPending) pending.removeFirst()
            if (pending.size >= flushThreshold && System.currentTimeMillis() >= nextAttemptAt) flush() else schedule()
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
        retryDelayMs = 5_000L
        retryMaxDelayMs = 30_000L
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
            consecutiveFailures = 0
            nextAttemptAt = 0L
        }
        awaitIdle()
    }

    /** App 在前台时广播 [LogContract.ACTION_FLUSH]：此刻 provider 现成，积压的记录能一次补齐。 */
    private fun ensureFlushReceiver(c: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                worker.execute { flush() }
            }
        }
        try {
            val filter = IntentFilter(LogContract.ACTION_FLUSH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                c.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                c.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            receiverRegistered = false
            ModuleLogger.error("注册记录刷新广播失败：${describe(t)}")
        }
    }

    private fun schedule() {
        if (scheduled != null || pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val delay = if (now < nextAttemptAt) minOf(flushDelayMs, nextAttemptAt - now) else flushDelayMs
        scheduled = worker.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        scheduled?.cancel(false)
        scheduled = null
        if (pending.isEmpty()) return
        // Context 尚未取到（漏斗还没被调用过）：留在队列里等下次 submit，不空转重试
        if (ctx == null && deliverOverride == null) return
        if (System.currentTimeMillis() < nextAttemptAt) {
            schedule()
            return
        }

        while (pending.isNotEmpty()) {
            val batch = pending.take(flushThreshold)
            val result = runCatching { deliver(batch) }
            if (result.getOrNull() != true) {
                failed.addAndGet(batch.size.toLong())
                logFailureOnce(result.exceptionOrNull())
                consecutiveFailures++
                // 每次失败退避翻倍（retryDelayMs 起步、retryMaxDelayMs 封顶）
                val backoff = minOf(
                    retryDelayMs * (1L shl (consecutiveFailures - 1).coerceAtMost(20)),
                    retryMaxDelayMs,
                )
                nextAttemptAt = System.currentTimeMillis() + backoff
                schedule()
                return
            }
            repeat(batch.size) { pending.removeFirst() }
            delivered.addAndGet(batch.size.toLong())
            failureLogged = false
            consecutiveFailures = 0
            // 积压超过一批就连续送完，否则 500 条要按 30s 一批送五分钟
            if (pending.size < flushThreshold) break
        }
        nextAttemptAt = 0L
        schedule()
    }

    private fun logFailureOnce(t: Throwable?) {
        if (failureLogged) return
        failureLogged = true
        val why = if (t == null) "insert 返回 null" else describe(t)
        ModuleLogger.error("记录回流失败（$why）⇒ 记录留在队列里，App 起来后会补送；本代只报这一次，后续见统计行")
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}"

    private fun deliver(batch: List<LogRecord>): Boolean {
        deliverOverride?.let { return it(batch) }
        val c = ctx ?: return false
        val values = ContentValues().apply {
            put(LogContract.COL_PAYLOAD, LogCodec.encodeList(batch))
        }
        return c.contentResolver.insert(LogContract.CONTENT_URI, values) != null
    }
}
