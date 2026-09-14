package com.example.skipstart.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import com.example.skipstart.util.AccessibilityUtils

/**
 * 首页（阶段 1 起，阶段 4 增强）：
 * - 实时无障碍服务状态 + 开启引导；
 * - 总开关（一键停用自动点击，阶段 4）；
 * - 运行数据：今日跳过 / 启用规则 / 最近日志入口；
 * - 开发路线卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenLogs: () -> Unit = {}) {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }

    // 从系统无障碍设置页返回时自动刷新状态
    LifecycleResumeEffect(Unit) {
        serviceEnabled = AccessibilityUtils.isServiceEnabled(context)
        onPauseOrDispose { }
    }

    val settings by AppGraph.settingsStore.settings.collectAsStateWithLifecycle()
    val logs by AppGraph.logRepository.logs.collectAsStateWithLifecycle()
    val rules by AppGraph.ruleRepository.rules.collectAsStateWithLifecycle()
    val todayClicks = remember(logs) { AppGraph.logRepository.todayClickCount() }
    val enabledRules = remember(rules) { rules.count { it.enabled } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("开屏跳过助手") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatusCard(enabled = serviceEnabled)
            if (!serviceEnabled) {
                EnableGuideCard(
                    onOpenSettings = { AccessibilityUtils.openAccessibilitySettings(context) }
                )
            }
            MasterSwitchCard(masterEnabled = settings.masterEnabled)
            StatsCard(
                todayClicks = todayClicks,
                enabledRules = enabledRules,
                onOpenLogs = onOpenLogs,
            )
            RoadmapCard()
        }
    }
}

@Composable
private fun ServiceStatusCard(enabled: Boolean) {
    val title = if (enabled) "无障碍服务：已开启" else "无障碍服务：未开启"
    val color = if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(
                text = if (enabled) {
                    "服务运行中。高德冷启动开屏将在 8 秒内自动点击「跳过」，调试见「日志」页。"
                } else {
                    "开启后才能自动跳过目标应用的开屏广告。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnableGuideCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "如何开启", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "打开系统设置 → 无障碍 → 已安装的应用 →「开屏跳过服务」→ 开启。" +
                    "\n本应用只在本地处理数据，不会上传任何内容。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSettings) {
                Text("去开启无障碍服务")
            }
        }
    }
}

/** 总开关（阶段 4）：一键停用所有自动点击。 */
@Composable
private fun MasterSwitchCard(masterEnabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动跳过总开关", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (masterEnabled) "已开启：仅对规则目标包生效" else "已关闭：不会执行任何自动点击",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = masterEnabled,
                onCheckedChange = { on ->
                    AppGraph.settingsStore.update { it.copy(masterEnabled = on) }
                },
            )
        }
    }
}

@Composable
private fun StatsCard(
    todayClicks: Int,
    enabledRules: Int,
    onOpenLogs: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "运行数据", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatItem(label = "今日跳过", value = "$todayClicks")
                StatItem(label = "启用规则", value = "$enabledRules")
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLogs)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近日志", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "查看 →",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoadmapCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "开发路线（MVP）", style = MaterialTheme.typography.titleMedium)
            Text("✔ 阶段 1 · 项目初始化", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 2 · 节点调试工具 + 日志页", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 3 · 高德单规则自动点击", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 4 · 防误触与总开关（当前）", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 5 · 规则 JSON 导入导出（当前）", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 6 · 学习模式（当前）", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 7 · 真机验收与优化", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 8 · 可视化规则编辑器（P1）", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 9 · 评分细化 + 规则模板（P1，当前）", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
