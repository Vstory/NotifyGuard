package io.github.vstory.notifyguard.judge

import android.app.Notification
import android.os.Parcel
import android.util.Base64
import android.widget.RemoteViews

/**
 * 把通知的自定义布局（`contentView`）捕获成一段可回流的字节。
 *
 * 为什么需要它：文本字段全空的通知里，内容画在 `RemoteViews` 里（图标 + 快捷按钮那类），
 * 判定端读不到、界面只能显示「无文本」。判定仍然用不到它 —— 判定跑在通知入队的同步路径上，
 * 当场要决定拦不拦，来不及渲染。捕获出来只为**在 App 里显示**，让人认出这条通知长什么样。
 *
 * 只在 [NotifySnapshot] 三段加补充字段都为空时才调用：有文本的通知捕获它是白花成本。
 *
 * 渲染不在这里做。模块端在 system_server 里，inflate 别人的布局一旦漏出异常就是系统重启；
 * 字节交回 App，由 App 进程自己渲染，崩也只崩 App。
 */
internal object ContentViewCapture {

    /**
     * 单条上限。布局通常 1–5 KB；卡在这里是因为捕获物以 base64 进记录 JSON，
     * 记录满 500 组时体积会翻倍放大。
     */
    const val MAX_BYTES = 8 * 1024

    /**
     * `Notification.contentView` 是 `@hide` 字段，编译期看不见，只能反射。
     *
     * 反射失败（字段改名、ROM 裁剪）不是异常路径，按「没有」处理：判定与界面都不会因此出错。
     */
    private val viewField by lazy {
        runCatching { Notification::class.java.getField("contentView") }.getOrNull()
    }

    /** 捕获为 base64 的 `Parcel` 字节；任何失败都返回 null。 */
    fun capture(n: Notification): String? = runCatching {
        val view = viewField?.get(n) as? RemoteViews ?: return@runCatching null
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(view, 0)
            val bytes = parcel.marshall()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@runCatching null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()
}
