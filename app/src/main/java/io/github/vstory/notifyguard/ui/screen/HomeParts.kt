package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vstory.notifyguard.ui.AppIcon
import io.github.vstory.notifyguard.ui.appLabel

/**
 * 首页专用零件（M4g）。
 *
 * 不塞进 ScreenParts：那是三屏共用的渲染件，为一个屏的样式需求改公共件，改动面会摊到三屏。
 */

/**
 * 排行一行：图标 + 应用名 + 次数，占比条在 supporting 槽。
 *
 * 占比条原先单独占一行、`fillMaxWidth` 从卡片最左起算，而图标与文字有列表项的内边距 ⇒ 两行
 * 左端差 16dp，看着像两个不相干的元素。放进 supporting 槽后与 headline 同一起始边，整项才是一个整体。
 */
@Composable
internal fun RankRow(pkg: String, countText: String, fraction: Float) {
    ListItem(
        headlineContent = {
            Text(
                // 显示图标与名称而不是包名：这一屏是给用户看的，包名（com.eg.android.AlipayGphone）认不出来
                text = appLabel(pkg),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // 轨道色与指示色默认就是 surfaceVariant / primary，与原先手绘的两条 Box 同色
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        leadingContent = { AppIcon(pkg, size = 40.dp) },
        trailingContent = {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
