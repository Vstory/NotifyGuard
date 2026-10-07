package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 骨架期的屏占位：五屏共用一个「顶栏 + 正文」外框，正文逐屏替换。
 *
 * 这里刻意只写「本屏打算做什么」，不写任何看起来像已实现的功能文案 —— 骨架期最容易出的问题是
 * 界面先说了、功能还没有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    plan: String,
    content: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = plan,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
