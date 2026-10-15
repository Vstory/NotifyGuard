package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.vstory.notifyguard.ui.AppIcon
import io.github.vstory.notifyguard.ui.appLabel

/**
 * 首页专用零件（M4g）。
 *
 * 不塞进 ScreenParts：那是三屏共用的渲染件，为一个屏的样式需求改公共件，改动面会摊到三屏。
 */

/** 排行一行：图标 + 应用名 + 次数 + 占比条（相对本屏最高的那个应用，不是占总量）。 */
@Composable
internal fun RankRow(pkg: String, countText: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    // 显示图标与名称而不是包名：这一屏是给用户看的，包名（com.eg.android.AlipayGphone）认不出来
                    text = appLabel(pkg),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { AppIcon(pkg) },
            trailingContent = {
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        // 占比条用官方 LinearProgressIndicator：轨道色与指示色默认就是 surfaceVariant / primary，
        // 与原先手绘的两条 Box 同色，但自带圆角轨道与端点停顿，不必自己维护
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
