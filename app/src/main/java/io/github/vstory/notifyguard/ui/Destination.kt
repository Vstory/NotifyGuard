package io.github.vstory.notifyguard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 五屏目的地（设计方案 §9 的 App 侧结构）。
 *
 * route 是导航与状态保存的稳定标识，不要拿 label 当键 —— label 是要展示给人看的，改文案不该动导航。
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "首页", Icons.Filled.Home),
    Rules("rules", "规则", Icons.AutoMirrored.Filled.List),
    Records("records", "记录", Icons.Filled.Notifications),
    Learning("learning", "学习", Icons.Filled.Check),
    Settings("settings", "设置", Icons.Filled.Settings),
}
