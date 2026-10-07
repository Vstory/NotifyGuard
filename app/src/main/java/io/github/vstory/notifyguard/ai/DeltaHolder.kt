package io.github.vstory.notifyguard.ai

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.core.ModuleLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模块侧（system_server）的微调量热加载。
 *
 * 触发链：App 写 `spam_delta.bin` → App 写配置里的 `deltaVersion` → 本进程的配置监听回调
 * → [onConfigVersion] → 后台线程读文件换模型。**判定热路径永不读文件**，换入是一次 volatile 赋值。
 *
 * 两条不肯让步的规矩：
 * - **加载失败不登记版本号**：登记了就等于宣称「这一版已生效」，而配置不再变化时永远不会重试；
 * - **文件读不到就退避重试**：`App 先写文件、再写版本号` 的顺序已保证文件先落地，读不到只会是
 *   时序毛刺（框架分发 pid 文件与 App 写入不同步），不是用户错误。
 */
object DeltaHolder {

    /** 退避间隔；离线单测把它改小以验证重试路径（与 [io.github.vstory.notifyguard.sync.ConfigReader] 同做法）。 */
    internal var retryDelaysMs = longArrayOf(1_000, 3_000, 10_000)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-delta").apply { isDaemon = true }
    }

    @Volatile private var iface: XposedInterface? = null

    /** 已生效的版本号。只在成功换入后前进——它是「不必重试」的唯一凭据。 */
    @Volatile private var loadedVersion = 0L

    private val retries = AtomicInteger()

    /** 只登记接口；首次加载由紧接着的配置首读带出（`ConfigReader.start` 会 reload 一次）。 */
    fun start(iface: XposedInterface) {
        this.iface = iface
    }

    fun loadedVersion(): Long = loadedVersion

    fun onConfigVersion(version: Long) {
        if (version == loadedVersion) return
        val base = ModelHolder.base ?: return
        scheduler.execute { load(version, base) }
    }

    /** 只在后台线程上跑。 */
    private fun load(version: Long, base: SpamModel) {
        if (version == loadedVersion) return
        if (version == 0L) {
            // 「从未下发」与「已清空标注」都归这里：都是纯 base，不是失败，因此不重试
            ModelHolder.replace(base)
            loadedVersion = 0L
            retries.set(0)
            ModuleLogger.info("未启用微调（版本号 0）⇒ 按纯 base 打分")
            return
        }
        val api = iface ?: return
        val pfd = runCatching { api.openRemoteFile(SpamDelta.REMOTE_FILE) }.getOrNull()
        if (pfd == null) {
            retry(version, base, "微调量文件不可读（v$version）")
            return
        }
        val bytes = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            retry(version, base, "微调量文件读取失败（v$version）")
            return
        }
        when (val parsed = SpamDelta.parse(bytes, base.buckets, base.fingerprintU32)) {
            is SpamDelta.Parse.Ok -> {
                val delta = parsed.delta
                ModelHolder.replace(if (delta.isEmpty) base else TunedScorer(base, delta))
                loadedVersion = version
                retries.set(0)
                ModuleLogger.info("已加载微调量 v$version：${delta.indices.size} 个权重（base fp=${base.fingerprintHex()}）")
            }

            is SpamDelta.Parse.Rejected -> {
                // 预期内的降级（换过 base、文件半写）：按纯 base 打分，且**不登记版本号**才会重试
                ModelHolder.replace(base)
                retry(version, base, "微调量被拒（v$version）：${parsed.reason}")
            }
        }
    }

    private fun retry(version: Long, base: SpamModel, what: String) {
        val n = retries.getAndIncrement()
        if (n >= retryDelaysMs.size) {
            ModuleLogger.error("$what ⇒ 已重试 ${n} 次，停止（此后改标注需重启 system_server 才生效）")
            return
        }
        ModuleLogger.error("$what ⇒ 第 ${n + 1} 次重试在 ${retryDelaysMs[n]} ms 后（本轮按纯 base 打分）")
        scheduler.schedule({ load(version, base) }, retryDelaysMs[n], TimeUnit.MILLISECONDS)
    }

    internal fun resetForTest() {
        iface = null
        loadedVersion = 0L
        retries.set(0)
        ModelHolder.replace(null)
    }
}
