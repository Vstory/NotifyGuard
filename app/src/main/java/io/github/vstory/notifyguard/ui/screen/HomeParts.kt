package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

/** 应用图标到应用名的间距；占比条的左缩进按它 + 图标宽算出来，两处必须同源。 */
private val RANK_ICON_GAP = 8.dp
private val RANK_ICON_SIZE = 28.dp

/** 排行一行：图标 + 应用名 + 次数，下面一条占比条（相对本屏最高的那个应用，不是占总量）。 */
@Composable
internal fun RankRow(pkg: String, countText: String, fraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 显示图标与名称而不是包名：这一屏是给用户看的，包名（com.eg.android.AlipayGphone）认不出来
            AppIcon(pkg, size = RANK_ICON_SIZE)
            Spacer(Modifier.width(RANK_ICON_GAP))
            Text(
                text = appLabel(pkg),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 占比条从应用名的左边缘起算：与上一行文字左对齐，整项才是一个整体；顶到卡片最左会跟文字
        // 差出一个图标的宽度，看着像两个不相干的元素。轨道色与指示色默认就是 surfaceVariant / primary
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RANK_ICON_SIZE + RANK_ICON_GAP),
        )
    }
}
