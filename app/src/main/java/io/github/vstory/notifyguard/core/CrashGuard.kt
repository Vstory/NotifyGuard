package io.github.vstory.notifyguard.core

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 崩溃环路熔断（M0 = 读侧 + 异常计数）。
 *
 * 写侧（写 safe_mode 标志、AMS 侧监听 SystemUI 死亡、FileObserver 盯标志）属 M1：M0 不拦任何通知，
 * 异常无实质后果，而写标志要先动 Context 与 /data/system 目录。
 */
object CrashGuard {

    // system_server SELinux 域可写 /data/system（设计方案.md §7.2）
    private const val SAFE_MODE_PATH = "/data/system/io.github.vstory.notifyguard/safe_mode"

    // 判定异常属"不该发生"：连续到这个量级说明判定链与真机结构不符，先停判定保系统稳定
    private const val ERROR_STORM = 50

    private val errors = AtomicInteger()
    private var stormTripped = false

    /** 熔断动作由 EntryHook 注入（把判定权置回 NONE，hook 留作直通）。 */
    var onStorm: (() -> Unit)? = null

    fun isSafeMode(): Boolean = runCatching { File(SAFE_MODE_PATH).exists() }.getOrDefault(false)

    fun errorCount(): Int = errors.get()

    fun noteError(where: String, t: Throwable) {
        val n = errors.incrementAndGet()
        ModuleLogger.error("hook error[$where] #$n: ${t.javaClass.simpleName}: ${t.message}", t)
        if (n >= ERROR_STORM && !stormTripped) {
            stormTripped = true
            ModuleLogger.error("异常风暴（$n 次）⇒ 就地停用判定（hook 保留直通）")
            runCatching { onStorm?.invoke() }
        }
    }

    fun reset() {
        errors.set(0)
        stormTripped = false
    }
}
