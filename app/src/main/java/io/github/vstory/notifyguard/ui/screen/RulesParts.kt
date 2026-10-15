package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vstory.notifyguard.ui.AppIcon
import io.github.vstory.notifyguard.ui.appLabel

/**
 * 规则屏专用零件。
 *
 * 用官方 [ListItem]（M3 规范：行高由最高元素决定，56 / 72 / 88dp），不要自己拼 Row —— 原来把
 * 「包名 + 记录里 N 次」塞进同一行副文本，宽度不够就被省略号吃掉（真机上包名与次数一起断掉）。
 * 现在按槽位各归各位：**包名是 supporting**（标识，独占一行）、**次数是 trailing 的元信息**
 * （Material 列表控件把次要信息与动作都放右侧），开关也在 trailing。
 */

/** 整行可点、`Switch` 不吃事件（与 [SwitchRow] 同一套无障碍口径：读屏把整行当一个带名字的开关）。 */
@Composable
internal fun AppSwitchRow(
    pkg: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailingNote: String? = null,
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
            Text(
                text = pkg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { AppIcon(pkg) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                trailingNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        },
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}
