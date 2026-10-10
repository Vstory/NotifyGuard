package io.github.vstory.notifyguard.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.vstory.notifyguard.ai.DeltaStamp
import io.github.vstory.notifyguard.ai.SpamDelta
import io.github.vstory.notifyguard.ai.SpamModel
import io.github.vstory.notifyguard.ai.SpamTuner
import io.github.vstory.notifyguard.judge.LabelRecord
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * App 侧的拟合与下发编排：标注 + base 指纹 → 确定性 SGD → remote file → 配置里的 `deltaVersion`。
 *
 * 「需要重拟合」的判据是**输入摘要**（标注全集 + base 指纹）而不是「标注条数变了」：同一条标注被改主意、
 * 或 base 换了一版，条数都不变但样本集变了。摘要存 App 私有 prefs —— 它丢了最多让首启多拟合一轮，
 * 而拟合是确定性的（同输入同 delta），重复一轮没有副作用。
 *
 * 门槛没到、或链路任一步失败，都只回报状态、**不写版本号**：模块端按版本号决定是否读文件，
 * 写一个它读不出东西的版本号，等于把「还没准备好」显示成「已生效」。
 */
object DeltaFitter {

    sealed interface State {
        /** 尚未下发（版本号为 0）。 */
        data object None : State

        data class NotEnough(val readiness: SpamTuner.Readiness) : State

        /**
         * 这一步做不成。原因用枚举而非成品文案：文案归资源，这一层拿不到 Locale，
         * 拼好的中文串在英文界面里也没法再翻回去。
         */
        data class Unavailable(val reason: Reason) : State

        /**
         * 已下发（[delivery] 说明这次是真推了、还是因为内容一致而没推）。
         *
         * [version] 本身是**下发时刻**、[fittedAt] 是**拟合完成时刻** —— 两者分开记正是因为有了跳过：
         * 重算（重发按钮、改标注）只让拟合时间前进，下发时间只在真的推给模块端时才动。
         * [digest] 是下发文件的内容摘要；读不到文件时是 [DeltaStamp.UNKNOWN_DIGEST]。
         */
        data class Sent(
            val version: Long,
            val weights: Int,
            val digest: String = "",
            val fittedAt: Long = 0L,
            val delivery: Delivery = Delivery.SENT,
        ) : State
    }

    /** 拟合链路各步的失败原因（`Reason` 里带详情的两项是异常信息，本身不翻译）。 */
    sealed interface Reason {
        data object ModelUnavailable : Reason

        data object ModuleDisconnected : Reason

        data object DeltaWriteFailed : Reason

        data object ConfigReadFailed : Reason

        data object VersionWriteFailed : Reason

        data class FitError(val detail: String) : Reason

        data class StateError(val detail: String) : Reason
    }

    private const val PREFS = "notifyguard_fit"
    private const val KEY_SIG = "last_signature"

    /** 拟合完成时刻。与下发时刻（版本号本体）分开存：跳过下发时只有它会前进。 */
    private const val KEY_FITTED_AT = "last_fitted_at"

    /** 显示用：文件读不到时的权重数占位（用负数，与「0 个权重」区分开）。 */
    private const val UNKNOWN_WEIGHTS = -1

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotifyGuard-fit").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** App 进程内只解析一次内置模型（262 KB，与模块端用的是同一份资源）。 */
    @Volatile private var base: SpamModel? = null
    @Volatile private var baseTried = false

    /**
     * 标注有变就重拟合并下发；没变就只回报当前状态。
     *
     * 回调在主线程，可直接更新 UI。**不在这里判 `spamEnabled`**：微调量与「AI 段是否启用」是两件事 ——
     * 用户关掉 AI 再打开，不该顺带丢掉已拟合的微调。
     *
     * @param force 无视「输入摘要没变」也要重拟合一次（界面上的「重发微调」按钮）：
     *   摘要说的是「标注与 base 都没变」，而用户此刻要的是「不管变没变，按现在这份数据再算一遍」。
     *   它**不绕过内容比对** —— 重算出来与模块端持有的一致时仍然不下发（那种下发只有版本号在动）。
     * @param reload 内容一致时也让模块端重读一次（长按「重发微调」）。它隐含 [force]：不重算就无从谈重载。
     */
    fun ensureFitted(
        ctx: Context,
        labels: List<LabelRecord>,
        force: Boolean = false,
        reload: Boolean = false,
        onDone: (State) -> Unit,
    ) {
        val appContext = ctx.applicationContext
        worker.execute {
            val state = runCatching { fitIfNeeded(appContext, labels, force || reload, reload) }
                .getOrElse { State.Unavailable(Reason.FitError("${it.javaClass.simpleName}: ${it.message}")) }
            main.post { onDone(state) }
        }
    }

    /** 不带标注的纯查询（例如只想刷新那一行状态时用）。 */
    fun status(ctx: Context, onDone: (State) -> Unit) {
        val appContext = ctx.applicationContext
        worker.execute {
            val state = runCatching {
                val model = bundledBase() ?: return@runCatching State.Unavailable(Reason.ModelUnavailable)
                currentState(appContext, model)
            }.getOrElse { State.Unavailable(Reason.StateError("${it.javaClass.simpleName}: ${it.message}")) }
            main.post { onDone(state) }
        }
    }

