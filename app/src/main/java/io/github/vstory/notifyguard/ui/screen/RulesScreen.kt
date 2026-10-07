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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 规则屏（M4）：关键词与白名单。
 *
 * 提交口径见 [RulesViewModel] —— 关键词按钮提交、白名单勾选即下发。
 *
 * 屏内说明文字**刻意不重复**任何被产物门禁断言的字面量（见 build-ci.yml ③i）：说明文字顺带覆盖了
 * 断言词，那条断言就变成「被两处同时撑着」，任一处单独失效都发现不了（③g 与 ③h 都踩过）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: RulesViewModel = viewModel()) {
    val state = viewModel.state
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 订阅只在屏活着时有效。切 tab 回来会重新订阅并立刻渲染一次，不需要额外的进屏刷新
    DisposableEffect(Unit) {
        val unbind = viewModel.bind(ctx)
        onDispose { unbind() }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it.text)
            viewModel.noticeShown(it)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("规则") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val connected = state.connected
            StatusCard(state.serviceText, connected, state.cfg != null)

            GroupCard("关键词规则") {
                Note("命中即拦。按小写后的子串匹配（不是正则），所以 AD 与 ad 是同一条。")
                SwitchRow(
                    label = "启用这条规则",
                    note = if (viewModel.keywordsSaved()) {
                        "关掉后关键词仍留在配置里，只是不再参与判定"
                    } else {
                        "还没有关键词：填好并保存之后这个开关才有内容可启停"
                    },
                    checked = viewModel.ruleEnabled(),
                    enabled = viewModel.canToggleRule(),
                    onCheckedChange = viewModel::setRuleEnabled,
                )
                OutlinedTextField(
                    value = state.keywordDraft,
                    onValueChange = viewModel::dragKeywords,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("每行一个关键词") },
                    minLines = 4,
                    enabled = connected,
                )
                Note("打到一半就下发会被按半截词拦通知，所以只由下面的按钮提交；保存即下发，模块端立刻生效。")
                Button(
                    onClick = viewModel::saveKeywords,
                    enabled = connected,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(viewModel.saveLabel())
                }
            }

            GroupCard("白名单") {
                Note("名单里的应用跳过 AI 识别；关键词规则照常生效（那是显式要求拦的）。")
                Note("候选取自模块端记录里出现过的包名 —— 有记录就是发过通知的那些，不必额外申请「读取已安装应用」。")
                OutlinedButton(
                    onClick = viewModel::refreshCandidates,
                    enabled = connected && !state.refreshing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (state.refreshing) "正在拉取…" else "从模块端刷新候选")
                }
                val candidates = state.candidates
                if (candidates.isEmpty()) {
                    Note("暂时没有候选：模块端还没记下任何通知，或本页与记录页都还没拉取过。可以直接在下面填包名。")
                } else {
                    candidates.forEach { c ->
                        SwitchRow(
                            label = c.pkg,
                            note = if (c.groups > 0) "记录里出现过 ${c.groups} 次" else "记录里还没出现过",
                            checked = c.whitelisted,
                            enabled = connected,
                            onCheckedChange = { viewModel.setWhitelist(c.pkg, it) },
                        )
                    }
                    if (candidates.size >= RulesViewModel.CANDIDATE_MAX) {
                        Note("只列出前 ${RulesViewModel.CANDIDATE_MAX} 个（已放行的排在前面）。要放行的应用不在其中时，在下面直接填包名。")
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
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        enabled = connected,
                    )
                    Button(
                        onClick = viewModel::addWhitelist,
                        enabled = connected && state.pkgDraft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("加入白名单")
                    }
                }
            }
        }
    }
}
