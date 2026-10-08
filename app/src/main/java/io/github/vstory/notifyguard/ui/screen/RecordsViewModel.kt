package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
import io.github.vstory.notifyguard.ui.UiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录屏的状态与动作（M4）。
 *
 * 数据源全是模块端权威、App 侧只读缓存的两份单例（[LogStore] / [LabelStore]）：进屏先渲染缓存
 * （立刻有内容），再各拉一次覆盖。拉取前把 [LogRecord.lastTs] 与标注对好再交给界面，
 * 界面上就不需要再知道「记录怎么读、标注怎么对」。
 *
 * 状态放 ViewModel 而不是 `remember`：在途标志、清空确认这类状态必须跨重组与屏幕旋转存活，
 * 否则「等回执时转屏」会让标志位复位，用户再点一下就并发发出两条指令。
 *
 * 这里只产出 [UiText]（资源号 + 参数）与原始数据，不产出成品文案：文案的解析必须发生在重组里，
 * 见 ui/UiText.kt。
 */
class RecordsViewModel : ViewModel() {

    data class UiState(
        val rows: List<RecordRow> = emptyList(),
        val groups: Int = 0,
        val rawCount: Int = 0,
        val fetching: Boolean = false,
        val fetchError: UiText? = null,
        val labelCount: Int = 0,
        val labelError: UiText? = null,
        /** 当前筛选档。 */
        val filter: Filter = Filter.All,
        /**
         * 当前档在**整个记录窗口**里的命中组数，可能大于 [rows] 的条数。
         *
         * 与 [rows] 分开：只报行数会把窗口口径说小（用户以为被拦的只有屏上这几条），
         * 只报命中数又会让人以为界面丢了行。
         */
        val matched: Int = 0,
        /** 拟合状态；null = 还没拉过（界面显示「尚未拉取」而不是「未下发」）。 */
        val fit: DeltaFitter.State? = null,
        val busy: Boolean = false,
        val notice: Notice? = null,
        /** 非空即弹二次确认，值是点下去那一刻的标注条数。 */
        val confirmClearLabels: Int? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次（连标两条）也要各弹一次。 */
    data class Notice(val id: Long, val text: UiText)

    /**
     * 判定筛选档。`arg` 是导航参数与 route 的值，取稳定小写串 —— 枚举名是要改的，导航标识不是。
     *
     * 段内标签刻意用短词（`拦截` / `本应拦` / `放行`）：四段并排在手机宽度上放不下全称，
     * 全称由状态卡的命中行给出。
     */
    enum class Filter(val arg: String, @StringRes val labelRes: Int) {
        All("all", R.string.records_filter_all),
        Block("block", R.string.records_filter_block),
        Would("would", R.string.records_filter_would),
        Pass("pass", R.string.records_filter_pass);

        companion object {

            /** 认不出的参数退「全部」：导航参数是外部输入，坏值时给一个确定的档而不是空屏。 */
            fun of(arg: String?): Filter = entries.firstOrNull { it.arg == arg } ?: All
        }
    }

    /**
     * 界面直接渲染的一行。展示用的量（时间串、判定、原因拼接）在这里算好 ——
     * 界面只管画，也免得「同一份拼接逻辑在界面里再写一遍」。
     */
    data class RecordRow(
        val key: String,
        val time: String,
        val verdict: Verdict,
        val count: Int,
        val pkg: String,
        val title: String?,
        val text: String?,
        /** 模块端回传的技术串（`ai:0.87` 这类），不翻译。 */
        val reason: String,
        /** `null` = 未标注。 */
        val marked: Boolean?,
    )

    var state by mutableStateOf(UiState())
        private set

    /** 标注动作要拿原始记录去构造 [LabelRecord]，而界面只持 [RecordRow.key]。 */
    private var records: List<LogRecord> = emptyList()

    private var noticeSeq = 0L

    /**
     * 进屏 / 点刷新：先渲染本地缓存，再并发拉记录与标注。
     *
     * 超时即结束、不自动重试（与 [LogFetcher] / [LabelClient] 同口径）：刷新按钮就在顶栏。
     */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(fetching = true, fetchError = null, labelError = null)
        repaint(app)
        LabelClient.fetch(app) { onLabels(app, it) }
        LogFetcher.fetch(app) { list ->
            state = state.copy(
                fetching = false,
                fetchError = if (list == null) TIMEOUT_HINT else null,
            )
            repaint(app)
        }
    }

    fun setFilter(ctx: Context, filter: Filter) {
        if (state.filter == filter) return
        state = state.copy(filter = filter)
        repaint(ctx.applicationContext ?: ctx)
    }

