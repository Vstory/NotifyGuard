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
) {
    companion object {
        /**
         * 「自定义关键词」这条规则的固定 id：App 侧的规则编辑界面把它当作**关键词输入框的唯一落点**，
         * 改关键词时替换这条、不动 `rules` 里的其它条目。
         *
         * 换 id 的后果是旧的这条永远留在配置里、而界面改的是新那条（关键词看起来「改了不生效」）。
         */
        const val CUSTOM_KEYWORDS_ID = "custom-keywords"

        const val CUSTOM_KEYWORDS_NAME = "自定义关键词"
    }
}

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
data class Config(
    val schema: Int = SCHEMA,
    val enabled: Boolean = true,
    val observe: Boolean = true,
    val protect: ProtectSwitches = ProtectSwitches(),
    val whitelist: Set<String> = emptySet(),
    val rules: List<Rule> = emptyList(),
    val threshold: Double = DEFAULT_THRESHOLD,
    /** AI 段总开关。默认关：先观察模式跑分数分布，再谈默认开（M2 验收）。 */
    val spamEnabled: Boolean = false,
    /**
     * 已下发的微调量版本号（App 写，模块端读）。0 = 无微调。
     *
     * 为什么版本号走配置而不塞进 delta 文件：配置是本就有监听的回传通道（写它即触发模块端热更新），
     * 且「版本号变了才读文件」让模块端不必轮询文件系统。
     */
    val deltaVersion: Long = 0,
) {

    val compiledRules: List<CompiledRule> by lazy { RuleMatcher.compile(rules) }

    /**
     * 「启用了但不可用」的规则条数（type 未知 / 正则非法 / 关键词为空），用于热更新日志。
     * 统计口径要扣掉 `enabled=false`：用户主动关掉的规则不是被丢弃的。
     */
    val droppedRules: Int get() = rules.count { it.enabled } - compiledRules.size

    companion object {
        const val SCHEMA = 1

        /** 0.8 = 真机观察模式下的分数分布标定值（M2 遗留项）。改动它会改变已装用户的行为。 */
        const val DEFAULT_THRESHOLD = 0.8
    }
}
