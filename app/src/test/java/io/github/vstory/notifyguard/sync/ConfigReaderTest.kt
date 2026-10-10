package io.github.vstory.notifyguard.sync

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 配置通道的离线自检。
 *
 * 假 iface + 假 prefs 复现「框架未就绪 → 取 prefs 抛异常 → 退避重试 → 绑上并收推送」这条路径：
 * 它只出现在 system_server 启动早期，真机上撞不到（一旦退化成默认配置，两侧都不报错）。
 */
class ConfigReaderTest {

    private val originalDelays = ConfigReader.retryDelaysMs
    private val originalReader = ConfigReader.readRemoteFile

    @Before
    fun setUp() {
        ConfigReader.retryDelaysMs = longArrayOf(5, 5, 5)
        ConfigReader.resetForTest()
    }

    @After
    fun tearDown() {
        ConfigReader.retryDelaysMs = originalDelays
        ConfigReader.readRemoteFile = originalReader
        ConfigReader.resetForTest()
    }

    @Test
    fun retriesUntilPrefsAvailable() {
        val iface = FakeIface(failsBefore = 2, prefs = FakePrefs(JSON_STRICT))

        ConfigReader.start(iface)

        assertTrue("取 prefs 还没成功时应保持默认配置", ConfigReader.config().observe)
        assertTrue("两次重试后应绑上并读到远端配置", waitUntil { !ConfigReader.config().observe })
        assertEquals(3, iface.calls)
        assertFalse(ConfigReader.config().enabled)
        assertFalse(ConfigReader.config().protect.call)
    }

    @Test
    fun stopsRetryingAfterQuota() {
        val iface = FakeIface(failsBefore = Int.MAX_VALUE, prefs = FakePrefs(JSON_STRICT))

        ConfigReader.start(iface)

        Thread.sleep(120)
        assertEquals("额度用尽后不再重试", 1 + 3, iface.calls)
        assertTrue(ConfigReader.config().observe)
    }

    @Test
    fun pushUpdatesConfig() {
        val prefs = FakePrefs(null)
        ConfigReader.start(FakeIface(failsBefore = 0, prefs = prefs))
        assertTrue(ConfigReader.config().observe)

        prefs.setRemote(JSON_STRICT)

        assertTrue(waitUntil { !ConfigReader.config().observe })
    }

    @Test
    fun brokenJsonKeepsPreviousConfig() {
        val prefs = FakePrefs(JSON_STRICT)
        ConfigReader.start(FakeIface(failsBefore = 0, prefs = prefs))
        assertTrue(waitUntil { !ConfigReader.config().observe })

        prefs.setRemote("{ 不是 json")

        assertFalse("解析失败必须保留上一份有效配置", waitUntil { ConfigReader.config().observe })
        assertFalse(ConfigReader.config().enabled)
    }

    /**
     * push 静默失效时靠镜像文件自愈：prefs 永远停在旧值（框架按 group 缓存那份内存快照），
     * 文件是 App 顺手写的新值 —— 模块端除了认文件没有别的路。
     */
    @Test
    fun fileMirrorRecoversWhenPushIsLost() {
        val prefs = FakePrefs(JSON_STRICT)
        ConfigReader.readRemoteFile = { _, name ->
            if (name == ConfigReader.REMOTE_FILE) JSON_ENABLED else null
        }
        ConfigReader.start(FakeIface(failsBefore = 0, prefs = prefs))
        assertTrue(waitUntil { !ConfigReader.config().observe })
        assertFalse("启动时读的还是 prefs 里的旧值", ConfigReader.config().enabled)

        ConfigReader.verifyFromFile()

        assertTrue("核对文件后应换上新配置", ConfigReader.config().enabled)
    }

    /** 镜像文件读不到（App 还是老版本）只当兜底不可用，不动已生效的配置。 */
    @Test
    fun missingMirrorFileLeavesConfigAlone() {
        val prefs = FakePrefs(JSON_STRICT)
        ConfigReader.readRemoteFile = { _, _ -> null }
        ConfigReader.start(FakeIface(failsBefore = 0, prefs = prefs))
        assertTrue(waitUntil { !ConfigReader.config().observe })

        ConfigReader.verifyFromFile()

        assertFalse(ConfigReader.config().enabled)
        assertFalse(ConfigReader.config().observe)
    }

