package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.judge.LogRecord
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = LogSink.resetForTest()

    @After
    fun tearDown() = LogSink.resetForTest()

    @Test
    fun flushesAtThreshold() {
        val store = store()
        LogSink.flushThreshold = 3

        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertEquals(0, store.size())

        LogSink.submit(rec(3))
        LogSink.awaitIdle()
        assertEquals(listOf(3L, 2L, 1L), store.recent(10).map { it.ts })
    }

    @Test
    fun flushesOnTimer() {
        val store = store()
        LogSink.flushThreshold = 100
        LogSink.flushDelayMs = 5

        LogSink.submit(rec(1))
        assertTrue(waitUntil { store.size() == 1 })
    }

    @Test
    fun dropsOldestWhenQueueFull() {
        val store = store()
        LogSink.maxPending = 3
        LogSink.flushThreshold = 1000
        LogSink.flushDelayMs = 10

        (1..5).forEach { LogSink.submit(rec(it.toLong())) }
        assertTrue(waitUntil { store.size() == 3 })
        assertEquals(listOf(5L, 4L, 3L), store.recent(10).map { it.ts })
        assertTrue(LogSink.statsLine().contains("丢弃=2"))
    }

    /**
     * 拉取前必须把缓冲刷进文件：缓冲里的是「刚发生的通知」，用户打开 App 就该看到。
     *
     * 从 worker 上取快照 —— 这正是生产路径（LogChannel 的 `onWorker`）的调用方式。
     * 用「提交 flush 再等它」的旧写法会在这里等自己 3 秒后超时、缓冲仍未落盘，本用例即该缺陷的回归。
     */
    @Test
    fun snapshotFlushesBufferedRecords() {
        val store = store()
        LogSink.flushThreshold = 100
        LogSink.flushDelayMs = 60_000

        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertEquals(0, store.size())

        var out: String? = null
        LogSink.onWorker { out = LogSink.snapshotJson() }
        LogSink.awaitIdle()

        val json = requireNotNull(out)
        assertEquals(2, store.size())
        assertTrue(json.contains("\"ts\":1") && json.contains("\"ts\":2"))
    }

    /** 目录不可写时记录留在缓冲里（不出队），且不能把失败当成功计数。 */
    @Test
    fun keepsRecordsWhenPersistFails() {
        val blocker = File(tmp.root, "blocker")
        blocker.writeText("x")
        LogSink.storeOverride = LogStore(File(blocker, LogStore.FILE_NAME))
        LogSink.flushThreshold = 1

        LogSink.submit(rec(1))
        LogSink.awaitIdle()
        assertTrue(LogSink.statsLine().contains("已落盘=0"))
    }

    /** 目录还没解析出来时刷出什么都不做，记录留在队列里等下一次。 */
    @Test
    fun keepsRecordsWhileStoreMissing() {
        LogSink.flushThreshold = 1
        LogSink.submit(rec(1))
        LogSink.awaitIdle()
        assertTrue(LogSink.statsLine().contains("已落盘=0"))
    }

    @Test
    fun clearAllEmptiesStore() {
        val store = store()
        LogSink.flushThreshold = 2
        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertTrue(waitUntil { store.size() == 2 })

        LogSink.clearAll()
        LogSink.awaitIdle()
        assertEquals(0, store.size())
        assertEquals(0, LogStore(File(tmp.root, LogStore.FILE_NAME)).size())
    }

    private fun store(): LogStore =
        LogStore(File(tmp.root, LogStore.FILE_NAME)).also { LogSink.storeOverride = it }

    private fun waitUntil(cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun rec(ts: Long) = LogRecord(
        ts = ts,
        pkg = "com.x",
        title = "t",
        // 每条一个正文：同文本会被聚合，而这些用例要的是逐条独立
        text = "t$ts",
        reason = "rule:r1",
        would = true,
        block = false,
        slot = "EXT_SLOT",
        ruleId = "r1",
    )
}
