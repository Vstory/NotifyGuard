package io.github.vstory.notifyguard.core

import io.github.vstory.notifyguard.ai.DeltaHolder
import io.github.vstory.notifyguard.sync.ConfigChannel
import io.github.vstory.notifyguard.sync.ConfigReader
import io.github.vstory.notifyguard.sync.LabelChannel
import io.github.vstory.notifyguard.sync.LabelSink
import io.github.vstory.notifyguard.sync.LogChannel
import io.github.vstory.notifyguard.sync.LogSink
import io.github.vstory.notifyguard.sync.StatusChannel

/**
 * 过期代的退场：热重载换代后，旧代持有的常驻资源由它**自己**释放。
 *
 * 为什么必须自己动手：热重载不回收任何资源 —— 旧代的 receiver 仍注册在系统里（会继续收到广播并应答）、
 * FileObserver 仍在监听、线程池仍是 GC root（钉住旧 ClassLoader、连带那 262 KB 模型）。
 * 框架只把旧钩子句柄交给新代，资源引用拿不到，所以退场只能由旧代执行：它在每次活动时先问一句
 * [Generation.stale]，一旦过期就把自己拆干净。
 *
 * 调用点覆盖「旧代能被唤醒的每一条路」：判定入口（两个 hooker）、四条通道的 onReceive、
 * 标志监听的 onEvent、记录 worker 的周期落盘。判定入口那两处是安全带：即便 unhook 旧钩子失败，
 * 过期代也只是放行，不会拿着旧配置做决定、也不会重复落盘。
 */
object ModuleTeardown {

    @Volatile private var released = false

    /**
     * 「本代是否已过期」的唯一入口：过期即顺手退场。
     *
     * 返回值语义：true = 我已不是当前代，调用方**立即放弃本次动作**。
     */
    fun expired(): Boolean {
        if (!Generation.stale()) return false
        release()
        return true
    }

    private fun release() {
        if (released) return
        released = true
        safe("记录通道") { LogChannel.release() }
        safe("标注通道") { LabelChannel.release() }
        safe("状态通道") { StatusChannel.release() }
        safe("配置通道") { ConfigChannel.release() }
        safe("记录 worker") { LogSink.release() }
        safe("标注 worker") { LabelSink.release() }
        safe("配置核对 worker") { ConfigReader.release() }
        safe("微调加载 worker") { DeltaHolder.release() }
        safe("标志监听") { CrashGuard.release() }
        ModuleLogger.info(
            "本代（代际 ${Generation.mine()}）已被新代取代：已注销四条通道、停标志监听、关线程池；" +
                "判定转纯放行（此后行为只由新代负责）"
        )
    }

    /** 单测注入点。 */
    internal fun resetForTest() {
        released = false
    }

    private inline fun safe(what: String, block: () -> Unit) {
        runCatching(block).onFailure {
            ModuleLogger.error("释放 $what 失败（可能滞留）: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
