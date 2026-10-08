package io.github.vstory.notifyguard.ui.screen

import androidx.annotation.VisibleForTesting

/**
 * 记录屏的档位记忆（M4m）。
 *
 * 档位只放在 [RecordsViewModel] 里是不够的：底部栏切屏会销毁那个 ViewModel，切回来档位就回「全部」。
 * 档位是「上次看到哪」，属会话级状态，故记在进程内（用户口径：杀进程后回「全部」，与记录窗口同生命周期）。
 *
 * 首页的定向跳转**不改记忆**，走 [request] 这条一次性通道：点「已拦截」是临时查看，看完切走再回来
 * 还该是上次那档。「这次为什么进屏」与「上次看到哪」是两件事，合成一个通道就必然互相冒充 ——
 * 导航参数会被 back stack 的 saveState 一起保存与恢复，正是本轮修掉的毛病。
 */
internal object RecordsFilterMemory {

    private var last: RecordsViewModel.Filter = RecordsViewModel.Filter.All
    private var pending: RecordsViewModel.Filter? = null

    /**
     * 本次进屏该显示的档，并**消费**掉定向请求。
     *
     * 消费是必须的：留着它，用户这次切走再进来还会被送回首页那个档。
     */
    fun take(): RecordsViewModel.Filter {
        val f = pending ?: last
        pending = null
        return f
    }

    /** 首页的定向请求：只影响下一次进屏。 */
    fun request(filter: RecordsViewModel.Filter) {
        pending = filter
    }

    /** 用户在本屏切档 —— 这才是「上次使用的栏位」。 */
    fun remember(filter: RecordsViewModel.Filter) {
        pending = null
        last = filter
    }

    @VisibleForTesting
    fun reset() {
        last = RecordsViewModel.Filter.All
        pending = null
    }
}
