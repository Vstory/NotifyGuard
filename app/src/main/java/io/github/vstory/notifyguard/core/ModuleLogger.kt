package io.github.vstory.notifyguard.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 框架日志封装：必须走 [XposedInterface.log]，LSPosed 日志页不读 logcat。
 *
 * DEBUG 判定**不放在本类内**：调用点须自带 `if (BuildConfig.DEBUG)`，否则字符串常量会留在 dex
 * 里（编译期裁剪失效），这正是 [debugRaw] 只做写、不判级别的原因。
 */
object ModuleLogger {

    const val TAG = "NotifyGuard"

    private var iface: XposedInterface? = null

    fun bind(target: XposedInterface) {
        iface = target
    }

    fun info(msg: String) = write(Log.INFO, msg)

    fun error(msg: String, t: Throwable? = null) {
        val target = iface ?: return
        runCatching {
            if (t == null) target.log(Log.ERROR, TAG, msg) else target.log(Log.ERROR, TAG, msg, t)
        }
    }

    /** 仅供调用点已自行判定 `BuildConfig.DEBUG` 处使用。 */
    fun debugRaw(msg: String) = write(Log.DEBUG, msg)

    private fun write(priority: Int, msg: String) {
        runCatching { iface?.log(priority, TAG, msg) }
    }
}
