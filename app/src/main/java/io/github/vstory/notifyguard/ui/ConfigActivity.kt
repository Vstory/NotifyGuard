package io.github.vstory.notifyguard.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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

/**
 * App 侧配置入口（M1a 的最小可用版）。
 *
 * 只做「改开关 / 改关键词 / 保存下发」这一条链路，样式一律不做：M4 会用官方 Material 3 五屏取代本页，
 * 现在叠加的样式都要重写。
 */
class ConfigActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var observeSwitch: Switch
    private lateinit var enabledSwitch: Switch
    private lateinit var spamSwitch: Switch
    private lateinit var ruleSwitch: Switch
    private lateinit var keywordsInput: EditText
    private lateinit var jsonView: TextView
    private lateinit var recordInfo: TextView
    private lateinit var recordView: TextView

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

    private fun onService(svc: io.github.libxposed.service.XposedService?) {
        status.text = if (svc == null) {
            "框架服务：未连接（在 LSPosed 里启用本模块后重开本页）"
        } else {
            "框架服务：${svc.frameworkName} ${svc.frameworkVersion}"
        }
        if (svc != null) loadIntoUi()
    }

    private fun loadIntoUi() {
        val cfg = ConfigWriter.load() ?: return
        enabledSwitch.isChecked = cfg.enabled
        observeSwitch.isChecked = cfg.observe
        spamSwitch.isChecked = cfg.spamEnabled
        val custom = cfg.rules.firstOrNull { it.id == CUSTOM_RULE_ID }
        ruleSwitch.isChecked = custom?.enabled ?: true
        keywordsInput.setText(custom?.keywords?.joinToString("\n").orEmpty())
        jsonView.text = ConfigCodec.encode(cfg)
    }

    private fun save() {
        val base = ConfigWriter.load() ?: Config()
        val keywords = keywordsInput.text.toString()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val others = base.rules.filter { it.id != CUSTOM_RULE_ID }
        val custom = Rule(
            id = CUSTOM_RULE_ID,
            name = "自定义关键词",
            enabled = ruleSwitch.isChecked,
            type = RuleType.KEYWORD,
            logic = RuleLogic.OR,
            keywords = keywords,
        )
        val next = Config(
            schema = base.schema,
            enabled = enabledSwitch.isChecked,
            observe = observeSwitch.isChecked,
            protect = base.protect,
            whitelist = base.whitelist,
            rules = if (keywords.isEmpty()) others else others + custom,
            threshold = base.threshold,
            spamEnabled = spamSwitch.isChecked,
        )
        val ok = ConfigWriter.save(next)
        Toast.makeText(
            this,
            if (ok) "已保存并下发（模块端即时生效）" else "保存失败：框架服务未连接",
            Toast.LENGTH_LONG,
        ).show()
        if (ok) jsonView.text = ConfigCodec.encode(next)
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

        status = TextView(this).apply { text = "框架服务：检查中…" }

        enabledSwitch = Switch(this).apply { text = "启用拦截判定" }
        observeSwitch = Switch(this).apply { text = "观察模式（只记录，不拦截）" }
        spamSwitch = Switch(this).apply { text = "AI 识别垃圾通知（记录页 reason 里的分数就是它给的）" }
        ruleSwitch = Switch(this).apply { text = "启用「自定义关键词」规则" }
        keywordsInput = EditText(this).apply {
            hint = "每行一个关键词，命中即拦"
            minLines = 4
        }

        val saveButton = Button(this).apply {
            text = "保存并下发"
            setOnClickListener { save() }
        }

        root.addView(status)
        root.addView(label("开关"))
        root.addView(enabledSwitch)
        root.addView(observeSwitch)
        root.addView(spamSwitch)
        root.addView(label("规则"))
        root.addView(ruleSwitch)
        root.addView(keywordsInput)
        root.addView(saveButton)
        root.addView(label("当前配置（只读，便于排查）"))
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

    private companion object {
        const val CUSTOM_RULE_ID = "custom-keywords"
        const val RECENT_LIMIT = 20
        val TIME = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }
}
