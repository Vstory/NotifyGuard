package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.ProtectSwitches
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的 JSON 编解码（App 侧写、模块端读，两端共用同一份实现）。
 *
 * 解析原则：**缺字段取缺省值**（缺失不等于非法），未知字段忽略（前向兼容）。
 * 返回 null 只表示「整体不可用」——JSON 损坏或 schema 不认识；此时调用方必须保留上一份有效配置，
 * 直接回退默认会静默丢掉用户的全部规则。
 */
object ConfigCodec {

    fun decode(json: String?): Config? {
        if (json.isNullOrBlank()) return Config()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val schema = o.optInt("schema", Config.SCHEMA)
        if (schema != Config.SCHEMA) return null
        return Config(
            schema = schema,
            enabled = o.optBoolean("enabled", true),
            observe = o.optBoolean("observe", true),
            protect = protect(o.optJSONObject("protect")),
            whitelist = stringSet(o.optJSONArray("whitelist")),
            rules = rules(o.optJSONArray("rules")),
            threshold = o.optDouble("threshold", Config.DEFAULT_THRESHOLD),
            spamEnabled = o.optBoolean("spamEnabled", false),
        )
    }

    fun encode(c: Config): String {
        val o = JSONObject()
        o.put("schema", c.schema)
        o.put("enabled", c.enabled)
        o.put("observe", c.observe)
        o.put(
            "protect",
            JSONObject().apply {
                put("call", c.protect.call)
                put("alarm", c.protect.alarm)
                put("navigation", c.protect.navigation)
                put("media", c.protect.media)
                put("foregroundService", c.protect.foregroundService)
                put("conversation", c.protect.conversation)
            },
        )
        o.put("whitelist", JSONArray(c.whitelist.toList()))
        o.put("rules", JSONArray().apply { c.rules.forEach { put(ruleToJson(it)) } })
        o.put("threshold", c.threshold)
        o.put("spamEnabled", c.spamEnabled)
        return o.toString(2)
    }

    private fun protect(o: JSONObject?): ProtectSwitches {
        val d = ProtectSwitches()
        if (o == null) return d
        return ProtectSwitches(
            call = o.optBoolean("call", d.call),
            alarm = o.optBoolean("alarm", d.alarm),
            navigation = o.optBoolean("navigation", d.navigation),
            media = o.optBoolean("media", d.media),
            foregroundService = o.optBoolean("foregroundService", d.foregroundService),
            conversation = o.optBoolean("conversation", d.conversation),
        )
    }

    private fun rules(arr: JSONArray?): List<Rule> {
        if (arr == null) return emptyList()
        val byId = LinkedHashMap<String, Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = rule(o) ?: continue
            byId.putIfAbsent(r.id, r)
        }
        return byId.values.toList()
    }

    private fun rule(o: JSONObject): Rule? {
        val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        val type = when (o.optString("type", "keyword").trim().lowercase()) {
            "keyword" -> RuleType.KEYWORD
            "regex" -> RuleType.REGEX
            else -> return null
        }
        val logic =
            if (o.optString("logic", "or").trim().equals("and", ignoreCase = true)) RuleLogic.AND
            else RuleLogic.OR
        val keywords = stringList(o.optJSONArray("keywords"))
        val pattern = o.optString("pattern").trim().takeIf { it.isNotEmpty() }
        if (type == RuleType.KEYWORD && keywords.isEmpty()) return null
        if (type == RuleType.REGEX && pattern == null) return null
        return Rule(
            id = id,
            name = o.optString("name", id),
            enabled = o.optBoolean("enabled", true),
            type = type,
            logic = logic,
            keywords = keywords,
            pattern = pattern,
            packages = stringSet(o.optJSONArray("packages")),
        )
    }

    private fun ruleToJson(r: Rule): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("enabled", r.enabled)
        put("type", if (r.type == RuleType.REGEX) "regex" else "keyword")
        put("logic", if (r.logic == RuleLogic.AND) "and" else "or")
        put("keywords", JSONArray(r.keywords))
        r.pattern?.let { put("pattern", it) }
        put("packages", JSONArray(r.packages.toList()))
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    private fun stringSet(arr: JSONArray?) = stringList(arr).toSet()
}
