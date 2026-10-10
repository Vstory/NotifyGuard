package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.data.ModuleDir
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 判定次数的累计账本（模块端唯一一份）。
 *
 * 记录窗口只留最近若干组、清空即归零，而「一共拦了多少」得跨窗口、跨重启活着 —— 所以计数与记录分开存，
 * 落盘在自己那个小文件里（[ModuleDir.tally]），进程起来后从文件读回，**不从记录重算**（记录已被裁过）。
 *
 * 热路径只碰三个 AtomicLong 加一次「要不要投写盘」的判断；快照整份覆盖写（tmp + 改名），崩溃最多丢最近
 * [FLUSH_DELAY_MS] 窗口内的增量 —— 拿这个换「每条判定都写一次盘」不值。
 */
object Tally {

    private const val FLUSH_EVERY = 20L
    private const val FLUSH_DELAY_MS = 5_000L

    /** 单测注入点（与 [io.github.vstory.notifyguard.sync.LogSink] 的 flushThreshold 同一套做法）。 */
    internal var fileOverride: File? = null

    private val blocked = AtomicLong()
    private val would = AtomicLong()
    private val pass = AtomicLong()
    private val since = AtomicLong()
    private val pending = AtomicLong()

    @Volatile private var loaded = false
    @Volatile private var scheduled = false

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-tally").apply { isDaemon = true }
    }

    /** 一条判定落下。`block` ⇒ `would`（拦下来的必然也是建议拦截的那条），两者不能各记一套。 */
    fun add(block: Boolean, would: Boolean) {
        ensureLoaded()
        if (block) {
            blocked.incrementAndGet()
            this.would.incrementAndGet()
        } else if (would) {
            this.would.incrementAndGet()
        } else {
            pass.incrementAndGet()
        }
        if (since.get() == 0L) since.compareAndSet(0L, System.currentTimeMillis())
        if (pending.incrementAndGet() >= FLUSH_EVERY) flush() else scheduleFlush()
    }

    fun blockedTotal(): Long {
        ensureLoaded()
        return blocked.get()
    }

    fun wouldTotal(): Long {
        ensureLoaded()
        return would.get()
    }

    fun passTotal(): Long {
        ensureLoaded()
        return pass.get()
    }

    /** 起算时刻（第一条判定）；0 = 还没有过判定。 */
    fun sinceAt(): Long {
        ensureLoaded()
        return since.get()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val f = fileOverride ?: ModuleDir.tally()
            val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            blocked.set(o.optLong("blocked", 0L))
            would.set(o.optLong("would", 0L))
            pass.set(o.optLong("pass", 0L))
            since.set(o.optLong("since", 0L))
        }
    }

    private fun scheduleFlush() {
        if (scheduled) return
        scheduled = true
        worker.schedule({ scheduled = false; write() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        pending.set(0L)
        worker.execute { write() }
    }

    private fun write() {
        val f = fileOverride ?: ModuleDir.tally()
        val text = JSONObject().apply {
            put("blocked", blocked.get())
            put("would", would.get())
            put("pass", pass.get())
            put("since", since.get())
        }.toString()
        // 先写临时文件再改名：读方要么看到上一份完整快照、要么看到这一份，不会读到写了一半的 JSON
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }.onFailure { ModuleLogger.error("record.tally_failed", it, "path=${f.absolutePath}") }
    }

    /** 单测用：清内存、换文件、重新加载（单例跨用例不复位会互相污染）。 */
    internal fun resetForTest(file: File?) {
        fileOverride = file
        blocked.set(0L)
        would.set(0L)
        pass.set(0L)
        since.set(0L)
        pending.set(0L)
        loaded = false
    }

    /** 单测用：同步落盘一次，免得等 [FLUSH_DELAY_MS]。 */
    internal fun flushForTest() = write()
}
