package io.github.vstory.notifyguard.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModuleDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(tmp.root, "notifyguard")
        ModuleDir.dirOverride = dir
    }

    @After
    fun tearDown() {
        ModuleDir.dirOverride = null
    }

    /** 熔断标志要在没有 Context 的注入早期可读 ⇒ 默认路径必须是编译期常量。 */
    @Test
    fun defaultPathIsAFixedConstant() {
        ModuleDir.dirOverride = null
        assertEquals("/data/misc/notifyguard", ModuleDir.PATH)
        assertEquals(File("/data/misc/notifyguard/safe_mode"), ModuleDir.safeMode())
        assertEquals(File("/data/misc/notifyguard/logs.json"), ModuleDir.logs())
        assertEquals(File("/data/misc/notifyguard/labels.json"), ModuleDir.labels())
    }

    @Test
    fun createsDirectoryAndStamp() {
        assertEquals(dir, ModuleDir.prepare(100L))
        assertTrue(dir.isDirectory)
        assertEquals("100", File(dir, "install_time").readText())
    }

    @Test
    fun keepsRecordsWhenInstallTimeMatches() {
        ModuleDir.prepare(100L)
        ModuleDir.logs().writeText("[]")
        ModuleDir.prepare(100L)
        assertTrue(ModuleDir.logs().exists())
    }

    /** 重装（firstInstallTime 变）⇒ 旧记录作废：固定路径没有「换随机目录」来顺手清空，必须显式做。 */
    @Test
    fun wipesRecordsOnReinstall() {
        ModuleDir.prepare(100L)
        ModuleDir.logs().writeText("[]")
        ModuleDir.prepare(200L)
        assertFalse(ModuleDir.logs().exists())
        assertEquals("200", File(dir, "install_time").readText())
    }

    /** 重装只作废记录：标注是用户手工劳动，比流水贵 —— 被顺手一起清掉就等于逼用户重标。 */
    @Test
    fun keepsLabelsOnReinstall() {
        ModuleDir.prepare(100L)
        ModuleDir.labels().writeText("[]")
        ModuleDir.prepare(200L)
        assertFalse(ModuleDir.logs().exists())
        assertTrue(ModuleDir.labels().exists())
    }

    /** 取不到安装时间（PM 查询失败返回 -1）时不许动用户数据，也不许写错标记。 */
    @Test
    fun keepsEverythingWhenInstallTimeUnknown() {
        ModuleDir.prepare(100L)
        ModuleDir.logs().writeText("[]")
        ModuleDir.prepare(-1L)
        assertTrue(ModuleDir.logs().exists())
        assertEquals("100", File(dir, "install_time").readText())
    }

    @Test
    fun replacesStrayFileWithDirectory() {
        dir.writeText("x")
        assertEquals(dir, ModuleDir.prepare(100L))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun returnsNullWhenDirectoryCannotBeCreated() {
        val blocker = File(tmp.root, "blocker").apply { writeText("x") }
        ModuleDir.dirOverride = File(blocker, "notifyguard")
        assertNull(ModuleDir.prepare(100L))
    }
}
