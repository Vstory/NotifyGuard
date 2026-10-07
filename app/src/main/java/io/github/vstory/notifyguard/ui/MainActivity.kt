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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.vstory.notifyguard.ui.screen.HomeScreen
import io.github.vstory.notifyguard.ui.screen.LearningScreen
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
            composable(Destination.Home.route) { HomeScreen() }
            composable(Destination.Rules.route) { RulesScreen() }
            composable(Destination.Records.route) { RecordsScreen() }
            composable(Destination.Learning.route) { LearningScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
