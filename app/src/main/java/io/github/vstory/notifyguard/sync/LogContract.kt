package io.github.vstory.notifyguard.sync

/**
 * 记录通道的坐标：模块端（system_server）与 App 端共用同一份常量，避免 action 名各写一份而漂移——
 * 漂移的表现是「包照装、模块照跑，只是记录永远拉不到」，CI 里靠门禁断言这两个 action 字面量在 dex 中兜住。
 */
object LogContract {

    /** App → 模块：请把记录回传过来。**不能 `setPackage()` 定向**——system_server 里的动态 receiver 不属于任何包。 */
    const val ACTION_GET_LOGS = "io.github.vstory.notifyguard.action.GET_LOGS"

    /** App → 模块：清空记录（权威源在模块端，App 侧只能请求）。 */
    const val ACTION_CLEAR_LOGS = "io.github.vstory.notifyguard.action.CLEAR_LOGS"

    /** 模块 → App：记录内容（JSON 数组字符串）。这个方向**要** `setPackage()`，App 侧 receiver 属于该包。 */
    const val ACTION_LOGS_RESULT = "io.github.vstory.notifyguard.action.LOGS_RESULT"

    /**
     * 回传载荷用字符串而非 Serializable List：广播 extra 的类型判断有坑（`CopyOnWriteArrayList`
     * 不是 `ArrayList` 子类，用 `instanceof ArrayList` 判断会永远为 false），字符串没有这层类型歧义。
     */
    const val EXTRA_LOGS = "logs"
}
