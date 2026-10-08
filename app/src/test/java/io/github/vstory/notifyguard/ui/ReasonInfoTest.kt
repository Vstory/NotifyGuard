package io.github.vstory.notifyguard.ui

import io.github.vstory.notifyguard.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 原因码 → 人话的分档（M4k）。
 *
 * 认不出的码必须落 [ReasonKind.UNKNOWN] 并带上原文：判定链以后加码时，旧版 App 要诚实地说
 * 「看不懂」，而不是拿一个像模像样的短语糊过去 —— 那时界面与框架日志会对不上，而这条测试
 * 是唯一会先红的地方。
 */
class ReasonInfoTest {

    /** 判定链上每条出口都得分到自己的档：落错档等于给用户一句错话。 */
    @Test
    fun everyChainExitHasItsOwnKind() {
        val expected = mapOf(
            "bad_args" to ReasonKind.BAD_ARGS,
            "disabled" to ReasonKind.DISABLED,
            "self_pkg" to ReasonKind.SELF_PKG,
            "group_summary" to ReasonKind.GROUP_SUMMARY,
            "empty_text" to ReasonKind.EMPTY_TEXT,
            "text_too_short" to ReasonKind.TEXT_TOO_SHORT,
            "hard_word" to ReasonKind.HARD_WORD,
            "whitelisted" to ReasonKind.WHITELISTED,
            "ai_off" to ReasonKind.AI_OFF,
            "no_model" to ReasonKind.NO_MODEL,
            "ai_error" to ReasonKind.AI_ERROR,
        )

        expected.forEach { (raw, kind) ->
            assertEquals(raw, kind, ReasonInfo.of(raw).kind)
            assertEquals(raw, raw, ReasonInfo.of(raw).raw)
        }
    }

    /** `ai_off` / `ai_error` 是完整串：只有带冒号的才是带分数那条，按前缀判会把它俩当成分数。 */
    @Test
    fun aiOffAndAiErrorAreNotScored() {
        assertEquals(ReasonKind.AI_OFF, ReasonInfo.of("ai_off").kind)
        assertEquals(ReasonKind.AI_ERROR, ReasonInfo.of("ai_error").kind)
        assertEquals(emptyList<UiText>(), ReasonInfo.of("ai_off").args)
    }

    /** 分数取记录里的结构字段：字段是数据，串是给人看的那一份，两者不一致时以字段为准。 */
    @Test
    fun scoreComesFromTheFieldNotFromTheString() {
        val info = ReasonInfo.of("ai:0.10", score = 0.87)
        assertEquals(ReasonKind.AI_HIT, info.kind)
        assertEquals("0.87", (info.args.single() as UiText.Raw).text)
    }

    /** 字段缺失（旧记录）时退回串的尾部，不显示成空。 */
    @Test
    fun scoreFallsBackToTheStringWhenTheFieldIsMissing() {
        assertEquals("0.42", (ReasonInfo.of("below_threshold:0.42").args.single() as UiText.Raw).text)
    }

    /** 规则 id 同样优先用字段；串里的那份是日志形态。 */
    @Test
    fun ruleIdComesFromTheField() {
        val info = ReasonInfo.of("rule:old-id", ruleId = "custom-keywords")
        assertEquals(ReasonKind.RULE, info.kind)
        assertEquals("custom-keywords", (info.args.single() as UiText.Raw).text)
    }

    /**
     * 保护类型的名字与设置页的键不同源：判定侧是 `fgs`，资源键是 `protect_foreground_service`。
     * 靠 `"protect_" + 类型名` 拼键，前台服务这一项会找不到资源。
     */
    @Test
    fun foregroundServiceMapsToItsSettingKey() {
        val fgs = ReasonInfo.of("protect_fgs").args.single() as UiText.Res
        assertEquals(R.string.protect_foreground_service, fgs.id)

        val call = ReasonInfo.of("protect_call").args.single() as UiText.Res
        assertEquals(R.string.protect_call, call.id)
    }

    /** 判定侧加了新的保护类型时，界面显示原文而不是编一个名字。 */
    @Test
    fun unknownProtectedKindKeepsItsRawName() {
        val info = ReasonInfo.of("protect_something_new")
        assertEquals(ReasonKind.PROTECT, info.kind)
        assertEquals("something_new", (info.args.single() as UiText.Raw).text)
    }

    /** 未知码要原样带到弹窗里，用户与日志都能对上。 */
    @Test
    fun unknownReasonKeepsItsRawCode() {
        val info = ReasonInfo.of("future_code")
        assertEquals(ReasonKind.UNKNOWN, info.kind)
        assertEquals("future_code", info.raw)
        assertEquals("future_code", (info.args.single() as UiText.Raw).text)
    }

    /** 槽位文案与设置页同源（同一概念在两屏必须同一句话）。 */
    @Test
    fun slotLabelsShareTheSettingsWording() {
        assertEquals(R.string.module_slot_ext, (slotLabel("EXT_SLOT") as UiText.Res).id)
        assertEquals(R.string.module_slot_funnel, (slotLabel("FUNNEL") as UiText.Res).id)
        assertEquals(R.string.module_slot_unknown, (slotLabel("") as UiText.Res).id)
        // 认不出的槽给原文：那是个能拿去对日志的枚举名，抹掉等于扔线索
        assertEquals("nms", (slotLabel("nms") as UiText.Raw).text)
    }
}
