package io.github.vstory.notifyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.ModuleLogger

/**
 * api102 模块入口（java_init.list 声明）。scope = system，只注入 system_server。
 *
 * 生命周期：onModuleLoaded → onSystemServerStarting → 装配
 * （system_server 不走 onPackageReady，那里只作兜底；心跳/Context 获取见 设计方案.md §8.5）
 * 热重载：onHotReloading 返回 true → 旧句柄由框架 unhook → onHotReloaded 手动重装
 * （热重载不重放 onSystemServerStarting，且静态状态不重置，故必须自行清旧再装）
 */
class MainHook : XposedModule() {

    companion object {
        private var systemServerStartingSeen = false
        private var classLoader: ClassLoader? = null
        private var installed = false
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        ModuleLogger.bind(this)
        ModuleLogger.info(
            "api102 module loaded: api=$apiVersion framework=$frameworkName/$frameworkVersion " +
                "isSystemServer=${param.isSystemServer} process=${param.processName}"
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        systemServerStartingSeen = true
        classLoader = param.classLoader
        ModuleLogger.info("onSystemServerStarting: classLoader=${param.classLoader}")
        installAll(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // scope=system 时框架走 onSystemServerStarting；这里只覆盖「框架没回调 system 生命周期」的情形
        if (systemServerStartingSeen || param.packageName != "android") return
        classLoader = param.classLoader
        installAll(param.classLoader)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        param.oldHookHandles.forEach { runCatching { it.unhook() } }
        val cl = classLoader
            ?: param.oldHookHandles.firstOrNull()?.executable?.declaringClass?.classLoader
            ?: javaClass.classLoader
        installed = false
        ModuleLogger.info("onHotReloaded: 重装 hooks（classLoader=$cl）")
        installAll(cl)
    }

    private fun installAll(cl: ClassLoader?) {
        if (installed) {
            ModuleLogger.info("install skipped: 本代已装过（防叠加）")
            return
        }
        if (cl == null) {
            ModuleLogger.info("install skipped: classLoader 为空（注入未生效？）")
            return
        }
        installed = true
        val report = EntryHook.install(this, cl)
        ModuleLogger.info("installHooks: ${report.okCount} ok / ${report.skipCount} skip / ${report.failCount} fail${report.detail()}")
        ModuleLogger.info("slot: ${EntryHook.statsLine()}")
    }
}
