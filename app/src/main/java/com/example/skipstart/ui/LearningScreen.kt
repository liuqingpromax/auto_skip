package com.example.skipstart.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skipstart.AppGraph
import com.example.skipstart.data.InstalledApp
import com.example.skipstart.data.InstalledAppScanner
import com.example.skipstart.service.LearnedSample
import com.example.skipstart.service.LearningCandidate
import com.example.skipstart.service.LearningController
import com.example.skipstart.util.AccessibilityUtils
import com.example.skipstart.util.ScreenUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学习页（v0.2.0 重做，对应说明书第 10 章）。
 *
 * 设计目标：**看得懂、点得动、学得准**。
 * - 顶部用「三步图 + 一句人话」说清流程，不写术语；
 * - 目标 App 从已安装列表里选，不用手敲包名；
 * - 开始后自动打开目标 App，学习期间只观察不点击；
 * - 用户点一下「跳过」即捕获，页面用进度条 + 倒计时告诉他「现在该做什么」；
 * - 捕获后给出多条候选规则（带推荐度和解释），并可在保存前试跑验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by AppGraph.learningController.enabled.collectAsStateWithLifecycle()
    val target by AppGraph.learningController.targetPackage.collectAsStateWithLifecycle()
    val sample by AppGraph.learningController.sample.collectAsStateWithLifecycle()
    val candidates by AppGraph.learningController.candidates.collectAsStateWithLifecycle()
    val phase by AppGraph.learningController.phase.collectAsStateWithLifecycle()
    val count by AppGraph.learningController.captureCount.collectAsStateWithLifecycle()
    val feedback by AppGraph.learningController.feedback.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    var serviceReady by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }
    var pkgInput by rememberSaveable { mutableStateOf("") }
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    var timedOut by remember { mutableStateOf(false) }

    val targetLabel = remember(target) {
        target?.let { InstalledAppScanner.labelOf(context, it) }
    }

    // 从目标 App 切回来时刷新服务状态与倒计时
    LifecycleResumeEffect(Unit) {
        serviceReady = AccessibilityUtils.isServiceEnabled(context)
        onPauseOrDispose { }
    }

    // 学习进行中：每秒刷新倒计时，到点自动收口并给出原因
    LaunchedEffect(enabled) {
        while (enabled) {
            remaining = AppGraph.learningController.remainingSeconds()
            if (AppGraph.learningController.checkTimeout()) {
                timedOut = true
                break
            }
            delay(1_000)
        }
    }

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        AppGraph.learningController.consumeFeedback()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("学习模式") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!serviceReady) {
                ServiceRequiredCard(
                    onOpenSettings = {
                        val ok = AccessibilityUtils.openAccessibilitySettings(context)
                        if (!ok) {
                            scope.launch {
                                snackbarHostState.showSnackbar("打不开系统设置，请手动进入「设置 → 无障碍」")
                            }
                        }
                    },
                )
            }

            when {
                enabled -> RunningCard(
                    targetLabel = targetLabel ?: target.orEmpty(),
                    targetPackage = target.orEmpty(),
                    remaining = remaining,
                    onOpenTarget = { ScreenUtils.launchApp(context, target.orEmpty()) },
                    onCancel = {
                        AppGraph.learningController.stop("已取消学习")
                        timedOut = false
                    },
                )

                sample != null -> CapturedSection(
                    sample = sample!!,
                    candidates = candidates,
                    count = count,
                    onSave = { candidate ->
                        AppGraph.ruleRepository.addOrUpdate(candidate.rule)
                        AppGraph.learningController.clearSample()
                        AppGraph.learningController.stop()
                        scope.launch {
                            snackbarHostState.showSnackbar("已保存：${candidate.title}，下次打开自动跳过")
                        }
                    },
                    onRetry = {
                        AppGraph.learningController.clearSample()
                        val pkg = target.orEmpty()
                        if (pkg.isNotBlank()) {
                            startLearning(context, pkg, scope)
                        }
                    },
                    onDiscard = {
                        AppGraph.learningController.clearSample()
                        AppGraph.learningController.stop()
                        scope.launch { snackbarHostState.showSnackbar("已放弃本次样本") }
                    },
                )

                else -> IdleSection(
                    showExpiredHint = timedOut || phase == LearningController.Phase.EXPIRED_TIME,
                    pkgInput = pkgInput,
                    onInput = { pkgInput = it },
                    manualMode = manualMode,
                    onToggleManual = { manualMode = !manualMode },
                    serviceReady = serviceReady,
                    onPickApp = { app ->
                        pkgInput = app.packageName
                        serviceReady = AccessibilityUtils.isServiceEnabled(context)
                        startLearning(context, app.packageName, scope)
                    },
                    onStartManual = {
                        val pkg = pkgInput.trim()
                        when {
                            pkg.isBlank() -> scope.launch {
                                snackbarHostState.showSnackbar("请先选择或输入目标 App 包名")
                            }
                            !AccessibilityUtils.isPackageInstalled(context, pkg) -> scope.launch {
                                snackbarHostState.showSnackbar("本机没有安装这个包名的应用")
                            }
                            else -> {
                                serviceReady = AccessibilityUtils.isServiceEnabled(context)
                                startLearning(context, pkg, scope)
                            }
                        }
                    },
                )
            }

            GuideCard()
        }
    }
}

