package io.github.vstory.notifyguard.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.appiconloader.AppIconLoader

/**
 * 包名 → 应用图标与名称。给排行这类「按应用列」的地方用（记录与规则屏仍显示包名：那里要的是可复制的标识）。
 *
 * 图标渲染交给 [AppIconLoader]（AOSP iconloaderlib 的移植，Apache-2.0），不要自己拿
 * `PackageManager.getApplicationIcon()` 画：那个对**自适应图标**没有阴影，白底图标会直接融进背景；
 * iconloaderlib 自带阴影与 badging 逻辑，正是系统自己画通知栏图标的做法。
 *
 * 缓存键用库的 `getIconKey()`（含 versionCode 与用户序号）而不是裸包名 —— 应用升级换了图标，
 * 裸包名键会一直吐旧图。
 *
 * 为什么要 manifest 里的 `<queries>`：Android 11 起默认看不见别的应用，`getApplicationInfo` 对
 * 不可见包抛 `NameNotFoundException`。那不是权限项 —— manifest 里仍没有一条 `uses-permission` ——
 * 但语义上等于声明「要枚举已安装应用」，隐私说明里另记一笔。
 */
internal object AppIcons {

    /** 图标渲染到这个边长（像素）。通知风格的列表里 28dp 够看清，再大就吃掉名称的宽度。 */
    private const val ICON_PX = 96

    private val labels = LruCache<String, String>(64)
    private val icons = LruCache<String, Bitmap>(64)

    /**
     * 「查过但没有」的负缓存，键是裸包名（失败时还拿不到含 versionCode 的键）。
     * 必须留：系统包、已卸载、不可见的包名会一直出现在记录里，不记住就每次重组都去 binder 问一遍。
     */
    private val unresolved = LruCache<String, Boolean>(256)

    @Volatile
    private var loader: AppIconLoader? = null

    fun label(context: Context, pkg: String): String {
        labels.get(pkg)?.let { return it }
        val name = runCatching {
            val pm = context.applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        // 回落包名而不是空串：排行榜上出现空行比出现包名更难理解
        val value = name ?: pkg
        labels.put(pkg, value)
        return value
    }

    fun icon(context: Context, pkg: String): Bitmap? {
        val app = context.applicationContext
        val info = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        // 两个都要显式判：新版 SDK 把 PackageInfo.applicationInfo 标了可空，而 Kotlin 不会从
        // applicationInfo 非空反推 info 非空
        val appInfo = info?.applicationInfo
        if (info == null || appInfo == null) {
            unresolved.put(pkg, true)
            return null
        }
        val key = AppIconLoader.getIconKey(info, app)
        icons.get(key)?.let { return it }
        if (unresolved.get(key) != null) return null
        val bitmap = runCatching { loader(app).loadIcon(appInfo) }.getOrNull()
        if (bitmap == null) unresolved.put(key, true) else icons.put(key, bitmap)
        return bitmap
    }

    /**
     * 复用同一个实例：它内部有 IconFactory 池，每次新建都要重新初始化 AOSP 那套绘制对象。
     */
    private fun loader(context: Context): AppIconLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: AppIconLoader(ICON_PX, false, context).also { loader = it }
        }
    }
}

/** 应用图标；取不到就留出一块同尺寸空白，避免同一列里名称左右跳。 */
@Composable
internal fun AppIcon(pkg: String, size: Dp = 28.dp) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = pkg) {
        value = withContext(Dispatchers.IO) { AppIcons.icon(context, pkg)?.asImageBitmap() }
    }
    Box(Modifier.size(size)) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 应用名（跟随系统语言）；未安装或不可见时回落包名。 */
@Composable
internal fun appLabel(pkg: String): String {
    val context = LocalContext.current
    val name by produceState(initialValue = pkg, key1 = pkg) {
        value = withContext(Dispatchers.IO) { AppIcons.label(context, pkg) }
    }
    return name
}
