package io.github.vstory.notifyguard.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.ModuleLogger

/**
 * 模块端标注通道（system_server 内）：应答 App 的四类写指令与全量拉取。
 *
 * 三个约定，缺一条就会出问题：
 * - **来源校验同步做**：`getCallingUid()` 只在 `onReceive` 当前线程有效；
 * - **落盘在 worker 上**：写盘是 IO，不能占 system_server 主线程；
 * - **落盘成功才回发全量**：回执的语义是「已持久化」，失败时干脆不回 —— 让 App 走超时。
 */
object LabelChannel {

    @Volatile private var registered = false

    fun register(c: Context) {
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!ChannelAccess.isFromApp(c)) {
                    ModuleLogger.error("标注通道拒绝了非本模块的调用（uid=${Binder.getCallingUid()}，action=$action）")
                    return
                }
                // 指令名取自 action 末段：App 侧也打同一段名字，两端日志能直接对上
                if (BuildConfig.DEBUG) {
                    ModuleLogger.debugRaw("[DBG] 标注通道：收到 ${action.substringAfterLast('.')}")
                }
                when (action) {
                    LabelContract.ACTION_GET_LABELS -> LabelSink.onWorker { replyAll(c) }

                    LabelContract.ACTION_SET_LABEL -> {
                        val label = LabelCodec.decodeOne(intent.getStringExtra(LabelContract.EXTRA_LABEL))
                        if (label == null) {
                            ModuleLogger.error("标注载荷解析失败（action=$action）⇒ 丢弃该次写入")
                            return
                        }
                        LabelSink.onWorker { if (LabelSink.mutate { it.upsert(label) }) replyAll(c) }
                    }

                    LabelContract.ACTION_DELETE_LABEL -> {
                        val key = intent.getStringExtra(LabelContract.EXTRA_KEY) ?: return
                        LabelSink.onWorker { if (LabelSink.mutate { it.delete(key) }) replyAll(c) }
                    }

                    LabelContract.ACTION_CLEAR_LABELS ->
                        LabelSink.onWorker { if (LabelSink.mutate { it.clear() }) replyAll(c) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LabelContract.ACTION_GET_LABELS)
            addAction(LabelContract.ACTION_SET_LABEL)
            addAction(LabelContract.ACTION_DELETE_LABEL)
            addAction(LabelContract.ACTION_CLEAR_LABELS)
        }
        ChannelAccess.registerExported(c, receiver, filter).onFailure {
            registered = false
            ModuleLogger.error("注册标注通道失败（${it.javaClass.simpleName}: ${it.message}）")
        }
    }

    /** 只在 worker 线程上调用：快照不可信时**不发**（发空列表会让 App 以为标注丢了）。 */
    private fun replyAll(c: Context) {
        LabelSink.snapshotJson()?.let {
            ChannelAccess.replyToApp(c, LabelContract.ACTION_LABELS_RESULT, LabelContract.EXTRA_LABELS, it)
        }
    }
}
