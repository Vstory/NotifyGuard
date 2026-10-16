package io.github.vstory.notifyguard

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.vstory.notifyguard.core.CrashGuard
import io.github.vstory.notifyguard.core.EntryHook
import io.github.vstory.notifyguard.core.Generation
import io.github.vstory.notifyguard.core.ModuleLogger
import io.github.vstory.notifyguard.core.ModuleStatus
import io.github.vstory.notifyguard.core.brief

/**
 * api102 模块入口（java_init.list 声明）。scope = system，只注入 system_server。
 *
 * 生命周期：onModuleLoaded → onSystemServerStarting → 装配
 * （system_server 不走 onPackageReady，那里只作兜底；心跳/Context 获取见 设计方案.md §8.5）
 *
 * **日志口径**（release 与 debug 跑同一份代码，分叉只靠 `BuildConfig.DEBUG`，规则见 AGENTS.md）：
 *  - 两版都打：`boot.start`（variant / API / 框架 / ClassLoader）、`*.hooks` 汇总、配置生效、
 *    模型就绪、热重载各阶段、以及所有 E 级失败行。hook **明细只列 skip/fail**，成功项靠 `ok=N` 回答
 *  - 仅 debug 版：hook 的成功明细、`slot.stats`、`record.*` 的逐条与周期行 —— release 的框架日志
 *    不承担明细，逐条明细在 App 的记录页与诊断屏
 *
 * 热重载（api102 的 autoHotReload + 本文件的三个回调）：新代重装 hooks 成功后发布新代际号，
 * 旧代在判定入口与各路回调上读到过期即自行释放常驻资源（见 [Generation] / [ModuleTeardown]）。
 * 只有从**不覆写 onHotReloading（默认拒绝）的旧版本**装上来的那一次会失败 —— 框架问的是正在跑的
 * 那一代；跑到这份代码之后，改代码即可热重载（勾选作用域、改 module.prop 这类仍要重启）。
 */
class MainHook : XposedModule() {

