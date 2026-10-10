package io.github.vstory.notifyguard.sync

/**
 * 模块端状态快照（模块端现场读出、App 端展示）。
 *
 * 每一项都是**读出来的活状态**，没有一项由界面填默认值 —— 状态页最坏的失败不是显示「未知」，
 * 而是把「看起来正常」当成事实（用户在熔断期以为拦截还在跑，正是这里要防的）。
 *
 * 缺字段一律取默认：字段只增不减，App 与模块的版本差只会让新增项缺失（[StatusCodec] 负责）。
 */
data class StatusReport(
    /** 模块自身的 versionName。用于识别「装的是哪一版」，与 App 版本可以不同。 */
    val version: String = "",
    /**
     * 模块端**正在跑的那份代码**的构建提交短号（注入时打进来的）。
     *
     * 与 [version] 是两件事：versionName 取自 apk，而注入进程跑的是「它启动那一刻」加载的代码 ——
     * App 更新后 system_server 里仍是旧的。两者不一致就等于「模块端跑着旧代码」，
     * 这正是「改了配置/代码没生效」的第一嫌疑。
     */
    val moduleSha: String = "",
    /** 本代装配汇总 + 明细（明细含每条 OK/SKIP/FAIL 的原因）。 */
    val assembly: String = "",
    val assemblyAt: Long = 0L,
    val okCount: Int = 0,
    val skipCount: Int = 0,
    val failCount: Int = 0,
    /** 判定链此刻在跑吗（= 没被熔断停用，且装配期装上了判定 hook）。 */
    val judging: Boolean = false,
    /** 判定被停用的原因（异常风暴 / 崩溃环路熔断）；空 = 没停。 */
    val stopReason: String = "",
    /** 当前判定权在谁手上：`EXT_SLOT`（主路径）/ `FUNNEL`（兜底）/ `NONE`。 */
    val slot: String = "",
    val safeMode: Boolean = false,
    val safeModeAt: Long = 0L,
    val safeModeReason: String = "",
    /** 标志监听可用性：不可用时清掉标志也不会重装拦截，仍需重启 system_server。 */
    val autoRecover: Boolean = false,
    val errorCount: Int = 0,
    val extHits: Long = 0L,
    val funnelJudgeHits: Long = 0L,
    val funnelPassHits: Long = 0L,
    val romBlocked: Long = 0L,
    /** AI 段：base 模型是否可打分（不可用即整段放行）。 */
    val modelReady: Boolean = false,
    /** 已生效的端侧微调版本号；0 = 纯 base。取值是下发时刻的毫秒时间戳。 */
    val deltaVersion: Long = 0L,
    /** 已生效的微调权重数（0 = 纯 base）：版本号非 0 但权重为 0 说明那份 delta 是空的。 */
    val deltaWeights: Int = 0,
    val recordsPersisted: Long = 0L,
    val recordsDropped: Long = 0L,
)
