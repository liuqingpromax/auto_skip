package com.example.skipstart.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalSnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import com.example.skipstart.capture.NodeSnapshot
import com.example.skipstart.data.LogEntry
import com.example.skipstart.service.SkipAccessibilityService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun fmt(ts: Long): String = timeFormat.format(Date(ts))

/**
 * 日志页（阶段 2）：
 * - 实时日志列表（最新在前）；
 * - 筛选：按包名、按成功/失败；
 * - 复制 / 清空；
 * - 节点调试工具：自动抓取开关 + 立即抓取按钮，快照内联展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    val logs by AppGraph.logRepository.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pkgFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var resultFilter by rememberSaveable { mutableStateOf<String?>(null) } // null | ok | fail
    var showClearDialog by remember { mutableStateOf(false) }

    val packages = remember(logs) { logs.map { it.packageName }.distinct() }
    val filtered = remember(logs, pkgFilter, resultFilter) {
        logs.filter { entry ->
            (pkgFilter == null || entry.packageName == pkgFilter) &&
                when (resultFilter) {
                    "ok" -> entry.success
                    "fail" -> !entry.success
                    else -> true
                }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                actions = {
                    TextButton(
                        onClick = {
                            val text = filtered.joinToString("\n\n") { it.toPlainText() }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("SkipStart 日志", text))
                            scope.launch {
                                snackbarHostState.showSnackbar("已复制 ${filtered.size} 条日志")
                            }
                        },
                    ) { Text("复制") }
                    TextButton(onClick = { showClearDialog = true }) { Text("清空") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DebugToolbar()
            FilterSection(
                packages = packages,
                pkgFilter = pkgFilter,
                resultFilter = resultFilter,
                onPkgFilter = { pkgFilter = it },
                onResultFilter = { resultFilter = it },
            )
            if (filtered.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        LogCard(entry)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("将删除全部日志记录（含已落盘数据），确定？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppGraph.logRepository.clear()
                        showClearDialog = false
                        scope.launch { snackbarHostState.showSnackbar("日志已清空") }
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 节点调试工具（阶段 2）。 */
@Composable
private fun DebugToolbar() {
    val autoDump by AppGraph.debugAutoDump.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("节点调试（阶段 2）", style = MaterialTheme.typography.titleSmall)
            Text(
                "自动抓取：每次窗口切换记录一次节点树；立即抓取：抓取当前窗口。" +
                    "快照仅保留本次会话，不上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动抓取", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = autoDump,
                    onCheckedChange = { AppGraph.setDebugAutoDump(it) },
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val service = SkipAccessibilityService.instance
                        if (service == null) {
                            scope.launch {
                                snackbarHostState.showSnackbar("请先开启无障碍服务")
                            }
                        } else {
                            service.dumpCurrentWindow()
                            scope.launch {
                                snackbarHostState.showSnackbar("已抓取当前窗口")
                            }
                        }
                    },
                ) { Text("立即抓取") }
            }
        }
    }
}

/** 筛选行：包名 + 成功/失败。 */
@Composable
private fun FilterSection(
    packages: List<String>,
    pkgFilter: String?,
    resultFilter: String?,
    onPkgFilter: (String?) -> Unit,
    onResultFilter: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = pkgFilter == null,
                    onClick = { onPkgFilter(null) },
                    label = { Text("全部包名") },
                )
            }
            items(packages) { pkg ->
                FilterChip(
                    selected = pkgFilter == pkg,
                    onClick = { onPkgFilter(pkg) },
                    label = { Text(pkg, maxLines = 1) },
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = resultFilter == null,
                onClick = { onResultFilter(null) },
                label = { Text("全部结果") },
            )
            FilterChip(
                selected = resultFilter == "ok",
                onClick = { onResultFilter("ok") },
                label = { Text("成功") },
            )
            FilterChip(
                selected = resultFilter == "fail",
                onClick = { onResultFilter("fail") },
                label = { Text("失败") },
            )
        }
    }
}

@Composable
private fun ColumnScope.EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "暂无日志\n开启「自动抓取」或点击「立即抓取」试试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 单条日志卡片；调试快照可展开查看节点树。 */
@Composable
private fun LogCard(entry: LogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fmt(entry.ts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ResultBadge(success = entry.success)
            }
            entry.activityName?.let { KeyValue("Activity", it) }
            KeyValue("事件", entry.eventType ?: "-")
            if (!entry.ruleName.isNullOrBlank()) KeyValue("命中规则", entry.ruleName)
            entry.score?.let { KeyValue("得分", it.toString()) }
            entry.matchedBy?.takeIf { it.isNotBlank() }?.let { KeyValue("命中条件", it) }
            KeyValue("节点文本", entry.nodeText ?: "-")
            entry.nodeDesc?.takeIf { it.isNotBlank() }?.let { KeyValue("描述", it) }
            entry.nodeViewId?.takeIf { it.isNotBlank() }?.let { KeyValue("viewId", it) }
            entry.bounds?.takeIf { it.isNotBlank() }?.let { KeyValue("bounds", it) }
            if (!entry.actionType.isNullOrBlank()) KeyValue("动作", entry.actionType)
            entry.failReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "原因：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            }
            val nodes = entry.nodes
            if (!nodes.isNullOrEmpty()) {
                Text(
                    text = "节点树（${nodes.size} 个）${if (expanded) "▾" else "▸"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
                if (expanded) {
                    NodeList(nodes)
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            text = "$key：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultBadge(success: Boolean) {
    Text(
        text = if (success) "成功" else "失败",
        style = MaterialTheme.typography.labelMedium,
        color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828),
    )
}

/** 节点树内联列表（固定最大高度，避免撑爆外层列表）。 */
@Composable
private fun NodeList(nodes: List<NodeSnapshot>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
    ) {
        items(nodes) { node -> NodeRow(node) }
    }
}

@Composable
private fun NodeRow(node: NodeSnapshot) {
    Column(
        modifier = Modifier.padding(
            start = (node.depth.coerceAtMost(12) * 8).dp,
            top = 4.dp,
            bottom = 4.dp,
            end = 8.dp,
        ),
    ) {
        val title = listOfNotNull(
            node.text?.takeIf { it.isNotBlank() },
            node.contentDescription?.takeIf { it.isNotBlank() }?.let { "desc=$it" },
        ).joinToString(" | ")
            .ifEmpty { node.className?.substringAfterLast('.') ?: "?" }
        Text(
            text = "#${node.index}${if (node.clickable) " [可点击]" else ""} $title",
            style = MaterialTheme.typography.bodySmall,
        )
        val meta = buildString {
            node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { append("id=$it  ") }
            append("bounds=${node.bounds ?: "-"}")
        }
        Text(
            text = meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 日志转纯文本（供「复制」）。 */
private fun LogEntry.toPlainText(): String = buildString {
    appendLine("${fmt(ts)}  $packageName  ${if (success) "成功" else "失败"}")
    appendLine("Activity: ${activityName ?: "-"}")
    appendLine("事件: ${eventType ?: "-"}  规则: ${ruleName ?: "-"}")
    appendLine("文本: ${nodeText ?: "-"}  描述: ${nodeDesc ?: "-"}")
    appendLine("viewId: ${nodeViewId ?: "-"}  bounds: ${bounds ?: "-"}")
    appendLine("动作: ${actionType ?: "-"}  ${failReason?.let { "原因: $it" } ?: ""}")
    score?.let { appendLine("得分: $it") }
    matchedBy?.takeIf { it.isNotBlank() }?.let { appendLine("命中条件: $it") }
}
