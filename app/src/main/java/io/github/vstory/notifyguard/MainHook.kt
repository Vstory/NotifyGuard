package io.github.vstory.notifyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.Generation
import io.github.vstory.notifyguard.core.ModuleLogger
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
        ModuleLogger.info("同意热重载：新代装配成功后接管，旧代随后自行释放资源")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 热重载不重放 onModuleLoaded ⇒ 不重绑则新代 iface 为 null，新代日志会被静默丢弃
        ModuleLogger.bind(this)
        // 新代的静态全空，宿主 classLoader 只能从旧钩子推：钩子落在宿主类上，其 declaringClass 的 loader
        // 就是宿主 loader（boot loader 加载时会得到 null，故逐级 fallback 到本模块的 loader）
        val cl = classLoader
            ?: param.oldHookHandles.firstNotNullOfOrNull { it.executable.declaringClass?.classLoader }
            ?: javaClass.classLoader
        installed = false
        ModuleLogger.info("onHotReloaded: 新代装配开始（旧句柄 ${param.oldHookHandles.size} 个，classLoader=$cl）")

        // 顺序即安全边界：**先装新、装上了才踢旧**。反过来（先 unhook 再装）一旦新代装失败，
        // 模块就彻底停摆（旧钩子已卸、新钩子没上）；这个顺序最坏也只是「仍在旧代码上跑」。
        val report = installAll(cl)
        if (report == null || report.okCount == 0) {
            installed = false
            ModuleLogger.error("新代装配失败 ⇒ 保留旧代继续服务（本次改动不生效，重启 system_server 才能换）")
            return
        }
        param.oldHookHandles.forEach {
            runCatching { it.unhook() }.onFailure { t -> ModuleLogger.error("unhook 旧句柄失败：${t.message}") }
        }
        val gen = Generation.publish()
        ModuleLogger.info("换代完成：代际=$gen；旧代待下一次活动（通知入队 / 广播 / 周期落盘）时释放自己")
        ModuleLogger.info("新代待接线：下次通知入队时会重新取 Context 并注册记录 / 标注 / 状态 / 配置四条通道")
    }

    private fun installAll(cl: ClassLoader?): EntryHook.InstallReport? {
        if (installed) {
            ModuleLogger.info("install skipped: 本代已装过（防叠加）")
            return null
        }
        if (cl == null) {
            ModuleLogger.info("install skipped: classLoader 为空（注入未生效？）")
            return null
        }
        installed = true
        val report = EntryHook.install(this, cl)
        ModuleStatus.recordInstall(report)
        ModuleLogger.info("installHooks: ${report.okCount} ok / ${report.skipCount} skip / ${report.failCount} fail${report.detail()}")
        ModuleLogger.info("slot: ${EntryHook.statsLine()}")
        return report
    }
}
