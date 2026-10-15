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
import androidx.core.graphics.drawable.toBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 包名 → 应用图标与名称。给排行这类「按应用列」的地方用（记录与规则屏仍显示包名：那里要的是可复制的标识）。
 *
 * 为什么要 manifest 里的 `<queries>`：Android 11 起默认看不见别的应用，`getApplicationIcon("com.taobao.taobao")`
 * 直接抛 `NameNotFoundException`。那不是权限项 —— `AndroidManifest.xml` 里仍没有一条 `uses-permission` ——
 * 但语义上等于声明「要枚举已安装应用」，隐私说明里另记一笔。
 *
 * 解码走 androidx.core 的 `toBitmapOrNull`（core-ktx 已由 compose / activity-compose 传递引入，不必显式声明）。
 * 它内部就是「BitmapDrawable 直接取，其余建同尺寸 bitmap 后 setBounds 再 draw」—— 这段不要手写：
 * 自适应图标的 intrinsic 尺寸可能是 -1，手写时漏掉 setBounds 会得到一张全透明图，且不报错。
 *
 * 缓存用框架的 [LruCache]（自身线程安全，故这里不加锁）：图标与「查过但没有」各一个。
 * 负缓存必须留 —— 系统包、已卸载、不可见的包名会一直出现在记录里，不记住就会每次重组都去 binder 问一遍。
 */
internal object AppIcons {

    /** 图标画到这个边长（像素）。通知风格的列表里 28dp 够看清，再大就吃掉名称的宽度。 */
    private const val ICON_PX = 96

    private val labels = LruCache<String, String>(64)
    private val icons = LruCache<String, Bitmap>(64)
    private val unresolved = LruCache<String, Boolean>(256)

    fun label(context: Context, pkg: String): String {
        labels.get(pkg)?.let { return it }
        val name = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        // 回落包名而不是空串：排行榜上出现空行比出现包名更难理解
        val value = name ?: pkg
        labels.put(pkg, value)
        return value
    }

    fun icon(context: Context, pkg: String): Bitmap? {
        icons.get(pkg)?.let { return it }
        if (unresolved.get(pkg) != null) return null
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmapOrNull(ICON_PX, ICON_PX)
        }.getOrNull()
        if (bitmap == null) unresolved.put(pkg, true) else icons.put(pkg, bitmap)
        return bitmap
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
