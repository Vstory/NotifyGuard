package io.github.vstory.notifyguard.sync

/**
 * 配置变更广播（App 发、模块端收）。
 *
 * 存在的理由：配置的 prefs 通道**在模块更新后必然断线**——注入进程拿到的
 * `LSPosedRemotePreferences` 是构造时那份内存快照（core 里按 group 缓存，重新
 * `getRemotePreferences` 拿到的是同一个对象），之后只靠框架的 push 增量更新；
 * 而 push 是 daemon 侧按「当前加载的模块服务实例」投递的，模块 apk 一重载，
 * 新预写都推给新实例，老进程那条回调再也收不到。于是「改了配置不生效、要重启 system_server」
 * 只在日志里表现为「什么都没有」。
 *
 * 广播绕开 daemon：App → system_server 的投递由 Android 框架完成（记录/标注/状态通道
 * 一直在用同一条路）。载荷**只带一个时间戳**，内容仍从镜像文件读（文件在 daemon 的模块私有目录，
 * 别的应用既写不进也读不走），所以这里不需要权限保护。
 */
object ConfigContract {

    const val ACTION_CONFIG_CHANGED = "io.github.vstory.notifyguard.CONFIG_CHANGED"

    /** App 侧写盘时刻（毫秒）：只用于日志与去重，模块端不拿它做判断。 */
    const val EXTRA_SAVED_AT = "savedAt"
}
