package io.github.vstory.notifyguard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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

/**
 * 包名 → 应用图标与名称。给排行这类「按应用列」的地方用（记录与规则屏仍显示包名：那里要的是可复制的标识）。
 *
 * 为什么要 manifest 里的 `<queries>`：Android 11 起默认看不见别的应用，`getApplicationIcon("com.taobao.taobao")`
 * 直接抛 `NameNotFoundException`。那不是权限项 —— `AndroidManifest.xml` 里仍没有一条 `uses-permission` ——
 * 但语义上等于声明「要枚举已安装应用」，隐私说明里另记一笔。
 *
 * 全部带缓存且分正负两面：取不到（未安装 / 系统包 / 不可见）也要记住，否则每次重组都去 binder 问一遍。
 * 查询本身是 binder 调用，排行是列表，逐行重复付这个成本会明显拖滚动。
 */
internal object AppIcons {

    /** 图标画到这个边长（像素）。通知风格的列表里 28dp 够看清，再大就吃掉名称的宽度。 */
    private const val ICON_PX = 96

    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Bitmap>()
    private val unresolved = HashSet<String>()

    @Synchronized
    fun label(context: Context, pkg: String): String {
        labels[pkg]?.let { return it }
        val name = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        // 回落包名而不是空串：排行榜上出现空行比出现包名更难理解
        val value = name ?: pkg
        labels[pkg] = value
        return value
    }

    @Synchronized
    fun icon(context: Context, pkg: String): Bitmap? {
        icons[pkg]?.let { return it }
        if (unresolved.contains(pkg)) return null
        val bitmap = runCatching {
            val pm = context.packageManager
            pm.getApplicationIcon(pkg).toSquareBitmap(ICON_PX)
        }.getOrNull()
        if (bitmap == null) unresolved.add(pkg) else icons[pkg] = bitmap
        return bitmap
    }

    /**
     * 自己画而不走 `Drawable.toBitmap()`（那在 androidx.core 里，本项目没引）。
     *
     * 必须显式 `setBounds`：图标 drawable 的 intrinsic 尺寸可能为 -1（自适应图标常见），
     * 不设边界就画不出东西，得到一张全透明的图。
     */
    private fun Drawable.toSquareBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
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
