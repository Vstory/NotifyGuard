package io.github.vstory.notifyguard.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * 时间串按设备时区显示，且**换时区后跟着变** —— `SimpleDateFormat` 会把时区固化在实例上，
 * 不重取默认时区的话，用户改设置或跨时区之后看到的是老时区的时间（与他手上的表对不上）。
 */
class DeltaStampTest {

    private lateinit var original: TimeZone

    @Before
    fun keepTimeZone() {
        original = TimeZone.getDefault()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(original)
    }

    @Test
    fun formatsInDeviceTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals("1970-01-01 08:00:00", DeltaStamp.timeOf(0L))
    }

    @Test
    fun followsTimeZoneChanges() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("1970-01-01 00:00:00", DeltaStamp.timeOf(0L))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals("1970-01-01 08:00:00", DeltaStamp.timeOf(0L))
    }

    @Test
    fun stampKeepsDigestSuffix() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("1970-01-01 00:00:00+3f9a1c2b", DeltaStamp.of(0L, "3f9a1c2b"))
    }
}
