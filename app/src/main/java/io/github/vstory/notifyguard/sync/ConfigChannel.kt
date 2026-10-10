package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger

/**
 * 模块端配置通道（system_server 内）：收 App 的「配置变了」广播，就地重读镜像文件。
 *
 * 这是配置热重载的主力 —— prefs 的 push 在模块更新后必断（原因见 [ConfigContract]），
 * 而广播不依赖 daemon，App 一保存就能把新配置推进来。
 *
 * 载荷不带内容：内容只从镜像文件读，所以伪造广播最多让模块端白读一次文件（节流另算），
 * 拿不到任何东西、也改不了任何东西。入口仍按 [ChannelAccess.isFromApp] 认调用方，与其它通道同源。
 */
object ConfigChannel {

    @Volatile private var registered = false

    fun register(c: Context) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ConfigContract.ACTION_CONFIG_CHANGED) return
                // 与记录/标注通道同一条纪律：必须在 onReceive 的当前线程同步取调用 uid
                if (!ChannelAccess.isFromApp(c)) {
                    ModuleLogger.error("配置通道拒绝了非本模块的调用（uid=${Binder.getCallingUid()}）")
                    return
                }
                val savedAt = intent.getLongExtra(ConfigContract.EXTRA_SAVED_AT, 0L)
                if (BuildConfig.DEBUG) {
                    ModuleLogger.debugRaw("[DBG] 配置通道：收到变更广播（savedAt=$savedAt）⇒ 重读镜像")
                }
                // 读文件是 IO，扔给记录 worker（同一线程上串行，不占 system_server 主线程）
                LogSink.onWorker { ConfigReader.verifyFromFile(force = true, trigger = "broadcast") }
            }
        }
        ChannelAccess.registerExported(c, receiver, IntentFilter(ConfigContract.ACTION_CONFIG_CHANGED))
            .onFailure {
                registered = false
                ModuleLogger.error("注册配置通道失败（${it.javaClass.simpleName}: ${it.message}）")
            }
    }
}
