package io.github.vstory.notifyguard.core

import android.content.Context

/**
 * App 进程侧的 Context 持有者。
 *
 * 需要它的只有一件事：发那条「配置变了」的广播（[io.github.vstory.notifyguard.sync.ConfigWriter]）。
 * 配置写入走的是框架服务（`XposedService`），本身不需要 Context，所以这个持有者不被写读路径依赖。
 *
 * 存 applicationContext：持有者的生命周期与进程一样长，留 Activity 会泄漏。
 */
object AppContextHolder {

    @Volatile private var ctx: Context? = null

    fun install(c: Context) {
        ctx = c.applicationContext ?: c
    }

    fun get(): Context? = ctx

    internal fun resetForTest() {
        ctx = null
    }
}
