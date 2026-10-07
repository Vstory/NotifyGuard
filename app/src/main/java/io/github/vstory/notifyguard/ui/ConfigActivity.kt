package io.github.vstory.notifyguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.LabelRecord
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import io.github.vstory.notifyguard.sync.ConfigCodec
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.DeltaFitter
import io.github.vstory.notifyguard.sync.LabelClient
import io.github.vstory.notifyguard.sync.LogFetcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App 侧配置入口（M1a 的最小可用版）。
 *
 * 只做「改开关 / 改关键词 / 下发」这一条链路，样式一律不做：M4 会用官方 Material 3 五屏取代本页，
 * 现在叠加的样式都要重写。
 *
 * 开关与关键词的提交方式不同（M1f）：开关没有中间态，拨动即下发；关键词是文本，半截词下发会被照它拦通知，
 * 所以仍由按钮显式提交。两条路径都只替换自己负责的字段，绝不从界面收集全部字段整份重写 ——
 * 那会把对方尚未提交的改动一起带下去。
 */
class ConfigActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var observeSwitch: Switch
    private lateinit var enabledSwitch: Switch
    private lateinit var spamSwitch: Switch
    private lateinit var ruleSwitch: Switch
    private lateinit var thresholdBar: SeekBar
    private lateinit var thresholdLabel: TextView
    private lateinit var keywordsInput: EditText
    private lateinit var saveButton: Button
    private lateinit var jsonView: TextView
    private lateinit var recordInfo: TextView
    private lateinit var labelInfo: TextView
    private lateinit var root: ScrollView
    private lateinit var recordList: LinearLayout

    /** 程序化改开关（渲染 / 回滚）期间抑制监听回调，否则「渲染触发下发、下发触发渲染」会成环。 */
    private var suppressSwitch = false

    /** 框架服务是否已连上。滑杆可用性要在开关变化时重算，故必须留成字段而不只是 [onService] 的局部量。 */
    private var connected = false

    /** 最近一次生效的关键词文本，作为「有未保存改动」的比较基准。 */
    private var savedKeywords = ""

    /** 最近一次生效的阈值（取档位比较，避免浮点等值判断）。 */
    private var savedThresholdStep = stepOf(Config.DEFAULT_THRESHOLD)

    /** 标注行与微调行分别存文本再合成：两者的刷新时机不同（标注先到、拟合后到）。 */
    private var labelSummary = "标注：尚未拉取"
    private var fitSummary = "微调：尚未拉取"

    /** 标注指令在途标志：重绘时读它决定按钮可用性，所以必须跨重绘存活。 */
    private var labelBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = buildUi()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 框架服务从 Binder 线程回调，触碰 UI 必须回主线程
        ConfigWriter.observe { svc -> runOnUiThread { onService(svc) } }
        refreshRecords()
    }

    override fun onPause() {
        super.onPause()
        ConfigWriter.clear()
    }

    private fun onService(svc: XposedService?) {
        status.text = if (svc == null) {
            "框架服务：未连接（在 LSPosed 里启用本模块后重开本页；未连接时改动无法下发，控件已置灰）"
        } else {
            "框架服务：${svc.frameworkName} ${svc.frameworkVersion}"
        }
        connected = svc != null
        for (s in listOf(enabledSwitch, observeSwitch, spamSwitch, ruleSwitch)) s.isEnabled = connected
        saveButton.isEnabled = connected
        syncThresholdEnabled()
        if (connected) loadIntoUi()
    }

    private fun loadIntoUi() {
        val cfg = ConfigWriter.load() ?: return
        renderSwitches(cfg)
        renderThreshold(cfg)
        renderKeywords(cfg)
        jsonView.text = ConfigCodec.encode(cfg)
    }

    private fun renderSwitches(cfg: Config) {
        suppressSwitch = true
        enabledSwitch.isChecked = cfg.enabled
        observeSwitch.isChecked = cfg.observe
        spamSwitch.isChecked = cfg.spamEnabled
        ruleSwitch.isChecked = customRule(cfg)?.enabled ?: true
        suppressSwitch = false
    }

    /**
     * 有未保存改动时不动输入框：`onResume` 也会走到这里，无条件灌值会把用户刚输入、还没提交的关键词静默抹掉。
     */
    private fun renderKeywords(cfg: Config) {
        val dirty = keywordsDirty()
        val text = customRule(cfg)?.keywords.orEmpty().joinToString("\n")
        savedKeywords = text
        if (!dirty && keywordsInput.text.toString() != text) keywordsInput.setText(text)
        updateKeywordState()
    }

    /**
     * 拨动开关即刻下发：读回已生效配置，只替换这一个字段。
     *
     * 关键词取的是**已生效值**而不是输入框当前文本 —— 用户打到一半的词不能被这次拨动顺手带下去。
     */
    private fun pushSwitch(change: (Config) -> Config) {
        if (suppressSwitch) return
        val base = ConfigWriter.load()
        if (base == null) {
            toast("框架服务未连接，改动没有下发")
            return
        }
        val next = change(base)
        if (ConfigWriter.save(next)) {
            jsonView.text = ConfigCodec.encode(next)
        } else {
            // 停在「看起来开了」的状态比下发失败更糟：用户会以为规则已经生效
            toast("下发失败，开关已还原为生效值")
            renderSwitches(base)
        }
    }

    /**
     * 阈值的提交方式是「松手」而不是「进一格」：拖动途中的中间值会被当场拿去拦通知
     * （score >= threshold 即拦），而拖动控件的松手那一下才是用户的最终意图。
     */
    private fun renderThreshold(cfg: Config) {
        savedThresholdStep = stepOf(cfg.threshold)
        thresholdBar.progress = savedThresholdStep
        updateThresholdLabel()
    }

    /**
     * 滑杆的不可用有两种原因，含义不同且都要表达出来：下不去（无框架服务）/ 值不起作用（AI 段短路在阈值比较之前）。
     * 合成一个布尔，用户就无法从「置灰」本身看出自己该去修哪一个。
     */
    private fun syncThresholdEnabled() {
        thresholdBar.isEnabled = connected && spamSwitch.isChecked
        updateThresholdLabel()
    }

    private fun updateThresholdLabel() {
        val step = thresholdBar.progress
        val state = when {
            !connected -> "框架服务未连接，未读到生效值"
            !spamSwitch.isChecked -> "AI 未开启，不生效"
            step == savedThresholdStep -> "当前生效"
            else -> "松手下发，当前生效 ${fmt(valueOf(savedThresholdStep))}"
        }
        thresholdLabel.text = "AI 分数阈值 ${fmt(valueOf(step))}（$state）"
    }

    private fun pushThreshold() {
        val step = thresholdBar.progress
        if (step == savedThresholdStep) {
            updateThresholdLabel()
            return
        }
        val base = ConfigWriter.load()
        if (base == null) {
            toast("框架服务未连接，改动没有下发")
            rollbackThreshold()
            return
        }
        val next = base.copy(threshold = valueOf(step))
        if (!ConfigWriter.save(next)) {
            toast("下发失败，阈值已还原为生效值")
            rollbackThreshold()
            return
        }
        savedThresholdStep = step
        jsonView.text = ConfigCodec.encode(next)
        updateThresholdLabel()
    }

    /** 生效值以回读为准：只按内存里的旧值还原，界面可能停在一个其实没生效的数上。 */
    private fun rollbackThreshold() {
        savedThresholdStep = stepOf(ConfigWriter.load()?.threshold ?: valueOf(savedThresholdStep))
        thresholdBar.progress = savedThresholdStep
        updateThresholdLabel()
    }

    /** 关键词与开关走不同的提交路径：这里读的是输入框，其余字段一律沿用已生效配置。 */
    private fun save() {
        val base = ConfigWriter.load()
        if (base == null) {
            toast("保存失败：框架服务未连接")
            return
        }
        val keywords = normalizeKeywords(keywordsInput.text.toString())
        val others = base.rules.filter { it.id != CUSTOM_RULE_ID }
        val custom = Rule(
            id = CUSTOM_RULE_ID,
            name = "自定义关键词",
            enabled = ruleSwitch.isChecked,
            type = RuleType.KEYWORD,
            logic = RuleLogic.OR,
            keywords = keywords,
        )
        val next = base.copy(rules = if (keywords.isEmpty()) others else others + custom)
        if (!ConfigWriter.save(next)) {
            toast("保存失败：框架服务未连接")
            return
        }
        savedKeywords = keywords.joinToString("\n")
        updateKeywordState()
        jsonView.text = ConfigCodec.encode(next)
        toast("已保存并下发（模块端即时生效）")
    }

    private fun keywordsDirty(): Boolean =
        normalizeKeywords(keywordsInput.text.toString()) != normalizeKeywords(savedKeywords)

    private fun updateKeywordState() {
        saveButton.text = if (keywordsDirty()) "保存关键词并下发（有未保存改动）" else "保存关键词并下发"
    }

    /**
     * 记录权威源在模块端（`/data/misc/notifyguard/logs.json`），App 侧只有上次拉取的缓存：
     * 进页面先渲染缓存（立刻有内容），再拉一次覆盖。
     *
     * 标注（`labels.json`）跟着一起拉：它同样是模块端权威、App 侧缓存，且这一行是本片唯一能看出
     * 标注通道是否活着的窗口。
     *
     * 拉完标注就顺手喂给拟合（[DeltaFitter]）：标注是微调的唯一输入，两者天然同源；
     * 拟合按「输入摘要有没有变」自行决定要不要真跑，重复进页面不会白算。
     */
    private fun refreshRecords() {
        labelSummary = "标注：正在从模块端拉取…"
        fitSummary = "微调：检查中…"
        paintLabelInfo()
        renderRecords(null)
        renderRecordViews()
        LabelClient.fetch(this) { renderLabels(it) }
        LogFetcher.fetch(this) { list ->
            renderRecords(
                if (list == null) {
                    "拉取超时：模块未激活或装完还没重启过系统框架（记录本身没丢，仍在模块端的 /data/misc/notifyguard 下）"
                } else {
                    null
                },
            )
            renderRecordViews()
        }
    }

    private fun renderLabels(list: List<LabelRecord>?) {
        labelSummary = if (list == null) {
            "标注：拉取超时（模块未激活或装完还没重启过系统框架；标注本身没丢，仍在模块端）"
        } else {
            labelSummaryOf(list)
        }
        // 拉取与前一次标注的回执都会把模块端全量写进本地缓存（LabelClient），据此重绘每条的标注状态
        renderRecordViews()
        if (list == null) {
            DeltaFitter.status(this) { onFitState(it) }
        } else {
            DeltaFitter.ensureFitted(this, list) { onFitState(it) }
        }
    }

    private fun labelSummaryOf(list: List<LabelRecord>): String =
        "标注 ${list.size} 条 · 权威源在模块端的 labels.json（与记录一样，卸载重装 App 不会丢）"

    private fun onFitState(state: DeltaFitter.State) {
        if (isFinishing || isDestroyed) return
        fitSummary = DeltaFitter.describe(state)
        paintLabelInfo()
    }

    private fun paintLabelInfo() {
        labelInfo.text = "$labelSummary\n$fitSummary"
    }

    private fun renderRecords(error: String?) {
        val store = LogStore.get(this)
        recordInfo.text = buildString {
            append("共 ${store.size()} 组 / 累计 ${store.rawCount()} 次（上限 ${LogStore.MAX_RECORDS} 组），下列最近 $RECENT_LIMIT 组；权威源在模块端")
            error?.let { append("\n$it") }
        }
    }

    /**
     * 重绘记录列表。整表重建而不是增量更新：一次点击、一次拉取各只重建一遍（至多 [RECENT_LIMIT] 行），
     * 而增量更新要多维护一份「哪一行是哪条记录」的索引 —— 那才是真会出错的地方。
     *
     * 重建前后恢复滚动位置：按钮在列表靠下的行上，跳回顶部会让人当场丢失上下文（尤其连标几条时）。
     */
    private fun renderRecordViews() {
        val list = LogStore.get(this).recent(RECENT_LIMIT)
        // 标注同样来自模块端的本地缓存：对不上时只会少显示「已标注」，不会写坏权威源
        val marks = LabelRecord.marksOf(list, LabelStore.get(this).all())
        val y = root.scrollY
        recordList.removeAllViews()
        if (list.isEmpty()) {
            recordList.addView(hint("（暂无记录。判定链是否在跑看框架日志；模块端记录落在 /data/misc/notifyguard/logs.json）"))
        } else {
            list.forEach { recordList.addView(recordRow(it, marks[LabelRecord.keyOf(it)])) }
        }
        root.scrollTo(0, y)
    }

    private fun recordRow(r: LogRecord, marked: Boolean?): View {
        val mark = when {
            r.block -> "拦截"
            r.would -> "本应拦"
            else -> "放行"
        }
        // 一条记录是一组通知：时间取最近一次，次数附在后面（首见时间对用户没有意义）
        val times = if (r.count > 1) " ×${r.count}" else ""
        val labeled = when (marked) {
            true -> " 【已标垃圾】"
            false -> " 【已标正常】"
            null -> ""
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, pad / 2)

            addView(
                TextView(this@ConfigActivity).apply {
                    textSize = 12f
                    text = "${TIME.format(Date(r.lastTs))} [$mark]$times ${r.pkg} · ${r.slot.orEmpty()}$labeled"
                },
            )
            addView(
                TextView(this@ConfigActivity).apply {
                    textSize = 13f
                    text = listOfNotNull(r.title, r.text).joinToString(" / ").ifEmpty { "(无文本)" }
                },
            )
            addView(
                TextView(this@ConfigActivity).apply {
                    textSize = 10f
                    text = r.reason + (r.ruleId?.let { " · $it" } ?: "")
                },
            )
            addView(labelButtons(r, marked))
        }
    }

    /** 三个动作对已标注状态互斥收敛：已标垃圾时「标垃圾」置灰，免得按出一串同义指令。 */
    private fun labelButtons(r: LogRecord, marked: Boolean?): View {
        fun action(text: String, enabled: Boolean, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 11f
            // 贴合文字宽度：三个中文按钮走默认 minWidth 会在窄屏上把「撤销」挤出屏幕外
            minWidth = 0
            setPadding(pad / 3, pad / 6, pad / 3, pad / 6)
            // 在途时全部置灰：连点会并发发出多条指令，而「先到的回执」会把后点那次的状态盖掉
            isEnabled = enabled && !labelBusy
            setOnClickListener { onClick() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action("标垃圾", marked != true) { markAs(r, true) })
            addView(action("标正常", marked != false) { markAs(r, false) })
            addView(action("撤销", marked != null) { undoMark(r) })
        }
    }

    private fun markAs(r: LogRecord, spam: Boolean) = submitLabel(if (spam) "标为垃圾" else "标为正常") { done ->
        LabelClient.set(
            this,
            LabelRecord.of(r, spam, System.currentTimeMillis(), DeltaFitter.baseFingerprint()),
            done,
        )
    }

    private fun undoMark(r: LogRecord) = submitLabel("撤销标注") { done ->
        LabelClient.delete(this, LabelRecord.keyOf(r), done)
    }

    private fun clearLabels() {
        val n = LabelStore.get(this).size()
        if (n == 0) {
            toast("当前没有标注")
            return
        }
        // 二次确认：标注是手工劳动且没有回收站，误触一下整批就没了
        AlertDialog.Builder(this)
            .setTitle("清空全部标注？")
            .setMessage("$n 条标注会被删除且无法恢复。清空后模型退回纯内置模型，分数会立刻变回去。")
            .setPositiveButton("清空") { _, _ ->
                submitLabel("清空标注") { done -> LabelClient.clear(this, done) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 标注动作串行化：一次只允许一条指令在途。
     *
     * 广播是异步的、超时窗 5s，连点会并发发出多条；模块端按到达顺序串行落盘，而 App 侧**先到的
     * 回执**会把后点那次的结果覆盖掉 —— 界面最终留下的可能是用户最后一次没点的那个标注。
     */
    private fun submitLabel(what: String, call: ((List<LabelRecord>?) -> Unit) -> Unit) {
        if (labelBusy) {
            toast("上一个标注动作还在等回执")
            return
        }
        labelBusy = true
        renderRecordViews()
        call { list -> onLabelResult(what, list) }
    }

    private fun onLabelResult(what: String, list: List<LabelRecord>?) {
        labelBusy = false
        if (list == null) {
            // 没有回执 ⇒ LabelClient 不会动本地缓存 ⇒ 重绘后显示的还是原状态，界面与权威源仍一致
            toast("$what 未生效：模块端没有回执（模块未激活 / 装完还没重启过系统框架 / labels.json 损坏）")
        } else {
            toast("$what 已生效（共 ${list.size} 条标注）")
            labelSummary = labelSummaryOf(list)
        }
        renderRecordViews()
        paintLabelInfo()
        // 标注就是微调的输入：改完立刻重拟合下发，用户不必再去找别的入口
        if (list != null) DeltaFitter.ensureFitted(this, list) { onFitState(it) }
    }

    /** 内边距基准。UI 一律手搓，M4 会用 Material 3 重做整页，样式不值得在这里收敛。 */
    private val pad: Int get() = (16 * resources.displayMetrics.density).toInt()

    /** 说明文字。抽成成员是因为记录列表在重建时也要用它（列表为空时的占位）。 */
    private fun hint(text: String): TextView =
        TextView(this).apply { this.text = text; textSize = 11f; setPadding(0, 0, 0, pad / 4) }

    private fun buildUi(): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String): TextView =
            TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, pad / 4) }

        status = TextView(this).apply { text = "框架服务：检查中…" }

        enabledSwitch = Switch(this).apply { text = "启用拦截判定" }
        observeSwitch = Switch(this).apply { text = "观察模式（只记录，不拦截）" }
        spamSwitch = Switch(this).apply { text = "AI 识别垃圾通知（记录页 reason 里的分数就是它给的）" }
        ruleSwitch = Switch(this).apply { text = "启用「自定义关键词」规则" }

        thresholdLabel = TextView(this)
        // 未连接框架时读不到生效值：先落在默认档，别让滑杆停在 0.00 这个「全拦」的位置
        thresholdBar = SeekBar(this).apply {
            max = THRESHOLD_STEPS
            progress = savedThresholdStep
        }
        thresholdBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // 靠 fromUser 而不是自备抑制标志：程序化 setProgress 不触发 onStopTrackingTouch，
            // 渲染与回滚因此天然不会误下发
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) updateThresholdLabel()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = pushThreshold()
        })

        keywordsInput = EditText(this).apply {
            hint = "每行一个关键词，命中即拦"
            minLines = 4
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = updateKeywordState()
            })
        }

        bindSwitches()

        saveButton = Button(this).apply {
            text = "保存关键词并下发"
            setOnClickListener { save() }
        }

        column.addView(status)
        column.addView(label("开关"))
        column.addView(hint("这一组拨动即下发，模块端立刻生效；右侧「已生效配置」会跟着变。"))
        column.addView(enabledSwitch)
        column.addView(observeSwitch)
        column.addView(spamSwitch)
        column.addView(thresholdLabel)
        column.addView(thresholdBar)
        column.addView(
            hint("滑杆松手即下发。AI 分数 ≥ 阈值就拦；关掉上面「AI 识别垃圾通知」时本滑杆置灰（这个值不参与判定）。调低更激进，0 等于全拦。"),
        )
        column.addView(label("规则"))
        column.addView(ruleSwitch)
        column.addView(keywordsInput)
        column.addView(hint("关键词是文本，打到一半就下发会被按半截词拦通知，所以由下面的按钮提交。"))
        column.addView(saveButton)
        column.addView(label("当前已生效配置（只读，便于排查）"))
        jsonView = TextView(this).apply { textSize = 10f }
        column.addView(jsonView)

        column.addView(label("记录（模块端回流）"))
        recordInfo = TextView(this)
        column.addView(recordInfo)
        labelInfo = TextView(this).apply { textSize = 12f }
        column.addView(labelInfo)
        column.addView(
            hint(
                "「标垃圾 / 标正常」把这条通知的文本交给端侧微调；标完累计到门槛本页会自动拟合并下发给模块端" +
                    "（下次判定即生效），无需手动触发。标注存模块端，卸载重装 App 不会丢。",
            ),
        )
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(this@ConfigActivity).apply {
                        text = "刷新记录"
                        setOnClickListener { refreshRecords() }
                    },
                )
                addView(
                    Button(this@ConfigActivity).apply {
                        text = "清空记录"
                        setOnClickListener {
                            LogFetcher.clear(this@ConfigActivity)
                            renderRecords(null)
                            renderRecordViews()
                        }
                    },
                )
            },
        )
        // 与「清空记录」分开一行：记录是流水（丢了无所谓），标注是手工劳动（清空要二次确认），
        // 两个破坏性动作并排在同一行的相邻位置，误触代价不对等
        column.addView(
            Button(this).apply {
                text = "清空标注"
                setOnClickListener { clearLabels() }
            },
        )
        recordList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(recordList)

        return ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bindSwitches() {
        enabledSwitch.setOnCheckedChangeListener { _, _ -> pushSwitch { it.copy(enabled = enabledSwitch.isChecked) } }
        observeSwitch.setOnCheckedChangeListener { _, _ -> pushSwitch { it.copy(observe = observeSwitch.isChecked) } }
        spamSwitch.setOnCheckedChangeListener { _, _ ->
            pushSwitch { it.copy(spamEnabled = spamSwitch.isChecked) }
            // 渲染期（suppressSwitch）也会走到这里，正是需要跟着重算的时机
            syncThresholdEnabled()
        }
        ruleSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            val base = ConfigWriter.load()
            // 没有关键词时这条规则无处可挂（空关键词规则会在解码侧被丢弃），先让用户填词
            if (base != null && customRule(base)?.keywords.isNullOrEmpty()) {
                toast("先在下面填关键词并保存，规则开关才有内容可匹配")
                renderSwitches(base)
                return@setOnCheckedChangeListener
            }
            pushSwitch { cfg -> cfg.copy(rules = cfg.rules.withCustomEnabled(checked)) }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun customRule(cfg: Config): Rule? = cfg.rules.firstOrNull { it.id == CUSTOM_RULE_ID }

    private fun List<Rule>.withCustomEnabled(checked: Boolean): List<Rule> {
        val i = indexOfFirst { it.id == CUSTOM_RULE_ID }
        if (i < 0) return this
        return toMutableList().also { it[i] = it[i].copy(enabled = checked) }
    }

    private fun normalizeKeywords(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val CUSTOM_RULE_ID = "custom-keywords"
        const val RECENT_LIMIT = 20

        /** 阈值档位数：0.00–1.00 步长 0.01，与判定链 reason 里 %.2f 的粒度对齐（更粗就对不上数）。 */
        const val THRESHOLD_STEPS = 100

        fun stepOf(v: Double): Int = (v * THRESHOLD_STEPS).roundToInt().coerceIn(0, THRESHOLD_STEPS)

        fun valueOf(step: Int): Double = step.toDouble() / THRESHOLD_STEPS

        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

        val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }
}
