package io.github.vstory.notifyguard.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordDirTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createsRandomDirAndIdFile() {
        val dir = dir().resolve()!!
        assertTrue(dir.isDirectory)
        assertTrue(dir.name.startsWith(RecordDir.PREFIX))
        assertTrue(
            dir.name.removePrefix(RecordDir.PREFIX).matches(Regex("[A-Za-z0-9]{16}")),
        )
        assertEquals("100", File(tmp.root, RecordDir.ID_NAME).readText().substringBefore(":"))
    }

    @Test
    fun reusesDirWhenInstallTimeMatches() {
        val first = dir().resolve()!!
        // 新实例 = system_server 重启后重新解析：必须从 id 文件读回同一个目录
        assertEquals(first, dir().resolve()!!)
    }

    @Test
    fun reusesExistingDirWhenInstallTimeChanged() {
        val first = dir(installTime = 100).resolve()!!
        File(first, LogStore.FILE_NAME).writeText("[]")

        // 卸载重装：id 文件里的时间对不上，兜底复用已有非空目录（不新建、不丢记录）
        assertEquals(first, dir(installTime = 200).resolve()!!)
    }

    @Test
    fun brokenIdFileFallsBackToExistingDir() {
        val first = dir().resolve()!!
        File(first, LogStore.FILE_NAME).writeText("[]")
        File(tmp.root, RecordDir.ID_NAME).writeText("not an id")

        assertEquals(first, dir().resolve()!!)
    }

    @Test
    fun distinctBaseGetsDistinctSuffix() {
        val a = RecordDir(tmp.newFolder(), RecordDir.PREFIX) { 1L }.resolve()!!
        val b = RecordDir(tmp.newFolder(), RecordDir.PREFIX) { 1L }.resolve()!!
        assertNotEquals(a.name, b.name)
    }

    @Test
    fun emptyDirIsNotReused() {
        val first = dir().resolve()!!
        // 目录里没记录（system_server 建完目录就被杀过）→ 不该被当成"有数据的目录"复用
        File(tmp.root, RecordDir.ID_NAME).writeText("broken")
        val second = dir().resolve()!!
        assertNotEquals(first, second)
    }

    private fun dir(installTime: Long = 100L, base: File = tmp.root) =
        RecordDir(base, RecordDir.PREFIX) { installTime }
}
