package io.github.vstory.notifyguard.ai

/**
 * 判定链需要的唯一能力：给一段文本打分。
 *
 * 抽成接口是为了让「base 模型」与「base + 端侧 delta」在判定链眼里是同一个东西，
 * 单测也能注入会抛异常的实现来验证「打分失败一律放行」。
 */
fun interface SpamScorer {
    fun score(text: String): Double
}
