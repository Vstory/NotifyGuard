package io.github.vstory.notifyguard.core

import io.github.vstory.notifyguard.data.ModuleDir
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 代际仲裁的判据测试。热重载后「旧代自行退场」全靠这套判定，判错的方向只有两种，
 * 而两种的后果不对称：把当前代误判成过期 ⇒ 模块停摆；把过期代当成当前代 ⇒ 两代并存（脏但可用）。
 * 故这里重点钉住「不该判过期」的几种情形。
 */
class GenerationTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("notifyguard-gen", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        ModuleDir.dirOverride = dir
        Generation.resetForTest()
        ModuleTeardown.resetForTest()
    }

    @After
    fun tearDown() {
        Generation.resetForTest()
        ModuleTeardown.resetForTest()
        ModuleDir.dirOverride = null
        dir.deleteRecursively()
    }

    @Test
    fun publishIsVisibleToTheSameGeneration() {
        val gen = Generation.publish()
        assertTrue(gen > 0)
        assertFalse("刚登记的一代不该被自己判过期", Generation.stale())
    }

    /** 旧代读到新代写下的号 ⇒ 过期。这是热重载能退场的全部依据。 */
    @Test
    fun aNewerGenerationMakesTheOldOneStale() {
        Generation.publish()
        Generation.file().writeText((Generation.mine() + 1).toString())
        Generation.invalidateCacheForTest()
        assertTrue(Generation.stale())
    }

    /** 读不到代际文件时判「不过期」：宁可两代并存，也不能让唯一在服务的一代自己停掉。 */
    @Test
    fun unreadableStampKeepsServing() {
        Generation.publish()
        assertTrue(Generation.file().delete())
        Generation.invalidateCacheForTest()
        assertFalse(Generation.stale())
    }

    /** 没登记过就说自己过期是最坏的误判（判定链会在装配前就整段放行）。 */
    @Test
    fun unpublishedGenerationIsNeverStale() {
        Generation.file().writeText("123456")
        assertFalse(Generation.stale())
    }

    /** 内容损坏（写了非数字）同样按「不过期」处理。 */
    @Test
    fun garbageStampKeepsServing() {
        Generation.publish()
        Generation.file().writeText("not-a-number")
        Generation.invalidateCacheForTest()
        assertFalse(Generation.stale())
    }

    /** 判定热路径每次都会问一次，故缓存：一秒内的重复提问不读文件。 */
    @Test
    fun verdictIsCachedWithinTheWindow() {
        Generation.publish()
        Generation.file().writeText((Generation.mine() + 1).toString())
        assertFalse("缓存窗口内不应重新读文件", Generation.stale())
        Generation.invalidateCacheForTest()
        assertTrue(Generation.stale())
    }
    // [ModuleTeardown.expired] 不在这里测：它的释放动作是进程级的不可逆操作（关闭各常驻线程池），
    // 在共享 JVM 的单测进程里跑一次就会污染后续测试。退场本身靠真机验收（见 M4o 验收单）。
}
