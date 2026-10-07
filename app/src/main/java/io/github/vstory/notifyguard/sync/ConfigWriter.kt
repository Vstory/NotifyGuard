package io.github.vstory.notifyguard.sync

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.vstory.notifyguard.judge.Config

/**
 * App 侧的配置读写（配置的唯一写方）。
 *
 * 必须经框架服务写 remote prefs：`context.getSharedPreferences` 是 App 私有文件，模块端读的是框架库，
 * 两者不同源 —— 写错时「App 改了设置、模块端毫无反应」且两端都不报错。
 *
 * 用 `commit()` 不用 `apply()`：remote prefs 的 apply 是后台线程异步 binder 提交，进程被杀即丢写。
 */
object ConfigWriter {

    private const val TAG = "NotifyGuard"

    /** 框架服务是异步连上的（App 启动时可能还没到），故用回调而不是一次性查询。 */
    @Volatile private var onServiceChange: ((XposedService?) -> Unit)? = null

    @Volatile private var service: XposedService? = null
    @Volatile private var registered = false

    fun observe(cb: (XposedService?) -> Unit) {
        onServiceChange = cb
        ensureRegistered()
        cb(service)
    }

    fun clear() {
        onServiceChange = null
    }

    fun isConnected(): Boolean = service != null

    /**
     * 给同进程的其它出口用（目前是 [DeltaWriter] 写 remote file）：框架服务只在这里注册一次监听，
     * 各处各自注册会撞上框架「registerListener 只许调一次」的限制。
     */
    internal fun currentService(): XposedService? = service

    /** 读回当前配置；未连接或内容不可解析时返回 null（调用方按「未知」处理，不要当默认值用）。 */
    fun load(): Config? {
        val s = service ?: return null
        return runCatching {
            ConfigCodec.decode(s.getRemotePreferences(ConfigReader.GROUP).getString(ConfigReader.KEY, null))
        }.getOrElse {
            Log.e(TAG, "read config failed", it)
            null
        }
    }

    fun save(config: Config): Boolean {
        val s = service ?: return false
        return runCatching {
            s.getRemotePreferences(ConfigReader.GROUP).edit()
                .putString(ConfigReader.KEY, ConfigCodec.encode(config))
                .commit()
        }.getOrElse {
            Log.e(TAG, "write config failed", it)
            false
        }
    }

    private fun ensureRegistered() {
        if (registered) return
        registered = true
        runCatching {
            // 框架要求 registerListener 只调一次，后续换人靠 onServiceChange 覆盖
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    Log.i(TAG, "xposed service connected: ${s.frameworkName}/${s.frameworkVersion}")
                    onServiceChange?.invoke(s)
                }

                override fun onServiceDied(s: XposedService) {
                    if (service === s) {
                        service = null
                        onServiceChange?.invoke(null)
                    }
                }
            })
        }.onFailure { Log.e(TAG, "register xposed service listener failed", it) }
    }
}
