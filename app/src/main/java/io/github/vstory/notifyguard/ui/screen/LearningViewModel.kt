package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.SpamDelta
import io.github.vstory.notifyguard.ai.SpamTuner
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.DeltaWriter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
import io.github.vstory.notifyguard.ui.ReasonExplanation
import io.github.vstory.notifyguard.ui.LabelGate
import io.github.vstory.notifyguard.ui.UiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 学习屏的状态与动作（M4h）：端侧学习这条链路的**可解释面**。
 *
 * 三栏各自独立滚动，不并成一条长页：样本库有几千条，和待处理、微调挤在同一个滚动容器里就是滚到死。
 *
 * - 待处理：被拦但还没表态的，误杀回溯的入口；
 * - 样本库：**全部**样本（不分来源、不分时间、不分是否还有对应记录），按来源 App 收组折叠 —— 同一
 *   个 App 的垃圾往往几十上百条，平铺只能一条条滚过去；栏内可搜索、可筛选、可改判、可删除。它也是
 *   **唯一**能删掉一条标注的地方（记录窗口会裁掉老记录，那些样本在记录屏里再也找不到）；
 * - 微调：这条链路走到哪一步。
 *
 * 「孤儿」不再单列：它只是「这条样本目前没有对应记录」这个实现细节，样本库全量列出之后，用户既看
 * 不到也不需要这个概念。
 *
 * 数据源与记录屏一样：全是模块端权威、App 侧只读缓存的 [LogStore] / [LabelStore]，进屏先渲染缓存
 * 再各拉一次。标注动作沿用记录屏的口径（串行化 + 无回执不改本地状态），两个入口的语义不互相覆盖：
 * 记录屏是「标这一条」，学习屏是「回看与修正」。
 *
 * 归因在后台线程按需算（展开哪条算哪条）：它要遍历整条文本的 n-gram，进屏就全算是白烧电。
 */
class LearningViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: UiText = UiText.Res(R.string.service_checking),
        val cfgLoaded: Boolean = false,
        val fetching: Boolean = false,
        val fetchError: UiText? = null,
        val labelError: UiText? = null,
        /** 拟合状态；null = 还没拉过。 */
        val fit: DeltaFitter.State? = null,
        /** 门槛进度（可训练样本数与两类各自的量）；null = 还没拉过或内置 base 不可用。 */
        val fitProgress: SpamTuner.Readiness? = null,
        val labelTotal: Int = 0,
        val tab: Tab = Tab.Library,
        val pending: List<Row> = emptyList(),
        /** 样本库：按来源 App 收好的组（筛选与搜索已作用其上）。 */
        val libGroups: List<Group> = emptyList(),
        /** 包名 → App 名（分组名与搜索都用它）。在 [repaint] 里解析一次，重分组时只查这张表。 */
        val appNames: Map<String, String> = emptyMap(),
        /** 全库的垃圾条数（筛选按钮上的数字用它，不能用过滤后的组算）。 */
        val libSpam: Int = 0,
        val libQuery: String = "",
        val libFilter: LibFilter = LibFilter.All,
        /** 搜索态：搜索框占顶栏，与记录屏同一套交互（同一份 [SearchTopBar]）。 */
        val libSearchOpen: Boolean = false,
        /** 展开中的组。 */
        val openGroups: Set<String> = emptySet(),
        /** 每组已展开的条数（键是包名）；缺省即只显示 [GROUP_PAGE] 条。 */
        val groupShown: Map<String, Int> = emptyMap(),
        /** 非空即弹某条记录的判定说明（与记录屏共用同一份说明件）。 */
        val explanation: ReasonExplanation? = null,
        val busy: Boolean = false,
        /** 「重发微调」在途：按钮据此置灰，避免连点堆多轮拟合。 */
        val fitBusy: Boolean = false,
        val notice: Notice? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次也要各弹一次。 */
    data class Notice(val id: Long, val text: UiText)

    /**
     * 记录侧的一行（待处理卡与 AI 卡共用：行长相一样，只是动作与展开行为不同）。
     */
    data class Row(
        val key: String,
        val time: String,
        val verdict: Verdict,
        val pkg: String,
        val title: String?,
        val text: String?,
        /**
         * 底部那行：原因短语与判定槽（都是人话，槽位与设置页同一套文案）。
         *
         * 原始技术串不在这儿 —— 这行是给用户读的，解释与原文都在 [UiState.explanation] 里。
         */
        val meta: List<UiText>,
        val score: Double?,
        /** `null` = 未标注。 */
        val marked: Boolean?,
        /** 判定实际用过的文本（[LogRecord.aiText]）；null = 这条记录早于该字段，展开也没有高亮可看。 */
        val aiText: String?,
    )

    enum class Tab { Pending, Library, Fit }

    enum class LibFilter { All, Spam, Ham }

    /** 样本库的一组：一个来源 App 底下的全部样本（已排序、已格式化，界面直接画）。 */
    data class Group(
        val pkg: String,
        val name: String,
        val rows: List<SampleRow>,
    )

    /**
     * 样本库的一行。
     *
     * [spam] 必须带上：样本库是**唯一能改判与删除标注的地方**，两个动作都要先知道这条标在哪个方向
     * —— 改判前不给方向，那两个动作就没有互斥的依据（按同义的一次等于白跑一趟广播）。
     */
    data class SampleRow(
        val key: String,
        val time: String,
        val text: String,
        val spam: Boolean,
    )

    var state by mutableStateOf(UiState())
        private set

    /** 标注动作要拿原始记录构造 [LabelRecord]，而界面只持 [Row.key] / [SampleRow.key]。 */
    private var records: List<LogRecord> = emptyList()

    /** 改判的来源：记录里已经找不到的条目，只能拿库里那份标注翻方向。 */
    private var labels: List<LabelRecord> = emptyList()

    /**
     * App 名缓存（样本库的组名与搜索都用它）。`getApplicationInfo` 是 Binder 调用，列表每次重绘都问
     * 一遍包名会把主线程拖住；样本几千条也就几十个包名，缓存起来是一次性的开销。
     */
    private val appNames = HashMap<String, String>()

    private fun appName(ctx: Context, pkg: String): String = appNames.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private var noticeSeq = 0L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NotifyGuard-learning").apply { isDaemon = true }
    }

    /** Binder 线程回调不能直接写 Compose 状态，一律转主线程（与设置屏 / 首页同口径）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 订阅框架服务状态；返回注销句柄，界面离开时务必调用。 */
    fun bind(): () -> Unit = ConfigWriter.observe { svc -> main.post { onService(svc) } }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(fetching = true, fetchError = null, labelError = null)
        repaint(app)
        LabelClient.fetch(app) { onLabels(app, it) }
        LogFetcher.fetch(app) { list ->
            state = state.copy(fetching = false, fetchError = if (list == null) TIMEOUT_HINT else null)
            repaint(app)
        }
    }

    /**
     * 标注 / 改判。两种目标（有记录、只有标注）由 [labelFor] 统一解析，界面不必先判断这条有没有记录。
     */
    fun mark(ctx: Context, key: String, spam: Boolean) {
        val app = ctx.applicationContext ?: ctx
        // 记录屏把按钮置灰了，这里再拦一道：学习屏这几块卡的行范围一旦放宽（待办从「被拦」改成「全部」），
        // 保护条目就会顺着同一个入口漏进来
        val blocked = records.firstOrNull { LabelRecord.keyOf(it) == key }
            ?.let { LabelGate.blockedLabel(it, ConfigWriter.load()?.protect) }
        if (blocked != null) {
            notify(UiText.Res(R.string.records_label_blocked, listOf(blocked)))
            return
        }
        val label = labelFor(
            records,
            labels,
            key,
            spam,
            System.currentTimeMillis(),
            DeltaFitter.baseFingerprint(),
        ) ?: return
        val what = if (spam) {
            UiText.Res(R.string.label_action_mark_spam)
        } else {
            UiText.Res(R.string.label_action_mark_ham)
        }
        submit(app, what) { done -> LabelClient.set(app, label, done) }
    }

    fun undo(ctx: Context, key: String) {
        val app = ctx.applicationContext ?: ctx
        submit(app, UiText.Res(R.string.label_action_undo)) { done -> LabelClient.delete(app, key, done) }
    }

    /** 删掉一条样本（样本库那个唯一的删除入口）。 */
    fun deleteSample(ctx: Context, key: String) {
        val app = ctx.applicationContext ?: ctx
        submit(app, UiText.Res(R.string.learning_sample_delete)) { done -> LabelClient.delete(app, key, done) }
    }

    fun setTab(tab: Tab) {
        // 搜索态只属于样本库：切走再切回来看到搜索框还开着、而栏里是别的栏，是个说不通的界面
        state = if (tab == Tab.Library) state.copy(tab = tab) else state.copy(tab = tab, libSearchOpen = false)
    }

    /**
     * 筛选 / 搜索变更后**必须**走这里重算样本库。
     *
     * 只 `copy` 状态是不够的：[UiState.libGroups] 是算好的快照，不重算就等于筛选只换了按钮的高亮，
     * 列表仍是上一次的结果 —— 界面上表现为「点了筛选没反应」。
     */
    private fun refreshLibrary() {
        state = state.copy(
            libGroups = libGroupsOf(labels, state.libFilter, state.libQuery, state.appNames),
        )
    }

    fun setLibSearchOpen(open: Boolean) {
        state = state.copy(libSearchOpen = open, libQuery = if (open) state.libQuery else "")
        if (!open) refreshLibrary()
    }

    fun setLibQuery(query: String) {
        state = state.copy(libQuery = query)
        refreshLibrary()
    }

    fun setLibFilter(filter: LibFilter) {
        state = state.copy(libFilter = filter)
        refreshLibrary()
    }

    fun toggleGroup(pkg: String) {
        val open = state.openGroups
        state = if (pkg in open) {
            // 收起时把「展开更多」也复位：下次点开看到的还是前几条，而不是上次翻到的位置
            state.copy(openGroups = open - pkg, groupShown = state.groupShown - pkg)
        } else {
            state.copy(openGroups = open + pkg)
        }
    }

    fun showMoreInGroup(pkg: String) {
        state = state.copy(groupShown = state.groupShown + (pkg to (state.groupShown[pkg] ?: GROUP_PAGE) + GROUP_PAGE))
    }

    fun noticeShown(n: Notice) {
        if (state.notice == n) state = state.copy(notice = null)
    }

    /**
     * 点开行底部那行元数据：讲清这条为什么这么判。
     *
     * 与记录屏同一份说明件 —— 同一条记录在两屏点开必须是同一段话。展开归因（[UiState.openKey]）与
     * 弹窗各管各的：关弹窗不该把展开态一起收掉。
     */
    fun askExplain(key: String) {
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        state = state.copy(explanation = ReasonExplanation.of(r))
    }

    fun dismissExplain() {
        state = state.copy(explanation = null)
    }

    /**
     * 标注动作串行化：一次只允许一条指令在途（同 [RecordsViewModel]）。
     * 广播异步、超时窗 5s，连点会并发发出多条，而 App 侧**先到的回执**会把后点那次的结果覆盖掉。
     */
    private fun submit(ctx: Context, what: UiText, call: ((List<LabelRecord>?) -> Unit) -> Unit) {
        if (state.busy) {
            notify(UiText.Res(R.string.notice_label_busy))
            return
        }
        state = state.copy(busy = true)
        call { list -> onLabelResult(ctx, what, list) }
    }

    private fun onLabelResult(ctx: Context, what: UiText, list: List<LabelRecord>?) {
        state = state.copy(busy = false)
        // 没有回执时 LabelClient 不动本地缓存 ⇒ 重绘后显示的仍是原状态，界面与权威源仍一致
        notify(
            if (list == null) {
                UiText.Res(R.string.notice_label_failed, listOf(what))
            } else {
                UiText.Res(R.string.notice_label_done, listOf(what, list.size))
            },
        )
        repaint(ctx)
        // 标注就是微调的输入：改完立刻重拟合下发（重复进屏不会白算，拟合按输入摘要决定要不要真跑）
        if (list != null) DeltaFitter.ensureFitted(ctx, list) { onFit(it) }
    }

    /**
     * 「重发微调」：不看输入摘要变没变，按**权威源**的标注重新拟合、重新下发（样本门槛照旧）。
     *
     * 不拿界面手上那份列表去拟合：它可能比标注库旧，而重发的全部意义就是「用现在这份数据再算一遍」。
     * 取不到标注时不下发 —— 拿一份空列表拟合等于把微调清空。
     */
    fun resendFit(ctx: Context) = refit(ctx)

    private fun refit(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(fitBusy = true)
        LabelClient.fetch(app) { list ->
            state = state.copy(fitBusy = false)
            if (list == null) {
                notify(UiText.Res(R.string.notice_label_failed, listOf(UiText.Res(R.string.learning_fit_resend))))
                return@fetch
            }
            DeltaFitter.progress(app, list) { state = state.copy(fitProgress = it) }
            DeltaFitter.ensureFitted(app, list, force = true) { fit ->
                onFit(fit)
                fitNotice(fit)?.let { notify(it) }
            }
        }
    }

    private fun onLabels(ctx: Context, list: List<LabelRecord>?) {
        state = state.copy(labelError = if (list == null) TIMEOUT_HINT else null)
        repaint(ctx)
        if (list == null) {
            DeltaFitter.status(ctx) { onFit(it) }
        } else {
            DeltaFitter.ensureFitted(ctx, list) { onFit(it) }
        }
        // 进度单独算：拟合成功后就拿不到 Fit.NotReady 的门槛数了，而那正是用户最想看见「还差几条」的时候
        if (list != null) DeltaFitter.progress(ctx, list) { state = state.copy(fitProgress = it) }
    }

    private fun onFit(fit: DeltaFitter.State) {
        state = state.copy(fit = fit)
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = serviceTextOf(svc),
            cfgLoaded = svc != null && ConfigWriter.load() != null,
        )
    }

    /**
     * 本次开机的墙钟时刻（`now - 开机以来毫秒`）。用墙钟而非 [SystemClock]：记录的时间戳来自
     * 判定侧的 `System.currentTimeMillis()`，两者只有在同一条时间轴上才能比较。
     */
    private val bootWallClock: Long get() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun repaint(ctx: Context) {
        val store = LogStore.get(ctx)
        val labels0 = LabelStore.get(ctx).all()
        records = store.all()
        labels = labels0
        // 标注索引算一次给所有行与所有卡用：每行各构造一遍是 O(行 × 标注数)，标注上限 5000 条
        val marks = LabelRecord.marksOf(records, labels0)
        state = state.copy(
            labelTotal = labels0.size,
            pending = pendingOf(records, marks, bootWallClock).take(LIST_LIMIT).map { row(it, marks) },
            libSpam = labels0.count { it.spam },
            appNames = labels0.map { it.pkg }.distinct().associateWith { appName(ctx, it) },
        )
        refreshLibrary()
    }

    private fun row(r: LogRecord, marks: Map<String, Boolean>): Row = Row(
        key = LabelRecord.keyOf(r),
        // 一条记录是一组通知：时间取最近一次（与记录屏同口径）
        time = TIME.format(Date(r.lastTs)),
        verdict = Verdict.of(r),
        pkg = r.pkg,
        title = r.title,
        text = r.text,
        // 判定元数据出人话（与记录屏同一份实现），界面只管画
        meta = ReasonExplanation.meta(r),
        score = r.score,
        marked = marks[LabelRecord.keyOf(r)],
        aiText = r.aiText,
    )
    private fun notify(text: UiText) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /** 每块卡的上限：这一屏是「回看与处理待办」，不是记录列表（全量列表在记录屏）。 */
        const val LIST_LIMIT = 10

        private val TIMEOUT_HINT = UiText.Res(R.string.records_fetch_timeout)

        private val TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        /**
         * 待处理 = 被拦下、还没表态、且发生在 [since] 之后的：误杀回溯要找的正是它们。
         *
         * 被拦而用户没表态的条目**默认就是「拦得对」**—— 它已经被模型记成垃圾，用户没有要补充的
         * 信息，所以不该被逐条确认（那会把这张卡永远堵住，也把确认当成了标注）。
         *
         * 清空界限取「本次开机」而不是超时：超时会让「昨天没顾上看」的误杀今天再也找不到；
         * 开机是用户自己会经历的一条界（更早的记录仍可在记录屏标注，只是不再占这张待办卡）。
         */
        fun pendingOf(records: List<LogRecord>, marks: Map<String, Boolean>, since: Long): List<LogRecord> =
            records.sortedByDescending { it.lastTs }.filter {
                it.block && it.lastTs >= since && marks[LabelRecord.keyOf(it)] == null
            }

        /**
         * 「这个 key 该发出去的标注」：记录还在窗口里就按记录派生，只在标注库里（孤儿）就翻转原来那条。
         *
         * 两条来源收进同一个函数，是为了让界面不判断「这条有没有记录」—— 判断落在界面上时，
         * 漏判的那一侧点下去**静默无动作**（没有通知、没有日志、状态也不变），是最难查的一类失效。
         *
         * 改判只翻 [LabelRecord.spam]，`key`/`ts`/`pkg`/`text` 逐字保留：`key` 是 upsert 的身份，
         * 换了它库里会同时留下方向相反的两条样本（而界面只看得到一条）；`ts` 是组的首见时间；
         * `text` 在记录被裁掉后是仅存的那份训练文本。
         *
         * `at` 与 `modelVersion` 刷新是有意的：`at` 是淘汰依据（保留旧值会让刚表过态的样本继续排在
         * 淘汰队首），`modelVersion` 是样本产生时的 base 指纹（保留旧值会让「筛出旧样本重标」的清单
         * 永远清不掉这条）。
         */
        fun labelFor(
            records: List<LogRecord>,
            labels: List<LabelRecord>,
            key: String,
            spam: Boolean,
            at: Long,
            modelVersion: Int,
        ): LabelRecord? {
            records.firstOrNull { LabelRecord.keyOf(it) == key }?.let {
                return LabelRecord.of(it, spam, at, modelVersion)
            }
            return labels.firstOrNull { it.key == key }
                ?.copy(spam = spam, at = at, modelVersion = modelVersion)
        }

        /** 每组默认展开几条：再多就得让位给「展开更多」，否则折叠等于没折。 */
        const val GROUP_PAGE = 8

        /**
         * 样本库的分组：按来源 App 收组，组内按标注时间倒序，组间按条数降序（哪个 App 在灌，一眼看到）。
         *
         * 过滤与搜索放在分组**之前**：先收组再筛，组头上的条数就会与实际展开的内容对不上。
         * `nameOf` 由调用方注入（要读 App 名就得有 Context），这样这层仍是纯函数、可单测。
         */
        internal fun libGroupsOf(
            labels: List<LabelRecord>,
            filter: LibFilter,
            query: String,
            names: Map<String, String>,
        ): List<Group> {
            // 解析不出 App 名的（已卸载等）回落包名：组名总得有个能读的东西
            val nameOf: (String) -> String = { names[it] ?: it }
            val k = query.trim().lowercase()
            return labels
                .filter {
                    when (filter) {
                        LibFilter.All -> true
                        LibFilter.Spam -> it.spam
                        LibFilter.Ham -> !it.spam
                    }
                }
                .filter { k.isEmpty() || (it.text + " " + it.pkg + " " + nameOf(it.pkg)).lowercase().contains(k) }
                .sortedWith(compareByDescending<LabelRecord> { it.at }.thenBy { it.key })
                .groupBy { it.pkg }
                .map { (pkg, list) ->
                    Group(
                        pkg = pkg,
                        name = nameOf(pkg),
                        rows = list.map {
                            SampleRow(
                                key = it.key,
                                // 标注时间：这一栏说的是「这条什么时候标的」
                                time = TIME.format(Date(it.at)),
                                text = it.text,
                                spam = it.spam,
                            )
                        },
                    )
                }
                .sortedWith(compareByDescending<Group> { it.rows.size }.thenBy { it.pkg })
        }
    }
}
