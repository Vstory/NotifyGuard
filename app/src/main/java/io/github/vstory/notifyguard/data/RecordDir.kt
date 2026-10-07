package io.github.vstory.notifyguard.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.vstory.notifyguard.BuildConfig
import java.io.File
import java.security.SecureRandom

/**
 * system_server 侧记录目录：`<base>/notifyguard_<random16>/`（`/data/misc` 是 system_server 可写、
 * 且 App 侧不直读的位置——App 读它会被 SELinux + DAC 双重拦，读取只能走 [io.github.vstory.notifyguard.sync.LogChannel] 回传）。
 *
 * 目录名带随机后缀并绑定模块 firstInstallTime：升级沿用、卸载重装换新，路径不可预测（不与别的模块撞名）。
 * id 文件持久化随机串——只存内存的话重启 system_server 就丢。
 *
 * 不区分「能不能生成」：本方案里只有 system_server 一个调用方碰文件（App 侧走广播）。
 */
class RecordDir(
    private val base: File,
    private val prefix: String,
    private val installTime: () -> Long,
) {

    @Volatile private var cached: File? = null

    fun resolve(): File? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val dir = fromIdFile() ?: fromExisting() ?: createNew()
            cached = dir
            return dir
        }
    }

    private fun fromIdFile(): File? {
        val id = readId() ?: return null
        // 卸载重装后 firstInstallTime 变：不复用旧目录（旧数据随之废弃）
        if (id.installTime != installTime()) return null
        return ensureDir(File(base, prefix + id.random))
    }

    /**
     * id 文件丢失/校验失败时的兜底：复用已有的非空目录。
     * 少了这一步，每次解析失败都会新建一个目录，真机上会越堆越多。
     */
    private fun fromExisting(): File? {
        val dirs = runCatching { base.listFiles() }.getOrNull() ?: return null
        val best = dirs
            .filter { it.isDirectory && it.name.startsWith(prefix) }
            .filter { dir -> dir.listFiles()?.isNotEmpty() == true }
            .maxByOrNull { it.lastModified() }
            ?: return null
        val random = best.name.removePrefix(prefix)
        if (RANDOM_PATTERN.matches(random)) writeId(installTime(), random)
        return ensureDir(best)
    }

    private fun createNew(): File? {
        val random = buildString(RANDOM_LENGTH) {
            repeat(RANDOM_LENGTH) { append(RANDOM_CHARS[random.nextInt(RANDOM_CHARS.length)]) }
        }
        val dir = ensureDir(File(base, prefix + random)) ?: return null
        // 写 id 失败不放弃本次生成的路径：目录已经能用了，下一个进程只是会再兜底扫到它
        writeId(installTime(), random)
        return dir
    }

    private fun ensureDir(dir: File): File? = runCatching {
        if (!dir.isDirectory) {
            dir.delete()
            if (!dir.mkdirs()) return null
        }
        relax(dir, executable = true)
        dir
    }.getOrNull()

    private fun readId(): Id? = runCatching {
        val f = File(base, ID_NAME)
        if (!f.isFile) return null
        val parts = f.readText().trim().split(":", limit = 2)
        if (parts.size != 2) return null
        val time = parts[0].toLongOrNull() ?: return null
        if (!RANDOM_PATTERN.matches(parts[1])) return null
        Id(time, parts[1])
    }.getOrNull()

    private fun writeId(time: Long, random: String) = runCatching {
        val f = File(base, ID_NAME)
        f.writeText("$time:$random")
        relax(f, executable = false)
    }

    /** DAC 放开只是兜底（读取实际走广播），顺带保证 system_server 内多线程可写。 */
    private fun relax(f: File, executable: Boolean) {
        runCatching {
            f.setReadable(true, false)
            f.setWritable(true, false)
            if (executable) f.setExecutable(true, false)
        }
    }

    private data class Id(val installTime: Long, val random: String)

    companion object {

        const val BASE = "/data/misc"
        const val PREFIX = "notifyguard_"

        /** id 文件放在 base 根下且**不带随机串**：两个进程都要能按固定路径读到权威随机串。 */
        const val ID_NAME = PREFIX + "dir_id"

        private const val RANDOM_LENGTH = 16
        private const val RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val RANDOM_PATTERN = Regex("[A-Za-z0-9]{8,32}")

        private val random = SecureRandom()

        fun forSystemServer(ctx: Context): RecordDir =
            RecordDir(File(BASE), PREFIX) { moduleInstallTime(ctx) }

        /**
         * 必须按**模块自身包名**取：system_server 的 `ctx.getPackageName()` 返回 `"android"`，
         * 用它取到系统包的安装时间（甚至 -1）⇒ 校验永远不通过 ⇒ 每次重启 system_server 新建一个目录。
         */
        private fun moduleInstallTime(ctx: Context): Long = runCatching {
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
}
