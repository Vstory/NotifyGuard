package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.data.ModuleDir
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 判定次数的累计账本（模块端唯一一份）。
 *
 * 记录窗口只留最近若干组、清空即归零，而「一共拦了多少」得跨窗口、跨重启活着 —— 所以计数与记录分开存，
 * 落盘在自己那个小文件里（[ModuleDir.tally]），进程起来后从文件读回，**不从记录重算**（记录已被裁过）。
 *
 * 热路径只碰两个计数（拦下 / 放行）、一次按包名的计数与一次「要不要投写盘」的判断；快照整份覆盖写（tmp + 改名），崩溃最多丢最近
 * [FLUSH_DELAY_MS] 窗口内的增量 —— 拿这个换「每条判定都写一次盘」不值。
 */
object Tally {

    private const val FLUSH_EVERY = 20L
    private const val FLUSH_DELAY_MS = 5_000L
    private const val DAY_MS = 86_400_000L

    /** 单测注入点（与 [io.github.vstory.notifyguard.sync.LogSink] 的 flushThreshold 同一套做法）。 */
    internal var fileOverride: File? = null

    /** 单测注入点：真机取挂钟。 */
    internal var clock: () -> Long = System::currentTimeMillis

    private val blocked = AtomicLong()
    private val pass = AtomicLong()
    private val since = AtomicLong()
    private val pending = AtomicLong()

    /** 今日拦截的总量，与它所属那天的零点（[todayStart] 一变就意味着跨天）。 */
    private val todayBlocked = AtomicLong()
    private val todayStart = AtomicLong()

    /**
     * 今日**按包名**的拦截次数（首页排行的唯一数据源）。
     *
     * 首页排行不能由 App 侧从记录聚合：记录是按内容聚合的组（只留首见 [io.github.vstory.notifyguard.judge.LogRecord.ts]
     * 与最近一次），「昨天首见、今天又拦了 3 次」这种组整组算在昨天，排行会漏掉今天那 3 次 ——
     * 首页的「今日拦截」与排行之和就对不上。计数发生在判定那一刻，这里才拆得开。
     */
    private val todayByPkg = ConcurrentHashMap<String, Long>()

    @Volatile private var loaded = false
    @Volatile private var scheduled = false

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-tally").apply { isDaemon = true }
    }

    /** 一条判定落下：要么拦下（`block`），要么放行。 */
    fun add(block: Boolean, pkg: String?) {
        ensureLoaded()
        rollToday()
        if (block) {
            blocked.incrementAndGet()
            todayBlocked.incrementAndGet()
            if (!pkg.isNullOrEmpty()) todayByPkg.merge(pkg, 1L, Long::plus)
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

    fun passTotal(): Long {
        ensureLoaded()
        return pass.get()
    }

    /** 起算时刻（第一条判定）；0 = 还没有过判定。 */
    fun sinceAt(): Long {
        ensureLoaded()
        return since.get()
    }

    /**
     * 今日拦截（模块端按**本地日期**维护）。
     *
     * 读数前先滚一次日期：被动等下一次判定才归零的话，跨天后的第一次进屏会把昨天的数当今天报。
     */
    fun todayBlockedTotal(): Long {
        ensureLoaded()
        rollToday()
        return todayBlocked.get()
    }

    /** 今日按包名的拦截次数（读前先滚日期，与 [todayBlockedTotal] 同口径）。 */
    fun todayBlockedByPkg(): Map<String, Long> {
        ensureLoaded()
        rollToday()
        return HashMap(todayByPkg)
    }

    /**
     * 跨天就把今日计数推到今天。
     *
     * 热路径（每条判定）只做一次比较：当前时刻越过已记的「明天零点」才重算日历 —— 否则每条判定都要
     * 构造一次 Calendar。
     */
    private fun rollToday() {
        val now = clock()
        val start = todayStart.get()
        if (start != 0L && now < start + DAY_MS) return
        val fresh = startOfDay(now)
        if (todayStart.getAndSet(fresh) != fresh) {
            todayBlocked.set(0L)
            todayByPkg.clear()
        }
    }

    /**
     * 本地日期的零点。
     *
     * **模块端的「今日拦截」与首页排行的「今日」必须用这一份**：两处各写一份 Calendar 算法时，
     * 只要有一处把字段少清一个（比如漏了秒），两个「今日」在一天里就会各算各的，界面上表现为
     * 首页的今日数与排行之和对不上，且只在一天中某段时间可见。
     */
    internal fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val f = fileOverride ?: ModuleDir.tally()
            val text = runCatching { if (f.exists()) f.readText() else null }.getOrNull() ?: return
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            blocked.set(o.optLong("blocked", 0L))
            pass.set(o.optLong("pass", 0L))
            since.set(o.optLong("since", 0L))
            // 重启后今天还是同一天 ⇒ 今日计数接着算；跨了天 ⇒ rollToday 会把它清零
            todayBlocked.set(o.optLong("todayBlocked", 0L))
            todayStart.set(o.optLong("todayStart", 0L))
            o.optJSONObject("todayByPkg")?.let { m ->
                m.keys().forEach { k -> todayByPkg[k] = m.optLong(k, 0L) }
            }
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
            put("pass", pass.get())
            put("since", since.get())
            put("todayBlocked", todayBlocked.get())
            put("todayStart", todayStart.get())
            put("todayByPkg", JSONObject(todayByPkg.toMap()))
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
        pass.set(0L)
        since.set(0L)
        todayBlocked.set(0L)
        todayStart.set(0L)
        todayByPkg.clear()
        pending.set(0L)
        clock = System::currentTimeMillis
        loaded = false
    }

    /** 单测用：同步落盘一次，免得等 [FLUSH_DELAY_MS]。 */
    internal fun flushForTest() = write()
}
