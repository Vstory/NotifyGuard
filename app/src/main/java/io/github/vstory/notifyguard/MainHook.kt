package io.github.vstory.notifyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.core.ModuleStatus

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
        // 版本横幅：注入进程跑的是「注入那一刻」的那份代码，App 更新后 system_server 里仍是旧的 ——
        // 排障的第一件事就是确定这一点，所以版本必须由被注入的这份代码自己报出来
        ModuleLogger.info(
            "NotifyGuard 模块 ${BuildConfig.VERSION_NAME}" +
                "（${if (BuildConfig.DEBUG) "debug" else "release"}）已注入：" +
                "api=$apiVersion framework=$frameworkName/$frameworkVersion " +
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

    /**
     * 热重载**暂不开放**，一律拒绝。
     *
     * 本模块在 system_server 里持有 7 个常驻线程池、3 个动态注册的 receiver、一个 FileObserver
     * 与一份内置模型；换代只换代码、不回收这些资源，旧代那批全部滞留 —— 线程是 GC root，
     * 持有旧 ClassLoader ⇒ 旧代（连同模型）永远卸载不掉，每次热重载净增一份。
     * 更麻烦的是框架 unhook 旧句柄后**不重放** `onSystemServerStarting`，判定链停在半路，
     * 日志上却看不出任何异常（表现就是「记录不再增长」）。
     *
     * 这一行是装完模块后**唯一**能说明「为什么改了代码没生效」的线索，故为 I 级（正式版保留）。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        ModuleLogger.info("拒绝热重载：teardown 未实现（本代继续运行，改代码需重启 system_server）")
        return false
    }

    /** 当前不会触发（[onHotReloading] 恒拒绝）；保留为开放热重载时的实现。 */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 热重载不重放 onModuleLoaded ⇒ 不重绑则新代 iface 为 null，新代日志会被静默丢弃
        ModuleLogger.bind(this)
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
        ModuleStatus.recordInstall(report)
        ModuleLogger.info("installHooks: ${report.okCount} ok / ${report.skipCount} skip / ${report.failCount} fail${report.detail()}")
        ModuleLogger.info("slot: ${EntryHook.statsLine()}")
    }
}
