package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vstory.notifyguard.ui.AppIcon
import io.github.vstory.notifyguard.ui.appLabel

/**
 * 规则屏专用零件。
 *
 * 用官方 [ListItem] 的槽位分层（M3：headline 在上、supporting 在下，行高由最高元素决定
 * 56 / 72 / 88dp）。**槽位的宽度是各自的、不互相抢** —— 早先「记录里出现过 N 次」放在 trailing
 * 与开关并排，那一串占掉约 200dp，headline 只剩几十 dp，真机上应用名被截成 `A...`、`Ke...`；
 * 后来放 overline 又让元信息压在应用名上方。现在 supporting 内自上而下：包名、出现次数。
 */

/**
 * 整行可点、`Switch` 不吃事件（与 [SwitchRow] 同一套无障碍口径：读屏把整行当一个带名字的开关）。
 *
 * [seenText] 传的是**始终非空**的 composable，内部再判空：按空值决定传不传 lambda 会让每处都要
 * 处理「空 lambda」，而空 composable 测出的高度是 0，不占位。
 */
@Composable
internal fun AppSwitchRow(
    pkg: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    seenText: String? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = appLabel(pkg),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                seenText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = { AppIcon(pkg, size = 40.dp) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}
