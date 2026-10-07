package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * M0 的记录出口 = 框架日志（每条一行 + 每 [SUMMARY_EVERY] 条汇总）。
 *
 * 落盘与回流（ContentProvider.insert）属 M1：system_server 内没有合适的私有目录。
 */
object RecordSink {

    private const val SUMMARY_EVERY = 20L
    private const val TOP_PKG_LIMIT = 5

    private val total = AtomicLong()
    private val byReason = ConcurrentHashMap<String, AtomicLong>()
    private val byPkg = ConcurrentHashMap<String, AtomicLong>()
    private var firstRecordLogged = false

    fun record(s: NotifySnapshot?, d: Judge.Decision) {
        val n = total.incrementAndGet()
        byReason.computeIfAbsent(d.reason) { AtomicLong() }.incrementAndGet()
        s?.pkg?.let { byPkg.computeIfAbsent(it) { AtomicLong() }.incrementAndGet() }

        // 首条打全字段：真机核对「字段是否完整」看这一行就够
        if (!firstRecordLogged) {
            firstRecordLogged = true
            ModuleLogger.info("record#1 ${line(s, d)}")
        }
        if (BuildConfig.DEBUG) {
            ModuleLogger.debugRaw("[DBG] record#$n ${line(s, d)}")
        }
        if (n % SUMMARY_EVERY == 0L) {
            ModuleLogger.info(summary(n))
        }
    }

    private fun line(s: NotifySnapshot?, d: Judge.Decision): String =
        if (s == null) {
            "snapshot=null → ${d.reason}"
        } else {
            "pkg=${s.pkg} tag=${s.tag} id=${s.id} uid=${s.uid} pid=${s.pid} user=${s.userId} " +
                "ch=${s.channelId} flags=0x${Integer.toHexString(s.flags)} len=${s.textLength} " +
                "group=${s.isGroupSummary} → ${d.reason} block=${d.block}"
        }

    private fun summary(n: Long): String {
        val reasons = byReason.entries.joinToString(", ") { "${it.key}=${it.value.get()}" }
        val topPkg = byPkg.entries.sortedByDescending { it.value.get() }
            .take(TOP_PKG_LIMIT)
            .joinToString(", ") { "${it.key}=${it.value.get()}" }
        return "统计 #$n：原因[$reasons] 包名TOP[$topPkg]"
    }
}
