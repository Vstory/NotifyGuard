package io.github.vstory.notifyguard.core

import android.os.FileObserver
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.data.ModuleDir
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 熔断（设计方案.md §8.4）。两条互相独立的路径：
 *
 *  - **异常风暴**：判定链自身异常累计到 [ERROR_STORM] ⇒ 就地停判定。只在内存里生效，不碰磁盘。
 *  - **崩溃环路**：SystemUI 在 [DEATH_WINDOW_MS] 窗口内死亡超过 [MAX_RESTARTS] 次 ⇒ 写 safe_mode 标志 + 停判定。
 *
 * 用「SystemUI 死亡」当信号而不是别的：我们注入在 system_server 内，它自己崩了就没有自我观测的余地，
 * 只能取代理信号；SystemUI 是新通知链路上第一个可见消费者，它连续崩说明系统状态已经不稳。
 *
 * 标志文件是**粘滞**的，且只在**装配期**生效：装配前先看它，存在就只装监听不装拦截（宁可没有过滤，
 * 也不能重启循环）。[FileObserver] 盯着它 ⇒ 用户删掉即免重启恢复；反向（运行期新建标志）不卸已装的 hook，
 * 因为 `tripped` 只在装配期被读一次（见 M1e实施方案.md §1.4）。
 *
 * 标志落在 [ModuleDir] 的固定路径：本方法在注入最早期被调用，那时**还没有 Context**，路径必须是常量。
 */
object CrashGuard {

    /** 单测注入点。 */
    internal var deathWindowMs = 30_000L
    internal var watchEnabled = true
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 熔断动作由 EntryHook 注入（停判定 / 恢复重装）。 */
    var onStorm: (() -> Unit)? = null
    var onTrip: (() -> Unit)? = null
    var onCleared: (() -> Unit)? = null

    private const val ERROR_STORM = 50
    private const val DEATH_WINDOW_MS = 30_000L
    private const val MAX_RESTARTS = 2
    private const val SYSTEMUI = "com.android.systemui"

    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
    private const val AMS_READY_METHOD = "systemReady"
    private val DEATH_METHODS = setOf("appDiedLocked", "handleAppDiedLocked")

    private val errors = AtomicInteger()
    private var stormTripped = false

    private val deaths = ArrayDeque<Long>()
    private var tripped = false
    private var watcher: FileObserver? = null
    private val amsHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()

    /** 磁盘 IO 不放在 AMS 的调用线程上。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotifyGuard-safety").apply { isDaemon = true }
    }

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

    fun isSafeMode(): Boolean = synchronized(this) { tripped }

    /**
     * 装配熔断的两只眼睛。与「本代是否装拦截」无关：safe_mode 存在时也必须能看见用户删标志，
     * 否则恢复只剩重启一条路。
     */
    fun attach(
        iface: XposedInterface,
        cl: ClassLoader,
        ok: (String) -> Unit,
        skip: (String) -> Unit,
    ) {
        syncFromDisk()
        if (tripped) ModuleLogger.error("safe_mode 标志存在 ⇒ 本代不装拦截（删掉 ${ModuleDir.safeMode().path} 即免重启恢复）")
        val ams = runCatching { cl.loadClass(AMS_CLASS) }.getOrNull()
        if (ams == null) skip("$AMS_CLASS 加载失败 ⇒ 崩溃环路检测与熔断期间的状态通道均不可用")
        installAmsWatch(iface, ams, ok, skip)
        installContextProbe(iface, ams, ok, skip)
        watchFlag(ok, skip)
    }

    /** SystemUI 死亡事件入口（AMS hook 调用）。 */
    fun onSystemUiDied() {
        if (syncFromDisk()) return
        val now = clock()
        synchronized(this) {
            deaths.addLast(now)
            while (deaths.isNotEmpty() && now - deaths.first() > deathWindowMs) deaths.removeFirst()
            val n = deaths.size
            ModuleLogger.info("SystemUI 死亡（${deathWindowMs / 1000}s 窗口内第 $n 次）")
            if (n <= MAX_RESTARTS || tripped) return
            tripped = true
            ModuleLogger.error("!!! 崩溃环路：SystemUI 在 ${deathWindowMs / 1000}s 内死亡 $n 次 ⇒ 写 safe_mode 并停用判定")
            // 停判定是内存操作，必须当场做；写标志是磁盘 IO，挪到 worker
            runCatching { onTrip?.invoke() }
        }
        worker.execute { writeFlag("SystemUI 在 ${deathWindowMs / 1000}s 内死亡 >$MAX_RESTARTS 次") }
    }

