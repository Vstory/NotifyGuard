package io.github.vstory.notifyguard.sync

import android.net.Uri
import io.github.vstory.notifyguard.BuildConfig

/** 记录回流通道的坐标：模块端与 App 端共用，避免把 authority / 列名各写一份。 */
object LogContract {

    const val COL_PAYLOAD = "payload"

    /**
     * App 侧「我在跑，把积压的记录送过来」的信号。
     *
     * 必须存在这条反向信号：ColorOS 的 OplusAppStartupManager 会拦「system_server 经 provider 拉起 App」，
     * 只有 App 进程已经活着时 insert 才走得通；App 起来后广播一次，模块端就能把缓冲补送出去。
     */
    const val ACTION_FLUSH = "io.github.vstory.notifyguard.action.FLUSH"

    val AUTHORITY: String get() = BuildConfig.APPLICATION_ID + ".logs"

    val CONTENT_URI: Uri get() = Uri.parse("content://$AUTHORITY/records")
}
