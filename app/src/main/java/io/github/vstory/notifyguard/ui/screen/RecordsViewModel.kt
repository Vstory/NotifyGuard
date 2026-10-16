package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.judge.ProtectSwitches
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
import io.github.vstory.notifyguard.ui.UiText
import io.github.vstory.notifyguard.ui.LabelGate
import io.github.vstory.notifyguard.ui.ReasonExplanation
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
        /** 当前筛选档；进屏取哪一档由 [RecordsFilterMemory] 决定。 */
        val filter: Filter = Filter.All,
        /**
         * 搜索词；空 = 不过滤。
         *
         * 刻意不进 [RecordsFilterMemory]：退出搜索会连词一起清掉，而跨进屏留着一个词、搜索框却不在
         * 屏幕上时，再现的就是「列表被静默过滤」——档位可以记住（界面上看得见），搜索词不行。
         */
        val query: String = "",
        /** 顶栏处于搜索态（输入框占住标题位）。 */
        val searchOpen: Boolean = false,
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
        /** 非空即弹某条记录的判定说明。 */
        val explanation: ReasonExplanation? = null,
        /** 弹窗里的归因；`null` = 还没点「看高亮」。 */
        val attribution: AttributionState? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次（连标两条）也要各弹一次。 */
    data class Notice(val id: Long, val text: UiText)

    /**
     * 判定筛选档。
     *
     * 段内标签刻意用短词（`拦截` / `放行`）：全称由状态卡的命中行给出。
     */
    enum class Filter(@StringRes val labelRes: Int) {
        All(R.string.records_filter_all),
        Block(R.string.records_filter_block),
        Pass(R.string.records_filter_pass),
    }

    /**
     * 界面直接渲染的一行。展示用的量（时间串、判定、原因短语）在这里算好 ——
     * 界面只管画，也免得「同一份拼接逻辑在界面里再写一遍」。
     */
    data class RecordRow(
        /**
         * 内容身份：标注 / 撤销 / 解释都作用在它上面（同一段文本换个 App 推来算同一条样本）。
         */
        val key: String,
        /**
         * `LazyColumn` 的键，**只能用这个**。
         *
         * [key] 不含包名、也不含判定，而列表的聚合键含这两者 ⇒ 同一段文案被两个 App 推来、或同一 App
         * 下同内容两种判定，都会出现两行同 [key] 的记录。拿它当列表键，LazyColumn 会直接抛
         * 「Key was already used」把整个进程带崩（Android 16 上实测）。
         */
        val listKey: String,
        val time: String,
        val verdict: Verdict,
        val count: Int,
        val pkg: String,
        val title: String?,
        val text: String?,
        /**
         * 底部那行：原因短语与判定槽（都是人话，槽位与设置页同一套文案）。
         *
         * 原始技术串不在这儿 —— 这行是给用户读的，解释与原文都在 [Explanation] 里。
         */
        val meta: List<UiText>,
        /** `null` = 未标注。 */
        val marked: Boolean?,
        /**
         * 非空 = 这条命中保护类型且该保护仍开着 ⇒ 禁止标注，值是保护类型的人话（提示用）。
         * 保护出口的通知不进规则与 AI，标了只会给微调喂噪音。
         */
        val labelBlocked: UiText? = null,
        /** 非空 = 这条通知带自定义布局，可给「显示原始通知样式」入口（见 [RemoteViewBlock]）。 */
        val contentView: String? = null,
    )

    /**
     * 档位取 [RecordsFilterMemory]，而不是 [UiState] 的默认值：本 ViewModel 会随导航被销毁（从别的
     * 板块切回记录屏就是一次重建），每次重建都算一次进屏，档位要跟着回来。
     */
    var state by mutableStateOf(UiState(filter = RecordsFilterMemory.take()))
        private set

    /** 标注动作要拿原始记录去构造 [LabelRecord]，而界面只持 [RecordRow.key]。 */
    private var records: List<LogRecord> = emptyList()

     /**
      * 归因在后台算（冷路径），结果一律转主线程写 Compose 状态 —— 工作线程直接改 state 会与
      * 主线程的重绘竞争（与学习屏 / 设置屏同口径）。
      */
     private val worker = Executors.newSingleThreadExecutor { r ->
         Thread(r, "NotifyGuard-records-attribution").apply { isDaemon = true }
     }

     private val main = Handler(Looper.getMainLooper())

    /**
     * 最近一次读盘的结果，供 [applyRows] 重算列表。
     *
     * 搜索词每敲一下都要重算列表，而 [repaint] 里的 [ConfigWriter] 是跨进程调用 —— 键一个字符
     * 读一次盘，输入就会跟着卡。
     */
    private var allCache: List<LogRecord> = emptyList()
    private var marksCache: Map<String, Boolean> = emptyMap()
    private var protectCache: ProtectSwitches? = null

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
        // 用户手动切的档才是「上次使用的栏位」：顺手把首页那次定向请求清掉，否则它下次进屏还会生效
        RecordsFilterMemory.remember(filter)
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

    /** 点开底部那行：讲清这条为什么没被判定（带参的那几档用记录里的结构字段，不抠串）。 */
    fun askExplain(key: String) {
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        // 换了条目要把上一条的归因丢掉：留着会让新弹窗一开就显示别人的高亮
        state = state.copy(explanation = ReasonExplanation.of(r), attribution = null)
    }

    /**
     * 算归因（弹窗里那个「看高亮」）。
     *
     * 后台算：要遍历整条文本的 n-gram，主线程上算会卡住弹窗的动画与滚动；算完只回填一次，
     * 中途用户关掉弹窗也没关系（[dismissExplain] 会清掉）。
     */
    fun askAttribution() {
        val text = state.explanation?.aiText ?: return
        if (state.attribution != null) return
        state = state.copy(attribution = AttributionState.Computing)
        worker.execute {
            val out = runCatching { attributeOf(text) }.getOrNull() ?: AttributionState.NoModel
            main.post { state = state.copy(attribution = out) }
        }
    }

    fun dismissExplain() {
        state = state.copy(explanation = null, attribution = null)
    }

    fun clearLabels(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(confirmClearLabels = null)
        submit(app, UiText.Res(R.string.label_action_clear)) { done -> LabelClient.clear(app, done) }
    }

    fun mark(ctx: Context, key: String, spam: Boolean) {
        val app = ctx.applicationContext ?: ctx
        val r = records.firstOrNull { LabelRecord.keyOf(it) == key } ?: return
        // 界面已把两个按钮置灰，这里再拦一道：只靠置灰挡时，将来多一个入口（或无障碍触发）就绕过去了
        // 未连接时读不到开关 ⇒ 不拦（「未知不等于开着」同口径）
        val blocked = protectedLabel(r, ConfigWriter.load()?.protect)
        if (blocked != null) {
            notify(UiText.Res(R.string.notice_label_blocked, listOf(blocked)))
            return
        }
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
        allCache = all
        marksCache = LabelRecord.marksOf(all, LabelStore.get(ctx).all())
        // 保护开关读一次就够：它有上限 100 行的列表，逐行读就是每行一次跨进程调用
        protectCache = ConfigWriter.load()?.protect
        state = state.copy(
            groups = store.size(),
            rawCount = store.rawCount(),
            labelCount = LabelStore.get(ctx).size(),
        )
        applyRows()
    }

    /** 只重算列表，不读盘（搜索词逐字符触发，见 [allCache] 的注释）。 */
    private fun applyRows() {
        val hit = filteredOf(allCache, state.filter, state.query)
        records = hit.take(LIST_LIMIT)
        val keys = listKeysOf(records)
        state = state.copy(
            matched = hit.size,
            rows = records.mapIndexed { i, r ->
                row(r, marksCache[LabelRecord.keyOf(r)], protectCache, keys[i])
            },
        )
    }

    /** 展开 / 收起顶栏搜索。收起时一并清词：留着词而不显示搜索框，列表就是「被静默过滤」的状态。 */
    fun setSearchOpen(open: Boolean) {
        if (open == state.searchOpen) return
        state = state.copy(searchOpen = open, query = if (open) state.query else "")
        applyRows()
    }

    fun setQuery(q: String) {
        if (q == state.query) return
        state = state.copy(query = q)
        applyRows()
    }

    private fun row(
        r: LogRecord,
        marked: Boolean?,
        protect: ProtectSwitches?,
        listKey: String,
    ): RecordRow = RecordRow(
        key = LabelRecord.keyOf(r),
        listKey = listKey,
        // 一条记录是一组通知：时间取最近一次，首见时间对用户没有意义
        time = TIME.format(Date(r.lastTs)),
        verdict = Verdict.of(r),
        count = r.count,
        pkg = r.pkg,
        title = r.title,
        text = r.text,
        // 规则 id 与槽位的人话由共用件给出（学习屏同一份），界面只管画
        meta = ReasonExplanation.meta(r),
        marked = marked,
        labelBlocked = protectedLabel(r, protect),
        contentView = r.contentView,
    )

    /** 命中保护类型且该保护仍开着 → 不可标注（返回类型的人话标签）；null = 可以标。 */
    private fun protectedLabel(r: LogRecord, protect: ProtectSwitches?): UiText? =
        LabelGate.blockedLabel(r, protect)

    private fun notify(text: UiText) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {
        /**
         * 给每一行算一个在列表内唯一的键：主体是「包名 / 内容身份 / 判定」，仍撞则补序号。
         *
         * 纯函数，便于单测 —— 这条不变量（不唯一就崩进程）值得有个断言盯着，不能只靠「应该不会撞」。
         */
        internal fun listKeysOf(records: List<LogRecord>): List<String> {
            val seen = HashMap<String, Int>()
            return records.map { r ->
                val base = r.pkg + "/" + LabelRecord.keyOf(r) + "/" + Verdict.of(r)
                val n = (seen[base] ?: 0)
                seen[base] = n + 1
                if (n == 0) base else base + "#" + n
            }
        }

        /**
         * 每档在屏上列出的上限。筛选**先作用于整个窗口**再截到这里，所以命中数可以大于它。
         *
         * 100 是被「标注」需求顶上去的：骚扰源多半是高频通知（设备状态、步数、社区推送），几组就能
         * 把一条广告挤出前 20 组，而点不到它就标不了、训练就收不到这条样本。再往上收益递减 ——
         * 窗口本身只有 500 组，列表又是 LazyColumn（行数不构成渲染成本，只影响滚动手感）。
         */
        const val LIST_LIMIT = 100

        private val TIMEOUT_HINT = UiText.Res(R.string.records_fetch_timeout)

        private val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        /**
         * 取全窗口、按判定过滤、再按最近活跃排序。
         *
         * 顺序不可颠倒：先截断再筛时，「拦截」档只覆盖最近若干组里恰好被拦的那几条，
         * 窗口里更早被拦的条目永远进不了这个档 —— 而档名叫「拦截」。
         */
        fun filteredOf(records: List<LogRecord>, filter: Filter, query: String = ""): List<LogRecord> =
            records.filter { matches(it, filter) && matchesQuery(it, query) }
                .sortedByDescending { it.lastTs }

        /**
         * 搜索词对包名 / 标题 / 正文做子串匹配，不区分大小写、首尾空白不算。
         *
         * 只搜这三项：用户在记录页看到的「这条通知是什么」就在这三项里。判定原因、槽位、规则 id
         * 不进搜索 —— 要找「为什么被判成这样」是展开那条记录的解释，不是搜索框的事。
         */
        fun matchesQuery(r: LogRecord, query: String): Boolean {
            val q = query.trim()
            if (q.isEmpty()) return true
            return listOfNotNull(r.pkg, r.title, r.text).any { it.contains(q, ignoreCase = true) }
        }

        private fun matches(r: LogRecord, filter: Filter): Boolean = when (filter) {
            Filter.All -> true
            Filter.Block -> r.block
            Filter.Pass -> !r.block
        }
    }
}
