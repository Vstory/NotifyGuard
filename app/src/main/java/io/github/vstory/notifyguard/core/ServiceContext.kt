package io.github.vstory.notifyguard.core

import android.content.Context
import io.github.vstory.notifyguard.sync.ConfigChannel
import io.github.vstory.notifyguard.sync.LabelSink
import io.github.vstory.notifyguard.sync.LogSink
import io.github.vstory.notifyguard.sync.StatusChannel

/**
 * system_server 内的 Context 来源（设计方案.md §8.5）。
 *
 * 唯一安全时机是 hook 到系统服务实例之后、从实例上取；不在启动期调 `ActivityThread.systemMain()`
 * （那会 new 出第二个 ActivityThread，撑坏 installSystemProviders 的 ClassLoader 链）。
 *
 * 两条来源：NMS 漏斗首次被调用（正常路径），以及 AMS 的 `systemReady`（熔断期间不装拦截时唯一可用的一条，
 * 见 core/CrashGuard.installContextProbe）。
 */
object ServiceContext {

    @Volatile private var ctx: Context? = null
    private var attempted = false

    fun get(): Context? = ctx

    /** 解析一次即缓存；失败只报一次（失败信息对排查「记录回流为什么不动」是关键线索）。 */
    fun bindFrom(service: Any) {
        if (ctx != null) return
        synchronized(this) {
            if (ctx != null || attempted) return
            attempted = true
        }
        val c = fromGetter(service) ?: fromField(service)
        if (c == null) {
            ModuleLogger.error("context.unavailable", "sink=disabled", "judge=unaffected")
            return
        }
        ctx = c
        LogSink.bindContext(c)
        LabelSink.bindContext(c)
        StatusChannel.register(c)
        // 配置通道跟着 Context 一起上：它是 prefs push 断线后唯一能把配置推进来的路
        ConfigChannel.register(c)
    }

    private fun fromGetter(service: Any): Context? = runCatching {
        // NMS 继承 SystemService 的 public final getContext()：ColorOS smali 里该方法被大量调用
        service.javaClass.getMethod("getContext").invoke(service) as? Context
    }.getOrNull()

    private fun fromField(service: Any): Context? = runCatching {
        service.javaClass.getDeclaredField("mContext").apply { isAccessible = true }.get(service) as? Context
    }.getOrNull()
}
