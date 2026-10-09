package io.github.vstory.notifyguard.sync

import android.content.Context
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.data.ModuleDir
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
    private val snapshotSeq = AtomicLong()

    @Volatile private var bound = false

    fun persistedCount(): Long = persisted.get()

    fun droppedCount(): Long = dropped.get()

    fun bindContext(c: Context) {
        if (bound) return
        bound = true
        val dir = ModuleDir.ensure(c)
        if (dir == null) {
            ModuleLogger.error("记录落盘目录不可用（${ModuleDir.PATH}）⇒ 记录不落盘（判定不受影响）")
            return
        }
        worker.execute {
            store = LogStore(ModuleDir.logs())
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

    /**
     * 拉取前把缓冲刷进文件：缓冲意味着最多 [flushDelayMs] 的记录还没落盘，不刷就看不到刚发生的通知。
     *
     * **必须在 [worker] 线程上调用**（生产路径是 LogChannel 的 `onWorker`）：它直接读 `pending`，
     * 那是 worker 私有状态。历史写法是「提交 flush 任务再等它回来」（`submit { flush() }.get(3s)`），
     * 但调用点本身就在 worker 上 —— 那个任务永远排在当前任务之后，必然超时，结果是白等 3 秒、
     * 缓冲依旧没落盘，拉取永远滞后一拍。现在直接调 [flush]（同一线程上串行，本就等价）。
     *
     * 自检行的用途：D 级日志里直接给出「缓冲是否全部落盘 / 回传多少条」，排障时不必再推断。
     */
    internal fun snapshotJson(): String {
        // 拉取是「App 活着且与本进程通信」的信号，顺手核对一次配置镜像：
        // push 静默失效时（见 ConfigReader.verifyFromFile），用户的改动靠这里回到本进程
        ConfigReader.verifyFromFile()
        val before = pending.size
        flush()
        val after = pending.size
        val s = storeOverride ?: store
        if (s == null) {
            if (BuildConfig.DEBUG) {
                ModuleLogger.debugRaw(
                    "[DBG] 拉取#${snapshotSeq.incrementAndGet()} 未就绪（目录不可用/Context 未取到）：" +
                        "缓冲=$before→$after 回传=[]"
                )
            }
            return "[]"
        }
        val recent = s.recent(LogStore.MAX_RECORDS)
        if (BuildConfig.DEBUG) {
            ModuleLogger.debugRaw(
                "[DBG] 拉取#${snapshotSeq.incrementAndGet()} 缓冲=$before→$after 文件=${s.size()} " +
                    "回传=${recent.size} 最新=${recent.firstOrNull()?.lastTs} 最旧=${recent.lastOrNull()?.lastTs} " +
                    "自检=${if (after == 0) "✓ 缓冲已全部落盘" else "✗ $after 条仍在缓冲（写盘未成功）"}"
            )
        }
        return LogCodec.encodeList(recent)
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
        // 有记录在流动就等于本进程在干活：顺带核对配置镜像，让 push 失效最多自愈一个 flush 周期
        ConfigReader.verifyFromFile()
        schedule()
    }
}
