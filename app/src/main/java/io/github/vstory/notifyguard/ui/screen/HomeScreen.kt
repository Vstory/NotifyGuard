package io.github.vstory.notifyguard.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.vstory.notifyguard.R

@Composable
fun HomeScreen() = PlaceholderScreen(
    title = stringResource(R.string.nav_home),
    plan = stringResource(R.string.home_plan),
)
