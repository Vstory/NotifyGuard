package io.github.vstory.notifyguard.core

import android.app.Notification
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.ai.DeltaHolder
import io.github.vstory.notifyguard.ai.ModelHolder
import io.github.vstory.notifyguard.judge.Judge
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.judge.NotifySnapshot
import io.github.vstory.notifyguard.judge.RecordSink
import io.github.vstory.notifyguard.sync.ConfigReader
import io.github.vstory.notifyguard.sync.LogSink
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 拦截点解析与装配（设计方案.md §4，勘察 §四）。
 *
 * 两条路径都装，靠 [owner] 保证**同一时刻只有一处判定**：
 *  - 漏斗（`NMS#enqueueNotificationInternal`，兜底）启动即装，保证无判定空窗；
 *  - 扩展槽（ColorOS 原生拦截槽，主路径）解析成功后接管判定权，漏斗转为直通。
 *
 * 不用「先试主路径、失败才装兜底」的原因：扩展槽要等 NMS 初始化后才有实例可解析（见 [installClinitTrigger]），
 * 那段时间会出现判定空窗；也不能在漏斗 hooker 里装上扩展槽后自己退出——同一条通知会被判两次。
 */
object EntryHook {

    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"
    private const val EXT_STATIC_FIELD = "mNMSExt"
    private const val NMS_WRAPPER_FIELD = "mNMSWrapper"
    private const val EXT_GETTER = "getNMSExt"
    private const val EXT_METHOD = "interceptEnqueueNotificationInternal"
    private const val FUNNEL_METHOD = "enqueueNotificationInternal"

    private const val HIT_LOG_EVERY = 20L

    enum class Slot {
        NONE,
        FUNNEL,
        EXT_SLOT,
        ;

        /** 写进日志的取值：全模块统一小写。 */
        val wire: String get() = name.lowercase()
    }

    /**
     * 装配结果汇总（ok / skip / fail + 明细），由 MainHook 打一次 INFO。
     *
     * 明细**逐行自成一条日志**（每行已是 `boot.hook <结果> kind=… k=v` 的形式），不再拼成一个多行巨串：
     * 多行串的后续行不带版本前缀，混进日志里无法归属到哪次装配。
     */
    class InstallReport {
        var okCount = 0
            private set
        var skipCount = 0
            private set
        var failCount = 0
            private set

        private val lines = ArrayList<String>()

        fun markOk(kind: String, vararg fields: String) = add("ok", kind, fields.toList())

        fun markOk(kind: String, fields: List<String>) = add("ok", kind, fields)

        fun markSkip(kind: String, vararg fields: String) = add("skip", kind, fields.toList())

        fun markSkip(kind: String, fields: List<String>) = add("skip", kind, fields)

        fun markFail(kind: String, fields: String, t: Throwable) {
            failCount++
            lines.add(head("fail", kind) + " " + fields + " " + ModuleLogger.err(t))
        }

        fun markFail(kind: String, fields: List<String>, t: Throwable) {
            failCount++
            lines.add(
                head("fail", kind) + " " + fields.joinToString(" ") + " " + ModuleLogger.err(t)
            )
        }

        fun detailLines(): List<String> = lines

        private fun add(result: String, kind: String, fields: List<String>) {
            if (result == "ok") okCount++ else skipCount++
            lines.add(head(result, kind) + if (fields.isEmpty()) "" else " " + fields.joinToString(" "))
        }

        private fun head(result: String, kind: String) = "boot.hook $result kind=$kind"
    }

    /** 计数快照。整体取一份：逐项取值会在两次读之间被判定线程改掉，读出的数自相矛盾。 */
    data class Hits(val ext: Long, val funnelJudge: Long, val funnelPass: Long, val romBlocked: Long)

    private val owner = AtomicReference(Slot.NONE)
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    @Volatile private var judgingStopped = false
    @Volatile private var stopReason: String? = null

    private val extHits = AtomicLong()
    private val funnelJudgeHits = AtomicLong()
    private val funnelPassHits = AtomicLong()
    private val romBlocked = AtomicLong()

    fun install(iface: XposedInterface, cl: ClassLoader): InstallReport {
        reset()
        CrashGuard.onStorm = { stopJudging("异常风暴") }
        CrashGuard.onTrip = { stopJudging("崩溃环路熔断") }
        // 恢复走 install 自身：它开头先 reset（unhook + 停 watcher）再装，天然幂等，不会叠加
        CrashGuard.onCleared = {
            ModuleLogger.info("safemode.cleared", "reinstall=hooks")
            runCatching { install(iface, cl) }
        }

        val report = InstallReport()
        // 先于 safe_mode 检查：标志存在时也要能看见用户把它删掉，否则恢复只剩重启一条路
        CrashGuard.attach(iface, cl, report::markOk, report::markSkip)
        if (CrashGuard.isSafeMode()) {
            report.markSkip("safemode", "reason=safemode_active")
            return report
        }

        // 先于 hook 装配：判定链要读配置，且框架只向「已取过该组」的进程推送变更
        DeltaHolder.start(iface)
        ConfigReader.start(iface)
        report.markOk("config", "group=${ConfigReader.GROUP}", "observe=${ConfigReader.config().observe}")

        // 同步加载一次（262 KB 解析，毫秒级）：判定链里绝不做 IO，装不上就整个 AI 段放行
        ModelHolder.loadBundled()
        report.markOk("model", if (ModelHolder.current == null) "state=unavailable action=pass" else "state=ready")

        val nms = runCatching { cl.loadClass(NMS_CLASS) }.getOrElse {
            report.markSkip("nms", ModuleLogger.err(it))
            return report
        }

        installFunnel(iface, nms, report)
        installClinitTrigger(iface, nms, report)

        ModuleLogger.info("assemble.done", "owner=${owner.get().wire}", "ext_hits=${extHits.get()}")
        return report
    }

