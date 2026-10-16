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
import io.github.vstory.notifyguard.judge.Config
import io.github.vstory.notifyguard.sync.ConfigWriter
import io.github.vstory.notifyguard.sync.StatusClient
import io.github.vstory.notifyguard.ui.UiText

/**
 * 首页的状态与统计（M4g）。
 *
 * 首页的数只有一个来源：**模块端的判定账本**（`judge/Tally`，经状态通道回传）—— 今日拦截、总拦截、
 * 今日按包名的排行。不从记录窗口聚合：记录是按内容聚合的组且会被裁、被清，拿它算「今天拦了多少」必然
 * 与账本对不上（首见在昨天、今天继续刷的那种组会被整组算到昨天）。
 *
 * 这里刻意不放开关：开关的生效值口径（未连接置灰、拨动即下发）在设置屏已经有一份，首页再放一份
 * 就是第二处会与真实配置分歧的地方。
 */
class HomeViewModel : ViewModel() {

    data class UiState(
        val connected: Boolean = false,
        val serviceText: UiText = UiText.Res(R.string.service_checking),
        /** 读回的生效配置；null = 未连接或读不出 —— 界面按「未知」处理，不当成任何开关是开着的。 */
        val cfg: Config? = null,
        /** 模块端账本；null = 模块端还没回传（未连接 / 刚装完没重启系统框架）。 */
        val totals: Totals? = null,
        val fetching: Boolean = false,
    )

    /** 模块端账本的一行快照。次数按**条**算：每次判定各计一次。 */
    data class Totals(
        val blocked: Long,
        val would: Long,
        val pass: Long,
        val since: Long,
        /** 今日拦截（模块端按本地日期维护，过零点归零）；-1 = 模块端没上报这个字段。 */
        val today: Long,
        /** 今日按包名的拦截次数（模块端过零点清零）。空 = 今天还没拦过。 */
        val todayByPkg: Map<String, Long> = emptyMap(),
    )

    /** 排行里的一行。 */
    data class AppCount(val pkg: String, val events: Long)

    /** 排行结果：前十 + 其余应用。 */
    data class Ranking(
        val top: List<AppCount> = emptyList(),
        val otherApps: Int = 0,
        val otherEvents: Long = 0L,
    )

    var state by mutableStateOf(UiState())
        private set

    /** Binder 线程回调不能直接写 Compose 状态，一律转主线程（与设置屏同口径）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 订阅框架服务状态；返回注销句柄，界面离开时务必调用。 */
    fun bind(): () -> Unit = ConfigWriter.observe { svc -> main.post { onService(svc) } }

    /** 拉一次账本。超时不重试，刷新入口就在顶栏。 */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        state = state.copy(fetching = true)
        StatusClient.fetch(app) { report ->
            state = state.copy(
                fetching = false,
                totals = report?.let {
                    Totals(
                        it.blockedTotal, it.wouldTotal, it.passTotal, it.tallySince,
                        it.blockedToday, it.todayByPkg,
                    )
                },
            )
        }
    }

    private fun onService(svc: XposedService?) {
        state = state.copy(
            connected = svc != null,
            serviceText = serviceTextOf(svc),
            cfg = if (svc == null) null else ConfigWriter.load(),
        )
    }

    companion object {

        /** 排行只到前十：长尾在手机屏幕上没有可读性，只报「其余几个应用多少次」。 */
        const val TOP_N = 10

        /**
         * 今日排行（纯函数，便于单测）。
         *
         * 同数按包名升序：只按次数排会让同数项的顺序随 map 迭代顺序漂，同一份数据两次进屏显示两种顺序。
         */
        fun rank(counts: Map<String, Long>): Ranking {
            val ranked = counts.entries
                .filter { it.value > 0 }
                .map { AppCount(it.key, it.value) }
                .sortedWith(compareByDescending<AppCount> { it.events }.thenBy { it.pkg })
            val top = ranked.take(TOP_N)
            val rest = ranked.drop(TOP_N)
            return Ranking(top = top, otherApps = rest.size, otherEvents = rest.sumOf { it.events })
        }
    }
}
