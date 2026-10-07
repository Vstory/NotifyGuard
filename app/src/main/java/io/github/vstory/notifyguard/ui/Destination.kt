package io.github.vstory.notifyguard.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.vstory.notifyguard.R

/**
 * 五屏目的地（设计方案 §9 的 App 侧结构）。
 *
 * route 是导航与状态保存的稳定标识，不要拿标签当键 —— 标签是要展示给人看的，改文案不该动导航。
 * 标签同样只存资源号：这个枚举在 Compose 之外也能被拿到，存成品文本就等于把语言钉死在枚举里。
 */
enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Rules("rules", R.string.nav_rules, Icons.AutoMirrored.Filled.List),
    Records("records", R.string.nav_records, Icons.Filled.Notifications),
    Learning("learning", R.string.nav_learning, Icons.Filled.Check),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}
