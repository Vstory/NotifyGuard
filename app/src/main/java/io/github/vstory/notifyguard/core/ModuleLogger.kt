package io.github.vstory.notifyguard.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.BuildConfig

/**
 * 框架日志封装：必须走 [XposedInterface.log]，LSPosed 日志页不读 logcat。
 *
 * **每行都带版本串**（`[1.7.0+ci-debug.b4efc10a]`）：模块代码活在被注入的进程里，App 更新后
 * system_server 里跑的仍是注入那一刻的那份代码，日志里不带版本就只能靠猜。
 * 串就是 versionName 本身（构建期已把提交短号并进去），不在这里二次拼装。
 *
 * DEBUG 判定**不放在本类内**：调用点须自带 `if (BuildConfig.DEBUG)`，否则字符串常量会留在 dex
 * 里（编译期裁剪失效），这正是 [debugRaw] 只做写、不判级别的原因。
 */
object ModuleLogger {

    const val TAG = "NotifyGuard"

    private val PREFIX = "[" + BuildConfig.VERSION_NAME + "] "

    private var iface: XposedInterface? = null

    fun bind(target: XposedInterface) {
        iface = target
    }

    fun info(msg: String) = write(Log.INFO, PREFIX + msg)

    fun error(msg: String, t: Throwable? = null) {
        val target = iface ?: return
        val line = PREFIX + msg
        runCatching {
            if (t == null) target.log(Log.ERROR, TAG, line) else target.log(Log.ERROR, TAG, line, t)
        }
    }

    /** 仅供调用点已自行判定 `BuildConfig.DEBUG` 处使用。 */
    fun debugRaw(msg: String) = write(Log.DEBUG, PREFIX + msg)

    private fun write(priority: Int, msg: String) {
        runCatching { iface?.log(priority, TAG, msg) }
    }
}
