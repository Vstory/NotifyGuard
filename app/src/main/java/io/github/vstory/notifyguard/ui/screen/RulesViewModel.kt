package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.Rule
import io.github.vstory.notifyguard.judge.RuleLogic
import io.github.vstory.notifyguard.judge.RuleType
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.LogFetcher

/**
 * 规则屏的状态与动作（M4）。
 *
 * 两条写入路径的提交口径不同，与 M1f 一致：关键词是文本，**打到一半下发会被按半截词拦通知**，
 * 所以由按钮显式提交；白名单是集合成员，勾选即下发（同开关语义）。
 *
 * 两条路径都只替换自己负责的字段、且以**模块端读回的生效配置**为基准 —— 从界面收集全部字段整份重写
 * 会把另一条尚未提交的改动（只在草稿里）一起带下去。
 */
class RulesViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: String = "框架服务：检查中…",
        /** 读回的生效配置；null = 未连接或读不出（界面按「未知」渲染，不要当默认值用）。 */
        val cfg: Config? = null,
        /** 关键词输入框的草稿。与生效值分开：它是文本，有中间态。 */
        val keywordDraft: String = "",
        val pkgDraft: String = "",
        val candidates: List<Candidate> = emptyList(),
        val refreshing: Boolean = false,
        val notice: Notice? = null,
    )

    /** 一次性提示。带自增 id：同一条文案连发两次也要各弹一次。 */
    data class Notice(val id: Long, val text: String)

    /**
     * 白名单的一个候选应用。
     *
     * `groups` = 它在模块端回流记录里出现过的组数，0 表示只存在于白名单、还没出现在记录里。
     */
    data class Candidate(val pkg: String, val whitelisted: Boolean, val groups: Int)

    var state by mutableStateOf(UiState())
        private set

    /** 候选要读记录缓存，故留住 applicationContext（不是 Activity，不存在泄漏界面）。 */
    private var app: Context? = null

    private var noticeSeq = 0L

    /** Binder 线程回调不能直接写 Compose 状态，一律转主线程（与其它屏同一口径）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 订阅框架服务状态并立刻渲染一次；返回注销句柄，界面离开时务必调用。 */
    fun bind(ctx: Context): () -> Unit {
        app = ctx.applicationContext ?: ctx
        return ConfigWriter.observe { svc -> main.post { onService(svc) } }
    }

    fun dragKeywords(text: String) {
        state = state.copy(keywordDraft = text)
    }

    fun dragPkg(text: String) {
        state = state.copy(pkgDraft = text)
    }

    fun noticeShown(n: Notice) {
        if (state.notice == n) state = state.copy(notice = null)
    }

    /** 关键词与生效值是否不同（按行归一后比较，所以行尾空格、空行不算改动）。 */
    fun keywordsDirty(): Boolean = normalize(state.keywordDraft) != savedKeywords()

    fun saveLabel(): String =
        if (keywordsDirty()) "保存关键词并下发（有未保存改动）" else "保存关键词并下发"

    /** 生效配置里那条关键词规则的启停状态；没有这条规则时按「未启用」算。 */
    fun ruleEnabled(): Boolean = state.cfg?.let(::customRule)?.enabled ?: false

    /**
     * 规则开关的可点条件：没有关键词时这条规则无处可挂（空关键词规则在解码侧就被丢弃），
     * 拨动它只会得到一个「开关开着但什么都不拦」的假象。
     */
    fun canToggleRule(): Boolean = state.connected && savedKeywords().isNotEmpty()

    fun keywordsSaved(): Boolean = savedKeywords().isNotEmpty()

    /**
     * 关键词按**整条规则替换**下发：读回生效配置，只换 [Rule.CUSTOM_KEYWORDS_ID] 这一条，
     * 其余规则原样带走（用户可能在别处加了正则规则）。
     *
     * 关键词清空 ⇒ 整条规则从配置里移除。留一条空关键词规则没有意义：解码侧会丢弃它，
     * 界面要显示它就得额外区分「有规则但没词」和「没规则」，而两者行为完全一样。
     */
    fun saveKeywords() {
        val base = ConfigWriter.load()
        if (base == null) {
            notify("保存失败：框架服务未连接")
            repaint()
            return
        }
        val keywords = normalize(state.keywordDraft)
        val others = base.rules.filterNot { it.id == Rule.CUSTOM_KEYWORDS_ID }
        val custom = Rule(
            id = Rule.CUSTOM_KEYWORDS_ID,
            name = Rule.CUSTOM_KEYWORDS_NAME,
            // 启停沿用已生效的那条，不读界面开关：界面上的开关状态可能与配置不同步
            // （比如这次保存前用户刚改过关键词），以配置为准才不会顺手改掉它
            enabled = base.rules.firstOrNull { it.id == Rule.CUSTOM_KEYWORDS_ID }?.enabled ?: true,
            type = RuleType.KEYWORD,
            logic = RuleLogic.OR,
            keywords = keywords,
        )
        val next = base.copy(rules = if (keywords.isEmpty()) others else others + custom)
        if (!ConfigWriter.save(next)) {
            notify("保存失败：下发没有成功（模块端仍是上一份配置）")
            repaint()
            return
        }
        state = state.copy(cfg = next, keywordDraft = keywords.joinToString("\n"))
        notify(
            if (keywords.isEmpty()) {
                "已清空关键词并下发：这条规则不再拦任何通知"
            } else {
                "已保存 ${keywords.size} 个关键词并下发（模块端即时生效）"
            },
        )
    }

    /** 拨动即下发，与设置屏的开关同口径。 */
    fun setRuleEnabled(value: Boolean) {
        val base = ConfigWriter.load()
        if (base == null) {
            notify("框架服务未连接，改动没有下发")
            repaint()
            return
        }
        val i = base.rules.indexOfFirst { it.id == Rule.CUSTOM_KEYWORDS_ID }
        if (i < 0) {
            notify("还没有关键词：先在下面填关键词并保存")
            repaint()
            return
        }
        val rules = base.rules.toMutableList().also { it[i] = it[i].copy(enabled = value) }
        val next = base.copy(rules = rules)
        if (!ConfigWriter.save(next)) {
            notify("下发失败，开关已还原为生效值")
            repaint()
            return
        }
        state = state.copy(cfg = next)
        notify(if (value) "关键词规则已启用" else "关键词规则已停用（关键词仍保留在配置里）")
    }

    /** 勾选即下发，同开关语义。 */
    fun setWhitelist(pkg: String, inList: Boolean) {
        val base = ConfigWriter.load()
        if (base == null) {
            notify("框架服务未连接，改动没有下发")
            repaint()
            return
        }
        val next = base.copy(
            whitelist = if (inList) base.whitelist + pkg else base.whitelist - pkg,
        )
        if (!ConfigWriter.save(next)) {
            notify("下发失败，白名单已还原为生效值")
            repaint()
            return
        }
        state = state.copy(cfg = next)
        repaint()
        notify(
            if (inList) {
                "$pkg 已加入白名单：它的通知不再进 AI 识别（关键词规则照常生效）"
            } else {
                "$pkg 已移出白名单"
            },
        )
    }

    /**
     * 手动加包名。候选取自记录，而记录上限 500 组、会被裁剪 —— 要放行的 App 若已沉到裁剪线外，
     * 就只剩这条入口可用。
     */
    fun addWhitelist() {
        val pkg = state.pkgDraft.trim()
        if (pkg.isEmpty()) return
        if (!isPkgName(pkg)) {
            notify("包名格式不对：应形如 com.example.app（这里要的是包名，不是应用名）")
            return
        }
        if (state.cfg?.whitelist?.contains(pkg) == true) {
            state = state.copy(pkgDraft = "")
            notify("$pkg 已在白名单里")
            return
        }
        val base = ConfigWriter.load()
        if (base == null) {
            notify("框架服务未连接，改动没有下发")
            return
        }
        val next = base.copy(whitelist = base.whitelist + pkg)
        if (!ConfigWriter.save(next)) {
            notify("下发失败，白名单已还原为生效值")
            repaint()
            return
        }
        state = state.copy(cfg = next, pkgDraft = "")
        repaint()
        notify("$pkg 已加入白名单")
    }

    /**
     * 候选来自模块端回流记录里的包名，**不列全部已安装应用**：列全部要 `QUERY_ALL_PACKAGES`
     * （Android 11 起包可见性默认只给自身与已交互过的包），而这个权限对「只想放行某个 App」是过度的。
     * 记录里的包名恰好就是用户真的见过通知的那些，且零权限。
     */
    fun refreshCandidates() {
        val ctx = app ?: return
        if (state.refreshing) return
        state = state.copy(refreshing = true)
        LogFetcher.fetch(ctx) { list ->
            state = state.copy(refreshing = false)
            if (list == null) {
                notify("候选拉取超时：模块未激活或装完还没重启过系统框架（已有的候选照常可用）")
            } else {
                notify("候选已更新")
            }
            repaint()
        }
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = if (svc == null) {
                "框架服务：未连接（在 LSPosed 里启用本模块后重开本页；未连接时改动下发不了，控件已置灰）"
            } else {
                "框架服务：${svc.frameworkName} ${svc.frameworkVersion}"
            },
        )
        repaint()
    }

    /** 以模块端读回的配置为准重绘（回滚、失败、服务状态变化都走这里）。 */
    private fun repaint() {
        val ctx = app ?: return
        val cfg = if (state.connected) ConfigWriter.load() else null
        state = state.copy(
            cfg = cfg,
            keywordDraft = draftAfterReload(cfg),
            candidates = candidates(cfg, ctx),
        )
    }

    /**
     * 重载后输入框该显示什么：**有未保存改动时不动输入框** —— 本方法在服务状态变化、下发失败回滚、
     * 候选刷新时都会被调到，无条件灌值会把用户正在打的词静默抹掉。
     */
    private fun draftAfterReload(cfg: Config?): String {
        val saved = cfg?.let(::customKeywords)?.joinToString("\n") ?: return state.keywordDraft
        return if (normalize(state.keywordDraft) == normalize(saved)) saved else state.keywordDraft
    }

    private fun candidates(cfg: Config?, ctx: Context): List<Candidate> {
        val counts = HashMap<String, Int>()
        LogStore.get(ctx).recent(LogStore.MAX_RECORDS).forEach { r ->
            if (r.pkg.isNotEmpty()) counts[r.pkg] = (counts[r.pkg] ?: 0) + r.count
        }
        return rankCandidates(cfg?.whitelist.orEmpty(), counts)
    }

    private fun customRule(cfg: Config): Rule? =
        cfg.rules.firstOrNull { it.id == Rule.CUSTOM_KEYWORDS_ID }

    private fun customKeywords(cfg: Config): List<String> = customRule(cfg)?.keywords.orEmpty()

    private fun savedKeywords(): List<String> = state.cfg?.let(::customKeywords).orEmpty()

    private fun notify(text: String) {
        state = state.copy(notice = Notice(++noticeSeq, text))
    }

    companion object {

        /**
         * 候选上限。记录上限 500 组、去重后的包名一般远小于它，但界面是一次性渲染全部候选的，
         * 不留上限时一个「装了几百个应用且都在刷通知」的机器会让这一屏变成几千行。
         */
        const val CANDIDATE_MAX = 60

        private val PKG_RE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun normalize(raw: String): List<String> =
            raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** 白名单按包名精确匹配（判定链里是 `pkg in whitelist`），所以这里只认包名格式。 */
        internal fun isPkgName(v: String): Boolean = PKG_RE.matches(v)

        /**
         * 候选 = 记录里出现过的包名 ∪ 当前白名单，排序后截断。
         *
         * 白名单项排最前：最常被找的是「刚加进去、可能要撤掉的那个」。截断放在排序**之后** ——
         * 白名单项因此一定落在截断线内，否则用户取消不掉一个不在候选里的项。
         */
        internal fun rankCandidates(whitelist: Set<String>, counts: Map<String, Int>): List<Candidate> =
            (counts.keys + whitelist)
                .map { Candidate(it, it in whitelist, counts[it] ?: 0) }
                .sortedWith(
                    compareByDescending<Candidate> { it.whitelisted }
                        .thenByDescending { it.groups }
                        .thenBy { it.pkg },
                )
                .take(CANDIDATE_MAX)
    }
}
