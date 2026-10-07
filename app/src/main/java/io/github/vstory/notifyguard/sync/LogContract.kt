package io.github.vstory.notifyguard.sync

import android.net.Uri
import io.github.vstory.notifyguard.BuildConfig

/** 记录回流通道的坐标：模块端与 App 端共用，避免把 authority / 列名各写一份。 */
object LogContract {

    const val COL_PAYLOAD = "payload"

    val AUTHORITY: String get() = BuildConfig.APPLICATION_ID + ".logs"

    val CONTENT_URI: Uri get() = Uri.parse("content://$AUTHORITY/records")
}
