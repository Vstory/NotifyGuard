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
 * **输出格式全模块统一**（改任何一行日志前先读这段，规范同时见仓库 AGENTS.md）：
 *
 * ```
 * <域>.<事件> k=v k=v …                状态 / 就绪 / 汇总 / 接管
 * <域>.<事件> k=v err=<异常类>: <原文>   失败；自由文本只允许出现在 err= 里
 * ```
 *
 * - 域取有限集合：`boot` `hotreload` `assemble` `slot` `guard` `safemode` `systemui` `generation`
 *   `teardown` `context` `judge` `ai` `record` `label` `status` `config` `channel` `model` `delta`
 * - 键名与枚举值一律英文小写下划线（`on_success=takeover`），值里不带空格
 * - 一行一条、不换行；可能很长的值先截断（ClassLoader 一律走 [brief]）
 * - 中文只允许出现在 `err=` 的异常原文里 —— 那是给人读的异常信息，不参与解析
 * - 分级：I = 状态变迁 / 就绪 / 汇总；E = 失败终态 / 异常 / 拒绝；D = 高频逐条细节
 * - DEBUG 判定**不放在本类内**：调用点须自判 `BuildConfig.DEBUG` 后再调 [debug]，
 *   否则字符串常量会留在 dex 里（编译期裁剪失效）
 */
object ModuleLogger {

    const val TAG = "NotifyGuard"

    private val PREFIX = "[" + BuildConfig.VERSION_NAME + "] "

    private var iface: XposedInterface? = null

    fun bind(target: XposedInterface) {
        iface = target
    }

    fun info(event: String, vararg fields: String) = write(Log.INFO, line(event, fields))

    fun error(event: String, vararg fields: String) = write(Log.ERROR, line(event, fields))

    /** 带异常对象的失败行：异常整栈交给框架，不在这里拼栈。 */
    fun error(event: String, t: Throwable, vararg fields: String) {
        val target = iface ?: return
        runCatching { target.log(Log.ERROR, TAG, line(event, fields), t) }
    }

    fun debug(event: String, vararg fields: String) = write(Log.DEBUG, line(event, fields))

    /** 整行日志：调用方已按格式拼好（多行明细逐行调用，一次调用只写一行）。 */
    fun lines(line: String) = write(Log.INFO, PREFIX + line)

    /** 失败字段的统一写法。自由文本只出现在这里。 */
    fun err(t: Throwable): String = "err=${t.javaClass.simpleName}: ${t.message}"

    private fun line(event: String, fields: Array<out String>): String =
        PREFIX + if (fields.isEmpty()) event else event + " " + fields.joinToString(" ")

    private fun write(priority: Int, msg: String) {
        runCatching { iface?.log(priority, TAG, msg) }
    }
}

/** ClassLoader 的短标识：`DexPathList[[zip file …]]` 整串能有几 KB，日志里只留类型与身份。 */
internal fun ClassLoader?.brief(): String = this?.let {
    "${it.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(it))}"
} ?: "null"
