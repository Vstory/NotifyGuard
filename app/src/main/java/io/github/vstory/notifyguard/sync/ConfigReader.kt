package io.github.vstory.notifyguard.sync

import io.github.libxposed.api.XposedInterface
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

    /** 退避间隔；离线单测把它改小以验证重试路径。 */
    internal var retryDelaysMs = longArrayOf(1_000, 3_000, 10_000)

    private val current = AtomicReference(Config())
    private val retries = AtomicInteger()

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

    /** 单测复用同一个 object 单例，跨用例必须清干净（热重载在生产里等价于重建本 object）。 */
    internal fun resetForTest() {
        current.set(Config())
        retries.set(0)
        listenerBound = false
        ifaceRef = null
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
        ModuleLogger.info(
            "配置生效（$from）：enabled=${parsed.enabled} observe=${parsed.observe} " +
                "规则=${parsed.rules.size}(有效 ${parsed.compiledRules.size}) 白名单=${parsed.whitelist.size}",
        )
        if (parsed.droppedRules > 0) {
            ModuleLogger.error("配置里有 ${parsed.droppedRules} 条规则被丢弃（类型未知 / 正则非法 / 关键词为空）")
        }
    }
}
