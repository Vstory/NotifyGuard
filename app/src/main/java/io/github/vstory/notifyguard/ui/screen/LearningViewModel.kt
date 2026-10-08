package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ai.SpamAttribution
import io.github.vstory.notifyguard.ai.SpamDelta
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.DeltaWriter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
import io.github.vstory.notifyguard.ui.UiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 学习屏的状态与动作（M4h）：端侧学习这条链路的**可解释面**。
 *
 * 四块卡各自的用途不同，别把它们并成一个列表：拟合状态（这条链路走到哪一步）、待处理（被拦但还没标，
 * 也就是误杀回溯的入口）、有 AI 判定的记录（回看「为什么判成这样」，可展开看高亮）、孤儿标注
 * （记录窗口裁掉后仍在库里、否则用户永远删不掉的标注）。
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
        val labelTotal: Int = 0,
        val pending: List<Row> = emptyList(),
        val aiRows: List<Row> = emptyList(),
        val orphans: List<OrphanRow> = emptyList(),
        /** 展开中的那条；null = 都收着。 */
        val openKey: String? = null,
        val detail: Detail? = null,
        val busy: Boolean = false,
        val notice: Notice? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次也要各弹一次。 */
    data class Notice(val id: Long, val text: UiText)

    /** 记录侧的一行（待处理卡与 AI 卡共用：行长相一样，只是动作与展开行为不同）。 */
    data class Row(
        val key: String,
        val time: String,
        val pkg: String,
        val title: String?,
        val text: String?,
        /** 模块端回传的技术串（`ai:0.87` 这类），不翻译。 */
        val reason: String,
        val score: Double?,
        /** `null` = 未标注。 */
        val marked: Boolean?,
        /** 判定实际用过的文本（[LogRecord.aiText]）；null = 这条记录早于该字段，展开也没有高亮可看。 */
        val aiText: String?,
    )

    /** 孤儿标注一行。没有对应记录，所以给不出判定与分数。 */
    data class OrphanRow(val key: String, val time: String, val pkg: String, val text: String)

    /**
     * 展开一条后的归因状态。
     *
     * 分三态而不是 `Detail?`：`null` 同时被用来表示「还没算完」与「算不出来」，界面就只能二选一地说，
     * 两种说法在另一种情形下都是错的（说「正在算」会一直转，说「算不了」则是假话）。
     */
    sealed interface Detail {
        data object Computing : Detail

        /** 内置模型读不出（资源缺失 / 解析失败）：没有权重就没有归因，只能明说。 */
        data object NoModel : Detail

        data class Ready(
            val text: String,
            val score: Double?,
            /** 权重是否含已下发的微调量；false = 读不到 delta，降级为纯 base（界面必须说出来）。 */
            val tuned: Boolean,
            val result: SpamAttribution.Result,
        ) : Detail
    }

    var state by mutableStateOf(UiState())
        private set

    /** 标注动作要拿原始记录构造 [LabelRecord]，而界面只持 [Row.key]。 */
    private var records: List<LogRecord> = emptyList()

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

    fun mark(ctx: Context, key: String, spam: Boolean) {
        val app = ctx.applicationContext ?: ctx
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        val what = if (spam) {
            UiText.Res(R.string.label_action_mark_spam)
        } else {
            UiText.Res(R.string.label_action_mark_ham)
        }
        submit(app, what) { done ->
            LabelClient.set(
                app,
                LabelRecord.of(r, spam, System.currentTimeMillis(), DeltaFitter.baseFingerprint()),
                done,
            )
        }
    }

    fun undo(ctx: Context, key: String) {
        val app = ctx.applicationContext ?: ctx
        submit(app, UiText.Res(R.string.label_action_undo)) { done -> LabelClient.delete(app, key, done) }
    }

    /**
     * 展开 / 收起某条的高亮。
     *
     * 只在 [Row.aiText] 上归因：拿被截断到 200 字的展示文本去高亮，画的是一段判定根本没用过的文字。
     * 没有该字段（M2 后期才有）时不置 [UiState.detail]，界面据 [Row.aiText] 为 null 说「没有可高亮的文本」。
     */
    fun toggleDetail(key: String) {
        if (state.openKey == key) {
            state = state.copy(openKey = null, detail = null)
            return
        }
        val row = state.aiRows.firstOrNull { it.key == key } ?: return
        val text = row.aiText
        state = state.copy(openKey = key, detail = if (text == null) null else Detail.Computing)
        if (text == null) return
        worker.execute {
            val detail = runCatching { attribute(row, text) }.getOrNull() ?: Detail.NoModel
            main.post {
                // 用户可能已经点了别的行：只认此刻仍展开的那条
                if (state.openKey == key) state = state.copy(detail = detail)
            }
        }
    }

    fun noticeShown(n: Notice) {
        if (state.notice == n) state = state.copy(notice = null)
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

    private fun onLabels(ctx: Context, list: List<LabelRecord>?) {
        state = state.copy(labelError = if (list == null) TIMEOUT_HINT else null)
        repaint(ctx)
        if (list == null) {
            DeltaFitter.status(ctx) { onFit(it) }
        } else {
            DeltaFitter.ensureFitted(ctx, list) { onFit(it) }
        }
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
     * 与判定同源的权重：base 基权重叠加当前已下发的 delta（与 [io.github.vstory.notifyguard.ai.TunedScorer]
     * 同构）。读不到 delta 时退 base-only —— 界面据此明示降级，绝不假装与实际判定一致。
     */
    private fun attribute(row: Row, text: String): Detail.Ready? {
        val model = DeltaFitter.baseModel() ?: return null
        val delta = (DeltaWriter.read(model) as? SpamDelta.Parse.Ok)?.delta
        // 稀疏表：归因只碰文本里出现过的桶，摊成 2^18 长的稠密数组是白分配 1 MB
        val extra: HashMap<Int, Float>? = delta?.let { d ->
            HashMap<Int, Float>(d.indices.size).also { m ->
                for (i in d.indices.indices) m[d.indices[i]] = d.values[i]
            }
        }
        val weightOf: (Int) -> Double = { k ->
            model.baseWeight(k).toDouble() + (extra?.get(k)?.toDouble() ?: 0.0)
        }
        return Detail.Ready(
            text = text,
            score = row.score,
            tuned = extra != null,
            result = SpamAttribution.attribute(text, model.buckets, model.ngramMin, model.ngramMax, weightOf),
        )
    }

    private fun repaint(ctx: Context) {
        val store = LogStore.get(ctx)
        val labels = LabelStore.get(ctx).all()
        records = store.all()
        // 标注索引算一次给所有行与所有卡用：每行各构造一遍是 O(行 × 标注数)，标注上限 5000 条
        val marks = LabelRecord.marksOf(records, labels)
        state = state.copy(
            labelTotal = labels.size,
            pending = pendingOf(records, marks).take(LIST_LIMIT).map { row(it, marks) },
            aiRows = aiRowsOf(records).take(LIST_LIMIT).map { row(it, marks) },
            orphans = orphansOf(records, labels).take(LIST_LIMIT).map { orphan(it) },
        )
    }

    private fun row(r: LogRecord, marks: Map<String, Boolean>): Row = Row(
        key = LabelRecord.keyOf(r),
        // 一条记录是一组通知：时间取最近一次（与记录屏同口径）
        time = TIME.format(Date(r.lastTs)),
        pkg = r.pkg,
        title = r.title,
        text = r.text,
        reason = listOfNotNull(r.reason, r.ruleId).joinToString(" · "),
        score = r.score,
        marked = marks[LabelRecord.keyOf(r)],
        aiText = r.aiText,
    )

    private fun orphan(l: LabelRecord): OrphanRow = OrphanRow(
        key = l.key,
        // 标注时间：孤儿卡要说的是「这条什么时候标的」，不是通知什么时候来的
        time = TIME.format(Date(l.at)),
        pkg = l.pkg,
        text = l.text,
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
         * 「AI 段真的跑过」的判据用 [LogRecord.score] 而不是解析 `reason` 前缀：
         * 判定链里只有 AI 打分那两条分支会写这个字段，而分数本身也要原样展示（`reason` 里只有两位小数）。
         */
        fun hasAiVerdict(r: LogRecord): Boolean = r.score != null

        /** 待处理 = 被拦下但还没表态的：误杀回溯要找的正是它们。 */
        fun pendingOf(records: List<LogRecord>, marks: Map<String, Boolean>): List<LogRecord> =
            records.sortedByDescending { it.lastTs }.filter {
                it.block && marks[LabelRecord.keyOf(it)] == null
            }

        fun aiRowsOf(records: List<LogRecord>): List<LogRecord> =
            records.sortedByDescending { it.lastTs }.filter(::hasAiVerdict)

        /**
         * 孤儿标注 = 记录窗口里已经找不到的标注。
         *
         * 记录会裁剪（[LogStore.MAX_RECORDS] 组），标注不会（它是用户的手工劳动，模块端保留）。
         * 不单列出来，用户就永远删不掉一条不再出现在记录里的标注。
         * `at` 相同时按 key 兜底，否则同一份数据两次进屏可能给出两种顺序。
         */
        fun orphansOf(records: List<LogRecord>, labels: List<LabelRecord>): List<LabelRecord> {
            val live = records.mapTo(HashSet()) { LabelRecord.keyOf(it) }
            return labels.filter { it.key !in live }
                .sortedWith(compareByDescending<LabelRecord> { it.at }.thenBy { it.key })
        }
    }
}
