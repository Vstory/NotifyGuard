package io.github.vstory.notifyguard.core

import android.app.Notification
import io.github.libxposed.api.XposedInterface
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.judge.Judge
import io.github.vstory.notifyguard.judge.NotifySnapshot
import io.github.vstory.notifyguard.judge.RecordSink
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

    enum class Slot { NONE, FUNNEL, EXT_SLOT }

    /** 装配结果汇总（ok / skip / fail + 明细），由 MainHook 一次性打 INFO。 */
    class InstallReport {
        var okCount = 0
            private set
        var skipCount = 0
            private set
        var failCount = 0
            private set

        private val lines = StringBuilder()

        fun markOk(what: String) {
            okCount++
            lines.append("\n  [OK] ").append(what)
        }

        fun markSkip(what: String) {
            skipCount++
            lines.append("\n  [SKIP] ").append(what)
        }

        fun markFail(what: String, t: Throwable) {
            failCount++
            lines.append("\n  [FAIL] ").append(what).append(": ").append(t.message)
        }

        fun detail(): String = lines.toString()
    }

    private val owner = AtomicReference(Slot.NONE)
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()

    private val extHits = AtomicLong()
    private val funnelJudgeHits = AtomicLong()
    private val funnelPassHits = AtomicLong()
    private val romBlocked = AtomicLong()

    fun install(iface: XposedInterface, cl: ClassLoader): InstallReport {
        reset()
        CrashGuard.onStorm = {
            owner.set(Slot.NONE)
            ModuleLogger.error("判定已停用：hook 保留直通，不再记录")
        }

        val report = InstallReport()
        if (CrashGuard.isSafeMode()) {
            report.markSkip("safe_mode 标志存在 ⇒ 不装 hook")
            return report
        }

        val nms = runCatching { cl.loadClass(NMS_CLASS) }.getOrElse {
            report.markSkip("$NMS_CLASS 加载失败: ${it.message}")
            return report
        }

        installFunnel(iface, nms, report)
        installClinitTrigger(iface, nms, report)

        ModuleLogger.info("装配完成：owner=${owner.get()}；扩展槽命中=${extHits.get()}")
        return report
    }

    fun reset() {
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        owner.set(Slot.NONE)
        extHits.set(0)
        funnelJudgeHits.set(0)
        funnelPassHits.set(0)
        romBlocked.set(0)
        CrashGuard.reset()
    }

    fun statsLine(): String =
        "owner=${owner.get()} 扩展槽命中=${extHits.get()} 漏斗判定=${funnelJudgeHits.get()} " +
            "漏斗直通=${funnelPassHits.get()} ROM已拦=${romBlocked.get()} 异常=${CrashGuard.errorCount()}"

    // ===== 装配 =====

    private fun installFunnel(iface: XposedInterface, nms: Class<*>, report: InstallReport) {
        val target = findMethod(nms, FUNNEL_METHOD)
        if (target == null) {
            report.markSkip("漏斗方法未找到（$FUNNEL_METHOD + 参数含 Notification）")
            return
        }
        try {
            handles.add(
                iface.hook(target).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = onFunnel(iface, nms, chain)
                })
            )
            owner.set(Slot.FUNNEL)
            report.markOk("漏斗 ${desc(target)}（兜底路径，当前判定权）")
        } catch (t: Throwable) {
            report.markFail("漏斗 ${desc(target)}", t)
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
            report.markOk("NMS 类初始化钩子（clinit 后解析扩展槽；类已初始化则不触发）")
        } catch (t: Throwable) {
            report.markSkip("NMS 类初始化钩子不可用（类已初始化属正常）: ${t.message}")
        }
    }

    /** 幂等：解析到扩展实例就 hook 扩展槽并把判定权转过来。 */
    private fun tryInstallExtSlot(iface: XposedInterface, nms: Class<*>, nmsInstance: Any?, from: String) {
        if (owner.get() == Slot.EXT_SLOT) return
        val ext = extInstance(nms, nmsInstance)
        if (ext == null) {
            if (BuildConfig.DEBUG) ModuleLogger.debugRaw("[DBG] 扩展实例未取到（$from）")
            return
        }
        val cls = ext.javaClass
        val target = findMethod(cls, EXT_METHOD)
        if (target == null) {
            // 不硬编码实现类名（随 ColorOS 版本走），故类名只作日志参考
            ModuleLogger.info("扩展类 ${cls.name} 无 $EXT_METHOD ⇒ 保持漏斗路径")
            return
        }
        handles.add(
            iface.hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = onExtSlot(chain)
            })
        )
        owner.set(Slot.EXT_SLOT)
        ModuleLogger.info("扩展槽接管判定权（解析时机=$from）：${cls.name}#${target.name}")
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
        if (owner.get() != Slot.EXT_SLOT) {
            // 走到这里说明 NMS 类必已初始化：用实例补装扩展槽（clinit 钩子已无机会触发的情况）
            runCatching { tryInstallExtSlot(iface, nms, chain.thisObject, "funnel-first-call") }
                .onFailure { CrashGuard.noteError("install-ext-slot", it) }
        }
        if (owner.get() != Slot.FUNNEL) {
            tick(funnelPassHits, "漏斗直通")
            return chain.proceed()
        }

        tick(funnelJudgeHits, "漏斗判定")
        val blocked = runCatching { decideAndRecord(chain.args, Slot.FUNNEL) }
            .getOrElse { t ->
                CrashGuard.noteError("funnel-judge", t)
                false
            }
        // 漏斗返回 void：拦住 = 不调原方法
        return if (blocked) null else chain.proceed()
    }

    private fun onExtSlot(chain: XposedInterface.Chain): Any? {
        tick(extHits, "扩展槽命中")

        // 不变式：ROM 自己的判定永远优先（隐藏应用 / 企业定制 / 通知中心黑名单…）
        val romResult = chain.proceed()
        if (romResult == true) {
            romBlocked.incrementAndGet()
            return true
        }
        if (owner.get() != Slot.EXT_SLOT) return romResult

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
        val decision = Judge.decide(snapshot)
        RecordSink.record(snapshot, decision)
        if (decision.block) {
            ModuleLogger.info("BLOCK[$slot] pkg=${snapshot?.pkg} reason=${decision.reason}")
        }
        return decision.block
    }

    // ===== 工具 =====

    private fun tick(counter: AtomicLong, name: String) {
        val n = counter.incrementAndGet()
        if (n % HIT_LOG_EVERY == 0L) {
            ModuleLogger.info("$name $n 次；${statsLine()}")
        }
    }

    private fun desc(m: Method): String = "${m.declaringClass.name}#${m.name}(${m.parameterCount} 参数)"
}
