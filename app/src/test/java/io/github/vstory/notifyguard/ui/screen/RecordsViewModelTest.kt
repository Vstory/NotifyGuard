package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 判定筛选的窗口口径（M4i）。
 *
 * 主战场是 [RecordsViewModel.filteredOf]：真机上「被拦的条目落在窗口尾部」这种分布不好复现，
 * 而它正是「先截断再筛」这个错误实现唯一会漏掉的情形。
 */
class RecordsViewModelTest {

    private fun record(
        ts: Long,
        block: Boolean = false,
        would: Boolean = false,
        lastTs: Long = ts,
    ) = LogRecord(
        ts = ts,
        pkg = "com.example.app",
        title = "t$ts",
        text = "b$ts",
        reason = "ai:0.90",
        would = would,
        block = block,
        slot = "nms",
        lastTs = lastTs,
    )

    /** 三档互不重叠、合计等于全窗口：判定的三态本身是划分，筛选若重叠或漏项，界面上的数就对不上。 */
    @Test
    fun filtersPartitionTheWindow() {
        val rs = listOf(
            record(1, block = true),
            record(2, would = true),
            record(3),
            record(4, block = true),
        )
        val sum = listOf(
            RecordsViewModel.Filter.Block,
            RecordsViewModel.Filter.Would,
            RecordsViewModel.Filter.Pass,
        ).sumOf { RecordsViewModel.filteredOf(rs, it).size }

        assertEquals(rs.size, sum)
        assertEquals(rs.size, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All).size)
    }

    /** 观察模式下 `would=true, block=false`：它属「建议拦截」，不许同时落进「拦截」。 */
    @Test
    fun wouldBlockIsNotCountedAsBlocked() {
        val rs = listOf(record(1, would = true), record(2, would = true, block = true))

        assertEquals(1, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.Block).size)
        assertEquals(listOf(1L), RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.Would).map { it.ts })
    }

    /**
     * 被拦的条目早于展示上限时仍要命中：筛的是整个窗口，截断发生在筛完之后。
     * 先截断再筛的实现只会返回空列表，而档名叫「拦截」。
     */
    @Test
    fun blockedRecordBehindTheVisibleHeadIsStillFound() {
        val head = (1L..RecordsViewModel.LIST_LIMIT).map { record(it) }
        val blocked = record(999L, block = true)

        val hit = RecordsViewModel.filteredOf(head + blocked, RecordsViewModel.Filter.Block)

        assertEquals(listOf(999L), hit.map { it.ts })
    }

    /** 列表按最近活跃倒序：组内后续通知会推 [LogRecord.lastTs]，用首见排序会让一直在刷的组沉底。 */
    @Test
    fun rowsAreOrderedByLastActivity() {
        val rs = listOf(record(1, lastTs = 100), record(2, lastTs = 50), record(3, lastTs = 90))

        assertEquals(listOf(1L, 3L, 2L), RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All).map { it.ts })
    }

    /**
     * 切屏不丢档：从别的板块切回记录屏，取的是上次用的那档。
     *
     * ViewModel 在切屏时被销毁、进屏时重建，所以「每次重建取一次」就是「切回来还是那档」。
     */
    @Test
    fun rememberedFilterSurvivesReentry() {
        RecordsFilterMemory.reset()
        RecordsFilterMemory.remember(RecordsViewModel.Filter.Pass)

        assertEquals(RecordsViewModel.Filter.Pass, RecordsFilterMemory.take())
        assertEquals(RecordsViewModel.Filter.Pass, RecordsFilterMemory.take())
    }

    /** 进程刚起来时是「全部」：档位记忆不做持久化。 */
    @Test
    fun freshProcessStartsAtAll() {
        RecordsFilterMemory.reset()

        assertEquals(RecordsViewModel.Filter.All, RecordsFilterMemory.take())
    }

    /** 首页的定向跳转是临时查看：本次进屏用它，但它不成为「上次用的档」。 */
    @Test
    fun homeJumpAppliesOnceAndIsNotRemembered() {
        RecordsFilterMemory.reset()
        RecordsFilterMemory.remember(RecordsViewModel.Filter.Pass)
        RecordsFilterMemory.request(RecordsViewModel.Filter.Block)

        assertEquals(RecordsViewModel.Filter.Block, RecordsFilterMemory.take())
        assertEquals(RecordsViewModel.Filter.Pass, RecordsFilterMemory.take())
    }

    /** 用户手动切档压掉残留的定向请求：否则下次进屏又被送回首页那个档。 */
    @Test
    fun manualSelectionOverridesAPendingRequest() {
        RecordsFilterMemory.reset()
        RecordsFilterMemory.request(RecordsViewModel.Filter.Block)
        RecordsFilterMemory.remember(RecordsViewModel.Filter.Would)

        assertEquals(RecordsViewModel.Filter.Would, RecordsFilterMemory.take())
    }

    /** 判定由 `block` / `would` 派生，与筛选口径同源。 */
    @Test
    fun verdictIsDerivedFromTheRecordFields() {
        assertEquals(Verdict.Block, Verdict.of(record(1, block = true)))
        assertEquals(Verdict.Would, Verdict.of(record(1, would = true)))
        assertEquals(Verdict.Pass, Verdict.of(record(1)))
    }
}