    /**
     * 熔断后只能靠这个标志拦住判定：`owner` 回到 NONE 会让 [onFunnel] 误以为「扩展槽还没装」而重新装它，
     * 等于熔断被自己解除。
     */
    private fun stopJudging(reason: String) {
        judgingStopped = true
        stopReason = reason
        owner.set(Slot.NONE)
        ModuleLogger.error("assemble.disabled", "reason=$reason", "hooks=passthrough")
    }

    fun reset() {
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        owner.set(Slot.NONE)
        judgingStopped = false
        stopReason = null
        extHits.set(0)
        funnelJudgeHits.set(0)
        funnelPassHits.set(0)
        romBlocked.set(0)
        CrashGuard.reset()
    }

    fun statsFields(): Array<String> = arrayOf(
        "owner=${owner.get().wire}",
        "ext_hits=${extHits.get()}",
        "funnel_judged=${funnelJudgeHits.get()}",
        "funnel_passed=${funnelPassHits.get()}",
        "rom_blocked=${romBlocked.get()}",
        "errors=${CrashGuard.errorCount()}",
        *LogSink.statsFields(),
    )

    // ===== 状态回传（M4e）=====

    fun hits(): Hits = Hits(extHits.get(), funnelJudgeHits.get(), funnelPassHits.get(), romBlocked.get())

    fun currentSlot(): Slot = owner.get()

    fun isJudgingStopped(): Boolean = judgingStopped

    fun stopReason(): String? = stopReason

    // ===== 装配 =====

