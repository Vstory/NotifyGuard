package io.github.vstory.notifyguard.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import io.github.vstory.notifyguard.sync.ConfigCodec
import io.github.vstory.notifyguard.sync.ConfigWriter
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
    private lateinit var recordView: TextView

    /** 程序化改开关（渲染 / 回滚）期间抑制监听回调，否则「渲染触发下发、下发触发渲染」会成环。 */
    private var suppressSwitch = false

    /** 最近一次生效的关键词文本，作为「有未保存改动」的比较基准。 */
    private var savedKeywords = ""

    /** 最近一次生效的阈值（取档位比较，避免浮点等值判断）。 */
    private var savedThresholdStep = stepOf(Config.DEFAULT_THRESHOLD)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
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
        val connected = svc != null
        for (s in listOf(enabledSwitch, observeSwitch, spamSwitch, ruleSwitch)) s.isEnabled = connected
        thresholdBar.isEnabled = connected
        saveButton.isEnabled = connected
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

    private fun updateThresholdLabel() {
        val step = thresholdBar.progress
        val state = if (step == savedThresholdStep) "当前生效"
        else "松手下发，当前生效 ${fmt(valueOf(savedThresholdStep))}"
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
     */
    private fun refreshRecords() {
        renderRecords(null)
        recordInfo.text = "正在从模块端拉取…"
        LogFetcher.fetch(this) { list ->
            renderRecords(
                if (list == null) {
                    "拉取超时：模块未激活或装完还没重启过系统框架（记录本身没丢，仍在模块端的 /data/misc/notifyguard 下）"
                } else {
                    null
                },
            )
        }
    }

    private fun renderRecords(error: String?) {
        val store = LogStore.get(this)
        val list = store.recent(RECENT_LIMIT)
        recordInfo.text = buildString {
            append("共 ${store.size()} 组 / 累计 ${store.rawCount()} 次（上限 ${LogStore.MAX_RECORDS} 组），下列最近 ${list.size} 组；权威源在模块端")
            error?.let { append("\n$it") }
        }
        recordView.text = if (list.isEmpty()) {
            "（暂无记录。判定链是否在跑看框架日志；模块端记录落在 /data/misc/notifyguard/logs.json）"
        } else {
            list.joinToString("\n\n") { r ->
                val mark = when {
                    r.block -> "拦截"
                    r.would -> "本应拦"
                    else -> "放行"
                }
                // 一条记录是一组通知：时间取最近一次，次数附在后面（首见时间对用户没有意义）
                val times = if (r.count > 1) " ×${r.count}" else ""
                "${TIME.format(Date(r.lastTs))} [$mark]$times ${r.pkg} · ${r.slot.orEmpty()}\n" +
                    listOfNotNull(r.title, r.text).joinToString(" / ").ifEmpty { "(无文本)" } + "\n" +
                    r.reason + (r.ruleId?.let { " · $it" } ?: "")
            }
        }
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String): TextView =
            TextView(this).apply { this.text = text; setPadding(0, pad / 2, 0, pad / 4) }

        fun hint(text: String): TextView =
            TextView(this).apply { this.text = text; textSize = 11f; setPadding(0, 0, 0, pad / 4) }

        status = TextView(this).apply { text = "框架服务：检查中…" }

        enabledSwitch = Switch(this).apply { text = "启用拦截判定" }
        observeSwitch = Switch(this).apply { text = "观察模式（只记录，不拦截）" }
        spamSwitch = Switch(this).apply { text = "AI 识别垃圾通知（记录页 reason 里的分数就是它给的）" }
        ruleSwitch = Switch(this).apply { text = "启用「自定义关键词」规则" }

        thresholdLabel = TextView(this)
        thresholdBar = SeekBar(this).apply { max = THRESHOLD_STEPS }
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

        root.addView(status)
        root.addView(label("开关"))
        root.addView(hint("这一组拨动即下发，模块端立刻生效；右侧「已生效配置」会跟着变。"))
        root.addView(enabledSwitch)
        root.addView(observeSwitch)
        root.addView(spamSwitch)
        root.addView(thresholdLabel)
        root.addView(thresholdBar)
        root.addView(
            hint("滑杆松手即下发。AI 分数 ≥ 阈值就拦（只在上面「AI 识别垃圾通知」开着时生效）；调低更激进，0 等于全拦。"),
        )
        root.addView(label("规则"))
        root.addView(ruleSwitch)
        root.addView(keywordsInput)
        root.addView(hint("关键词是文本，打到一半就下发会被按半截词拦通知，所以由下面的按钮提交。"))
        root.addView(saveButton)
        root.addView(label("当前已生效配置（只读，便于排查）"))
        jsonView = TextView(this).apply { textSize = 10f }
        root.addView(jsonView)

        root.addView(label("记录（模块端回流）"))
        recordInfo = TextView(this)
        root.addView(recordInfo)
        root.addView(
            Button(this).apply {
                text = "刷新记录"
                setOnClickListener { refreshRecords() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "清空记录"
                setOnClickListener {
                    LogFetcher.clear(this@ConfigActivity)
                    renderRecords(null)
                }
            },
        )
        recordView = TextView(this).apply { textSize = 12f }
        root.addView(recordView)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bindSwitches() {
        enabledSwitch.setOnCheckedChangeListener { _, _ -> pushSwitch { it.copy(enabled = enabledSwitch.isChecked) } }
        observeSwitch.setOnCheckedChangeListener { _, _ -> pushSwitch { it.copy(observe = observeSwitch.isChecked) } }
        spamSwitch.setOnCheckedChangeListener { _, _ -> pushSwitch { it.copy(spamEnabled = spamSwitch.isChecked) } }
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
