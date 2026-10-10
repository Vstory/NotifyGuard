package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.ai.DeltaHolder
import io.github.vstory.notifyguard.ai.ModelHolder
import io.github.vstory.notifyguard.core.CrashGuard
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.core.ModuleStatus
import java.util.concurrent.Executors

/**
 * 模块端状态通道（system_server 内）：应答 App 的状态拉取与「清除熔断」请求。
 *
 * 与记录 / 标注通道同构（动态注册、不给模块加组件、不进 LSPosed 作用域），安全边界同样走 [ChannelAccess]。
 *
 * 它的首要用途是把**熔断态**变成可见可恢复：熔断是 system_server 里发生的事，标志文件 App 读不到，
 * 没有这条通道就只能靠用户翻日志或手动 root 删文件。
 */
object StatusChannel {

    @Volatile private var registered = false

    /** 读标志文件与组载荷是磁盘 + 序列化工作，不放在 system_server 的主线程上。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotifyGuard-status").apply { isDaemon = true }
    }

    fun register(c: Context) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                // 必须在 onReceive 里同步取：getCallingUid 读的是本线程的 IPC 上下文，换线程就丢了
                if (!ChannelAccess.isFromApp(c)) {
                    ModuleLogger.error("状态通道拒绝了非本模块的调用（uid=${Binder.getCallingUid()}，action=$action）")
                    return
                }
                // 指令名取自 action 末段：App 侧也打同一段名字，两端日志能直接对上
                if (BuildConfig.DEBUG) {
                    ModuleLogger.debugRaw("[DBG] 状态通道：收到 ${action.substringAfterLast('.')}")
                }
                when (action) {
                    StatusContract.ACTION_GET_STATUS -> worker.execute { reply(c) }
                    StatusContract.ACTION_CLEAR_SAFE_MODE -> worker.execute {
                        val was = CrashGuard.clearSafeMode()
                        ModuleLogger.info(
                            "App 请求清除 safe_mode 标志：清除前熔断=$was，" +
                                "自动恢复可用=${CrashGuard.autoRecoverAvailable()}"
                        )
                        // 回执给的是**清除后现读的**状态，不做「已清除」的口头承诺
                        reply(c)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(StatusContract.ACTION_GET_STATUS)
            addAction(StatusContract.ACTION_CLEAR_SAFE_MODE)
        }
        ChannelAccess.registerExported(c, receiver, filter).onFailure {
            registered = false
            ModuleLogger.error("注册状态通道失败（${it.javaClass.simpleName}: ${it.message}）")
        }
    }

    private fun reply(c: Context) = ChannelAccess.replyToApp(
        c,
        StatusContract.ACTION_STATUS_RESULT,
        StatusContract.EXTRA_STATUS,
        StatusCodec.encode(report()),
    )

    /** 现场聚合一份快照。每项都来自权威处，不在这里推导（推导出来的状态没法与日志对上）。 */
    internal fun report(): StatusReport {
        val hits = EntryHook.hits()
        val slot = EntryHook.currentSlot()
        val info = CrashGuard.safeModeInfo()
        return StatusReport(
            version = ModuleStatus.version(),
            moduleSha = BuildConfig.GIT_SHA,
            moduleSha = BuildConfig.GIT_SHA,
            assembly = ModuleStatus.assemblyText(),
            assemblyAt = ModuleStatus.assemblyAt(),
            okCount = ModuleStatus.okCount(),
            skipCount = ModuleStatus.skipCount(),
            failCount = ModuleStatus.failCount(),
            judging = !EntryHook.isJudgingStopped() && slot != EntryHook.Slot.NONE,
            stopReason = EntryHook.stopReason().orEmpty(),
            slot = slot.name,
            safeMode = CrashGuard.isSafeMode(),
            safeModeAt = info?.at ?: 0L,
            safeModeReason = info?.reason.orEmpty(),
            autoRecover = CrashGuard.autoRecoverAvailable(),
            errorCount = CrashGuard.errorCount(),
            extHits = hits.ext,
            funnelJudgeHits = hits.funnelJudge,
            funnelPassHits = hits.funnelPass,
            romBlocked = hits.romBlocked,
            modelReady = ModelHolder.base != null,
            deltaVersion = DeltaHolder.loadedVersion(),
            deltaWeights = DeltaHolder.loadedWeights(),
            recordsPersisted = LogSink.persistedCount(),
            recordsDropped = LogSink.droppedCount(),
        )
    }
}
