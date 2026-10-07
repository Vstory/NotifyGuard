package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LogRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogSinkTest {

    @Before
    fun setUp() = LogSink.resetForTest()

    @After
    fun tearDown() = LogSink.resetForTest()

    @Test
    fun flushesAtThreshold() {
        val got = ArrayList<LogRecord>()
        LogSink.flushThreshold = 3
        LogSink.deliverOverride = { got.addAll(it); true }

        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertTrue(got.isEmpty())

        LogSink.submit(rec(3))
        LogSink.awaitIdle()
        assertEquals(listOf(1L, 2L, 3L), got.map { it.ts })
    }

    @Test
    fun flushesOnTimer() {
        val got = ArrayList<LogRecord>()
        LogSink.flushThreshold = 100
        LogSink.flushDelayMs = 5
        LogSink.deliverOverride = { got.addAll(it); true }

        LogSink.submit(rec(1))
        assertTrue(waitUntil { got.isNotEmpty() })
    }

    @Test
    fun dropsOldestWhenQueueFull() {
        val got = ArrayList<LogRecord>()
        LogSink.maxPending = 3
        LogSink.flushThreshold = 1000
        LogSink.flushDelayMs = 5
        LogSink.deliverOverride = { got.addAll(it); true }

        (1..5).forEach { LogSink.submit(rec(it.toLong())) }
        assertTrue(waitUntil { got.isNotEmpty() })
        LogSink.awaitIdle()
        assertEquals(listOf(3L, 4L, 5L), got.map { it.ts })
    }

    @Test
    fun countsFailedDeliveries() {
        LogSink.flushThreshold = 2
        LogSink.deliverOverride = { false }

        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertTrue(LogSink.statsLine().contains("回流失败=2"))
    }

    @Test
    fun keepsRecordsWhileContextMissing() {
        // 没有 Context 且无注入实现：刷出必须什么都不做，记录留在队列里等下一轮
        LogSink.flushThreshold = 1
        LogSink.submit(rec(1))
        LogSink.awaitIdle()
        assertTrue(LogSink.statsLine().contains("已回流=0"))
    }

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
        text = null,
        reason = "rule:r1",
        would = true,
        block = false,
        slot = "EXT_SLOT",
        ruleId = "r1",
    )
}
