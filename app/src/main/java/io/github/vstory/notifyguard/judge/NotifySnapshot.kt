package io.github.vstory.notifyguard.judge

import android.app.Notification
import android.os.Bundle

/**
 * 判定所需字段的**纯数据**快照。
 *
 * 只存基元/String（不持有 Notification），判定链因此可在 JVM 单测里跑。
 */
data class NotifySnapshot(
    val pkg: String?,
    val opPkg: String?,
    val uid: Int,
    val pid: Int,
    val tag: String?,
    val id: Int,
    val userId: Int,
    val channelId: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val flags: Int,
    val group: String?,
    val category: String?,
    val hasMessagingStyle: Boolean,
) {

    val isGroupSummary: Boolean
        get() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    val isForegroundService: Boolean
        get() = flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    /** 三段文本去重后长度（§6 第 2 步：标题与正文全空 ⇒ 放行）。 */
    val textLength: Int
        get() = setOfNotNull(title, text, bigText).sumOf { it.length }

    fun hasNoText(): Boolean = title.isNullOrBlank() && text.isNullOrBlank() && bigText.isNullOrBlank()

    /** 判定与将来 AI 共用的文本口径：三段去重后按行拼接。 */
    fun judgeText(): String =
        listOfNotNull(title, text, bigText).filter { it.isNotEmpty() }.distinct().joinToString("\n")

    companion object {

        /**
         * 从 hook 参数抽字段。
         *
         * 位置映射依据真机实测签名 `(pkg, opPkg, callingUid, callingPid, tag, id, Notification, userId, …)`；
         * 漏斗与扩展槽的参数序一致，故一套映射两处通用。签名若变，最多让记录字段错位，不影响放行。
         */
        fun from(args: List<Any?>): NotifySnapshot? {
            val n = args.firstOrNull { it is Notification } as? Notification ?: return null
            val strings = args.filter { it is String }.map { it as String }
            val ints = args.filter { it is Int }.map { it as Int }
            val extras = runCatching { n.extras }.getOrNull()
            return NotifySnapshot(
                pkg = strings.getOrNull(0),
                opPkg = strings.getOrNull(1),
                uid = ints.getOrNull(0) ?: -1,
                pid = ints.getOrNull(1) ?: -1,
                tag = strings.getOrNull(2),
                id = ints.getOrNull(2) ?: -1,
                userId = ints.getOrNull(3) ?: -1,
                channelId = runCatching { n.channelId }.getOrNull(),
                title = charSeq(extras, Notification.EXTRA_TITLE),
                text = charSeq(extras, Notification.EXTRA_TEXT),
                bigText = charSeq(extras, Notification.EXTRA_BIG_TEXT),
                flags = n.flags,
                // getGroup() 是 SDK 里公开的群标识；postTime 属隐藏成员，不取（记录时间由框架日志自带）
                group = runCatching { n.group }.getOrNull(),
                category = runCatching { n.category }.getOrNull(),
                // EXTRA_MESSAGING_STYLE 是 @hide 常量，写死字符串（值见 AOSP Notification.java）
                hasMessagingStyle = extras?.containsKey("android.messagingStyle") == true,
            )
        }

        private fun charSeq(extras: Bundle?, key: String): String? =
            runCatching { extras?.getCharSequence(key)?.toString() }.getOrNull()
    }
}