    /** 以磁盘为准同步内存状态。返回同步后的熔断状态。 */
    fun syncFromDisk(): Boolean {
        val onDisk = runCatching { ModuleDir.safeMode().exists() }.getOrDefault(false)
        synchronized(this) {
            if (onDisk != tripped) {
                tripped = onDisk
                // 恢复保护即重新起算窗口：不清的话恢复瞬间会被上一次崩溃的残留记录再次触发
                if (!onDisk) deaths.clear()
            }
            return tripped
        }
    }

    /**
     * 熔断的触发线索（写侧见 [writeFlag]）。App 侧「为什么熔断的」没有别的来源，只能从这里读。
     * 读失败只丢线索，不影响熔断本身的判定。无标志时返回 null。
     */
    data class SafeModeInfo(val at: Long, val reason: String)

    fun safeModeInfo(): SafeModeInfo? {
        if (!isSafeMode()) return null
        val text = runCatching { ModuleDir.safeMode().readText() }.getOrDefault("")
        return SafeModeInfo(
            at = noteField(text, "tripped_at")?.toLongOrNull() ?: 0L,
            reason = noteField(text, "reason").orEmpty(),
        )
    }

    /** 标志监听是否装着：没装时清掉标志也不会重装拦截（恢复需重启系统框架），界面据此如实提示。 */
    fun autoRecoverAvailable(): Boolean = watcher != null

    /**
     * 删标志并立刻按磁盘同步内存，返回删除前是否处于熔断。
     *
     * **不在这里重装拦截**：恢复统一由 [watchFlag] 的 FileObserver 走（与用户手动删文件同一条路），
     * 两条路都重装会在标志变化时装两遍。
     */
    fun clearSafeMode(): Boolean {
        val was = isSafeMode()
        runCatching { ModuleDir.safeMode().delete() }
        syncFromDisk()
        return was
    }

    /** 过期代退场：停标志监听 + 关线程池（旧代码不该再对熔断标志的变化做反应）。 */
    fun release() {
        runCatching { watcher?.stopWatching() }
        watcher = null
        worker.shutdown()
    }

    fun reset() {
        errors.set(0)
        stormTripped = false
        synchronized(this) { deaths.clear() }
        amsHandles.forEach { runCatching { it.unhook() } }
        amsHandles.clear()
        runCatching { watcher?.stopWatching() }
        watcher = null
    }

    // ===== AMS 监听 =====

