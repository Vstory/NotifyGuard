package io.github.vstory.notifyguard.core

import io.github.vstory.notifyguard.BuildConfig

/**
 * 装配结果的留档（M4e）。
 *
 * 装配明细原先只进框架日志、打完即丢，而「扩展槽到底装上了吗 / 为什么没装」正是状态页要回答的问题 ——
 * 要用户去翻 logcat 才答得出来的状态等于没有状态。
 *
 * 只存结果、不存活计数：计数各自在 [EntryHook] / [CrashGuard] / 记录出口里，这里再抄一份就会漂。
 */
object ModuleStatus {

    /** 明细行上限：非目标 ROM 上装配能产生几十行 SKIP，回传载荷要装得下。 */
    private const val MAX_ASSEMBLY_LINES = 40

    @Volatile private var okCount = 0
    @Volatile private var skipCount = 0
    @Volatile private var failCount = 0
    @Volatile private var assemblyText = ""
    @Volatile private var assemblyAt = 0L

    fun recordInstall(report: EntryHook.InstallReport) {
        okCount = report.okCount
        skipCount = report.skipCount
        failCount = report.failCount
        assemblyText = report.detailLines().take(MAX_ASSEMBLY_LINES).joinToString("\n")
        assemblyAt = System.currentTimeMillis()
    }

    fun okCount(): Int = okCount

    fun skipCount(): Int = skipCount

    fun failCount(): Int = failCount

    fun assemblyText(): String = assemblyText

    fun assemblyAt(): Long = assemblyAt

    fun version(): String = BuildConfig.VERSION_NAME
}
