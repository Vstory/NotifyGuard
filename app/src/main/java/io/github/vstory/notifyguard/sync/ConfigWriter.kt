package io.github.vstory.notifyguard.sync

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.vstory.notifyguard.judge.Config
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * 框架服务是异步连上的（App 启动时可能还没到），故用回调而不是一次性查询。
     *
     * 订阅者允许多个：设置屏与旧配置页会同时在位。此前只有一个槽位，后注册的覆盖先注册的，
     * 且任一方离开时的清空会把另一方一并静默摘掉 —— 表现是「状态显示未连接，其实连着」。
     */
    private val listeners = CopyOnWriteArrayList<(XposedService?) -> Unit>()

    @Volatile private var service: XposedService? = null
    @Volatile private var registered = false

    /**
     * 注册订阅者并**立即**回调一次当前状态；返回注销句柄。
     *
     * 这里的回调发生在调用线程上（旧状态可能是 null），界面务必在离开时注销：句柄不解除，
     * 回调闭包会一直持有界面对象。
     */
    fun observe(cb: (XposedService?) -> Unit): () -> Unit {
        listeners.add(cb)
        ensureRegistered()
        cb(service)
        return { listeners.remove(cb) }
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
        val json = ConfigCodec.encode(config)
        // 先落镜像文件、再写 remote prefs：文件是模块端 push 失效时的兜底源（见 ConfigReader.verifyFromFile），
        // 而 prefs 的写入本身是「有配置了」的信号。反序的话 push 送不到就什么都没有。
        if (!writeMirror(s, json)) Log.e(TAG, "write config mirror failed")
        return runCatching {
            s.getRemotePreferences(ConfigReader.GROUP).edit()
                .putString(ConfigReader.KEY, json)
                .commit()
        }.getOrElse {
            Log.e(TAG, "write config failed", it)
            false
        }
    }

    /** 写失败不阻塞 prefs 那条主路径：镜像只是兜底。 */
    private fun writeMirror(s: XposedService, json: String): Boolean = runCatching {
        ParcelFileDescriptor.AutoCloseOutputStream(s.openRemoteFile(ConfigReader.REMOTE_FILE)).use { out ->
            // 同 DeltaWriter：这个 fd 指向已存在的同名文件，不截断就会留下上一次的尾部字节
            (out as FileOutputStream).channel.truncate(0)
            out.write(json.toByteArray())
            out.flush()
        }
        true
    }.getOrElse { false }

    /** 一个订阅者抛异常不该让后面的收不到通知。 */
    private fun notifyService(s: XposedService?) {
        listeners.forEach { cb ->
            runCatching { cb(s) }.onFailure { Log.e(TAG, "service listener failed", it) }
        }
    }

    private fun ensureRegistered() {
        if (registered) return
        registered = true
        runCatching {
            // 框架要求 registerListener 只调一次，后续换人靠 notifyService 分发
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    Log.i(TAG, "xposed service connected: ${s.frameworkName}/${s.frameworkVersion}")
                    notifyService(s)
                }

                override fun onServiceDied(s: XposedService) {
                    if (service === s) {
                        service = null
                        notifyService(null)
                    }
                }
            })
        }.onFailure { Log.e(TAG, "register xposed service listener failed", it) }
    }
}
