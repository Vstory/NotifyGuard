package io.github.vstory.notifyguard.ai

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 微调版本标识：`<下发时刻 ISO 8601 UTC>+<delta 内容摘要>`，形如 `2026-10-10T08:41:33Z+3f9a1c2b`。
 *
 * 时刻用 UTC 而非本地时区：日志与界面要能跨设备、跨时区对照，ISO 8601 是通行写法。
 * 摘要取文件字节的 SHA-256 前 4 字节 —— 它是「同毫秒重发」与「版本号没变而文件被重写」的判据，
 * 光看时刻分不出来。
 *
 * 摘要只在**这里**实现一份：模块端日志与 App 侧界面各算一遍就会各显示一串，
 * 对不上时无法判断是文件不同还是算法不同。
 */
object DeltaStamp {

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** 文件被重写但版本号未变时用的占位摘要：不猜内容，只表明「读不到」。 */
    const val UNKNOWN_DIGEST = "????????"

    fun timeOf(atMs: Long): String = synchronized(ISO) { ISO.format(Date(atMs)) }

    fun of(atMs: Long, digest: String): String = timeOf(atMs) + "+" + digest

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
}