    /**
     * 广播触发的那条路（`force = true`）必须**不受节流**限制。
     *
     * 节流是为「记录落盘/拉取时顺手核对」设的；广播是 App 说「我刚写完」——
     * 被上一次核对挡掉的话，用户改完设置仍要等一个周期，热重载就退化成「迟早会生效」。
     */
    @Test
    fun forcedCheckIgnoresThrottle() {
        var body = JSON_STRICT
        ConfigReader.readRemoteFile = { _, _ -> body }
        ConfigReader.start(FakeIface(failsBefore = 0, prefs = FakePrefs(JSON_STRICT)))
        assertTrue(waitUntil { !ConfigReader.config().observe })

        // 普通核对一次：占住节流窗口（此时文件内容与已生效的相同，不会换配置）
        ConfigReader.verifyFromFile()
        body = JSON_ENABLED
        ConfigReader.verifyFromFile()
        assertFalse("节流窗口内的普通核对不该生效", ConfigReader.config().enabled)

        ConfigReader.verifyFromFile(force = true)
        assertTrue("广播路径必须绕过节流", ConfigReader.config().enabled)
    }

    private fun waitUntil(cond: () -> Boolean): Boolean {
        repeat(100) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private class FakeIface(private val failsBefore: Int, private val prefs: SharedPreferences) :
        XposedInterface {

        var calls = 0

        override fun getRemotePreferences(group: String): SharedPreferences {
            calls++
            if (calls <= failsBefore) throw IllegalStateException("框架未就绪")
            return prefs
        }

        override fun getFrameworkName(): String = "fake"
        override fun getFrameworkVersion(): String = "0"
        override fun getFrameworkVersionCode(): Long = 0
        override fun getFrameworkProperties(): Long = 0
        override fun log(priority: Int, tag: String?, msg: String) = Unit
        override fun log(priority: Int, tag: String?, msg: String, throwable: Throwable?) = Unit
        override fun listRemoteFiles(): Array<String> = emptyArray()
        override fun getModuleApplicationInfo(): ApplicationInfo = throw UnsupportedOperationException()
        override fun openRemoteFile(name: String): ParcelFileDescriptor = throw UnsupportedOperationException()
        override fun hook(executable: Executable): XposedInterface.HookBuilder =
            throw UnsupportedOperationException()

        override fun hookClassInitializer(clazz: Class<*>): XposedInterface.HookBuilder =
            throw UnsupportedOperationException()

        override fun deoptimize(executable: Executable): Boolean = false
        override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> =
            throw UnsupportedOperationException()

        override fun <T> getInvoker(constructor: Constructor<T>): XposedInterface.CtorInvoker<T> =
            throw UnsupportedOperationException()
    }

    /** 只读假实现：与框架下发给注入进程的那份一致（`edit()` 抛异常）。 */
    private class FakePrefs(private var value: String?) : SharedPreferences {

        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun setRemote(v: String?) {
            value = v
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, ConfigReader.KEY) }
        }

        override fun getAll(): MutableMap<String, *> = hashMapOf(ConfigReader.KEY to value)
        override fun getString(key: String?, defValue: String?): String? =
            if (key == ConfigReader.KEY) value else defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = key == ConfigReader.KEY
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("只读")

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listeners.add(requireNotNull(listener))
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listeners.remove(listener)
        }
    }

    private companion object {
        const val JSON_STRICT =
            """{"schema":1,"enabled":false,"observe":false,"protect":{"call":false}}"""

        /** 镜像文件里的那份：enabled 与 observe 都与 [JSON_STRICT] 相反，便于断言是文件生效还是 prefs 生效。 */
        const val JSON_ENABLED =
            """{"schema":1,"enabled":true,"observe":false,"protect":{"call":false}}"""
    }
}
