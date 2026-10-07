package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Process
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger

/**
 * 模块端记录通道（system_server 内）：应答 App 的拉取/清空请求。
 *
 * 动态注册、不写 manifest——模块不需要新组件，也不必进 LSPosed 作用域。
 *
 * 记录含通知标题与正文，所以两个方向都要设防：入口校验调用方 uid 属本模块 App（否则任何应用都能读走
 * 用户的通知内容），出口 `setPackage()` 定向回本模块 App。
 */
object LogChannel {

    private const val PER_USER_RANGE = 100000

    @Volatile private var registered = false

    fun register(c: Context) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                // 必须在 onReceive 里同步取：getCallingUid 读的是本线程的 IPC 上下文，换线程就丢了
                if (!isFromApp(c)) {
                    ModuleLogger.error("记录通道拒绝了非本模块的调用（uid=${Binder.getCallingUid()}，action=$action）")
                    return
                }
                when (action) {
                    LogContract.ACTION_GET_LOGS ->
                        // 刷缓冲 + 编码 + 回传都是 IO，投给 worker；onReceive 立刻返回，不占 system_server 主线程
                        LogSink.onWorker { reply(c, LogSink.snapshotJson()) }
                    LogContract.ACTION_CLEAR_LOGS -> LogSink.clearAll()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LogContract.ACTION_GET_LOGS)
            addAction(LogContract.ACTION_CLEAR_LOGS)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // App 是普通 uid，非 exported 的动态 receiver 收不到它的广播
                c.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                c.registerReceiver(receiver, filter)
            }
        }.onFailure {
            registered = false
            ModuleLogger.error("注册记录通道失败（${it.javaClass.simpleName}: ${it.message}）")
        }
    }

    private fun reply(c: Context, json: String) {
        val intent = Intent(LogContract.ACTION_LOGS_RESULT)
            .setPackage(BuildConfig.APPLICATION_ID)
            .putExtra(LogContract.EXTRA_LOGS, json)
        // 广播是异步的：binder 对象不跨线程保留
        runCatching { c.sendBroadcast(intent) }
            .onFailure { ModuleLogger.error("记录回传失败（${it.javaClass.simpleName}: ${it.message}）") }
    }

    private fun isFromApp(c: Context): Boolean {
        val calling = Binder.getCallingUid()
        if (calling == Process.SYSTEM_UID || calling == Process.myUid()) return true
        val appUid = runCatching {
            c.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        }.getOrNull() ?: return false
        // 比 appId 段（低位）而非整 uid：多用户/克隆下同一个包的 uid 不同但 appId 相同
        return calling / PER_USER_RANGE == appUid / PER_USER_RANGE
    }
}
