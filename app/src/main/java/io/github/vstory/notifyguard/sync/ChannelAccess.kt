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
 * 模块端广播通道的公共两件事：**入口认调用方**、**出口定向回本模块 App**。
 *
 * 抽出来是因为两个通道（[LogChannel] / [LabelChannel]）的安全边界必须同源：
 * 各写一份的话，将来只加固其中一处，另一处仍能被任意应用读走用户的通知内容与标注。
 */
internal object ChannelAccess {

    private const val PER_USER_RANGE = 100000

    /**
     * 比 **appId 段（低位，`uid % 100000`）** 而非整 uid：多用户/克隆下同一个包的 uid 不同但 appId 相同。
     *
     * ⚠️ 必须是取模。M1d 起这里写的是 `uid / 100000`（**userId** 段），单用户设备上该值恒为 0，
     * 等于「任何进程都放行」——记录与标注都能被同机任意应用读走，而日志上什么都看不出来。
     * system_server 自身也要放行（它也会走这条广播路径）。
     */
    fun isSameApp(callingUid: Int, appUid: Int): Boolean =
        callingUid == Process.SYSTEM_UID || callingUid == Process.myUid() ||
            callingUid % PER_USER_RANGE == appUid % PER_USER_RANGE

    /**
     * **必须在 `onReceive` 的当前线程同步调用**：`getCallingUid()` 读的是本线程的 IPC 上下文，
     * 换到 worker 上再取就成了 system_server 自己的 uid（等于放行任何人）。
     */
    fun isFromApp(c: Context): Boolean {
        val appUid = runCatching {
            c.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        }.getOrNull() ?: return false
        return isSameApp(Binder.getCallingUid(), appUid)
    }

    /** 广播是异步的：intent 必须自带包名定向（receiver 属于该包），binder 对象不跨线程保留。 */
    fun replyToApp(c: Context, action: String, extraKey: String, json: String) {
        val intent = Intent(action)
            .setPackage(BuildConfig.APPLICATION_ID)
            .putExtra(extraKey, json)
        runCatching { c.sendBroadcast(intent) }
            .onFailure { ModuleLogger.error("channel.reply_failed", it, "action=$action") }
    }

    /** 动态 receiver 要显式 exported：App 是普通 uid，非 exported 的 receiver 收不到它的广播。 */
    fun registerExported(c: Context, receiver: BroadcastReceiver, filter: IntentFilter) =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                c.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                c.registerReceiver(receiver, filter)
            }
        }
}
