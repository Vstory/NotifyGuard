package io.github.vstory.notifyguard.judge

/** 预编译后的规则；关键词统一小写，正则已构造。 */
class CompiledRule(
    val id: String,
    private val type: RuleType,
    private val logic: RuleLogic,
    private val keywords: List<String>,
    private val regex: Regex?,
    private val packages: Set<String>,
) {

    fun applies(pkg: String?): Boolean = packages.isEmpty() || (pkg != null && pkg in packages)

    fun matches(loweredText: String, rawText: String): Boolean = when (type) {
        RuleType.KEYWORD ->
            if (logic == RuleLogic.AND) keywords.all { loweredText.contains(it) }
            else keywords.any { loweredText.contains(it) }

        // find 语义：用户写的是片段而非整串
        RuleType.REGEX -> regex?.containsMatchIn(rawText) == true
    }
}

object RuleMatcher {

    /** 非法规则（关键词为空 / 正则编译失败 / 未知类型）直接丢弃，不降级成别的语义。 */
    fun compile(rules: List<Rule>): List<CompiledRule> = rules.mapNotNull { r ->
        if (!r.enabled) return@mapNotNull null
        when (r.type) {
            RuleType.KEYWORD -> {
                val kw = r.keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (kw.isEmpty()) null else CompiledRule(r.id, r.type, r.logic, kw, null, r.packages)
            }

            RuleType.REGEX -> {
                val pattern = r.pattern?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val regex = runCatching { Regex(pattern) }.getOrNull() ?: return@mapNotNull null
                CompiledRule(r.id, r.type, r.logic, emptyList(), regex, r.packages)
            }
        }
    }

    /** 按 rules[] 顺序求值，首个命中即返回（判定链只需要「拦或不拦」）。 */
    fun firstHit(compiled: List<CompiledRule>, pkg: String?, loweredText: String, rawText: String): String? =
        compiled.firstOrNull { it.applies(pkg) && it.matches(loweredText, rawText) }?.id
}
