package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vstory.notifyguard.R
import io.github.vstory.notifyguard.ui.text

/**
 * 规则屏（M4）：关键词与白名单。
 *
 * 提交口径见 [RulesViewModel] —— 关键词按钮提交、白名单勾选即下发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: RulesViewModel = viewModel()) {
    val state = viewModel.state
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    // 订阅只在屏活着时有效。切 tab 回来会重新订阅并立刻渲染一次，不需要额外的进屏刷新
    DisposableEffect(Unit) {
        val unbind = viewModel.bind(ctx)
        onDispose { unbind() }
    }
    // 文案必须在 Composable 上下文里解析：LaunchedEffect 的 block 不是 @Composable，
    // 里面调不了 stringResource
    val notice = state.notice
    val noticeText = notice?.text?.text()
    LaunchedEffect(notice) {
        if (notice != null && noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.noticeShown(notice)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                modifier = topBarScrollToTop(scrollState),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val connected = state.connected
            StatusCard(state.serviceText, connected, state.cfg != null)

            GroupCard(stringResource(R.string.rules_keyword_group)) {
                Note(stringResource(R.string.rules_keyword_note))
                SwitchRow(
                    label = stringResource(R.string.rules_rule_enabled),
                    note = stringResource(
                        if (viewModel.keywordsSaved()) {
                            R.string.rules_rule_enabled_note
                        } else {
                            R.string.rules_rule_empty_note
                        }
                    ),
                    checked = viewModel.ruleEnabled(),
                    enabled = viewModel.canToggleRule(),
                    onCheckedChange = viewModel::setRuleEnabled,
                )
                OutlinedTextField(
                    value = state.keywordDraft,
                    onValueChange = viewModel::dragKeywords,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.rules_keyword_hint)) },
                    minLines = 4,
                    enabled = connected,
                )
                Note(stringResource(R.string.rules_keyword_submit_note))
                Button(
                    onClick = viewModel::saveKeywords,
                    enabled = connected,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(viewModel.saveLabel().text())
                }
            }

            GroupCard(stringResource(R.string.rules_whitelist_group)) {
                Note(stringResource(R.string.rules_whitelist_note))
                Note(stringResource(R.string.rules_whitelist_candidates_note))
                OutlinedButton(
                    onClick = viewModel::refreshCandidates,
                    enabled = connected && !state.refreshing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.refreshing) R.string.rules_refreshing else R.string.rules_refresh_candidates
                        )
                    )
                }
                val candidates = state.candidates
                if (candidates.isEmpty()) {
                    Note(stringResource(R.string.rules_no_candidates))
                } else {
                    candidates.forEach { c ->
                        SwitchRow(
                            label = c.pkg,
                            note = if (c.groups > 0) {
                                stringResource(R.string.rules_candidate_seen, c.groups)
                            } else {
                                stringResource(R.string.rules_candidate_unseen)
                            },
                            checked = c.whitelisted,
                            enabled = connected,
                            onCheckedChange = { viewModel.setWhitelist(c.pkg, it) },
                        )
                    }
                    if (candidates.size >= RulesViewModel.CANDIDATE_MAX) {
                        Note(stringResource(R.string.rules_candidates_capped, RulesViewModel.CANDIDATE_MAX))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.pkgDraft,
                        onValueChange = viewModel::dragPkg,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.rules_pkg_hint)) },
                        singleLine = true,
                        enabled = connected,
                    )
                    Button(
                        onClick = viewModel::addWhitelist,
                        enabled = connected && state.pkgDraft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.rules_add_whitelist))
                    }
                }
            }
        }
    }
}
