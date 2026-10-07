package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LogRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录编解码（模块端写、App 端读，两端共用）。
 *
 * 单条缺字段取缺省、坏 JSON 返回 null；数组级解码逐条丢弃解不出的项（一条坏记录不该毁掉整批）。
 */
object LogCodec {

    fun toJson(r: LogRecord): JSONObject = JSONObject().apply {
        put("ts", r.ts)
        // 单次记录不写这两个键：格式与 M2 完全一致，旧版本读新文件也不会多解析任何东西
        if (r.lastTs != r.ts) put("lastTs", r.lastTs)
        if (r.count > 1) put("count", r.count)
        put("pkg", r.pkg)
        r.title?.let { put("title", it) }
        r.text?.let { put("text", it) }
        put("reason", r.reason)
        put("would", r.would)
        put("block", r.block)
        r.slot?.let { put("slot", it) }
        r.ruleId?.let { put("ruleId", it) }
        r.score?.let { put("score", it) }
        r.aiText?.let { put("aiText", it) }
    }

    fun fromJson(o: JSONObject): LogRecord? {
        // ts 既是排序键也是「这条记录写完整了」的标志，缺了就没法定位
        val ts = o.optLong("ts", -1L)
        if (ts <= 0L) return null
        return LogRecord(
            ts = ts,
            pkg = o.optString("pkg"),
            title = o.optString("title").takeIf { it.isNotEmpty() },
            text = o.optString("text").takeIf { it.isNotEmpty() },
            reason = o.optString("reason", "?"),
            would = o.optBoolean("would", false),
            block = o.optBoolean("block", false),
            slot = o.optString("slot").takeIf { it.isNotEmpty() },
            // M2 之前的记录没有这两个键：缺省即「只出现过一次」
            lastTs = o.optLong("lastTs", ts),
            count = o.optInt("count", 1).coerceAtLeast(1),
            ruleId = o.optString("ruleId").takeIf { it.isNotEmpty() },
            score = if (o.isNull("score")) null else o.optDouble("score"),
            aiText = o.optString("aiText").takeIf { it.isNotEmpty() },
        )
    }

    fun encodeList(records: List<LogRecord>): String =
        JSONArray().apply { records.forEach { put(toJson(it)) } }.toString()

    fun decodeList(json: String?): List<LogRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LogRecord>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { fromJson(it)?.let(out::add) }
        }
        return out
    }
}
