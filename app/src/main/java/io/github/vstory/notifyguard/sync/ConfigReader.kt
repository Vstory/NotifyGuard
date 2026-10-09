package io.github.vstory.notifyguard.sync

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.ai.DeltaHolder
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.judge.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块端（system_server）的配置读取与热更新。
 *
 * 必须在**装配时就**取一次本组并注册监听：框架只向「曾调用过 getRemotePreferences 的进程」推送变更，
 * 等要用配置时再取，会漏掉此前的全部改动。
 *
 * 取 prefs 或注册监听失败都要退避重试——注册不上的话，此后 App 侧怎么改都推不进这个进程，
 * 功能会永久停在默认配置上，而两端都不报错。
 *
 * 注意 `edit()` 在模块端抛 UnsupportedOperationException（框架下发的是只读实现），写方只有 App 端
 * 的 [ConfigWriter]。
 */
object ConfigReader {

    const val GROUP = "io.github.vstory.notifyguard_config"
    const val KEY = "config"

    /** 配置镜像文件（App 写、本进程读）：push 静默失效时的兜底来源，理由见 [verifyFromFile]。 */
    const val REMOTE_FILE = "config.json"

    /** 退避间隔；离线单测把它改小以验证重试路径。 */
    internal var retryDelaysMs = longArrayOf(1_000, 3_000, 10_000)

    /** 兜底核对的最小间隔：拉取与落盘都会调它，节流后频率远低于通知流量。 */
    internal var checkIntervalMs = 5_000L

    /** 单测注入点：JVM 单测拿不到 ParcelFileDescriptor，替换成假读取。 */
    internal var readRemoteFile: (XposedInterface, String) -> String? = { iface, name ->
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(iface.openRemoteFile(name))
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private val current = AtomicReference(Config())
    private val retries = AtomicInteger()

    /** 已生效的配置原文：与镜像文件逐字比对，相同就不重复解析。 */
    @Volatile private var appliedJson: String? = null
    @Volatile private var lastCheckMs = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifyGuard-config").apply { isDaemon = true }
    }

    @Volatile private var ifaceRef: XposedInterface? = null
    @Volatile private var listenerBound = false

    fun start(iface: XposedInterface) {
        ifaceRef = iface
        attach(iface, "startup")
    }

    fun config(): Config = current.get()

    private fun attach(iface: XposedInterface, from: String) {
        val prefs = runCatching { iface.getRemotePreferences(GROUP) }.getOrElse {
            ModuleLogger.error("取 remote prefs 失败（$from）⇒ 本轮用默认配置（observe=true，不拦任何通知）", it)
            scheduleRetry()
            return
        }
        reload(prefs.getString(KEY, null), from)

        if (listenerBound) return
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == KEY) {
                    runCatching { reload(prefs.getString(KEY, null), "push") }
                        .onFailure { ModuleLogger.error("配置热更新失败（保留旧配置）", it) }
                }
            }
        }.onSuccess {
            listenerBound = true
            retries.set(0)
        }.onFailure {
            ModuleLogger.error("注册配置监听失败（$from）⇒ 退避重试，期间改配置不生效", it)
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (listenerBound) return
        val n = retries.getAndIncrement()
        if (n >= retryDelaysMs.size) {
            ModuleLogger.error("配置通道连续 ${n} 次未连上 ⇒ 停止重试（此后改配置需重启 system_server）")
            return
        }
        scheduler.schedule(
            { ifaceRef?.let { attach(it, "retry#${n + 1}") } },
            retryDelaysMs[n],
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 从镜像文件核对配置。**必须在后台线程调用**（有文件 IO）。
     *
     * 为什么不能只信 push：push 是框架侧的内存回调，注册成功后仍可能被静默换掉——daemon 侧一旦重建
     * 本模块的注入服务对象（模块 apk 更新、节点缓存失效都会走到 `new LSPInjectedModuleService`），
     * 长命的 system_server 仍握着旧对象，此后 App 每次写入都推给空回调集合，不报错也不打日志。
     * 而模块端也自愈不了：框架按 group 缓存 prefs 实例，重新 `getRemotePreferences` 拿到的还是那份
     * 只在 push 时更新的内存快照，再读永远是旧值。唯一真实的通道是 App 顺手写的这个文件。
     */
    internal fun verifyFromFile() {
        val iface = ifaceRef ?: return
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < checkIntervalMs) return
        lastCheckMs = now
        // 文件不存在（App 还是老版本）不是异常：本方法只是兜底，绕开即可
        val json = readRemoteFile(iface, REMOTE_FILE) ?: return
        if (json == appliedJson) return
        reload(json, "file")
    }

    /** 单测复用同一个 object 单例，跨用例必须清干净（热重载在生产里等价于重建本 object）。 */
    internal fun resetForTest() {
        current.set(Config())
        retries.set(0)
        listenerBound = false
        ifaceRef = null
        appliedJson = null
        lastCheckMs = 0L
    }

    private fun reload(json: String?, from: String) {
        // 空值必须留线索：否则配置通道断了也只是「规则 0 条」，看起来像没配过规则
        if (json == null) {
            ModuleLogger.error("未收到配置（$from：远端组为空）⇒ 用默认配置（observe=true，不拦任何通知）")
        }
        val parsed = ConfigCodec.decode(json)
        if (parsed == null) {
            ModuleLogger.error(
                "配置解析失败（$from）⇒ 保留上一份有效配置（规则 ${current.get().rules.size} 条）",
            )
            return
        }
        parsed.compiledRules   // 先把正则编译掉，不能留给判定热路径
        current.set(parsed)
        appliedJson = json
        ModuleLogger.info(
            "配置生效（$from）：enabled=${parsed.enabled} observe=${parsed.observe} " +
                "规则=${parsed.rules.size}(有效 ${parsed.compiledRules.size}) 白名单=${parsed.whitelist.size} " +
                "微调版本=${parsed.deltaVersion}",
        )
        if (parsed.droppedRules > 0) {
            ModuleLogger.error("配置里有 ${parsed.droppedRules} 条规则被丢弃（类型未知 / 正则非法 / 关键词为空）")
        }
        // 版本号变了才去读 delta 文件（IO 在 DeltaHolder 的后台线程上，这里只是投递）
        DeltaHolder.onConfigVersion(parsed.deltaVersion)
    }
}
