package io.github.vstory.notifyguard.ui.screen

import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        lastTs: Long = ts,
        pkg: String = "com.example.app",
        title: String? = "t$ts",
        text: String? = "b$ts",
    ) = LogRecord(
        ts = ts,
        pkg = pkg,
        title = title,
        text = text,
        reason = "ai:0.90",
        block = block,
        slot = "nms",
        lastTs = lastTs,
    )

    /** 两档互不重叠、合计等于全窗口：判定的两态本身是划分，筛选若重叠或漏项，界面上的数就对不上。 */
    @Test
    fun filtersPartitionTheWindow() {
        val rs = listOf(
            record(1, block = true),
            record(2),
            record(3),
            record(4, block = true),
        )
        val sum = listOf(
            RecordsViewModel.Filter.Block,
            RecordsViewModel.Filter.Pass,
        ).sumOf { RecordsViewModel.filteredOf(rs, it).size }

        assertEquals(rs.size, sum)
        assertEquals(rs.size, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All).size)
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
        RecordsFilterMemory.remember(RecordsViewModel.Filter.Pass)

        assertEquals(RecordsViewModel.Filter.Pass, RecordsFilterMemory.take())
    }

    /** 搜索命中包名 / 标题 / 正文任一，且与档位是「与」的关系（不是「或」）。 */
    @Test
    fun searchMatchesAnyOfPkgTitleTextAndStacksWithFilter() {
        val rs = listOf(
            record(1, title = "限时优惠"),
            record(2, text = "限时优惠券"),
            record(3, pkg = "com.example.promo"),
            record(4, block = true, title = "限时优惠"),
        )

        // 3 号只被包名命中，不在这条搜索词里
        assertEquals(
            listOf(4L, 2L, 1L),
            RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All, "限时优惠").map { it.ts },
        )
        assertEquals(
            listOf(3L),
            RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All, "promo").map { it.ts },
        )
        assertEquals(
            listOf(4L),
            RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.Block, "限时优惠").map { it.ts },
        )
    }

    /** 大小写不敏感、纯空白等于不过滤 —— 否则「清空搜索框」会把列表也清空。 */
    @Test
    fun searchIsCaseInsensitiveAndBlankMeansNoFilter() {
        val rs = listOf(record(1, title = "Spotify"))

        assertEquals(1, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All, "spotify").size)
        assertEquals(1, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All, "  ").size)
        assertEquals(0, RecordsViewModel.filteredOf(rs, RecordsViewModel.Filter.All, "抖音").size)
    }

    /** 判定由 `block` 派生，与筛选口径同源。 */
    @Test
    fun verdictIsDerivedFromTheRecordFields() {
        assertEquals(Verdict.Block, Verdict.of(record(1, block = true)))
        assertEquals(Verdict.Pass, Verdict.of(record(1)))
    }

    /**
     * 列表键必须唯一 —— 内容身份不含包名与判定，而列表行的聚合键含这两者，撞键会让 `LazyColumn`
     * 直接抛「Key was already used」崩掉进程（Android 16 上实测过）。
     */
    @Test
    fun listKeysAreUniqueAcrossPackagesAndVerdicts() {
        fun rec(pkg: String, block: Boolean) = io.github.vstory.notifyguard.judge.LogRecord(
            ts = 1_000L,
            pkg = pkg,
            title = null,
            text = "同一段文案",
            reason = if (block) "ai:0.91" else "below_threshold:0.20",
            block = block,
            slot = null,
            count = 1,
        )
        val keys = RecordsViewModel.listKeysOf(
            listOf(rec("com.a", true), rec("com.b", true), rec("com.a", false), rec("com.a", true))
        )

        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }
}