    private fun installFunnel(iface: XposedInterface, nms: Class<*>, report: InstallReport) {
        val target = findMethod(nms, FUNNEL_METHOD)
        if (target == null) {
            report.markSkip("funnel", "reason=method_not_found", "hint=$FUNNEL_METHOD+Notification_arg")
            return
        }
        try {
            handles.add(
                iface.hook(target).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = onFunnel(iface, nms, chain)
                })
            )
            owner.set(Slot.FUNNEL)
            report.markOk("funnel", desc(target), "role=fallback")
        } catch (t: Throwable) {
            report.markFail("funnel", desc(target), t)
        }
    }

    /**
     * NMS 未初始化时**唯一能安全读到 `mNMSExt` 的时机**就是它自己的 `<clinit>` 之后。
     *
     * 不能改用主动 `Field.get(null)` 抢读：类未初始化时读静态字段会触发它的 `<clinit>`，
     * 等于抢跑 NMS 的初始化（启动顺序被扰动）。类**已经**初始化时这个 hook 不会再触发，
     * 那种情况由漏斗首次被调用兜住（见 [onFunnel]）。
     */
    private fun installClinitTrigger(iface: XposedInterface, nms: Class<*>, report: InstallReport) {
        try {
            handles.add(
                iface.hookClassInitializer(nms).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 不吞异常：吞掉会让「类初始化」看起来成功，类进入错误状态后整个 NMS 不可用
                        chain.proceed()
                        runCatching { tryInstallExtSlot(iface, nms, null, "clinit") }
                        return null
                    }
                })
            )
            report.markOk("nms_clinit", "reason=resolve_extension_after_clinit")
        } catch (t: Throwable) {
            report.markSkip("nms_clinit", "reason=class_already_initialized", ModuleLogger.err(t))
        }
    }

    /** 幂等：解析到扩展实例就 hook 扩展槽并把判定权转过来。 */
    private fun tryInstallExtSlot(iface: XposedInterface, nms: Class<*>, nmsInstance: Any?, from: String) {
        if (owner.get() == Slot.EXT_SLOT) return
        val ext = extInstance(nms, nmsInstance)
        if (ext == null) {
            if (BuildConfig.DEBUG) ModuleLogger.debug("slot.extension_missing", "at=$from")
            return
        }
        val cls = ext.javaClass
        val target = findMethod(cls, EXT_METHOD)
        if (target == null) {
            // 不硬编码实现类名（随 ColorOS 版本走），故类名只作日志参考
            ModuleLogger.info("slot.extension_no_method", "cls=${cls.name}", "fallback=funnel")
            return
        }
        handles.add(
            iface.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = onExtSlot(chain)
            })
        )
        owner.set(Slot.EXT_SLOT)
        ModuleLogger.info(
            "slot.takeover",
            "at=$from",
            "cls=${cls.name}",
            "method=${target.name}",
        )
    }

    private fun extInstance(nms: Class<*>, nmsInstance: Any?): Any? {
        if (nmsInstance != null) {
            runCatching {
                val wrapperField = nms.getDeclaredField(NMS_WRAPPER_FIELD).apply { isAccessible = true }
                val wrapper = wrapperField.get(nmsInstance) ?: return@runCatching null
                wrapper.javaClass.getMethod(EXT_GETTER).apply { isAccessible = true }.invoke(wrapper)
            }.getOrNull()?.let { return it }
        }
        // 静态字段途径：只有类已初始化时调用（调用点见 installClinitTrigger / onFunnel）
        return runCatching {
            nms.getDeclaredField(EXT_STATIC_FIELD).apply { isAccessible = true }.get(null)
        }.getOrNull()
    }

    private fun findMethod(cls: Class<*>, name: String): Method? {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            val hit = cur.declaredMethods
                .filter { it.name == name && it.parameterTypes.any { p -> Notification::class.java.isAssignableFrom(p) } }
                .maxByOrNull { it.parameterCount }
            if (hit != null) return hit
            cur = cur.superclass
        }
        return null
    }

    // ===== hooker =====

    private fun onFunnel(iface: XposedInterface, nms: Class<*>, chain: XposedInterface.Chain): Any? {
        // 换代的安全带：旧钩子若还没卸掉（或 unhook 失败），过期代也只放行 ——
        // 让它继续拿旧配置做决定，等于让「当前生效的是什么」变得不可知
        if (ModuleTeardown.expired()) return chain.proceed()
        // 漏斗必然先于扩展槽被触发（ROM 是在它内部调扩展方法的），这里是取 system_server Context 的时机
        runCatching { ServiceContext.bindFrom(chain.thisObject) }
        if (judgingStopped) return chain.proceed()
        if (owner.get() != Slot.EXT_SLOT) {
            // 走到这里说明 NMS 类必已初始化：用实例补装扩展槽（clinit 钩子已无机会触发的情况）
            runCatching { tryInstallExtSlot(iface, nms, chain.thisObject, "funnel-first-call") }
                .onFailure { CrashGuard.noteError("install-ext-slot", it) }
        }
        if (owner.get() != Slot.FUNNEL) {
            tick(funnelPassHits, "funnel_passed")
            return chain.proceed()
        }

        tick(funnelJudgeHits, "funnel_judged")
        val blocked = runCatching { decideAndRecord(chain.args, Slot.FUNNEL) }
            .getOrElse { t ->
                CrashGuard.noteError("funnel-judge", t)
                false
            }
        // 漏斗返回 void：拦住 = 不调原方法
        return if (blocked) null else chain.proceed()
    }

    private fun onExtSlot(chain: XposedInterface.Chain): Any? {
        // 同 [onFunnel]：过期代把判定交回 ROM，自己不拦不记录
        if (ModuleTeardown.expired()) return chain.proceed()
        tick(extHits, "ext_hits")

        // 不变式：ROM 自己的判定永远优先（隐藏应用 / 企业定制 / 通知中心黑名单…）
        val romResult = chain.proceed()
        if (romResult == true) {
            romBlocked.incrementAndGet()
            return true
        }
        if (owner.get() != Slot.EXT_SLOT || judgingStopped) return romResult

        val blocked = runCatching { decideAndRecord(chain.args, Slot.EXT_SLOT) }
            .getOrElse { t ->
                CrashGuard.noteError("ext-slot-judge", t)
                false
            }
        // 该方法声明返回 boolean：非拦时必须回传 ROM 的结果，不能返回 null（拆箱处 NPE）
        return if (blocked) true else romResult
    }

    private fun decideAndRecord(args: List<Any?>, slot: Slot): Boolean {
        val snapshot = NotifySnapshot.from(args)
        val decision = Judge.decide(snapshot, ConfigReader.config())
        RecordSink.record(snapshot, decision)
        LogSink.submit(LogRecord.from(snapshot, decision, slot.name, System.currentTimeMillis()))
        if (decision.block) {
            ModuleLogger.info(
                "record.blocked",
                "slot=${slot.wire}",
                "pkg=${snapshot?.pkg}",
                "reason=${decision.reason}",
            )
        } else if (decision.wouldBlock) {
            // 观察模式：判定已命中但未拦，这一行就是切到拦截模式前的证据
            ModuleLogger.info(
                "record.observed",
                "slot=${slot.wire}",
                "pkg=${snapshot?.pkg}",
                "reason=${decision.reason}",
            )
        }
        return decision.block
    }

    // ===== 工具 =====

    private fun tick(counter: AtomicLong, kind: String) {
        val n = counter.incrementAndGet()
        if (n % HIT_LOG_EVERY == 0L) {
            ModuleLogger.info("slot.stats", "kind=$kind", "n=$n", *statsFields())
        }
    }

    private fun desc(m: Method): String =
        "cls=${m.declaringClass.name} method=${m.name} params=${m.parameterCount}"
}
