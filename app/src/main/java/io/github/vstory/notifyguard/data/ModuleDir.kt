package io.github.vstory.notifyguard.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.vstory.notifyguard.BuildConfig
import java.io.File

/**
 * 模块在 system_server 侧的数据目录：固定 `/data/misc/notifyguard/`（记录 + 熔断标志同住）。
 *
 * 不用 AppErrorNotify 那套「随机变量目录」：读取方已改成广播（[io.github.vstory.notifyguard.sync.LogChannel]），
 * App 不直读文件，随机串/id 文件/兜底扫描失去用例支撑。
 * 固定常量路径另有一个硬约束上的必要：熔断标志要在注入最早期、**没有 Context 时**可读（见 core/CrashGuard），
 * 而定位随机目录本身就要 Context（取模块 firstInstallTime）。
 */
object ModuleDir {

    const val PATH = "/data/misc/notifyguard"
    const val FILE_LOGS = "logs.json"
    const val FILE_SAFE_MODE = "safe_mode"
    private const val FILE_INSTALL_TIME = "install_time"

    /** 单测注入点。 */
    internal var dirOverride: File? = null

    val dir: File get() = dirOverride ?: File(PATH)

    fun logs(): File = File(dir, FILE_LOGS)

    fun safeMode(): File = File(dir, FILE_SAFE_MODE)

    /** 备好目录；不可用返回 null（记录降级，判定不受影响）。 */
    fun ensure(ctx: Context): File? = prepare(installTime(ctx))

    internal fun prepare(installTime: Long): File? = runCatching {
        val d = dir
        if (!d.isDirectory) {
            d.delete()
            if (!d.mkdirs()) return null
        }
        checkInstallTime(d, installTime)
        d
    }.getOrNull()

    /**
     * 记录随模块重装作废：M1d 靠「重装即换随机目录」顺带实现，固定路径必须显式补偿。
     * 取不到安装时间时既不删也不写 —— 否则 PM 查询一失败就误清用户数据、还留下错的标记。
     */
    private fun checkInstallTime(d: File, installTime: Long) {
        if (installTime <= 0) return
        val stamp = File(d, FILE_INSTALL_TIME)
        val recorded = runCatching { stamp.readText().trim().toLongOrNull() }.getOrNull()
        if (recorded != null && recorded != installTime) logs().delete()
        runCatching { stamp.writeText(installTime.toString()) }
    }

    /** 必须按**模块自身包名**取：system_server 的 `ctx.getPackageName()` 返回 `"android"`。 */
    private fun installTime(ctx: Context): Long = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.PackageInfoFlags.of(0)).firstInstallTime
        } else {
            legacyInstallTime(pm)
        }
    }.getOrDefault(-1L)

    @Suppress("DEPRECATION")
    private fun legacyInstallTime(pm: PackageManager): Long =
        pm.getPackageInfo(BuildConfig.APPLICATION_ID, 0).firstInstallTime
}