/** 开始学习：清空旧状态 → 启动会话 → 拉目标 App 到前台。 */
private fun startLearning(
    context: android.content.Context,
    pkg: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    AppGraph.learningController.start(pkg)
    scope.launch {
        delay(450) // 等页面渲染完再切走，避免用户看不到「正在学习」
        val ok = ScreenUtils.launchApp(context, pkg)
        if (!ok) {
            AppGraph.learningController.stop("没能自动打开目标 App，请手动打开它")
        }
    }
}

// ---------------------------------------------------------------------------
// 未开启无障碍：先解决前置条件，再谈学习
// ---------------------------------------------------------------------------

@Composable
private fun ServiceRequiredCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "① 先开启无障碍服务",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE65100),
            )
            Text(
                "学习模式靠无障碍服务「看见」你点的按钮。没开启的话，点了也不会被记录。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                AccessibilityUtils.settingsPathHint(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("去开启无障碍服务") }
            Text(
                "开完回到本页会自动刷新状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 空闲态：选目标 App + 三步说明
// ---------------------------------------------------------------------------

@Composable
private fun IdleSection(
    showExpiredHint: Boolean,
    pkgInput: String,
    onInput: (String) -> Unit,
    manualMode: Boolean,
    onToggleManual: () -> Unit,
    serviceReady: Boolean,
    onPickApp: (InstalledApp) -> Unit,
    onStartManual: () -> Unit,
) {
    val context = LocalContext.current

    if (showExpiredHint) {
        NoticeCard(
            title = "上次学习没有捕获到点击",
            color = Color(0xFFC62828),
            lines = listOf(
                "· 目标 App 要真的打开，并等开屏广告出现；",
                "· 出现「跳过」按钮后，用手指点它一下（不是长按）；",
                "· 点完 1 秒内切回本应用即可看到结果。",
                "下面重新选一次目标 App 再试。",
            ),
        )
    }

    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var includeSystem by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(includeSystem) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            InstalledAppScanner.load(context, includeSystem)
        }
        loading = false
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("选择要学习的 App", style = MaterialTheme.typography.titleMedium)
            Text(
                "选一个广告总是要手点的 App，接下来只需手点一次「跳过」，之后交给它自动点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索应用名") },
                placeholder = { Text("如：高德地图") },
                singleLine = true,
            )

            val filtered = remember(apps, query) {
                if (query.isBlank()) apps
                else apps.filter { it.searchKey.contains(query.trim().lowercase()) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loading) "正在读取本机应用…" else "共 ${filtered.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("含系统应用", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 340.dp),
            ) {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的应用，换个关键词，或改用下面的「手动输入包名」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppRow(app = app, onClick = { onPickApp(app) })
                            HorizontalDivider()
                        }
                    }
                }
            }

            if (!serviceReady) {
                Text(
                    "提示：无障碍服务还没开启，建议先开好再开始学习。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            }

            TextButton(onClick = onToggleManual) {
                Text(if (manualMode) "收起手动输入" else "找不到？手动输入包名")
            }

            if (manualMode) {
                OutlinedTextField(
                    value = pkgInput,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标包名") },
                    placeholder = { Text("如 com.autonavi.minimap") },
                    singleLine = true,
                )
                Button(onClick = onStartManual, modifier = Modifier.fillMaxWidth()) {
                    Text("开始学习这个包名")
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = app.label + if (app.systemApp) "（系统）" else "",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 进行中：进度 + 倒计时 + 我现在该做什么
// ---------------------------------------------------------------------------

@Composable
private fun RunningCard(
    targetLabel: String,
    targetPackage: String,
    remaining: Int,
    onOpenTarget: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "正在学习 · 第 2 步",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE65100),
            )
            Text(
                "现在做一件事：等开屏广告出现，用手指点一下「跳过」按钮。",
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "目标 App：${targetLabel.ifBlank { targetPackage }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "剩余 ${remaining} 秒 · 学习期间本应用不会自动点击，放心点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoticeCard(
                title = "如果目标 App 没弹出来",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lines = listOf("点下面的按钮手动打开它，然后照样点「跳过」。"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTarget) { Text("打开目标 App") }
                TextButton(onClick = onCancel) { Text("取消学习") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 已捕获：样本 + 候选规则 + 试跑
// ---------------------------------------------------------------------------

@Composable
private fun CapturedSection(
    sample: LearnedSample,
    candidates: List<LearningCandidate>,
    count: Int,
    onSave: (LearningCandidate) -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val context = LocalContext.current
    var trialMessage by remember(sample) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "已捕获第 $count 次点击 · 第 3 步",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF2E7D32),
                )
                Text(
                    "你点的是「${sample.text ?: sample.contentDescription ?: "无文字按钮"}」" +
                        "（${LearningController.areaLabel(sample.bounds, sample.screenWidth, sample.screenHeight)}）。" +
                        "下面挑一条规则保存即可。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    captureMethodText(sample),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("捕获到的按钮信息", style = MaterialTheme.typography.titleSmall)
                InfoRow("应用", sample.packageName)
                InfoRow("按钮文字", sample.text ?: "—")
                InfoRow("无障碍描述", sample.contentDescription ?: "—")
                InfoRow("控件 ID", sample.viewIdResourceName ?: "—")
                InfoRow("类名", sample.className ?: "—")
                InfoRow(
                    "点击位置",
                    "${LearningController.percent(sample.tapRatioX)} / " +
                        "${LearningController.percent(sample.tapRatioY)}（屏幕比例）",
                )
                InfoRow("点击时刻", formatTime(sample.capturedAt))
            }
        }

        if (candidates.isEmpty()) {
            NoticeCard(
                title = "这次没拿到可用线索",
                color = Color(0xFFC62828),
                lines = listOf(
                    "按钮既没有文字、也没有无障碍描述和控件 ID。",
                    "换一个 App，或换成点广告上那个「×」试试，然后重新学习。",
                ),
            )
        } else {
            Text(
                "候选规则（推荐度从高到低，挑一条保存）",
                style = MaterialTheme.typography.titleMedium,
            )
            candidates.forEach { candidate ->
                CandidateCard(
                    candidate = candidate,
                    onSave = { onSave(candidate) },
                    onTrial = {
                        val service = com.example.skipstart.service.SkipAccessibilityService.instance
                        trialMessage = if (service == null) {
                            "无障碍服务当前未连接，无法试跑（可先保存，之后看「日志」页验证）"
                        } else {
                            service.evaluateRule(candidate.rule).message
                        }
                    },
                )
            }
        }

        trialMessage?.let { msg ->
            NoticeCard(title = "试跑结果", color = MaterialTheme.colorScheme.primary, lines = listOf(msg))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("重新学习一次") }
            TextButton(onClick = onDiscard) { Text("放弃样本") }
            TextButton(onClick = { ScreenUtils.bringSelfToFront(context) }) { Text("回到本应用") }
        }
        Text(
            "提示：保存后可以把目标 App 从最近任务划掉再打开，验证是否自动跳过；" +
                "结果会记在「日志」页。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CandidateCard(
    candidate: LearningCandidate,
    onSave: () -> Unit,
    onTrial: () -> Unit,
) {
    val border = if (candidate.recommended) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (candidate.recommended) {
                Color(0xFFF1F8E9)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.title, style = MaterialTheme.typography.titleSmall)
                if (candidate.recommended) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32),
                    )
                }
            }
            Text(
                candidate.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "推荐度 ${candidate.confidence}（${LearningController.confidenceLabel(candidate.confidence)}）" +
                    " · 命中阈值 ${candidate.rule.minScore} 分",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("保存这条") }
                TextButton(onClick = onTrial) { Text("先试跑一下") }
            }
            Text(
                text = candidate.rule.toJson().toString(2),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 通用小组件
// ---------------------------------------------------------------------------

/** 常驻的三步流程说明（放在页面最下方，随时可查）。 */
@Composable
private fun GuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("学习模式怎么用（三步）", style = MaterialTheme.typography.titleMedium)
            GuideStep(1, "选 App", "在上方列表点一下要学习的 App（如高德地图）。")
            GuideStep(2, "点一次跳过", "本应用会自动帮你打开它；等开屏广告出现，用手指点一下「跳过」。")
            GuideStep(3, "保存规则", "切回本应用，选一条候选规则保存；下次打开就自动跳过。")
            HorizontalDivider()
            Text("几点说明", style = MaterialTheme.typography.titleSmall)
            Text(
                "· 学习期间不会自动点击，随便点不会误触；\n" +
                    "· 一次学习只记一个按钮，点错了点「重新学习一次」即可；\n" +
                    "· 学习时长 60 秒，超时自动结束，不会一直在后台跑；\n" +
                    "· 保存后想在手机上验证：把目标 App 从最近任务划掉再打开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuideStep(index: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$index",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeCard(title: String, color: Color, lines: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
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

private fun captureMethodText(sample: LearnedSample): String {
    val base = when (sample.captureMethod) {
        LearnedSample.METHOD_SELF -> "样本质量：好（直接读到了按钮本身）"
        LearnedSample.METHOD_PARENT -> "样本质量：好（信息取自按钮所在的可点击区域）"
        LearnedSample.METHOD_CHILD -> "样本质量：中（按钮本身无文字，取自内部文字）"
        else -> "样本质量：一般（系统未给出节点，按点击坐标回溯）"
    }
    return sample.captureNote?.let { "$base · $it" } ?: base
}

private fun formatTime(ts: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(ts))
}