    private fun installAmsWatch(
        iface: XposedInterface,
        ams: Class<*>?,
        ok: (String) -> Unit,
        skip: (String) -> Unit,
    ) {
        if (ams == null) return
        // 不硬编码签名：方法名随 ROM 版本变，这里只要求「第一个参数是 ProcessRecord」
        val targets = ams.declaredMethods.filter { m ->
            m.name in DEATH_METHODS && m.parameterCount >= 1 &&
                m.parameterTypes[0].name.endsWith("ProcessRecord")
        }
        if (targets.isEmpty()) {
            skip("AMS 上未找到 app-death 方法 ⇒ 崩溃环路检测不可用（safe_mode 标志仍生效）")
            return
        }
        var hooked = 0
        targets.forEach { m ->
            runCatching {
                amsHandles.add(
                    iface.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            // 不吞异常：AMS 的死亡处理该抛什么就抛什么，我们只在它之后看一眼参数
                            val result = chain.proceed()
                            runCatching { inspectDeath(chain.args) }
                                .onFailure { noteError("ams-death", it) }
                            return result
                        }
                    }),
                )
                hooked++
            }.onFailure { skip("AMS#${m.name}(${m.parameterCount} 参数) hook 失败: ${it.message}") }
        }
        if (hooked > 0) ok("AMS app-death 监听（$hooked 个重载）⇒ SystemUI 崩溃环路熔断")
    }

    /**
     * 熔断期间的 Context 来源（M4e）。
     *
     * 熔断时**不装拦截**，于是取 Context 的正常入口（NMS 漏斗首次被调用，见 [ServiceContext]）根本不会走，
     * 状态通道与「清除熔断」也就注册不上 —— App 只能显示未响应，恢复只剩手动删文件一条路（而那是 root 操作）。
     * AMS 是此时另一个必然被创建并调用的服务，从它的实例上取 Context 不扰动启动顺序；
     * **绝不能**改用 `ActivityThread.systemMain()`（会 new 出第二个 ActivityThread，见 §8.5）。
     */
    private fun installContextProbe(
        iface: XposedInterface,
        ams: Class<*>?,
        ok: (String) -> Unit,
        skip: (String) -> Unit,
    ) {
        if (ams == null) return
        val targets = ams.declaredMethods.filter { it.name == AMS_READY_METHOD }
        if (targets.isEmpty()) {
            skip("AMS 上未找到 $AMS_READY_METHOD ⇒ 熔断期间 App 联系不上模块（恢复需重启系统框架）")
            return
        }
        var hooked = 0
        targets.forEach { m ->
            runCatching {
                amsHandles.add(
                    iface.hook(m).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            runCatching { ServiceContext.bindFrom(chain.thisObject) }
                            return result
                        }
                    }),
                )
                hooked++
            }.onFailure { skip("AMS#${m.name}(${m.parameterCount} 参数) hook 失败: ${it.message}") }
        }
        if (hooked > 0) ok("AMS $AMS_READY_METHOD 钩子（熔断期间也能注册状态通道）")
    }

    private fun inspectDeath(args: List<Any?>) {
        val rec = args.firstOrNull { it != null && it.javaClass.name.endsWith("ProcessRecord") } ?: return
        if (processName(rec) != SYSTEMUI) return
        onSystemUiDied()
    }

    private fun processName(rec: Any): String? {
        (readMember(rec, "processName") as? String)?.let { return it }
        readMember(rec, "info")?.let { info -> (readMember(info, "packageName") as? String)?.let { return it } }
        return runCatching { rec.javaClass.getMethod("getProcessName").invoke(rec) as? String }.getOrNull()
    }

    private fun readMember(o: Any, name: String): Any? =
        runCatching { o.javaClass.getField(name).get(o) }
            .recoverCatching { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) }
            .getOrNull()

    // ===== 标志文件 =====

    private fun watchFlag(ok: (String) -> Unit, skip: (String) -> Unit) {
        if (!watchEnabled || watcher != null) return
        val d = ModuleDir.dir
        runCatching {
            if (!d.exists()) d.mkdirs()
            watcher = object : FileObserver(d, CREATE or DELETE or MOVED_TO or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != ModuleDir.FILE_SAFE_MODE) return
                    // 换代后旧代的监听只会与新代重复动作，醒来先确认自己还是当前代
                    if (ModuleTeardown.expired()) return
                    worker.execute {
                        val now = syncFromDisk()
                        ModuleLogger.info("safe_mode 标志变化 ⇒ 熔断=${now}")
                        if (!now) runCatching { onCleared?.invoke() }
                    }
                }
            }.also { it.startWatching() }
        }.onSuccess {
            ok("safe_mode 标志监听（删标志即免重启恢复）")
        }.onFailure {
            skip("safe_mode 标志监听不可用（恢复需重启 system_server）: ${it.message}")
        }
    }

    private fun writeFlag(reason: String) {
        runCatching {
            val d = ModuleDir.dir
            if (!d.exists()) d.mkdirs()
            ModuleDir.safeMode().writeText("tripped_at=${clock()}\nreason=$reason\n")
        }.onFailure {
            ModuleLogger.error("写 ${ModuleDir.FILE_SAFE_MODE} 失败 ⇒ 熔断只在本代内存生效（重启后会重新装拦截）", it)
        }
    }

    private fun noteField(text: String, key: String): String? = text.lineSequence()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
}
