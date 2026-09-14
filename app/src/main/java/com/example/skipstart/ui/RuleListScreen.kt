package com.example.skipstart.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import com.example.skipstart.engine.Rule
import kotlinx.coroutines.launch

/**
 * 规则页（阶段 5 + 阶段 8/P1）：
 * - 规则列表：名称/包名/参数摘要、启用开关、内置徽标、编辑/删除；
 * - 导入 JSON / 导出 JSON（SAF 文件选择器，全程本地）；
 * - 新建/编辑：可视化编辑器（表单 + JSON 双模式，RuleEditDialog）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleListScreen() {
    val rules by AppGraph.ruleRepository.rules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editingRule by remember { mutableStateOf<Rule?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var deletingRule by remember { mutableStateOf<Rule?>(null) }

    // 导出：SAF 创建文档
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开输出流")
                out.use { it.write(AppGraph.ruleRepository.exportJson().toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            scope.launch {
                snackbarHostState.showSnackbar(if (ok) "已导出全部规则" else "导出失败")
            }
        }
    }

    // 导入：SAF 打开文档
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("无法读取文件")
                val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
                AppGraph.ruleRepository.importJson(text)
            }
            scope.launch {
                result.fold(
                    onSuccess = { count ->
                        snackbarHostState.showSnackbar("已导入 $count 条规则")
                    },
                    onFailure = { e ->
                        snackbarHostState.showSnackbar("导入失败：${e.message}")
                    },
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("规则") },
                actions = {
                    TextButton(
                        onClick = { exportLauncher.launch("skipstart_rules.json") },
                    ) { Text("导出") }
                    TextButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                    ) { Text("导入") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creatingNew = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建规则") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = "内置规则不可删除；导入的 JSON 按 id 覆盖同名规则。" +
                    "新建/编辑支持表单与 JSON 两种模式。数据仅保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        isBuiltin = AppGraph.ruleRepository.isBuiltin(rule.id),
                        onToggle = { on ->
                            AppGraph.ruleRepository.setEnabled(rule.id, on)
                        },
                        onEdit = { editingRule = rule },
                        onDelete = { deletingRule = rule },
                    )
                }
            }
        }
    }

    if (creatingNew || editingRule != null) {
        RuleEditDialog(
            initialRule = editingRule,
            onDismiss = { creatingNew = false; editingRule = null },
            onSave = { rule ->
                AppGraph.ruleRepository.addOrUpdate(rule)
                creatingNew = false
                editingRule = null
                scope.launch { snackbarHostState.showSnackbar("规则已保存") }
            },
        )
    }

    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text("删除规则") },
            text = { Text("确定删除「${rule.name}」？删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ok = AppGraph.ruleRepository.remove(rule.id)
                        deletingRule = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (ok) "已删除" else "内置规则不可删除"
                            )
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingRule = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    isBuiltin: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                        if (isBuiltin) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "内置",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        rule.packageNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "条件 ${rule.conditions.size} · 窗口 ${rule.launchWindowMs / 1000}s · " +
                            "最多 ${rule.maxClicksPerLaunch} 次 · 冷却 ${rule.cooldownMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                if (!isBuiltin) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}
