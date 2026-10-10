package io.github.vstory.notifyguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.vstory.notifyguard.BuildConfig
import io.github.vstory.notifyguard.core.AppContextHolder
import io.github.vstory.notifyguard.core.AppLogger
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.vstory.notifyguard.ui.screen.HomeScreen
import io.github.vstory.notifyguard.ui.screen.LearningScreen
import io.github.vstory.notifyguard.ui.screen.RecordsFilterMemory
import io.github.vstory.notifyguard.ui.screen.RecordsScreen
import io.github.vstory.notifyguard.ui.screen.RulesScreen
import io.github.vstory.notifyguard.ui.screen.SettingsScreen
import io.github.vstory.notifyguard.ui.theme.NotifyGuardTheme

/**
 * App 入口（launcher）。
 *
 * 只负责导航与主题（五屏各自管自己的状态），屏内容逐屏填。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.install(this)
        // App 侧版本横幅：App 进程与注入进程各自加载的是「各自启动那一刻」的代码，可能不是同一次构建，
        // 排障时要能分别看出两边各跑的是哪份代码（这行走 logcat；模块端那行走框架日志页）
        AppLogger.info(
            "app.start",
            "version=${BuildConfig.VERSION_NAME}",
            "variant=${if (BuildConfig.DEBUG) "debug" else "release"}",
        )
        enableEdgeToEdge()
        setContent {
            NotifyGuardTheme {
                NotifyGuardApp()
            }
        }
    }
}

@Composable
private fun NotifyGuardApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { destination ->
                item(
                    icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes)) },
                    label = { Text(stringResource(destination.labelRes)) },
                    selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                    onClick = {
                        navController.navigate(destination.route) {
                            // 五个 tab 平级：跳转前弹回起始目的地并保存各自状态，避免返回栈越堆越深
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(onOpenRecords = { filter ->
                    // 首页那一跳是「临时看这一类」，故走一次性请求而不是写档位记忆 —— 看完切走再回来，
                    // 该回到上次用的档。也不经 route 参数：参数会被 back stack 的 saveState 一起保存与
                    // 恢复，于是「上次用的档」会被「上次那次跳转的参数」冒充（这正是记录屏档位要么丢、
                    // 要么被拨回某一档的成因）
                    RecordsFilterMemory.request(filter)
                    navController.navigate(Destination.Records.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(Destination.Rules.route) { RulesScreen() }
            // 记录屏不带参数：档位由 RecordsFilterMemory 决定（见那段注释）
            composable(Destination.Records.route) { RecordsScreen() }
            composable(Destination.Learning.route) { LearningScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
