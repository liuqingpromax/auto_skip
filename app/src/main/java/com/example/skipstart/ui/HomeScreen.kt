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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * 服务状态三态（v0.2.0 机型适配新增）：
 * - OFF：系统里确实没有授权；
 * - GRANTED_IDLE：系统已授权，但本进程内服务还没连上（进程刚起或被杀后待拉起）；
 * - RUNNING：服务已连接，功能可用。
 *
 * 注意：连接态只反映**本进程**的绑定情况——冷启动刚打开 App 时服务往往还没连上，
 * 这属于正常现象，所以此态用中性提示，而不是报错。
 */
private enum class ServiceState { OFF, GRANTED_IDLE, RUNNING }

/**
 * 首页（阶段 1 起，v0.2.0 增强无障碍状态诊断）：
 * - 实时无障碍服务状态（三态）+ 一键刷新；
 * - 总开关（一键停用自动点击）；
 * - 运行数据：今日跳过 / 启用规则 / 最近日志入口；
 * - 厂商设置路径提示与排查建议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenLogs: () -> Unit = {}) {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }
    var connected by remember { mutableStateOf(AccessibilityUtils.serviceConnected.value) }
    var expanded by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // 从系统无障碍设置页返回时自动刷新状态
    LifecycleResumeEffect(Unit) {
        granted = AccessibilityUtils.isServiceEnabled(context)
        connected = AccessibilityUtils.serviceConnected.value
        refreshTick++
        onPauseOrDispose { }
    }

    val state = when {
        !granted -> ServiceState.OFF
        !connected -> ServiceState.GRANTED_IDLE
        else -> ServiceState.RUNNING
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceStatusCard(
                state = state,
                onRefresh = {
                    granted = AccessibilityUtils.isServiceEnabled(context)
                    connected = AccessibilityUtils.serviceConnected.value
                    refreshTick++
                },
                onToggleDetail = { expanded = !expanded },
                expanded = expanded,
                refreshTick = refreshTick,
            )
            if (state != ServiceState.RUNNING) {
                EnableGuideCard(
                    state = state,
                    onOpenSettings = { AccessibilityUtils.openAccessibilitySettings(context) },
                    onOpenAppDetails = { AccessibilityUtils.openAppDetailsSettings(context) },
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
private fun ServiceStatusCard(
    state: ServiceState,
    onRefresh: () -> Unit,
    onToggleDetail: () -> Unit,
    expanded: Boolean,
    refreshTick: Int,
) {
    val context = LocalContext.current
    val (title, color, body) = when (state) {
        ServiceState.OFF -> Triple(
            "无障碍服务：未开启",
            Color(0xFFC62828),
            "开启后才能自动跳过目标应用的开屏广告。",
        )
        ServiceState.GRANTED_IDLE -> Triple(
            "无障碍服务：已开启（服务待连接）",
            Color(0xFFE65100),
            "系统已记录授权。首次打开本应用时服务可能还没连上，正常使用一段时间或切到" +
                "目标 App 后会自动连接；若一直如此，按下面步骤处理。",
        )
        ServiceState.RUNNING -> Triple(
            "无障碍服务：运行中",
            Color(0xFF2E7D32),
            "服务已连接。目标 App 冷启动开屏时，会在窗口内自动点一次「跳过」，" +
                "详情见「日志」页。",
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("刷新状态") }
                TextButton(onClick = onToggleDetail) {
                    Text(if (expanded) "收起诊断信息" else "机型排查")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Text("当前机型：${AccessibilityUtils.romBrand()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "系统已启用的无障碍服务：" +
                        AccessibilityUtils.enabledServiceIds(context)
                            .joinToString("；")
                            .ifBlank { "（读不到，属正常，部分 ROM 不允许读取）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "本应用的服务组件名：${AccessibilityUtils.serviceComponentName(context)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AccessibilityUtils.troubleshootingTips().forEach { tip ->
                    Text("· $tip", style = MaterialTheme.typography.bodySmall)
                }
                // refreshTick 参与 remember，用于强制重算诊断文本
                Text(
                    "诊断版本：v0.2.0-$refreshTick",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EnableGuideCard(
    state: ServiceState,
    onOpenSettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state == ServiceState.OFF) "如何开启" else "「服务待连接」怎么处理",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (state == ServiceState.OFF) {
                    "1. 点下面的按钮进入系统无障碍设置；\n" +
                        "2. 找到「开屏跳过服务」并打开开关；\n" +
                        "3. 在系统弹窗里确认（安全提醒是安卓对所有辅助功能的通用提示）。\n\n" +
                        AccessibilityUtils.settingsPathHint()
                } else {
                    "安卓系统有时只是「记住」了授权，却没有把服务拉起来，多见于小米、华为、" +
                        "OPPO、vivo，或应用刚更新、手机刚重启时。按顺序试：\n" +
                        "1. 点「刷新状态」再等几秒（切到别的 App 再回来也能触发连接）；\n" +
                        "2. 回系统无障碍设置，把「开屏跳过服务」关掉再打开；\n" +
                        "3. 把本应用加入电池优化白名单（允许后台运行）；\n" +
                        "4. 重启手机后再看本页状态。\n\n" +
                        "本应用不联网、不上传任何数据，放心授权。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings) {
                    Text(if (state == ServiceState.OFF) "去开启无障碍服务" else "重新进入无障碍设置")
                }
                OutlinedButton(onClick = onOpenAppDetails) { Text("应用详情") }
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
            Text("✔ 阶段 1-9 · 基础能力、规则、防误触、学习模式、可视化编辑", style = MaterialTheme.typography.bodyMedium)
            Text("✔ 阶段 10 · 学习模式重做 + 无障碍机型适配 + 版本 0.2.0（当前）", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
