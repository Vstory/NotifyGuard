package io.github.vstory.notifyguard.sync

/**
 * 状态通道的坐标（两端共用）。
 *
 * 与记录 / 标注通道同构：App 发请求、模块端回执，action 名写一份以防漂移
 * （漂移的表现同样是「包照装、模块照跑，只是状态页永远显示未响应」）。
 */
object StatusContract {

    /** App → 模块：请回传当前状态。**不能 `setPackage()` 定向**——system_server 里的动态 receiver 不属于任何包。 */
    const val ACTION_GET_STATUS = "io.github.vstory.notifyguard.action.GET_STATUS"

    /**
     * App → 模块：清除熔断标志（删 `safe_mode`）。
     *
     * 标志文件在 system_server 私有目录、App 读不到也删不掉，所以「熔断后怎么恢复」只能由模块端代做；
     * 删掉即走与用户手动删文件完全相同的那条恢复路径。
     */
    const val ACTION_CLEAR_SAFE_MODE = "io.github.vstory.notifyguard.action.CLEAR_SAFE_MODE"

    /** 模块 → App：状态快照（JSON）。这个方向**要** `setPackage()`，App 侧 receiver 属于该包。 */
    const val ACTION_STATUS_RESULT = "io.github.vstory.notifyguard.action.STATUS_RESULT"

    const val EXTRA_STATUS = "status"
}
