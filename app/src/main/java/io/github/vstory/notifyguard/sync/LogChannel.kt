package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.core.ModuleTeardown

/**
 * 模块端记录通道（system_server 内）：应答 App 的拉取/清空请求。
 *
 * 动态注册、不写 manifest——模块不需要新组件，也不必进 LSPosed 作用域。
 *
 * 记录含通知标题与正文，所以两个方向都要设防：入口校验调用方 uid 属本模块 App（否则任何应用都能读走
 * 用户的通知内容），出口 `setPackage()` 定向回本模块 App。两者的实现与标注通道共用 [ChannelAccess]。
 */
object LogChannel {

    @Volatile private var registered = false

    // 换代时要能注销：receiver 注册在系统里，留着它会继续应答，让「当前状态」变得不确定
    @Volatile private var ctx: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null

    /** 过期代退场时注销：不注销就会与新代同时应答同一条广播。 */
    fun release() {
        val c = ctx ?: return
        val r = receiver ?: return
        ctx = null
        receiver = null
        registered = false
        runCatching { c.unregisterReceiver(r) }
    }

    fun register(c: Context) {
        if (registered) return
        registered = true
        ctx = c
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // 换代后旧代仍注册着：先确认自己还是当前代，过期就拆掉自己并放弃应答
                if (ModuleTeardown.expired()) return
                val action = intent?.action ?: return
                // 必须在 onReceive 里同步取：getCallingUid 读的是本线程的 IPC 上下文，换线程就丢了
                if (!ChannelAccess.isFromApp(c)) {
                    ModuleLogger.error(
                        "channel.denied",
                        "ch=record",
                        "uid=${Binder.getCallingUid()}",
                        "action=$action",
                    )
                    return
                }
                when (action) {
                    LogContract.ACTION_GET_LOGS -> {
                        if (BuildConfig.DEBUG) {
                            ModuleLogger.debug(
                                "channel.request",
                                "ch=record",
                                "action=GET_LOGS",
                                "uid=${Binder.getCallingUid()}",
                            )
                        }
                        // 刷缓冲 + 编码 + 回传都是 IO，投给 worker；onReceive 立刻返回，不占 system_server 主线程
                        LogSink.onWorker { reply(c, LogSink.snapshotJson()) }
                    }

                    LogContract.ACTION_CLEAR_LOGS -> {
                        if (BuildConfig.DEBUG) {
                            ModuleLogger.debug("channel.request", "ch=record", "action=CLEAR_LOGS")
                        }
                        LogSink.clearAll()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LogContract.ACTION_GET_LOGS)
            addAction(LogContract.ACTION_CLEAR_LOGS)
        }
        receiver = r
        ChannelAccess.registerExported(c, r, filter).onFailure {
            registered = false
            ModuleLogger.error("channel.register_failed", it, "ch=record")
        }
    }

    private fun reply(c: Context, json: String) =
        ChannelAccess.replyToApp(c, LogContract.ACTION_LOGS_RESULT, LogContract.EXTRA_LOGS, json)
}
