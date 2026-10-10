package io.github.vstory.notifyguard.core

import android.util.Log
import io.github.vstory.notifyguard.BuildConfig

/**
 * App 进程侧的调试日志（走 logcat）。
 *
 * 为什么不复用 [ModuleLogger]：那个走 `XposedInterface.log`，只有注入进程（system_server）能写；
 * App 进程里 `iface` 是 null，写进去**静默丢弃**，日志页一条也看不到。
 * 而「App 发请求 → 模块回执 → App 收到」这条往返链路的头尾都发生在 App 进程，
 * 要观测它只能靠 logcat。
 *
 * 与 [ModuleLogger] 同一约定：DEBUG 判定放**调用点**（`if (BuildConfig.DEBUG)`），
 * 常量串才随分支一起被 R8 裁掉；判断放本类内部的话字符串已经先被拼好了，裁不掉。
 */
object AppLogger {

    const val TAG = "NotifyGuard"

    /** 与模块端同一口径：logcat 里每一行都标出版本串（App 与模块端可能加载的不是同一次构建）。 */
    private val PREFIX = "[" + BuildConfig.VERSION_NAME + "] "

    fun debugRaw(msg: String) = Log.d(TAG, PREFIX + msg)

    /** 非 DEBUG 也要留的 App 侧信息行（启动横幅、写配置结果这类排障入口）。 */
    fun info(msg: String) = Log.i(TAG, PREFIX + msg)
}
