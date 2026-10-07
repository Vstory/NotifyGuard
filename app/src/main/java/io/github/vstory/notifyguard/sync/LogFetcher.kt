package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LogRecord

/**
 * App 侧拉取器：发 [LogContract.ACTION_GET_LOGS] → 收模块端回传 → **覆盖式**写入本地缓存。
 *
 * 超时即结束、不自动重试：拉取由「打开记录页 / 点刷新记录」触发，用户手上就有重试入口，
 * 而重试窗口压在开机早期（模块还没注册通道）也没意义。
 *
 * 回调在主线程，可直接更新 UI；[onDone] 收到 null 表示超时（模块未激活，或装完还没重启过系统框架）。
 */
object LogFetcher {

    internal const val TIMEOUT_MS = 5_000L

    fun fetch(ctx: Context, onDone: (List<LogRecord>?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var receiver: BroadcastReceiver? = null

        val finish: (List<LogRecord>?) -> Unit = { list ->
            if (!finished) {
                finished = true
                handler.removeCallbacksAndMessages(null)
                // 收在后台线程时也能解绑：receiver 不绑定注册线程
                receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
                onDone(list)
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (finished) return
                val json = intent?.getStringExtra(LogContract.EXTRA_LOGS) ?: return
                val store = LogStore.get(ctx)
                store.replaceAll(LogCodec.decodeList(json))
                finish(store.recent(LogStore.MAX_RECORDS))
            }
        }

        val filter = IntentFilter(LogContract.ACTION_LOGS_RESULT)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 回传由 system_server 发出，非 exported 也收得到；普通应用发不进来
                ctx.registerReceiver(receiver!!, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver!!, filter)
            }
        }.isSuccess
        if (!registered) {
            finish(null)
            return
        }

        handler.postDelayed({ finish(null) }, TIMEOUT_MS)
        // 不能 setPackage：system_server 里的 receiver 不属于任何包，定向投递永远收不到
        ctx.sendBroadcast(Intent(LogContract.ACTION_GET_LOGS))
    }

    /** 清空只能请求模块端做（权威源在那边），本地缓存一并清掉免得页面还显示旧数据。 */
    fun clear(ctx: Context) {
        LogStore.get(ctx).clear()
        ctx.sendBroadcast(Intent(LogContract.ACTION_CLEAR_LOGS))
    }
}