    companion object {
        private var systemServerStartingSeen = false
        private var classLoader: ClassLoader? = null
        private var installed = false
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // 这里不打日志：模块版本就在每行的 `[<versionName>]` 前缀里，装配时的 boot.start 还会带上
        // API / 框架 / ClassLoader —— 再单独来一条横幅只是把同一次注入说两遍
        ModuleLogger.bind(this)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        systemServerStartingSeen = true
        classLoader = param.classLoader
        // 冷启动登记代际：进程重启后旧代已随进程消失，这一步只是把代际号对齐，
        // 好让日志里的「代际」在冷启动与热重载之间可比
        Generation.publish()
        installBoot(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // scope=system 时框架走 onSystemServerStarting；这里只覆盖「框架没回调 system 生命周期」的情形
        if (systemServerStartingSeen || param.packageName != "android") return
        classLoader = param.classLoader
        installBoot(param.classLoader)
    }

    /**
     * 允许热重载（I 级日志：两版都打，它是「改了代码为什么还没生效」的第一条线索）。
     *
     * 这条是被**旧代**打出来的（框架先问正在跑的那一代），所以字段写明 `by=old_generation`：
     * 看到它不代表新代码已接管，接管与否看后面的 `hotreload.hooks` / `hotreload.done`。
     *
     * 另外一件必须知道的事：新代装配失败时**不踢旧代**（见 [onHotReloaded]），
     * 代价是改动不生效，但模块不会停摆。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        ModuleLogger.info(
            "hotreload.accepted",
            "by=old_generation",
            "on_success=takeover",
            "on_failure=keep_old",
        )
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 热重载不重放 onModuleLoaded ⇒ 不重绑则新代 iface 为 null，新代日志会被静默丢弃
        ModuleLogger.bind(this)
        // 新代的静态全空，宿主 classLoader 只能从旧钩子推：钩子落在宿主类上，其 declaringClass 的 loader
        // 就是宿主 loader（boot loader 加载时会得到 null，故逐级 fallback 到本模块的 loader）
        val cl = classLoader
            ?: param.oldHookHandles.firstNotNullOfOrNull { it.executable.declaringClass.classLoader }
            ?: javaClass.classLoader
        installed = false
        ModuleLogger.info(
            "hotreload.start",
            "stale_handles=${param.oldHookHandles.size}",
            "classloader=${cl.brief()}",
        )

        // 顺序即安全边界：**先装新、装上了才踢旧**。反过来（先 unhook 再装）一旦新代装失败，
        // 模块就彻底停摆（旧钩子已卸、新钩子没上）；这个顺序最坏也只是「仍在旧代码上跑」。
        val report = assemble(cl, "hotreload")
        val hotFields = hookFields(report)
        if (report.failCount > 0) ModuleLogger.error("hotreload.hooks", *hotFields)
        else ModuleLogger.info("hotreload.hooks", *hotFields)
        dumpDetails(report)
        if (!EntryHook.isJudgingReady()) {
            // 新代这个空壳必须自己卸干净：留着它，旧代的钩子仍在（还没 unhook），同一条通知会被两代各判一次
            EntryHook.reset()
            installed = false
            ModuleLogger.error("hotreload.assemble_failed", "kept=old", "hooks=old_only")
            return
        }
        param.oldHookHandles.forEach {
            runCatching { it.unhook() }.onFailure { t -> ModuleLogger.error("hotreload.unhook_failed", ModuleLogger.err(t)) }
        }
        val gen = Generation.publish()
        // 新代到位的最后一步是重挂常驻资源（广播通道 / Context），它发生在下一次通知入队时
        ModuleLogger.info("hotreload.done", "gen=$gen", "old_release=on_next_activity", "rearm=on_next_enqueue")
    }

    /**
     * 冷启动装配。事件名在这里写成字面量（`boot.*`）：日志门禁按字面量认事件名，
     * 拼出来的「域」在门禁与检索里都不可见。
     */
    private fun installBoot(cl: ClassLoader?) {
        if (installed) {
            ModuleLogger.info("boot.skip", "reason=already_installed")
            return
        }
        if (cl == null) {
            // 框架没给 classLoader = 装配根本没开始，跟「钩子没挂上」同级，不能混在 I 里
            ModuleLogger.error("boot.assemble_failed", "reason=null_classloader", "hooks=passthrough")
            return
        }
        // 注入横幅：进程跑的是「注入那一刻」的那份代码，App 更新后 system_server 里可能仍是旧的，
        // 所以 variant / API / 框架版本必须由被注入的这份代码自己报出来（模块版本另见每行前缀）
        ModuleLogger.info(
            "boot.start",
            "variant=${if (BuildConfig.DEBUG) "debug" else "release"}",
            "api=$apiVersion",
            "framework=$frameworkName/$frameworkVersion",
            "cl=${cl.brief()}",
        )
        val report = assemble(cl, "boot")
        val bootFields = hookFields(report)
        if (report.failCount > 0) ModuleLogger.error("boot.hooks", *bootFields)
        else ModuleLogger.info("boot.hooks", *bootFields)
        dumpDetails(report)
        // 熔断中本来就不装钩子，不算故障；其余情况没钩子 = 模块只是个空壳，必须报出来
        if (!EntryHook.isJudgingReady() && !CrashGuard.isSafeMode()) {
            ModuleLogger.error("boot.assemble_failed", "owner=none", "hooks=passthrough")
        }
    }

    /**
     * [domain] 只决定装配明细行的前缀（`boot.hook` / `hotreload.hook`），供诊断屏与日志对照。
     *
     * 装配异常兜底成空报告：`install()` 内部多数步骤自带 runCatching，但配置/模型/句柄这些没包的
     * 一旦抛出来，状态机会停在「已置位 installed、钩子半装」的中间态 —— 那比装配失败更难查。
     */
    private fun assemble(cl: ClassLoader, domain: String): EntryHook.InstallReport {
        // 置位在装配之前：装配过程会回调进判定入口（配置/模型加载），重入一次就装两遍钩子
        installed = true
        return runCatching { EntryHook.install(this, cl, domain) }
            .onSuccess { ModuleStatus.recordInstall(it) }
            .onFailure {
                ModuleLogger.error("assemble.failed", "domain=$domain", ModuleLogger.err(it))
                EntryHook.reset()
            }
            .getOrElse { EntryHook.InstallReport(domain) }
    }

    private fun hookFields(report: EntryHook.InstallReport): Array<String> = arrayOf(
        "ok=${report.okCount}",
        "skip=${report.skipCount}",
        "fail=${report.failCount}",
        "owner=${EntryHook.currentSlot().wire}",
    )

    /**
     * 成功项只在 debug 版逐条列出；release 只列 skip/fail（AGENTS.md 的 release 口径）。
     * 级别由 [EntryHook.InstallReport.emitDetails] 按每条结果给出：失败明细走 E，其余走 I。
     */
    private fun dumpDetails(report: EntryHook.InstallReport) {
        report.emitDetails(
            includeOk = BuildConfig.DEBUG,
            info = ModuleLogger::lines,
            error = ModuleLogger::errorLine,
        )
        if (BuildConfig.DEBUG) ModuleLogger.debug("slot.stats", *EntryHook.statsFields())
    }
}
