package io.github.vstory.notifyguard.sync

/**
 * 标注通道的坐标（与 [LogContract] 同构，刻意分成两个 object）。
 *
 * 不合并的理由：两组 action 的演进节奏不同——记录通道自 M1d 起结构稳定，标注通道随 M3 演进；
 * 合成一个 `Contract` 只会让「改一个忘一个」的半径变大。
 *
 * 方向的规矩与记录通道逐字相同，**别记混**：App → 模块不能 `setPackage()`，
 * 模块 → App 要 `setPackage()` 且 App 侧用 `RECEIVER_NOT_EXPORTED` 注册。
 */
object LabelContract {

    /** App → 模块：请求全量标注。 */
    const val ACTION_GET_LABELS = "io.github.vstory.notifyguard.action.GET_LABELS"

    /** App → 模块：单条新增或覆盖（同一 key 再标即用户改主意）。 */
    const val ACTION_SET_LABEL = "io.github.vstory.notifyguard.action.SET_LABEL"

    /** App → 模块：单条删除（撤销标注）。 */
    const val ACTION_DELETE_LABEL = "io.github.vstory.notifyguard.action.DELETE_LABEL"

    /** App → 模块：清空全部标注。 */
    const val ACTION_CLEAR_LABELS = "io.github.vstory.notifyguard.action.CLEAR_LABELS"

    /** 模块 → App：全量标注（JSON 数组字符串）。上面四个动作**落盘成功**后都回发一次，兼作回执。 */
    const val ACTION_LABELS_RESULT = "io.github.vstory.notifyguard.action.LABELS_RESULT"

    const val EXTRA_LABEL = "label"
    const val EXTRA_KEY = "key"
    const val EXTRA_LABELS = "labels"
}
