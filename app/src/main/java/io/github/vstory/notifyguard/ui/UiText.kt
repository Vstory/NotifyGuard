package io.github.vstory.notifyguard.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 界面文案的载体。
 *
 * ViewModel 只产出「资源号 + 参数」，不拼成品文本：拼好的是一个语言下的一份快照，语言或字号变了
 * 它不会跟着变，而重组时它也不会重算。参数里允许再嵌一个 [UiText]（外层模板套内层片段，
 * 例如「熔断触发：<时间>」）。
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** 不经翻译的原始串：包名、框架版本号、模块端回传的 reason 与异常详情。 */
    data class Raw(val text: String) : UiText
}

@Composable
fun UiText.text(): String = when (this) {
    is UiText.Raw -> text
    // 无参时不能走 stringResource 的多参重载：它会无条件过一次 String.format，文案里出现 % 就炸
    is UiText.Res -> if (args.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    }
}
