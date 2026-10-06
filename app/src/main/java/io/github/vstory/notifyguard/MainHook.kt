package io.github.vstory.notifyguard

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * api102 模块入口（java_init.list 声明）。
 *
 * 生命周期：onModuleLoaded → onPackageReady → installHooks
 * 热重载：onHotReloading 返回 true → 旧 HookHandle 由框架自动 unhook → installHooks 重装
 * （hook 落在 system_server 时生命周期回调是 onModuleLoaded → onSystemServerStarting，不走 onPackageReady）
 *
 * 新项目修改点：
 *  1. 包名 / 模块名（new_module_kotlin.sh 已替换；手改要同步 namespace / applicationId / label / TAG）
 *  2. installHooks：填要 hook 的类与方法（整段复制 addXxxHook 范例，改类名方法名）
 *  3. scope.list：填被 hook 应用的包名
 *
 * 日志分层（填码时不要删 DEBUG 记录）：
 *  - INFO：模块加载 / onPackageReady / hook 安装汇总 —— 正式版只留这些
 *  - DEBUG：类与方法匹配细节 + 每次拦截的参数 / 是否拦截 / 返回值 —— 真机排障「hook 成功没 / 拦没 /
 *    返回没 / 参数是啥」全靠它
 *    调用点必须直接写 `if (BuildConfig.DEBUG)`：release 下它是编译期常量 false，该分支连同其中的字符串
 *    整体不进 dex（实测 release 产物里 "[DBG]" 出现 0 次、debug 3 次），是编译期裁剪而非运行时跳过。
 *    因此不要把这层判断抽成 method —— 条件挪进 method 后调用点不再是常量，裁剪就失效了。
 */
class MainHook : XposedModule() {

    companion object {
        const val TAG = "NotifyGuard"
        private var hookOk = 0
        private var hookFail = 0
        private val hookDetail = StringBuilder()
    }

    // ===== 生命周期 =====

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "api102 module loaded")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // getClassLoader() 标了 @NonNull，Kotlin 侧类型是非空 ClassLoader：别写 != null 判断，
        // 那只会换来一条编译期 "Condition is always 'true'" 警告（Java 版没这问题）
        val cl = param.classLoader
        log(Log.INFO, TAG, "[pkg] onPackageReady, classLoader=$cl")
        installHooks(cl)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = true

    // ===== 入口：在此填要 hook 的类与方法 =====

    private fun installHooks(cl: ClassLoader) {
        hookOk = 0
        hookFail = 0
        hookDetail.clear()

        if (BuildConfig.DEBUG) {
            log(Log.DEBUG, TAG, "[DBG] installHooks start, classLoader=$cl")
        }

        // 示例（取消注释改成自己的目标）：
        // addXxxHook(cl, "com.example.target.TargetClass", "targetMethod")

        log(Log.INFO, TAG, "installHooks done: $hookOk OK / $hookFail FAIL$hookDetail")
    }

    // ===== 范例 hook（新增 hook 整段复制，改类名/方法名/逻辑即可）=====

    /** 范例：按方法名 hook；拦截时打 DEBUG、改参或改返回值。 */
    private fun addXxxHook(cl: ClassLoader, clsName: String, methodName: String) {
        val desc = "$clsName#$methodName"
        try {
            // 同名重载时 firstOrNull 拿到哪个不确定；要精确定位改用下面的 hookMethodBySig
            val target = cl.loadClass(clsName).declaredMethods.firstOrNull { it.name == methodName }
                ?: throw NoSuchMethodException(desc)
            target.isAccessible = true
            // HookBuilder 还可链 setPriority / setExceptionMode / setId
            hook(target).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // ① 进方法：参数是啥
                    if (BuildConfig.DEBUG) {
                        log(Log.DEBUG, TAG, "[DBG] >> $desc args=${chain.args}")
                    }
                    // ② 三种常用改法（按需保留一种，其余删掉）：
                    //   a) 不执行原方法，直接给返回值：
                    //      if (BuildConfig.DEBUG) log(Log.DEBUG, TAG, "[DBG] << $desc BLOCKED")
                    //      return Boolean.TRUE
                    //   b) 改第 0 个参数再继续：
                    //      return chain.proceed(arrayOf<Any?>("改后值"))
                    //   c) 默认：原方法继续
                    val result = chain.proceed()
                    // ③ 出方法：返回了啥
                    if (BuildConfig.DEBUG) {
                        log(Log.DEBUG, TAG, "[DBG] << $desc ret=$result")
                    }
                    return result
                }
            })
            markOk(desc)
        } catch (t: Throwable) {
            markFail(desc, t)
        }
    }

    /** 按 类名+方法名+参数类型 精确定位并 hook（方法重载 / 混淆后同名多参时用）。 */
    protected fun hookMethodBySig(
        cl: ClassLoader,
        clsName: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        hooker: XposedInterface.Hooker,
    ) {
        val desc = "$clsName#$methodName"
        try {
            val target: Method = cl.loadClass(clsName).getDeclaredMethod(methodName, *paramTypes)
            target.isAccessible = true
            hook(target).intercept(hooker)
            markOk(desc)
        } catch (t: Throwable) {
            markFail(desc, t)
        }
    }

    // ===== 安装结果汇总（installHooks 末尾一次性打 INFO，便于一眼看全）=====

    private fun markOk(desc: String) {
        hookOk++
        hookDetail.append("\n[OK] ").append(desc)
        if (BuildConfig.DEBUG) {
            log(Log.DEBUG, TAG, "[DBG] hook installed OK: $desc")
        }
    }

    private fun markFail(desc: String, t: Throwable) {
        hookFail++
        hookDetail.append("\n[FAIL] ").append(desc).append(": ").append(t.message)
        if (BuildConfig.DEBUG) {
            log(Log.DEBUG, TAG, "[DBG] hook install FAIL: $desc -> $t")
        }
    }
}
