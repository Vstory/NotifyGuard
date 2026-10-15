package io.github.vstory.notifyguard.ui.screen

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
 * 用官方 [ListItem] 的槽位分层（M3：overline 在 headline 上方、supporting 在下方，行高由最高元素
 * 决定 56 / 72 / 88dp）。**三个槽位的宽度是各自的、不互相抢** —— 早先把「记录里出现过 N 次」
 * 放进 trailing 与开关并排，那一串就占掉约 200dp，headline 只剩几十 dp，真机上应用名被截成
 * `A...`、`Ke...`，包名同样断掉。次数是「关于这一行的元信息」，规范给它的位置是 overline。
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
        overlineContent = {
            seenText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headlineContent = {
            Text(
                text = appLabel(pkg),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = pkg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
