package io.github.vstory.notifyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.Generation
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.core.brief
import io.github.vstory.notifyguard.core.ModuleStatus

/**
 * api102 模块入口（java_init.list 声明）。scope = system，只注入 system_server。
 *
 * 生命周期：onModuleLoaded → onSystemServerStarting → 装配
 * （system_server 不走 onPackageReady，那里只作兜底；心跳/Context 获取见 设计方案.md §8.5）
 *
 * 热重载（api102 的 autoHotReload + 本文件的三个回调）：新代重装 hooks 成功后发布新代际号，
 * 旧代在判定入口与各路回调上读到过期即自行释放常驻资源（见 [Generation] / [ModuleTeardown]）。
 * 换成新包后**第一次**热重载仍会失败 —— 框架问的是「正在跑的那一代」，而旧包里这一问是拒绝，
 * 重启一次 system_server 让新代码进来才谈得上免重启。
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
            "boot.injected",
            "variant=${if (BuildConfig.DEBUG) "debug" else "release"}",
            "api=$apiVersion",
            "framework=$frameworkName/$frameworkVersion",
            "system_server=${param.isSystemServer}",
            "process=${param.processName}",
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        systemServerStartingSeen = true
        classLoader = param.classLoader
        ModuleLogger.info("boot.starting", "classloader=${param.classLoader.brief()}")
        // 冷启动登记代际：进程重启后旧代已随进程消失，这一步只是把代际号对齐，
        // 好让日志里的「代际」在冷启动与热重载之间可比
        Generation.publish()
        installAll(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // scope=system 时框架走 onSystemServerStarting；这里只覆盖「框架没回调 system 生命周期」的情形
        if (systemServerStartingSeen || param.packageName != "android") return
        classLoader = param.classLoader
        installAll(param.classLoader)
    }

    /**
     * 允许热重载（I 级日志：正式版保留，它是「改了代码为什么还没生效」的第一条线索）。
     *
     * 旧代的退场机制见 [Generation]：本代装配成功后发布新代际号，旧代在自己下次活动时自行拆干净。
     * 三件必须知道的事：
     *  - **装上新包后的第一次热重载必定失败**（框架问的是正在跑的旧那代），需重启一次 system_server；
     *  - 新代装配失败时**不踢旧代**（见 [onHotReloaded]），代价是改动不生效，但模块不会停摆；
     *  - 旧代退场前仍会应答广播（那几十秒里两代并存），日志上的「代际」是分辨这件事的唯一线索。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        ModuleLogger.info("hotreload.accepted", "on_success=takeover", "on_failure=keep_old")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 热重载不重放 onModuleLoaded ⇒ 不重绑则新代 iface 为 null，新代日志会被静默丢弃
        ModuleLogger.bind(this)
        // 新代的静态全空，宿主 classLoader 只能从旧钩子推：钩子落在宿主类上，其 declaringClass 的 loader
        // 就是宿主 loader（boot loader 加载时会得到 null，故逐级 fallback 到本模块的 loader）
        val cl = classLoader
            ?: param.oldHookHandles.firstNotNullOfOrNull { it.executable?.declaringClass?.classLoader }
            ?: javaClass.classLoader
        installed = false
        ModuleLogger.info(
            "hotreload.start",
            "stale_handles=${param.oldHookHandles.size}",
            "classloader=${cl.brief()}",
        )

        // 顺序即安全边界：**先装新、装上了才踢旧**。反过来（先 unhook 再装）一旦新代装失败，
        // 模块就彻底停摆（旧钩子已卸、新钩子没上）；这个顺序最坏也只是「仍在旧代码上跑」。
        val report = installAll(cl)
        if (report == null || report.okCount == 0) {
            installed = false
            ModuleLogger.error("hotreload.assemble_failed", "kept=old", "restart_required=true")
            return
        }
        param.oldHookHandles.forEach {
            runCatching { it.unhook() }.onFailure { t -> ModuleLogger.error("hotreload.unhook_failed", ModuleLogger.err(t)) }
        }
        val gen = Generation.publish()
        ModuleLogger.info("hotreload.done", "gen=$gen", "old_release=on_next_activity")
        ModuleLogger.info(
            "hotreload.pending",
            "attach=on_next_enqueue",
            "channels=record,label,status,config",
        )
    }

    private fun installAll(cl: ClassLoader?): EntryHook.InstallReport? {
        if (installed) {
            ModuleLogger.info("boot.skip", "reason=already_installed")
            return null
        }
        if (cl == null) {
            ModuleLogger.info("boot.skip", "reason=null_classloader")
            return null
        }
        installed = true
        val report = EntryHook.install(this, cl)
        ModuleStatus.recordInstall(report)
        ModuleLogger.info(
            "boot.hooks",
            "ok=${report.okCount}",
            "skip=${report.skipCount}",
            "fail=${report.failCount}",
        )
        report.detailLines().forEach { ModuleLogger.lines(it) }
        ModuleLogger.info("slot.stats", *EntryHook.statsFields())
        return report
    }
}
