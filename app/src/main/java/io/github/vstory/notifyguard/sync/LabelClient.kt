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
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.judge.LabelRecord

/**
 * App 侧标注客户端：四个动作共用一套骨架 —— 注册临时 receiver → 发**单条指令** → 收到全量即刷缓存。
 *
 * 三条不肯让步的取舍：
 * - **只发增量指令，不发全量**：权威源在模块端，两端同时改时全量覆盖会互相丢数据（M3 §3.2 R1）；
 * - **超时即结束、不重试**：标注由用户点击触发，手上就有重试入口；同 [LogFetcher] 的口径；
 * - **收到回执才认成功**：回执由模块端在**落盘成功后**发出，所以「收到」就等于「已持久化」。
 *
 * 回调在主线程，可直接更新 UI；`null` 表示超时（模块未激活 / 装完还没重启过系统框架）。
 */
object LabelClient {

    internal const val TIMEOUT_MS = 5_000L

    fun fetch(ctx: Context, onDone: (List<LabelRecord>?) -> Unit) =
        send(ctx, LabelContract.ACTION_GET_LABELS, null, onDone)

    fun set(ctx: Context, label: LabelRecord, onDone: (List<LabelRecord>?) -> Unit) =
        send(
            ctx,
            LabelContract.ACTION_SET_LABEL,
            LabelContract.EXTRA_LABEL to LabelCodec.toJson(label).toString(),
            onDone,
        )

    /** 撤销标注 = 删这条 key；改主意则直接 [set] 覆盖，不必先删。 */
    fun delete(ctx: Context, key: String, onDone: (List<LabelRecord>?) -> Unit) =
        send(ctx, LabelContract.ACTION_DELETE_LABEL, LabelContract.EXTRA_KEY to key, onDone)

    fun clear(ctx: Context, onDone: (List<LabelRecord>?) -> Unit) =
        send(ctx, LabelContract.ACTION_CLEAR_LABELS, null, onDone)

    private fun send(
        ctx: Context,
        action: String,
        extra: Pair<String, String>?,
        onDone: (List<LabelRecord>?) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var receiver: BroadcastReceiver? = null
        var sentAt = 0L
        val what = action.substringAfterLast('.')

        val finish: (List<LabelRecord>?) -> Unit = { list ->
            if (!finished) {
                finished = true
                handler.removeCallbacksAndMessages(null)
                receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
                onDone(list)
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (finished) return
                val json = intent?.getStringExtra(LabelContract.EXTRA_LABELS) ?: return
                val store = LabelStore.get(ctx)
                // 覆盖式：模块端那份是唯一真相，本地只是它的缓存
                store.replaceAll(LabelCodec.decodeList(json))
                if (BuildConfig.DEBUG) {
                    AppLogger.debug(
                        "app.reply",
                        "ch=label",
                        "action=$what",
                        "items=${store.all().size}",
                        "rtt_ms=${System.currentTimeMillis() - sentAt}",
                    )
                }
                finish(store.all())
            }
        }

        val filter = IntentFilter(LabelContract.ACTION_LABELS_RESULT)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 回执由 system_server 发出，非 exported 也收得到；普通应用发不进来
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
                AppLogger.debug("app.timeout", "ch=label", "action=$what", "waited_ms=${TIMEOUT_MS}")
            }
            finish(null)
        }, TIMEOUT_MS)
        // 不能 setPackage：system_server 里的 receiver 不属于任何包，定向投递永远收不到
        sentAt = System.currentTimeMillis()
        if (BuildConfig.DEBUG) AppLogger.debug("app.send", "ch=label", "action=$what")
        ctx.sendBroadcast(
            Intent(action).apply { extra?.let { (k, v) -> putExtra(k, v) } }
        )
    }
}
