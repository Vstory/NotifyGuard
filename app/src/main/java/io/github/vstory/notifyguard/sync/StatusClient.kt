package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.AppLogger

/**
 * App 侧状态拉取器：发 [StatusContract.ACTION_GET_STATUS]（或清除熔断）→ 收模块端回传。
 *
 * 与 [LogFetcher] 同一套做法：临时 receiver + 超时即结束 + 不自动重试 —— 拉取由「进设置屏 / 点刷新」触发，
 * 用户手上就有重试入口，而开机早期（模块还没注册通道）重试窗口没意义。
 *
 * 回调在主线程；收到 null 表示超时：模块没注入 / 装完没重启系统框架 / 通道不可用，界面按「未响应」渲染。
 */
object StatusClient {

    internal const val TIMEOUT_MS = 5_000L

    fun fetch(ctx: Context, onDone: (StatusReport?) -> Unit) =
        request(ctx, StatusContract.ACTION_GET_STATUS, onDone)

    /** 清除熔断标志；回执是**清除后现读的**状态（不乐观地假定已经恢复）。 */
    fun clearSafeMode(ctx: Context, onDone: (StatusReport?) -> Unit) =
        request(ctx, StatusContract.ACTION_CLEAR_SAFE_MODE, onDone)

    private fun request(ctx: Context, action: String, onDone: (StatusReport?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var receiver: BroadcastReceiver? = null
        var sentAt = 0L
        val what = action.substringAfterLast('.')

        val finish: (StatusReport?) -> Unit = { report ->
            if (!finished) {
                finished = true
                handler.removeCallbacksAndMessages(null)
                receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
                onDone(report)
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (finished) return
                val json = intent?.getStringExtra(StatusContract.EXTRA_STATUS) ?: return
                if (BuildConfig.DEBUG) {
                    AppLogger.debugRaw("[DBG] 状态：$what 收到回执 往返=${System.currentTimeMillis() - sentAt}ms")
                }
                finish(StatusCodec.decode(json))
            }
        }

        val filter = IntentFilter(StatusContract.ACTION_STATUS_RESULT)
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

        handler.postDelayed({
            if (BuildConfig.DEBUG) {
                AppLogger.debugRaw("[DBG] 状态：$what 等 ${TIMEOUT_MS}ms 无回执 ⇒ 超时（界面按「未响应」渲染）")
            }
            finish(null)
        }, TIMEOUT_MS)
        // 不能 setPackage：system_server 里的 receiver 不属于任何包，定向投递永远收不到
        sentAt = System.currentTimeMillis()
        if (BuildConfig.DEBUG) AppLogger.debugRaw("[DBG] 状态：发出 $what")
        ctx.sendBroadcast(Intent(action))
    }
}
