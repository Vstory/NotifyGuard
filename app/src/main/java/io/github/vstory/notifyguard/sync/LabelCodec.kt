package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LabelRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标注编解码（模块端写、App 端读，两端共用）。
 *
 * 容错口径与 [LogCodec] 逐字一致：单条缺字段取缺省、坏 JSON 返回 null、数组级逐条丢弃解不出的项。
 */
object LabelCodec {

    fun toJson(l: LabelRecord): JSONObject = JSONObject().apply {
        put("key", l.key)
        put("ts", l.ts)
        put("pkg", l.pkg)
        put("text", l.text)
        put("spam", l.spam)
        put("at", l.at)
        put("modelVersion", l.modelVersion)
    }

    fun fromJson(o: JSONObject): LabelRecord? {
        // key 是主键：缺了就无法定位是哪条通知的标注，留着也无处安放
        val key = o.optString("key")
        if (key.isEmpty()) return null
        return LabelRecord(
            key = key,
            ts = o.optLong("ts"),
            pkg = o.optString("pkg"),
            text = o.optString("text"),
            // 缺省落在无害侧：漏标一条垃圾只是放过，反过来会让正常通知被拦
            spam = o.optBoolean("spam", false),
            at = o.optLong("at"),
            modelVersion = o.optInt("modelVersion"),
        )
    }

    fun encodeList(list: List<LabelRecord>): String =
        JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    fun decodeList(json: String?): List<LabelRecord> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LabelRecord>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { fromJson(it)?.let(out::add) }
        }
        return out
    }
}
