package io.github.vstory.notifyguard.judge

import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 记录出口：账本（[Tally]）+ 落盘/回传（[io.github.vstory.notifyguard.sync.LogSink]）+ **debug 版**的框架日志。
 *
 * 这里的逐条与周期行只进 debug 版（`BuildConfig.DEBUG`）：它们的条数随通知量线性增长，而 release
 * 的框架日志只承担状态变迁与故障 —— 逐条明细在 App 的记录页与诊断屏，两边分工见 AGENTS.md。
 */
object RecordSink {

    private const val SUMMARY_EVERY = 20L
    private const val TOP_PKG_LIMIT = 5

    private val total = AtomicLong()
    private val byReason = ConcurrentHashMap<String, AtomicLong>()
    private val byPkg = ConcurrentHashMap<String, AtomicLong>()

    fun record(s: NotifySnapshot?, d: Judge.Decision) {
        // 累计账本在这里记：判定链每次判定都会走到这个出口，而它自己的存储独立于记录（记录会被裁、会被清）
        Tally.add(block = d.block, pkg = s?.pkg)
        val n = total.incrementAndGet()
        byReason.computeIfAbsent(d.reason) { AtomicLong() }.incrementAndGet()
        s?.pkg?.let { byPkg.computeIfAbsent(it) { AtomicLong() }.incrementAndGet() }

        if (BuildConfig.DEBUG) {
            ModuleLogger.debug("record.kept", "n=$n", *lineFields(s, d))
            // 汇总串要拼 reasons 与 top_pkgs：放在闸内，release 里连这次分配都不发生
            if (n % SUMMARY_EVERY == 0L) ModuleLogger.debug("record.summary", *summaryFields(n))
        }
    }

    /** 逐条明细的字段（debug 版的 record.kept 一行带全，真机核对「字段是否完整」看它）。 */
    private fun lineFields(s: NotifySnapshot?, d: Judge.Decision): Array<String> =
        if (s == null) {
            arrayOf(
                "snapshot=null",
                "reason=${d.reason}",
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
