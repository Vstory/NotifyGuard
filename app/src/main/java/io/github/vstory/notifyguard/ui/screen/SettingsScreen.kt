package io.github.vstory.notifyguard.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.vstory.notifyguard.ui.ConfigActivity

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    PlaceholderScreen(
        title = "设置",
        plan = "规划：AI 阈值、保护类型、观察模式、安全模式（熔断状态）。",
    ) {
        // 过渡入口：五屏填完前，规则 / 开关 / 阈值 / 标注仍只能在旧配置页改。
        // 五屏最后一屏落地时连同 ConfigActivity 一起删除。
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, ConfigActivity::class.java)) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("打开旧配置页（临时）")
        }
    }
}
