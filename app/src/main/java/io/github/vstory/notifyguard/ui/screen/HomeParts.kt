package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 占比条：宽度按比例。自绘两条 Box，不为两张卡片引图表库。 */
@Composable
internal fun ShareBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** 排行一行：包名 + 次数 + 占比条（相对本屏最高的那个应用，不是占总量）。 */
@Composable
internal fun RankRow(pkg: String, countText: String, fraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 显示图标与名称而不是包名：这一屏是给用户看的，包名（com.eg.android.AlipayGphone）认不出来
            AppIcon(pkg)
            Spacer(Modifier.width(8.dp))
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
        ShareBar(fraction)
    }
}
