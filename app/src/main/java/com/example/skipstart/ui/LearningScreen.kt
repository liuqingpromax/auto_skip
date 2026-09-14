package com.example.skipstart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import com.example.skipstart.engine.Rule
import com.example.skipstart.service.LearnedSample
import kotlinx.coroutines.launch

/**
 * 学习页（阶段 6，对应说明书第 10 章 / 第 12 章 4 节）：
 * 选择目标包 → 学习期间不自动点击 → 用户手动点「跳过」→ 捕获样本
 * → 生成候选规则预览 → 确认保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen() {
    val enabled by AppGraph.learningController.enabled.collectAsStateWithLifecycle()
    val target by AppGraph.learningController.targetPackage.collectAsStateWithLifecycle()
    val sample by AppGraph.learningController.sample.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pkgInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("学习") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepsCard()

            if (!enabled && sample == null) {
                StartCard(
                    pkgInput = pkgInput,
                    onInput = { pkgInput = it },
                    onStart = {
                        val pkg = pkgInput.trim()
                        if (pkg.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("请输入目标包名")
                            }
                        } else {
                            AppGraph.learningController.start(pkg)
                        }
                    },
                )
            }

            if (enabled) {
                ProgressCard(
                    target = target,
                    onCancel = { AppGraph.learningController.stop() },
                )
            }

            sample?.let { s ->
                val candidate = remember(s) {
                    AppGraph.learningController.buildCandidateRule(s)
                }
                SampleCard(
                    sample = s,
                    candidate = candidate,
                    onSave = {
                        candidate?.let { AppGraph.ruleRepository.addOrUpdate(it) }
                        AppGraph.learningController.clearSample()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (candidate != null) "规则已保存，下次自动点击" else "样本无法生成规则"
                            )
                        }
                    },
                    onDiscard = {
                        AppGraph.learningController.clearSample()
                        scope.launch { snackbarHostState.showSnackbar("已放弃本次样本") }
                    },
                )
            }
        }
    }
}

@Composable
private fun StepsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("学习模式流程", style = MaterialTheme.typography.titleMedium)
            Text("1. 输入目标 App 包名，开始学习；", style = MaterialTheme.typography.bodyMedium)
            Text("2. 切到目标 App 等待开屏广告，手动点击「跳过」；", style = MaterialTheme.typography.bodyMedium)
            Text("3. 返回本应用查看捕获样本与候选规则；", style = MaterialTheme.typography.bodyMedium)
            Text(
                "4. 确认保存，下次自动点击。学习期间不会自动点击。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StartCard(
    pkgInput: String,
    onInput: (String) -> Unit,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("开始学习", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pkgInput,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标包名") },
                placeholder = { Text("如 com.autonavi.minimap") },
                supportingText = { Text("可先在「日志」页抓取节点确认包名") },
                singleLine = true,
            )
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("开始学习")
            }
        }
    }
}

@Composable
private fun ProgressCard(target: String?, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "学习模式进行中",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE65100),
            )
            Text(
                "目标包：${target ?: "?"}\n期间不会自动点击。请切换到目标 App，" +
                    "等待开屏广告出现后手动点击「跳过」按钮，再返回本应用查看捕获结果。",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onCancel) { Text("取消学习") }
        }
    }
}

@Composable
private fun SampleCard(
    sample: LearnedSample,
    candidate: Rule?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("已捕获点击", style = MaterialTheme.typography.titleMedium)
            InfoRow("包名", sample.packageName)
            sample.activityName?.let { InfoRow("Activity", it) }
            InfoRow("文本", sample.text ?: "—")
            InfoRow("描述", sample.contentDescription ?: "—")
            InfoRow("viewId", sample.viewIdResourceName ?: "—")
            InfoRow("bounds", sample.bounds ?: "—")
            InfoRow("类名", sample.className ?: "—")
            Text("候选规则预览", style = MaterialTheme.typography.titleMedium)
            if (candidate != null) {
                Text(
                    text = candidate.toJson().toString(2),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave) { Text("保存规则") }
                    TextButton(onClick = onDiscard) { Text("放弃") }
                }
            } else {
                Text(
                    "文本、描述、viewId 均为空，无法生成规则。请重新学习，点击含文字的「跳过」按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
                TextButton(onClick = onDiscard) { Text("放弃") }
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row {
        Text(
            text = "$key：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
