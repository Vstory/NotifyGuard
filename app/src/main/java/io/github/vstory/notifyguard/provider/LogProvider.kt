package io.github.vstory.notifyguard.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import io.github.vstory.notifyguard.data.LogStore
import io.github.vstory.notifyguard.sync.LogCodec
import io.github.vstory.notifyguard.sync.LogContract

/**
 * 接收模块端（system_server）回流的判定记录。
 *
 * **必须 exported**：system_server 与 App 不同 uid，非 exported 的 provider 跨 uid 访问会被系统直接拒；
 * 因此访问控制只能写在代码里 —— 只接受 SYSTEM_UID 与自身。少了这一步，任何应用都能往记录页灌假数据。
 *
 * 只实现 insert：App 侧读自己落盘的文件，不绕 provider。
 */
class LogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) return null
        val ctx = context ?: return null
        val payload = values?.getAsString(LogContract.COL_PAYLOAD) ?: return null
        LogStore.get(ctx).addAll(LogCodec.decodeList(payload))
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.io.github.vstory.notifyguard.log"
}
