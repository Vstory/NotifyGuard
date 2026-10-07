package io.github.vstory.notifyguard.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.vstory.notifyguard.R

@Composable
fun LearningScreen() = PlaceholderScreen(
    title = stringResource(R.string.nav_learning),
    plan = stringResource(R.string.learning_plan),
)
