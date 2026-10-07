package io.github.vstory.notifyguard.sync

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAccessTest {

    /** 多用户 / 应用克隆下同一个包的 uid 不同但 appId 相同，按 appId 段比才对。 */
    @Test
    fun sameAppIdInAnotherUserIsAccepted() {
        assertTrue(ChannelAccess.isSameApp(callingUid = 10123, appUid = 10123))
        assertTrue(ChannelAccess.isSameApp(callingUid = 110123, appUid = 10123))
    }

    @Test
    fun anotherAppIsRejected() {
        assertFalse(ChannelAccess.isSameApp(callingUid = 10124, appUid = 10123))
        assertFalse(ChannelAccess.isSameApp(callingUid = 110124, appUid = 10123))
    }

    /**
     * 单用户设备上 uid 全小于 100000 ⇒ 拿 `uid / 100000` 比会恒等（任何进程都放行）。
     * 这条用例钉的就是那个形态：同用户、不同 appId 必须拒。
     */
    @Test
    fun sameUserButDifferentAppIdIsRejected() {
        assertFalse(ChannelAccess.isSameApp(callingUid = 2000, appUid = 10123))
        assertFalse(ChannelAccess.isSameApp(callingUid = 10999, appUid = 10123))
    }

    /** system_server 自身要放行：它也会走这条广播路径。 */
    @Test
    fun systemUidIsAccepted() {
        assertTrue(ChannelAccess.isSameApp(callingUid = Process.SYSTEM_UID, appUid = 10123))
    }
}