    /**
     * 当前 base 的指纹：标注入库时随标注一起存（[LabelRecord.modelVersion]），供换模型后筛出旧样本重标。
     *
     * 取不到时返回 `0` 而不是拒绝标注 —— 这个字段是事后筛选的线索，缺了不影响拟合；
     * 为它禁掉用户的标注入口是拿辅助信息换掉了主功能。
     */
    fun baseFingerprint(): Int = bundledBase()?.fingerprint ?: 0

    /**
     * 内置 base 的共享快照。学习屏的贡献归因要用它（归因的权重必须与判定同源），
     * 而这层已经持有唯一一份解析结果 —— 别在界面侧再解析一遍 262 KB。
     */
    fun baseModel(): SpamModel? = bundledBase()

    private fun fitIfNeeded(ctx: Context, labels: List<LabelRecord>, force: Boolean, reload: Boolean): State {
        val model = bundledBase() ?: return State.Unavailable(Reason.ModelUnavailable)
        val sig = signature(labels, model)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SIG, null) == sig) return currentState(ctx, model)

        val samples = labels.map { SpamTuner.Sample(it.key, it.text, it.spam) }
        val delta = when (val fit = SpamTuner.fit(model, samples)) {
            is SpamTuner.Fit.NotReady -> return State.NotEnough(fit.readiness)
            is SpamTuner.Fit.Ok -> fit.delta
        }
        if (!ConfigWriter.isConnected()) return State.Unavailable(Reason.ModuleDisconnected)
        val fittedAt = System.currentTimeMillis()
        val digest = DeltaStamp.digest(delta.encode())
        val weights = delta.indices.size

        // 下发前先问一句「模块端手上那份是不是就是这一份」：读回文件比对内容摘要。
        // 拟合是确定性的（同标注必得同 delta），所以摘要相同 ⇔ 这份微调已在模块端，再下发只是推进版本号、
        // 让模块端把同一份文件重读一遍。判据取**读回的文件内容**而非状态回传：状态说的是「上次加载成功」，
        // 文件才是模块端下次加载要用的那份；换过 base 时读回会因指纹不符而不是 Ok ⇒ 照旧下发（那正是该重发的情形）。
        val cfg = ConfigWriter.load() ?: return State.Unavailable(Reason.ConfigReadFailed)
        val curVersion = cfg.deltaVersion
        val existing = DeltaWriter.read(model)
        if (curVersion > 0L && existing?.parsed is SpamDelta.Parse.Ok && existing.digest == digest) {
            if (!reload) {
                rememberFit(prefs, sig, fittedAt)
                return State.Sent(curVersion, weights, digest, fittedAt, Delivery.SKIPPED)
            }
            // 强制重载：只推进版本号，不重写文件 —— 模块端认版本号变化才会重读，重写同一份字节没有意义
            val bumped = bumpedVersion(curVersion)
            if (!ConfigWriter.save(cfg.copy(deltaVersion = bumped))) {
                return State.Unavailable(Reason.VersionWriteFailed)
            }
            rememberFit(prefs, sig, fittedAt)
            return State.Sent(bumped, weights, digest, fittedAt, Delivery.RELOADED)
        }

        // 顺序是正确性的一部分：文件先落地，版本号后写（见 DeltaWriter 类注释）
        val written = DeltaWriter.write(delta) ?: return State.Unavailable(Reason.DeltaWriteFailed)
        val version = bumpedVersion(cfg.deltaVersion)
        if (!ConfigWriter.save(cfg.copy(deltaVersion = version))) return State.Unavailable(Reason.VersionWriteFailed)

        rememberFit(prefs, sig, fittedAt)
        return State.Sent(version, weights, written, fittedAt)
    }

    /**
     * 版本号与时间戳同源，同毫秒连发两轮时 +1 —— 否则模块端会把第二轮当成「版本号没变」而不加载。
     */
    private fun bumpedVersion(current: Long): Long {
        val now = System.currentTimeMillis()
        return if (current == now) now + 1 else now
    }

    /** 跳过下发时也要记：不记就每次进屏都重拟合一遍（结果一样，纯浪费）。 */
    private fun rememberFit(prefs: SharedPreferences, sig: String, fittedAt: Long) {
        prefs.edit().putString(KEY_SIG, sig).putLong(KEY_FITTED_AT, fittedAt).apply()
    }

    private fun currentState(ctx: Context, model: SpamModel): State {
        val version = ConfigWriter.load()?.deltaVersion ?: 0L
        if (version == 0L) return State.None
        val read = DeltaWriter.read(model)
        val weights = when (val parsed = read?.parsed) {
            is SpamDelta.Parse.Ok -> parsed.delta.indices.size
            else -> UNKNOWN_WEIGHTS
        }
        val fittedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_FITTED_AT, 0L)
        return State.Sent(version, weights, read?.digest ?: DeltaStamp.UNKNOWN_DIGEST, fittedAt)
    }

    private fun bundledBase(): SpamModel? {
        if (baseTried) return base
        synchronized(this) {
            if (baseTried) return base
            baseTried = true
            base = SpamModel.bundled()
        }
        return base
    }

    /**
     * 输入摘要 = 标注全集（key + 文本 + 标注） + base 指纹。
     * 不含 `at`（改一次标注时间就重拟合没有意义）与 `pkg`（文本与 key 已覆盖它的信息）。
     */
    private fun signature(labels: List<LabelRecord>, model: SpamModel): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(model.fingerprintU32.toString().toByteArray())
        for (l in labels.sortedBy { it.key }) {
            md.update(0)
            md.update(l.key.toByteArray())
            md.update(if (l.spam) 1 else 0)
            md.update(l.text.toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