    fun clearRecords(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        LogFetcher.clear(app)
        state = state.copy(fetchError = null)
        repaint(app)
    }

    fun askClearLabels(ctx: Context) {
        val n = LabelStore.get(ctx.applicationContext ?: ctx).size()
        if (n == 0) {
            notify(UiText.Res(R.string.notice_no_labels))
            return
        }
        state = state.copy(confirmClearLabels = n)
    }

    fun dismissClearLabels() {
        state = state.copy(confirmClearLabels = null)
    }

    fun clearLabels(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(confirmClearLabels = null)
        submit(app, UiText.Res(R.string.label_action_clear)) { done -> LabelClient.clear(app, done) }
    }

    fun mark(ctx: Context, key: String, spam: Boolean) {
        val app = ctx.applicationContext ?: ctx
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        val what = if (spam) UiText.Res(R.string.label_action_mark_spam) else UiText.Res(R.string.label_action_mark_ham)
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

    fun noticeShown(n: Notice) {
        if (state.notice == n) state = state.copy(notice = null)
    }

    /**
     * 标注动作串行化：一次只允许一条指令在途。
     *
     * 广播是异步的、超时窗 5s，连点会并发发出多条；模块端按到达顺序串行落盘，而 App 侧**先到的
     * 回执**会把后点那次的结果覆盖掉 —— 界面最终留下的可能是用户最后一次没点的那个标注。
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
        // 标注就是微调的输入：改完立刻重拟合下发，用户不必再去找别的入口
        if (list != null) DeltaFitter.ensureFitted(ctx, list) { onFit(it) }
    }

    private fun onLabels(ctx: Context, list: List<LabelRecord>?) {
        state = state.copy(labelError = if (list == null) TIMEOUT_HINT else null)
        repaint(ctx)
        // 拟合按「输入摘要有没有变」自行决定要不要真跑，重复进屏不会白算
        if (list == null) {
            DeltaFitter.status(ctx) { onFit(it) }
        } else {
            DeltaFitter.ensureFitted(ctx, list) { onFit(it) }
        }
    }

    private fun onFit(fit: DeltaFitter.State) {
        state = state.copy(fit = fit)
    }

    private fun repaint(ctx: Context) {
        val store = LogStore.get(ctx)
        val all = store.all()
        // 标注索引按全窗口算：筛选档变了要重新截列表，索引在两处各算一遍就会漏掉被筛掉那批记录上的标注
        val marks = LabelRecord.marksOf(all, LabelStore.get(ctx).all())
        val hit = filteredOf(all, state.filter)
        records = hit.take(LIST_LIMIT)
        state = state.copy(
            groups = store.size(),
            rawCount = store.rawCount(),
            labelCount = LabelStore.get(ctx).size(),
            matched = hit.size,
            rows = records.map { row(it, marks[LabelRecord.keyOf(it)]) },
        )
    }

    private fun row(r: LogRecord, marked: Boolean?): RecordRow = RecordRow(
        key = LabelRecord.keyOf(r),
        // 一条记录是一组通知：时间取最近一次，首见时间对用户没有意义
        time = TIME.format(Date(r.lastTs)),
        verdict = Verdict.of(r),
        count = r.count,
        pkg = r.pkg,
        title = r.title,
        text = r.text,
        reason = listOfNotNull(r.reason, r.ruleId, r.slot).joinToString(" · "),
        marked = marked,
    )

    private fun notify(text: UiText) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /**
         * 每档在屏上列出的上限。筛选**先作用于整个窗口**再截到这里，所以命中数可以大于它。
         *
         * 聚合后一组的信息量远大于一条，20 组足够覆盖「刚才发生了什么」。
         */
        const val LIST_LIMIT = 20

        private val TIMEOUT_HINT = UiText.Res(R.string.records_fetch_timeout)

        private val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        /**
         * 取全窗口、按判定过滤、再按最近活跃排序。
         *
         * 顺序不可颠倒：先截断再筛时，「拦截」档只覆盖最近若干组里恰好被拦的那几条，
         * 窗口里更早被拦的条目永远进不了这个档 —— 而档名叫「拦截」。
         */
        fun filteredOf(records: List<LogRecord>, filter: Filter): List<LogRecord> =
            records.filter { matches(it, filter) }.sortedByDescending { it.lastTs }

        private fun matches(r: LogRecord, filter: Filter): Boolean = when (filter) {
            Filter.All -> true
            Filter.Block -> r.block
            Filter.Would -> r.would && !r.block
            Filter.Pass -> !r.block && !r.would
        }
    }
}
