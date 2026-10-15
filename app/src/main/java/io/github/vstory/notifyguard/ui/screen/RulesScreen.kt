package io.github.vstory.notifyguard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    // 候选是「几千条、以后只会更多」的列表，用 LazyColumn 而不是滚动 Column：
    // 后者把每一行都组合出来，等于把整份名单铺在内存里
    val listState = rememberLazyListState()

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

    // 文案先在这里求出来：LazyColumn 的 content 不是 @Composable，里面调不了 stringResource
    // （同本函数里 LaunchedEffect 那条口径）
    val keywordTitle = stringResource(R.string.rules_keyword_group)
    val keywordNote = stringResource(R.string.rules_keyword_note)
    val ruleEnabledLabel = stringResource(R.string.rules_rule_enabled)
    val ruleEnabledNote = stringResource(
        if (viewModel.keywordsSaved()) R.string.rules_rule_enabled_note else R.string.rules_rule_empty_note
    )
    val keywordHint = stringResource(R.string.rules_keyword_hint)
    val keywordSubmitNote = stringResource(R.string.rules_keyword_submit_note)
    val saveLabel = viewModel.saveLabel().text()
    val whitelistTitle = stringResource(R.string.rules_whitelist_group)
    val whitelistNote = stringResource(R.string.rules_whitelist_note)
    val candidatesNote = stringResource(R.string.rules_whitelist_candidates_note)
    val refreshLabel = stringResource(
        if (state.refreshing) R.string.rules_refreshing else R.string.rules_refresh_candidates
    )
    val noCandidates = stringResource(R.string.rules_no_candidates)
    val searchHint = stringResource(R.string.rules_search_hint)
    val searchEmpty = stringResource(R.string.rules_search_empty)
    val cappedNote = stringResource(R.string.rules_candidates_capped, RulesViewModel.CANDIDATE_MAX)
    val pkgHint = stringResource(R.string.rules_pkg_hint)
    val addLabel = stringResource(R.string.rules_add_whitelist)
    val title = stringResource(R.string.rules_title)

    val connected = state.connected
    val all = state.candidates
    val visible = state.visibleCandidates
    val ruleEnabled = viewModel.ruleEnabled()
    val canToggleRule = viewModel.canToggleRule()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                modifier = topBarScrollToTop(listState),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { StatusCard(state.serviceText, connected, state.cfg != null) }

            item { SectionTitle(keywordTitle) }
            item { Note(keywordNote) }
            item {
                SwitchRow(
                    label = ruleEnabledLabel,
                    note = ruleEnabledNote,
                    checked = ruleEnabled,
                    enabled = canToggleRule,
                    onCheckedChange = viewModel::setRuleEnabled,
                )
            }
            item {
                OutlinedTextField(
                    value = state.keywordDraft,
                    onValueChange = viewModel::dragKeywords,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(keywordHint) },
                    minLines = 4,
                    enabled = connected,
                )
            }
            item { Note(keywordSubmitNote) }
            item {
                Button(
                    onClick = viewModel::saveKeywords,
                    enabled = connected,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(saveLabel)
                }
            }

            item { SectionTitle(whitelistTitle) }
            item { Note(whitelistNote) }
            item { Note(candidatesNote) }
            item {
                OutlinedButton(
                    onClick = viewModel::refreshCandidates,
                    enabled = connected && !state.refreshing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(refreshLabel)
                }
            }
            if (all.isEmpty()) {
                item { Note(noCandidates) }
            } else {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::dragQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(searchHint) },
                        singleLine = true,
                        enabled = connected,
                    )
                }
                if (visible.isEmpty()) {
                    item { Note(searchEmpty) }
                }
                // 不加 key：候选的身份就是它自己那一行，用 index 就够了，而 key 写错会让列表崩
                items(visible) { c ->
                    // 包名由 AppSwitchRow 的 supporting 槽位显示（白名单是按包名精确匹配的配置，
                    // 界面上写着「微信」而配置里是包名，多用户/分身时没有包名就没有据可查）
                    AppSwitchRow(
                        pkg = c.pkg,
                        seenText = if (c.groups > 0) {
                            stringResource(R.string.rules_candidate_seen, c.groups)
                        } else {
                            stringResource(R.string.rules_candidate_unseen)
                        },
                        checked = c.whitelisted,
                        enabled = connected,
                        onCheckedChange = { viewModel.setWhitelist(c.pkg, it) },
                    )
                }
                if (visible.size < all.size) {
                    item { Note(cappedNote) }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.pkgDraft,
                        onValueChange = viewModel::dragPkg,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(pkgHint) },
                        singleLine = true,
                        enabled = connected,
                    )
                    Button(
                        onClick = viewModel::addWhitelist,
                        enabled = connected && state.pkgDraft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(addLabel)
                    }
                }
            }
        }
    }
}
