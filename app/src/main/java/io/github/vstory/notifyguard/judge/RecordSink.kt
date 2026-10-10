package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * M0 的记录出口 = 框架日志（每条一行 + 每 [SUMMARY_EVERY] 条汇总）。
 *
 * 落盘/回传在 [io.github.vstory.notifyguard.sync.LogSink]（M1d 起写 system_server 侧
 * `/data/misc/notifyguard/`）：框架日志是给人看的排查线索，两者互不替代。
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
            ModuleLogger.info("record.kept", "n=1", *lineFields(s, d))
        }
        if (BuildConfig.DEBUG) {
            ModuleLogger.debug("record.kept", "n=$n", *lineFields(s, d))
        }
        if (n % SUMMARY_EVERY == 0L) {
            ModuleLogger.info("record.summary", *summaryFields(n))
        }
    }

    /** 逐条明细的字段（首条打全字段：真机核对「字段是否完整」看这一行就够）。 */
    private fun lineFields(s: NotifySnapshot?, d: Judge.Decision): Array<String> =
        if (s == null) {
            arrayOf(
                "snapshot=null",
                "reason=${d.reason}",
                "would_block=${d.wouldBlock}",
                "blocked=${d.block}",
            )
        } else {
            arrayOf(
                "pkg=${s.pkg}",
                "tag=${s.tag}",
                "id=${s.id}",
                "uid=${s.uid}",
                "pid=${s.pid}",
                "user=${s.userId}",
                "ch=${s.channelId}",
                "flags=0x${Integer.toHexString(s.flags)}",
                "len=${s.textLength}",
                "group=${s.isGroupSummary}",
                "reason=${d.reason}",
                "would_block=${d.wouldBlock}",
                "blocked=${d.block}",
            )
        }

    /**
     * 汇总按**原因名**聚合。`reason` 里带分数（`below_threshold:0.63`），按原串分会把同一个原因拆成
     * 十几条并列项，汇总反而看不出哪一类在涨；分数属于逐条明细，不参与分类。
     */
    private fun summaryFields(n: Long): Array<String> {
        val reasons = byReason.entries
            .groupingBy { it.key.substringBefore(':') }
            .fold(0L) { acc, e -> acc + e.value.get() }
            .entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        val topPkg = byPkg.entries.sortedByDescending { it.value.get() }
            .take(TOP_PKG_LIMIT)
            .joinToString(",") { "${it.key}:${it.value.get()}" }
        return arrayOf("n=$n", "reasons=[$reasons]", "top_pkgs=[$topPkg]")
    }
}
