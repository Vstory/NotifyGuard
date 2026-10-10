package io.github.vstory.notifyguard.core

import io.github.vstory.notifyguard.data.ModuleDir
import java.io.File

/**
 * 代际仲裁：热重载之后新旧两代同处一个进程，靠它回答「谁还是有效的」。
 *
 * 热重载**只换代码、不回收常驻资源**：旧代的线程池（线程是 GC root，钉住旧 ClassLoader 与那份模型）、
 * 动态注册的 receiver、FileObserver 全都还活着，而框架又不重放 `onSystemServerStarting`。
 * 唯一由框架交付的跨代通道是 `HotReloadedParam.oldHookHandles`（只能 unhook 旧钩子），
 * 资源必须由旧代自己放 —— 于是把「当前第几代」落到模块自己的目录：新代发布新号，旧代一读就知道自己过期。
 *
 * 两条边界都朝「不过期」倒（反过来做会把模块停掉）：
 *  - 代际文件读不到 ⇒ 判不过期：宁可两代并存（多余的动作仍会被日志看出来），
 *    也不能让唯一在服务的那一代自己停掉；
 *  - 本代尚未 [publish] ⇒ 同样判不过期（还没登记就说自己过期是最坏的一种误判）。
 */
object Generation {

    private const val FILE = "generation"

    /** 判定热路径每次都会问一次，故加缓存：真正的文件读最多一秒一次。 */
    private const val RECHECK_MS = 1_000L

    @Volatile private var mine = 0L
    @Volatile private var lastCheck = 0L
    @Volatile private var outdated = false
    @Volatile private var published = false

    internal fun file(): File = File(ModuleDir.dir, FILE)

    /** 本代的代际号（毫秒时间戳：单调、且日志里能直接对照时间）。未登记为 0。 */
    fun mine(): Long = mine

    /**
     * 登记本代。写入失败只报错不影响运行 —— 此时旧代不会自行退场（两代并存，日志能看出来），
     * 但这比「因为写不进文件就拒绝服务」安全得多。
     */
    fun publish(): Long {
        val now = System.currentTimeMillis()
        mine = now
        published = true
        outdated = false
        lastCheck = now
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(now.toString())
        }.onFailure {
            ModuleLogger.error(
                "generation.write_failed",
                it,
                "file=${file().absolutePath}",
                "risk=two_generations",
            )
        }
        return now
    }

    /**
     * 本代是否已被新代取代。判定热路径调用：绝大多数情况只读一个 volatile，最多一秒读一次文件。
     */
    fun stale(): Boolean {
        if (!published) return false
        val now = System.currentTimeMillis()
        if (now - lastCheck < RECHECK_MS) return outdated
        lastCheck = now
        val cur = runCatching { file().readText().trim().toLongOrNull() }.getOrNull()
        outdated = cur != null && cur != mine
        return outdated
    }

    /** 单测用：让下一次 [stale] 真去读文件 —— 否则一秒缓存会把刚写进文件的新号挡在外面。 */
    internal fun invalidateCacheForTest() {
        lastCheck = 0L
    }

    internal fun resetForTest() {
        mine = 0L
        lastCheck = 0L
        outdated = false
        published = false
    }
}
