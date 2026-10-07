package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.judge.LogRecord
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    fun keepsRecordsOnFailure() {
        val got = CopyOnWriteArrayList<LogRecord>()
        val fail = AtomicBoolean(true)
        val attempts = AtomicInteger()
        LogSink.flushThreshold = 2
        LogSink.retryDelayMs = 5
        LogSink.retryMaxDelayMs = 10
        LogSink.deliverOverride = { batch ->
            attempts.incrementAndGet()
            if (fail.get()) false else { got.addAll(batch); true }
        }

        LogSink.submit(rec(1))
        LogSink.submit(rec(2))
        LogSink.awaitIdle()
        assertTrue(got.isEmpty())
        assertTrue(LogSink.statsLine().contains("已回流=0"))

        fail.set(false)
        assertTrue(waitUntil { got.size == 2 })
        assertEquals(listOf(1L, 2L), got.map { it.ts })
        assertTrue(LogSink.statsLine().contains("已回流=2"))
    }

    @Test
    fun backsOffBetweenRetries() {
        val attempts = AtomicInteger()
        LogSink.flushThreshold = 1
        LogSink.retryDelayMs = 60_000
        LogSink.retryMaxDelayMs = 60_000
        LogSink.deliverOverride = { attempts.incrementAndGet(); false }

        LogSink.submit(rec(1))
        LogSink.awaitIdle()
        assertEquals(1, attempts.get())

        // 退避窗口内继续投递不应再撞一次「被 ROM 拦住的启动路径」
        (2L..5L).forEach { LogSink.submit(rec(it)) }
        LogSink.awaitIdle()
        assertEquals(1, attempts.get())
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
