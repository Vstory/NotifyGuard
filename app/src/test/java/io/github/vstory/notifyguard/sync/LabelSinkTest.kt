package io.github.vstory.notifyguard.sync

import io.github.vstory.notifyguard.data.LabelStore
import io.github.vstory.notifyguard.judge.LabelRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LabelSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = LabelSink.resetForTest()

    private fun store(name: String = "labels.json") = LabelStore(File(tmp.root, name))

    private fun label(key: String = "1:com.x") =
        LabelRecord(key, ts = 1, pkg = "com.x", text = "t", spam = true, at = 5, modelVersion = 7)

    /** 未绑定（Context 没取到 / 目录不可用）时不能回发空列表：那会让 App 以为标注被清空了。 */
    @Test
    fun missingStoreYieldsNoSnapshot() {
        LabelSink.resetForTest()
        assertNull(LabelSink.snapshotJson())
        assertFalse(LabelSink.mutate { it.upsert(label()) })
    }

    @Test
    fun mutateReportsPersistedState() {
        LabelSink.storeOverride = store()
        assertTrue(LabelSink.mutate { it.upsert(label()) })
        assertEquals(listOf(label()), LabelCodec.decodeList(LabelSink.snapshotJson()))
    }

    /** 文件损坏时快照不可信 ⇒ 不回发（App 走超时提示），且写入被拒。 */
    @Test
    fun brokenStoreYieldsNoSnapshotAndRejectsWrites() {
        val f = File(tmp.root, "labels.json").apply { writeText("{ not an array") }
        LabelSink.storeOverride = LabelStore(f)
        assertNull(LabelSink.snapshotJson())
        assertFalse(LabelSink.mutate { it.upsert(label()) })
        assertEquals("{ not an array", f.readText())
    }

    /** 抛异常不能把 worker 带走：捕获后按「未落盘」处理。 */
    @Test
    fun throwingOperationIsTreatedAsFailure() {
        LabelSink.storeOverride = store()
        assertFalse(LabelSink.mutate { throw IllegalStateException("boom") })
    }
}
