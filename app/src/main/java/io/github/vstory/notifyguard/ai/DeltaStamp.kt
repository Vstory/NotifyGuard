package io.github.vstory.notifyguard.ai

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 微调版本标识：`<下发时刻>+<delta 内容摘要>`，形如 `2026-10-10 16:41:33+3f9a1c2b`。
 *
 * 时刻按**设备当前时区**显示（用户看的是自己手机上的时间，排障时不用在脑子里做换算）；
 * 不写时区偏移：两端同设备同时区，本来该显示同一串，紧凑度比跨时区对照更要紧。
 * 摘要取文件字节的 SHA-256 前 4 字节 —— 它是「同毫秒重发」与「版本号没变而文件被重写」的判据，
 * 光看时刻分不出来。
 *
 * 摘要只在**这里**实现一份：模块端日志与 App 侧界面各算一遍就会各显示一串，
 * 对不上时无法判断是文件不同还是算法不同。
 */
object DeltaStamp {

    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 文件被重写但版本号未变时用的占位摘要：不猜内容，只表明「读不到」。 */
    const val UNKNOWN_DIGEST = "????????"

    /**
     * 每次格式化前重取默认时区：`SimpleDateFormat` 会把时区固化在实例上，
     * 用户改设置或跨时区之后旧实例仍按老时区算，显示的时间就与他手上的表对不上。
     */
    fun timeOf(atMs: Long): String = synchronized(FMT) {
        FMT.timeZone = TimeZone.getDefault()
        FMT.format(Date(atMs))
    }

    fun of(atMs: Long, digest: String): String = timeOf(atMs) + "+" + digest

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
}
