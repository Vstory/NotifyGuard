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
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import io.github.vstory.notifyguard.sync.ConfigCodec
import io.github.vstory.notifyguard.sync.ConfigWriter

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
    private lateinit var ruleSwitch: Switch
    private lateinit var keywordsInput: EditText
    private lateinit var jsonView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 框架服务从 Binder 线程回调，触碰 UI 必须回主线程
        ConfigWriter.observe { svc -> runOnUiThread { onService(svc) } }
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
        )
        val ok = ConfigWriter.save(next)
        Toast.makeText(
            this,
            if (ok) "已保存并下发（模块端即时生效）" else "保存失败：框架服务未连接",
            Toast.LENGTH_LONG,
        ).show()
        if (ok) jsonView.text = ConfigCodec.encode(next)
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
        root.addView(label("规则"))
        root.addView(ruleSwitch)
        root.addView(keywordsInput)
        root.addView(saveButton)
        root.addView(label("当前配置（只读，便于排查）"))
        jsonView = TextView(this).apply { textSize = 10f }
        root.addView(jsonView)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private companion object {
        const val CUSTOM_RULE_ID = "custom-keywords"
    }
}
