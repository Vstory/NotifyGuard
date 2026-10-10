package io.github.vstory.notifyguard.sync

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.vstory.notifyguard.ai.DeltaStamp
import io.github.vstory.notifyguard.ai.SpamDelta
import io.github.vstory.notifyguard.ai.SpamModel
import java.io.FileOutputStream

/**
 * App 侧对微调量 remote file 的读写（方向：App 写、模块端读）。
 *
 * 两个必须记住的不对称：
 * - `XposedService.openRemoteFile`（这里）**文件不存在则创建**，而模块侧的
 *   `XposedInterface.openRemoteFile` 是只读、文件不在直接抛异常 —— 所以「文件先落地、版本号后写」
 *   的顺序不是优化，是正确性（反过来模块端会读到旧 delta 却登记新版本号，从此不再重试）。
 * - 这个 fd 指向**已存在的同名文件**，写入不截断就会留下上一次的尾部字节 ⇒ 解析成「项数巨大」被拒。
 */
object DeltaWriter {

    private const val TAG = "NotifyGuard"

    /** 读回结果 + 该文件的内容摘要（摘要口径与模块端日志同源，见 [DeltaStamp]）。 */
    data class Read(val parsed: SpamDelta.Parse, val digest: String)

    /** 读回已下发的 delta 并校验它与当前 base 是否配套；`null` = 拿不到文件（未连接 / 还没写过）。 */
    fun read(base: SpamModel): Read? {
        val svc = ConfigWriter.currentService() ?: return null
        val bytes = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(svc.openRemoteFile(SpamDelta.REMOTE_FILE))
                .use { it.readBytes() }
        }.getOrElse {
            Log.w(TAG, "read delta failed", it)
            return null
        }
        return Read(SpamDelta.parse(bytes, base.buckets, base.fingerprintU32), DeltaStamp.digest(bytes))
    }

    /** 返回写入内容的摘要（模块端日志里那串的另一半），失败返回 `null`。 */
    fun write(delta: SpamDelta): String? {
        val svc = ConfigWriter.currentService() ?: return null
        val bytes = delta.encode()
        return runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(svc.openRemoteFile(SpamDelta.REMOTE_FILE)).use { out ->
                // AutoCloseOutputStream 在 ParcelFileDescriptor 上是 FileOutputStream；truncate 是唯一
                // 能把「覆盖写」变成「替换写」的手段（pfd 本身没有截断语义）
                (out as FileOutputStream).channel.truncate(0)
                out.write(bytes)
                out.flush()
            }
            DeltaStamp.digest(bytes)
        }.getOrElse {
            Log.e(TAG, "write delta failed", it)
            null
        }
    }
}
