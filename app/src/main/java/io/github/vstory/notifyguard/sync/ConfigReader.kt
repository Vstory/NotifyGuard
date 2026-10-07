package io.github.vstory.notifyguard.sync

import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.judge.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块端（system_server）的配置读取与热更新。
 *
 * 必须在**装配时就**取一次本组并注册监听：框架只向「曾调用过 getRemotePreferences 的进程」推送变更，
 * 等要用配置时再取，会漏掉此前的全部改动。
 *
 * 注意 `edit()` 在模块端抛 UnsupportedOperationException（框架下发的是只读实现），写方只有 App 端
 * 的 [ConfigWriter]。
 */
object ConfigReader {

    const val GROUP = "io.github.vstory.notifyguard_config"
    const val KEY = "config"

    private val current = AtomicReference(Config())
    private var listenerBound = false

    fun start(iface: XposedInterface) {
        val prefs = runCatching { iface.getRemotePreferences(GROUP) }.getOrElse {
            ModuleLogger.error("取 remote prefs 失败 ⇒ 本次用默认配置（observe=true，不拦任何通知）", it)
            return
        }
        reload(prefs.getString(KEY, null), "startup")

        if (listenerBound) return
        listenerBound = true
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                // key == null 表示整组被 clear
                if (key == null || key == KEY) {
                    runCatching { reload(prefs.getString(KEY, null), "push") }
                        .onFailure { ModuleLogger.error("配置热更新失败（保留旧配置）", it) }
                }
            }
        }.onFailure { ModuleLogger.error("注册配置监听失败 ⇒ 此后改配置需重启 system_server 才生效", it) }
    }

    fun config(): Config = current.get()

    private fun reload(json: String?, from: String) {
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
