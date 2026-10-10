package io.github.vstory.notifyguard.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 保护出口的两件事：原因码能不能反推出类型（版本错配时不许误判），以及「开关开着就不许标注」。
 * 六类逐个走一遍 —— 只测一类会漏掉映射写错（`fgs` ↔ `foregroundService` 这种不同源的命名）。
 */
class ProtectGuardTest {

    private val allOn = ProtectSwitches()

    @Test
    fun typeOfReasonOnlyAcceptsKnownTypes() {
        assertEquals("media", ProtectGuard.typeOfReason("protect_media"))
        assertEquals("fgs", ProtectGuard.typeOfReason("protect_fgs"))
        // 模块端将来加的类型：旧版 App 必须判成「不认识」，不能当作可标或可禁
        assertNull(ProtectGuard.typeOfReason("protect_new_type"))
        assertNull(ProtectGuard.typeOfReason("protect_"))
        assertNull(ProtectGuard.typeOfReason("disabled"))
    }

    @Test
    fun eachSwitchGatesItsOwnType() {
        assertEquals("call", ProtectGuard.labelBlockedType("protect_call", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_call", allOn.copy(call = false)))

        assertEquals("alarm", ProtectGuard.labelBlockedType("protect_alarm", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_alarm", allOn.copy(alarm = false)))

        assertEquals("navigation", ProtectGuard.labelBlockedType("protect_navigation", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_navigation", allOn.copy(navigation = false)))

        assertEquals("media", ProtectGuard.labelBlockedType("protect_media", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_media", allOn.copy(media = false)))

        assertEquals("fgs", ProtectGuard.labelBlockedType("protect_fgs", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_fgs", allOn.copy(foregroundService = false)))

        assertEquals("conversation", ProtectGuard.labelBlockedType("protect_conversation", allOn))
        assertNull(ProtectGuard.labelBlockedType("protect_conversation", allOn.copy(conversation = false)))
    }

    @Test
    fun unrelatedPassReasonsAreNotBlocked() {
        assertNull(ProtectGuard.labelBlockedType("disabled", allOn))
        assertNull(ProtectGuard.labelBlockedType("whitelisted", allOn))
        assertNull(ProtectGuard.labelBlockedType("rule:kw-1", allOn))
        assertNull(ProtectGuard.labelBlockedType("ai:0.87", allOn))
        assertNull(ProtectGuard.labelBlockedType("below_threshold:0.20", allOn))
    }
}
