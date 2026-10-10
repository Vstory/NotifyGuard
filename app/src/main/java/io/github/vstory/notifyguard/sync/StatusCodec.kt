package io.github.vstory.notifyguard.sync

import org.json.JSONObject

/**
 * 状态快照的编解码（模块端写、App 端读）。
 *
 * 与配置的做法相反：这里**每位缺字段取默认、整份坏 JSON 返回 null**，
 * 缺省即「这一项未知 / 关着」，绝不能因为缺一个键就把整份状态判成不可用（那会让状态页直接空白）。
 */
object StatusCodec {

    fun encode(s: StatusReport): String = JSONObject().apply {
        put("version", s.version)
        put("moduleSha", s.moduleSha)
        put("assembly", s.assembly)
        put("assemblyAt", s.assemblyAt)
        put("okCount", s.okCount)
        put("skipCount", s.skipCount)
        put("failCount", s.failCount)
        put("judging", s.judging)
        put("stopReason", s.stopReason)
        put("slot", s.slot)
        put("safeMode", s.safeMode)
        put("safeModeAt", s.safeModeAt)
        put("safeModeReason", s.safeModeReason)
        put("autoRecover", s.autoRecover)
        put("errorCount", s.errorCount)
        put("extHits", s.extHits)
        put("funnelJudgeHits", s.funnelJudgeHits)
        put("funnelPassHits", s.funnelPassHits)
        put("romBlocked", s.romBlocked)
        put("modelReady", s.modelReady)
        put("deltaVersion", s.deltaVersion)
        put("deltaWeights", s.deltaWeights)
        put("recordsPersisted", s.recordsPersisted)
        put("recordsDropped", s.recordsDropped)
    }.toString()

    fun decode(json: String?): StatusReport? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val d = StatusReport()
        return StatusReport(
            version = o.optString("version", d.version),
            moduleSha = o.optString("moduleSha", d.moduleSha),
            assembly = o.optString("assembly", d.assembly),
            assemblyAt = o.optLong("assemblyAt", d.assemblyAt),
            okCount = o.optInt("okCount", d.okCount),
            skipCount = o.optInt("skipCount", d.skipCount),
            failCount = o.optInt("failCount", d.failCount),
            judging = o.optBoolean("judging", d.judging),
            stopReason = o.optString("stopReason", d.stopReason),
            slot = o.optString("slot", d.slot),
            safeMode = o.optBoolean("safeMode", d.safeMode),
            safeModeAt = o.optLong("safeModeAt", d.safeModeAt),
            safeModeReason = o.optString("safeModeReason", d.safeModeReason),
            autoRecover = o.optBoolean("autoRecover", d.autoRecover),
            errorCount = o.optInt("errorCount", d.errorCount),
            extHits = o.optLong("extHits", d.extHits),
            funnelJudgeHits = o.optLong("funnelJudgeHits", d.funnelJudgeHits),
            funnelPassHits = o.optLong("funnelPassHits", d.funnelPassHits),
            romBlocked = o.optLong("romBlocked", d.romBlocked),
            modelReady = o.optBoolean("modelReady", d.modelReady),
            deltaVersion = o.optLong("deltaVersion", d.deltaVersion),
            deltaWeights = o.optInt("deltaWeights", d.deltaWeights),
            recordsPersisted = o.optLong("recordsPersisted", d.recordsPersisted),
            recordsDropped = o.optLong("recordsDropped", d.recordsDropped),
        )
    }
}
