package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
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
 */
class RecordsViewModel : ViewModel() {

    data class UiState(
        val rows: List<RecordRow> = emptyList(),
        val groups: Int = 0,
        val rawCount: Int = 0,
        val fetching: Boolean = false,
        val fetchError: String? = null,
        val labelCount: Int = 0,
        val labelError: String? = null,
        val fitText: String? = null,
        val busy: Boolean = false,
        val notice: Notice? = null,
        /** 非空即弹二次确认，值是点下去那一刻的标注条数。 */
        val confirmClearLabels: Int? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次（连标两条）也要各弹一次。 */
    data class Notice(val id: Long, val text: String)

    enum class Verdict { Block, Would, Pass }

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

    fun clearRecords(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        LogFetcher.clear(app)
        state = state.copy(fetchError = null)
        repaint(app)
    }

    fun askClearLabels(ctx: Context) {
        val n = LabelStore.get(ctx.applicationContext ?: ctx).size()
        if (n == 0) {
            notify("当前没有标注")
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
        submit(app, "清空标注") { done -> LabelClient.clear(app, done) }
    }

    fun mark(ctx: Context, key: String, spam: Boolean) {
        val app = ctx.applicationContext ?: ctx
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        submit(app, if (spam) "标为垃圾" else "标为正常") { done ->
            LabelClient.set(
                app,
                LabelRecord.of(r, spam, System.currentTimeMillis(), DeltaFitter.baseFingerprint()),
                done,
            )
        }
    }

    fun undo(ctx: Context, key: String) {
        val app = ctx.applicationContext ?: ctx
        submit(app, "撤销标注") { done -> LabelClient.delete(app, key, done) }
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
    private fun submit(ctx: Context, what: String, call: ((List<LabelRecord>?) -> Unit) -> Unit) {
        if (state.busy) {
            notify("上一个标注动作还在等回执")
            return
        }
        state = state.copy(busy = true)
        call { list -> onLabelResult(ctx, what, list) }
    }

    private fun onLabelResult(ctx: Context, what: String, list: List<LabelRecord>?) {
        state = state.copy(busy = false)
        // 没有回执时 LabelClient 不动本地缓存 ⇒ 重绘后显示的仍是原状态，界面与权威源仍一致
        notify(
            if (list == null) {
                "$what 未生效：模块端没有回执（模块未激活 / 装完还没重启过系统框架 / labels.json 损坏）"
            } else {
                "$what 已生效（共 ${list.size} 条标注）"
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
        state = state.copy(fitText = DeltaFitter.describe(fit))
    }

    private fun repaint(ctx: Context) {
        val store = LogStore.get(ctx)
        records = store.recent(RECENT_LIMIT)
        val marks = LabelRecord.marksOf(records, LabelStore.get(ctx).all())
        state = state.copy(
            groups = store.size(),
            rawCount = store.rawCount(),
            labelCount = LabelStore.get(ctx).size(),
            rows = records.map { row(it, marks[LabelRecord.keyOf(it)]) },
        )
    }

    private fun row(r: LogRecord, marked: Boolean?): RecordRow = RecordRow(
        key = LabelRecord.keyOf(r),
        // 一条记录是一组通知：时间取最近一次，首见时间对用户没有意义
        time = TIME.format(Date(r.lastTs)),
        verdict = when {
            r.block -> Verdict.Block
            r.would -> Verdict.Would
            else -> Verdict.Pass
        },
        count = r.count,
        pkg = r.pkg,
        title = r.title,
        text = r.text,
        reason = listOfNotNull(r.reason, r.ruleId, r.slot).joinToString(" · "),
        marked = marked,
    )

    private fun notify(text: String) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /** 列表上限：聚合后一组的信息量远大于一条，20 组足够覆盖「刚才发生了什么」。 */
        const val RECENT_LIMIT = 20

        const val TIMEOUT_HINT =
            "拉取超时：模块未激活或装完还没重启过系统框架（数据本身没丢，仍在模块端的 /data/misc/notifyguard 下）"

        private val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }
}
