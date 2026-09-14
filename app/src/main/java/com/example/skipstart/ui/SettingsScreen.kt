package com.example.skipstart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import kotlin.math.roundToInt

/**
 * 设置页（阶段 4，对应说明书第 12 章 5 节）：
 * - 总开关（一键停用自动点击）；
 * - 冷启动窗口 / 点击冷却（全局硬性上限，只会更严格）；
 * - 隐私说明 / 开源许可 / OCR 兜底占位（P2 未实现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val settings by AppGraph.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GuardCard(
                masterEnabled = settings.masterEnabled,
                launchWindowMs = settings.launchWindowMs,
                cooldownMs = settings.cooldownMs,
            )
            TextCard(
                title = "隐私说明",
                body = "本应用不申请任何网络权限，不联网、不上传截图/节点树/日志。" +
                    "无障碍权限仅用于在本机识别目标应用开屏按钮并模拟点击。" +
                    "所有规则与日志仅保存在本机，可随时清空。",
            )
            TextCard(
                title = "开源许可",
                body = "本项目采用 Apache-2.0 许可；依赖 Jetpack Compose、Kotlin 等" +
                    "开源组件。仅供个人学习与自用，请遵守目标应用用户协议。",
            )
            TextCard(
                title = "OCR 兜底（P2）",
                body = "MediaProjection 截屏 + ML Kit 识别右上角「跳过」。计划功能，MVP 未实现。",
            )
            TextCard(
                title = "关于",
                body = "SkipStart v$versionName · minSdk 26 / targetSdk 35\n" +
                    "本地运行、规则驱动、防误触、合规。仅供个人学习与自用。",
            )
        }
    }
}

@Composable
private fun GuardCard(
    masterEnabled: Boolean,
    launchWindowMs: Long,
    cooldownMs: Long,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("总开关", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "关闭后不再执行任何自动点击",
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
            HorizontalDivider()
            Text(
                "冷启动窗口：${launchWindowMs / 1000} 秒",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = (launchWindowMs / 1000).toFloat(),
                onValueChange = { v ->
                    AppGraph.settingsStore.update {
                        it.copy(launchWindowMs = (v * 1000).roundToInt().toLong())
                    }
                },
                valueRange = 3f..15f,
                steps = 11,
            )
            Text(
                "点击冷却：${cooldownMs / 1000} 秒",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = (cooldownMs / 1000).toFloat(),
                onValueChange = { v ->
                    AppGraph.settingsStore.update {
                        it.copy(cooldownMs = (v * 1000).roundToInt().toLong())
                    }
                },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(
                "全局参数为硬性上限：实际窗口取规则与全局的较小值、冷却取较大值，只会更严格。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
