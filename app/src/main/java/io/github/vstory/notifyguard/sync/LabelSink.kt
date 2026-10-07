package io.github.vstory.notifyguard.sync

import android.content.Context
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.ModuleDir
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 模块端标注出口：worker 单线程串行，一次任务里跑完「应用改动 → 落盘 → 取快照」。
 *
 * 与 [LogSink] 的差别是本类**不做批量缓冲**：标注是低频手工动作，用户点一下就该落盘并回执，
 * 攒批只会让「到底存没存上」更难回答。
 *
 * 只暴露 [onWorker]（投递）与 [mutate] / [snapshotJson]（**约定在 worker 线程上调用**），
 * 刻意不提供「提交并等待结果」的 API：worker 是单线程，从 worker 内部再等自己就是自锁，
 * 而这种 API 存在就等于给后来人埋一个死锁入口。
 */
object LabelSink {

    /** 单测注入点（与 [LogSink.storeOverride] 同一套做法）。 */
    internal var storeOverride: LabelStore? = null

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotifyGuard-label").apply { isDaemon = true }
    }

    // 只在 worker 线程上访问
    private var store: LabelStore? = null

    @Volatile private var bound = false

    fun bindContext(c: Context) {
        if (bound) return
        bound = true
        val dir = ModuleDir.ensure(c)
        if (dir == null) {
            ModuleLogger.error("标注目录不可用（${ModuleDir.PATH}）⇒ 标注不可读写（判定不受影响）")
            return
        }
        worker.execute {
            store = LabelStore(ModuleDir.labels())
            ModuleLogger.info("标注落盘就绪（${ModuleDir.labels().absolutePath}）")
        }
        LabelChannel.register(c)
    }

    /**
     * 只在 worker 线程上调用。返回是否**已持久化** —— 调用方靠它决定回不回执
     * （回执语义是「已经落盘」，不是「收到了」）。
     */
    internal fun mutate(op: (LabelStore) -> Boolean): Boolean {
        val s = storeOverride ?: store
        if (s == null) {
            ModuleLogger.error("标注尚未就绪（目录不可用或 Context 未取到）⇒ 本次改动未落盘")
            return false
        }
        val ok = runCatching { op(s) }.getOrDefault(false)
        if (!ok) {
            ModuleLogger.error(
                if (s.isLoadFailed) "labels.json 解析失败 ⇒ 拒绝写入（继续写会用空快照覆盖用户已有标注）"
                else "标注落盘失败 ⇒ 本次改动未生效"
            )
        }
        return ok
    }

    /** 只在 worker 线程上调用。`null` = 快照不可信（未就绪 / 文件损坏）⇒ **不回发**，让 App 走超时。 */
    internal fun snapshotJson(): String? {
        val s = storeOverride ?: store ?: return null
        if (s.isLoadFailed) return null
        return LabelCodec.encodeList(s.all())
    }

    internal fun onWorker(block: () -> Unit) {
        worker.execute(block)
    }

    internal fun awaitIdle() {
        worker.submit { }.get(5, TimeUnit.SECONDS)
    }

    internal fun resetForTest() {
        storeOverride = null
        worker.execute { store = null }
        awaitIdle()
    }
}
