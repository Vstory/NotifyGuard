package io.github.vstory.notifyguard.judge

enum class RuleType { KEYWORD, REGEX }

enum class RuleLogic { OR, AND }

/**
 * 一条用户规则。`packages` 为空 = 对全部 App 生效。
 *
 * 关键词不做正则语义：用户词里出现 `(`、`[` 一类字符时，正则解析会给出与字面预期不同的结果。
 */
data class Rule(
    val id: String,
    val name: String = id,
    val enabled: Boolean = true,
    val type: RuleType = RuleType.KEYWORD,
    val logic: RuleLogic = RuleLogic.OR,
    val keywords: List<String> = emptyList(),
    val pattern: String? = null,
    val packages: Set<String> = emptySet(),
)

data class ProtectSwitches(
    val call: Boolean = true,
    val alarm: Boolean = true,
    val navigation: Boolean = true,
    val media: Boolean = true,
    val foregroundService: Boolean = true,
    val conversation: Boolean = true,
)

/**
 * 判定链的配置快照（不可变，热更新时整体替换）。
 *
 * 正则在这里预编译：编译要几毫秒到几十毫秒，绝不能留给判定热路径（那会拖慢 system_server 的通知入队）。
 * 调用方读一次 [compiledRules] 就会触发编译，配置解析阶段就把它读掉。
 */
class Config(
    val schema: Int = SCHEMA,
    val enabled: Boolean = true,
    val observe: Boolean = true,
    val protect: ProtectSwitches = ProtectSwitches(),
    val whitelist: Set<String> = emptySet(),
    val rules: List<Rule> = emptyList(),
    val threshold: Double = DEFAULT_THRESHOLD,
) {

    val compiledRules: List<CompiledRule> by lazy { RuleMatcher.compile(rules) }

    /**
     * 「启用了但不可用」的规则条数（type 未知 / 正则非法 / 关键词为空），用于热更新日志。
     * 统计口径要扣掉 `enabled=false`：用户主动关掉的规则不是被丢弃的。
     */
    val droppedRules: Int get() = rules.count { it.enabled } - compiledRules.size

    companion object {
        const val SCHEMA = 1
        const val DEFAULT_THRESHOLD = 0.7
    }
}
