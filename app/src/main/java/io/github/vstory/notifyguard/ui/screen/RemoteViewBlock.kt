package io.github.vstory.notifyguard.ui.screen

import android.content.Context
import android.os.Parcel
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.vstory.notifyguard.R

/** 渲染结果。用三态而不是可空 View：没展开与渲染失败要分得开（失败要说明，没展开不用）。 */
private sealed class RemoteRender {
    class Ok(val root: View) : RemoteRender()
    data object Failed : RemoteRender()
}

/**
 * 用模块端捕获的布局还原通知在通知栏里的样子。
 *
 * 默认折叠、点了才渲染：`RemoteViews` 的渲染会执行对方布局里的代码，列表里每条都做会拖慢滚动，
 * 也可能踩到坏布局 —— 与归因面板「点了才算」同一原则。
 *
 * 渲染刻意放在 App 进程：模块端在 system_server，那里漏出异常就是系统重启（见设计方案「风险」节）。
 */
@Composable
internal fun RemoteViewBlock(base64: String) {
    val context = LocalContext.current
    var expanded by rememberSaveable(base64) { mutableStateOf(false) }
    // 结果记住：重组（滚动、标注后刷新）不该把对方的布局再 inflate 一遍
    val rendered = remember(base64, expanded) {
        if (expanded) renderRemoteViews(context, base64) else null
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (expanded) R.string.records_view_hide else R.string.records_view_show))
        }
        when (rendered) {
            // 先取成局部 val：跨 lambda 的 smart cast 不保险，直接捕获会编不过
            is RemoteRender.Ok -> {
                val root = rendered.root
                AndroidView(
                    factory = { root },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }

            RemoteRender.Failed -> Note(stringResource(R.string.records_view_failed))
            null -> Unit
        }
    }
}

/**
 * base64 `Parcel` → 可显示的 View。
 *
 * 失败是预期路径而不是异常：布局里若嵌了目标应用自己的自定义控件，本进程加载不到那个类
 * （普通应用不能加载别的应用的代码），`apply` 会抛 —— 这类通知就显示「无法渲染」。
 */
private fun renderRemoteViews(context: Context, base64: String): RemoteRender = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    val parcel = Parcel.obtain()
    val views = try {
        parcel.unmarshall(bytes, 0, bytes.size)
        // unmarshall 之后必须归零，否则读到的是缓冲区尾部
        parcel.setDataPosition(0)
        @Suppress("DEPRECATION")
        parcel.readParcelable<RemoteViews>(RemoteViews::class.java.classLoader)
    } finally {
        parcel.recycle()
    }
    if (views == null) {
        RemoteRender.Failed
    } else {
        // parent 只是用来取 LayoutParams，apply 不会把它加进去，得自己装
        val frame = FrameLayout(context)
        frame.addView(views.apply(context, frame))
        RemoteRender.Ok(frame)
    }
}.getOrElse { RemoteRender.Failed }
