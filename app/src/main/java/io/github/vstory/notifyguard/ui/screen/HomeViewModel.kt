package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.libxposed.service.XposedService
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.judge.LogRecord
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.LogFetcher
import io.github.vstory.notifyguard.ui.UiText

/**
 * 首页的状态与统计（M4g）。
 *
 * 数字的口径是**当前记录窗口**而不是历史累计（`M4g实施方案.md` §三）：数据源是模块端 `logs.json`
 * 的快照（上限 [LogStore.MAX_RECORDS] 组、按内容聚合），清空记录后统计随之归零 —— 界面必须写明。
 *
 * 与记录屏共用同一份缓存列表，所以两屏的「组数 / 事件数」不会各算一套而在某天对不上。
 *
 * 这里刻意不放开关：开关的生效值口径（未连接置灰、拨动即下发）在设置屏已经有一份，首页再放一份
 * 就是第二处会与真实配置分歧的地方。
 */
class HomeViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: UiText = UiText.Res(R.string.service_checking),
        /** 读回的生效配置；null = 未连接或读不出 —— 此时不提观察模式（未知不等于开着）。 */
        val cfg: Config? = null,
        val stats: Stats = Stats.EMPTY,
        val fetching: Boolean = false,
    )

    /** 排行里的一行。 */
    data class AppCount(val pkg: String, val events: Int)

    /**
     * 窗口内的统计。三个判定数按**事件次数**（`count` 之和）算，与记录屏状态卡的「累计」同口径；
     * 另给组数，让「上限是组数」这件事在界面上说得通。
     */
    data class Stats(
        val groups: Int,
        val events: Int,
        val blocked: Int,
        val would: Int,
        val pass: Int,
        val top: List<AppCount>,
        val otherApps: Int,
        val otherEvents: Int,
    ) {

        companion object {

            /** 排行只到前十：长尾在手机屏幕上没有可读性，只报「其余几个应用多少次」。 */
            const val TOP_N = 10

            val EMPTY = Stats(0, 0, 0, 0, 0, emptyList(), 0, 0)

            /**
             * 纯函数，便于单测（真机上这几个分支只在特定状态下才看得到）。
             *
             * 同组记录的 `block` / `would` 必然相同 —— `LogRecord.sameGroup` 把它们纳入了聚合键，
             * 所以这里不需要处理「同组两种判定」。
             */
            fun of(records: List<LogRecord>): Stats {
                var events = 0
                var blocked = 0
                var would = 0
                val byPkg = HashMap<String, Int>()
                for (r in records) {
                    events += r.count
                    when {
                        r.block -> {
                            blocked += r.count
                            byPkg[r.pkg] = (byPkg[r.pkg] ?: 0) + r.count
                        }

                        r.would -> would += r.count
                    }
                }
                // 同数按包名升序：只按次数排会让同数项的顺序随 HashMap 迭代顺序漂，
                // 同一份数据两次进屏显示两种顺序
                val ranked = byPkg.map { AppCount(it.key, it.value) }
                    .sortedWith(compareByDescending<AppCount> { it.events }.thenBy { it.pkg })
                val top = ranked.take(TOP_N)
                val rest = ranked.drop(TOP_N)
                return Stats(
                    groups = records.size,
                    events = events,
                    blocked = blocked,
                    would = would,
                    pass = events - blocked - would,
                    top = top,
                    otherApps = rest.size,
                    otherEvents = rest.sumOf { it.events },
                )
            }
        }
    }

    var state by mutableStateOf(UiState())
        private set

    /** Binder 线程回调不能直接写 Compose 状态，一律转主线程（与设置屏同口径）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 订阅框架服务状态；返回注销句柄，界面离开时务必调用。 */
    fun bind(): () -> Unit = ConfigWriter.observe { svc -> main.post { onService(svc) } }

    /**
     * 先渲染缓存再拉一次（与记录屏同口径）：进屏立刻有内容，拉取结果回来再覆盖。
     * 超时不重试，刷新入口就在顶栏。
     */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(fetching = true)
        repaint(app)
        LogFetcher.fetch(app) {
            state = state.copy(fetching = false)
            repaint(app)
        }
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = serviceTextOf(svc),
            cfg = if (svc == null) null else ConfigWriter.load(),
        )
    }

    private fun repaint(ctx: Context) {
        state = state.copy(stats = Stats.of(LogStore.get(ctx).all()))
    }
}
