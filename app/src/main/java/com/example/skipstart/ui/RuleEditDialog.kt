package com.example.skipstart.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.skipstart.data.RuleTemplate
import com.example.skipstart.data.RuleTemplates
import com.example.skipstart.engine.Rule
import com.example.skipstart.engine.RuleAction
import com.example.skipstart.engine.RuleCondition
import com.example.skipstart.engine.RuleFallback
import org.json.JSONObject
import kotlin.math.roundToInt

private val AREA_OPTIONS = listOf(
    "top_right" to "右上",
    "top_left" to "左上",
    "bottom_right" to "右下",
    "bottom_left" to "左下",
    "full" to "全屏",
)

private val NEW_RULE_JSON_TEMPLATE = """
{
  "id": "user_new_rule",
  "name": "新规则",
  "enabled": true,
  "packageNames": ["com.example.app"],
  "activityPatterns": ["*"],
  "launchWindowMs": 8000,
  "maxClicksPerLaunch": 1,
  "cooldownMs": 2000,
  "matchMode": "any",
  "minScore": 60,
  "conditions": [
    { "type": "text_regex", "pattern": "跳过", "area": "top_right", "score": 50 }
  ],
  "action": { "type": "click_node_or_parent" }
}
""".trimIndent()

/**
 * 阶段 8/9（P1）：可视化规则编辑器，表单 / JSON 双模式。
 * - 表单：模板预填 + 名称、包名、文本/描述/viewId 关键词、按钮区域、
 *   窗口、冷却、最多点击次数、最低命中得分（minScore）、启用；
 * - JSON：保留精确编辑能力（复杂正则、多条件、自定义动作）；
 * - 两种模式可互相切换（表单 → 生成 JSON；JSON → 回填表单）。
 */
@Composable
fun RuleEditDialog(
    initialRule: Rule?,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    var jsonMode by remember(initialRule) { mutableStateOf(false) }

    // ---- 表单字段 ----
    var name by remember(initialRule) { mutableStateOf(initialRule?.name ?: "") }
    var pkgText by remember(initialRule) {
        mutableStateOf(initialRule?.packageNames?.joinToString(", ") ?: "")
    }
    var textKeywords by remember(initialRule) {
        mutableStateOf(extractKeywords(initialRule, "text_regex"))
    }
    var descKeywords by remember(initialRule) {
        mutableStateOf(extractKeywords(initialRule, "desc_regex"))
    }
    var viewIdKeywords by remember(initialRule) {
        mutableStateOf(extractKeywords(initialRule, "view_id"))
    }
    var area by remember(initialRule) {
        mutableStateOf(initialRule?.conditions?.firstOrNull()?.area ?: "top_right")
    }
    var windowSeconds by remember(initialRule) {
        mutableStateOf(((initialRule?.launchWindowMs ?: 8_000L) / 1000L).toFloat().coerceIn(3f, 15f))
    }
    var cooldownSeconds by remember(initialRule) {
        mutableStateOf(((initialRule?.cooldownMs ?: 2_000L) / 1000L).toFloat().coerceIn(1f, 10f))
    }
    var maxClicks by remember(initialRule) {
        mutableStateOf((initialRule?.maxClicksPerLaunch ?: 1).toString())
    }
    var minScore by remember(initialRule) {
        mutableStateOf((initialRule?.minScore ?: 60).coerceIn(40, 90))
    }
    var enabled by remember(initialRule) { mutableStateOf(initialRule?.enabled ?: true) }

    // ---- JSON 文本 ----
    var jsonText by remember(initialRule) {
        mutableStateOf(initialRule?.toJson()?.toString(2) ?: NEW_RULE_JSON_TEMPLATE)
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun formRule(): Rule {
        require(name.isNotBlank()) { "缺少规则名称" }
        val pkgs = splitKeywords(pkgText)
        require(pkgs.isNotEmpty()) { "目标包名不能为空" }
        val conditions = buildList {
            splitKeywords(textKeywords).forEach {
                add(RuleCondition("text_regex", Regex.escape(it), area, 50))
            }
            splitKeywords(descKeywords).forEach {
                add(RuleCondition("desc_regex", Regex.escape(it), area, 40))
            }
            splitKeywords(viewIdKeywords).forEach {
                add(RuleCondition("view_id", it, area, 30))
            }
        }
        require(conditions.isNotEmpty()) { "关键词或 viewId 至少填写一项" }
        val max = maxClicks.trim().toIntOrNull() ?: 1
        require(max in 1..5) { "每次启动最多点击次数需为 1-5" }
        return Rule(
            id = initialRule?.id ?: "user_" + System.currentTimeMillis(),
            name = name.trim(),
            enabled = enabled,
            packageNames = pkgs,
            activityPatterns = initialRule?.activityPatterns ?: listOf("*"),
            launchWindowMs = windowSeconds.roundToInt() * 1000L,
            maxClicksPerLaunch = max,
            cooldownMs = cooldownSeconds.roundToInt() * 1000L,
            matchMode = initialRule?.matchMode ?: "any",
            minScore = minScore,
            conditions = conditions,
            action = initialRule?.action
                ?: RuleAction(
                    "click_node_or_parent",
                    RuleFallback("click_xy_ratio", 0.92f, 0.08f),
                ),
        )
    }

    fun applyRuleToForm(rule: Rule) {
        name = rule.name
        pkgText = rule.packageNames.joinToString(", ")
        textKeywords = extractKeywords(rule, "text_regex")
        descKeywords = extractKeywords(rule, "desc_regex")
        viewIdKeywords = extractKeywords(rule, "view_id")
        area = rule.conditions.firstOrNull()?.area ?: "top_right"
        windowSeconds = (rule.launchWindowMs / 1000L).toFloat().coerceIn(3f, 15f)
        cooldownSeconds = (rule.cooldownMs / 1000L).toFloat().coerceIn(1f, 10f)
        maxClicks = rule.maxClicksPerLaunch.toString()
        minScore = rule.minScore.coerceIn(40, 90)
        enabled = rule.enabled
    }

    fun applyTemplate(template: RuleTemplate) {
        textKeywords = template.textKeywords
        descKeywords = template.descKeywords
        viewIdKeywords = template.viewIdKeywords
        area = template.area
        error = null
    }

    fun save() {
        runCatching {
            if (jsonMode) {
                val rule = Rule.fromJson(JSONObject(jsonText))
                require(rule.id.isNotBlank()) { "缺少 id" }
                require(rule.name.isNotBlank()) { "缺少 name" }
                require(rule.packageNames.isNotEmpty()) { "packageNames 不能为空" }
                require(rule.conditions.isNotEmpty()) { "conditions 不能为空" }
                rule
            } else {
                formRule()
            }
        }.onSuccess(onSave)
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRule == null) "新建规则" else "编辑规则") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !jsonMode,
                        onClick = {
                            // JSON → 表单：解析回填；失败则停留 JSON 模式并提示
                            runCatching {
                                Rule.fromJson(JSONObject(jsonText)).also {
                                    require(it.conditions.isNotEmpty()) { "conditions 不能为空" }
                                }
                            }.onSuccess { rule ->
                                applyRuleToForm(rule)
                                jsonMode = false
                                error = null
                            }.onFailure { error = it.message }
                        },
                        label = { Text("表单") },
                    )
                    FilterChip(
                        selected = jsonMode,
                        onClick = {
                            // 表单 → JSON：从当前表单生成
                            runCatching { formRule() }
                                .onSuccess { rule ->
                                    jsonText = rule.toJson().toString(2)
                                    jsonMode = true
                                    error = null
                                }
                                .onFailure { error = it.message }
                        },
                        label = { Text("JSON") },
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (jsonMode) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it; error = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        label = { Text("规则 JSON") },
                    )
                } else {
                    FormFields(
                        name = name, onName = { name = it },
                        pkgText = pkgText, onPkgText = { pkgText = it },
                        textKeywords = textKeywords, onTextKeywords = { textKeywords = it },
                        descKeywords = descKeywords, onDescKeywords = { descKeywords = it },
                        viewIdKeywords = viewIdKeywords, onViewIdKeywords = { viewIdKeywords = it },
                        area = area, onArea = { area = it },
                        windowSeconds = windowSeconds, onWindowSeconds = { windowSeconds = it },
                        cooldownSeconds = cooldownSeconds, onCooldownSeconds = { cooldownSeconds = it },
                        maxClicks = maxClicks, onMaxClicks = { maxClicks = it },
                        minScore = minScore, onMinScore = { minScore = it },
                        enabled = enabled, onEnabled = { enabled = it },
                        onApplyTemplate = { applyTemplate(it) },
                        clearError = { error = null },
                    )
                }

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun FormFields(
    name: String, onName: (String) -> Unit,
    pkgText: String, onPkgText: (String) -> Unit,
    textKeywords: String, onTextKeywords: (String) -> Unit,
    descKeywords: String, onDescKeywords: (String) -> Unit,
    viewIdKeywords: String, onViewIdKeywords: (String) -> Unit,
    area: String, onArea: (String) -> Unit,
    windowSeconds: Float, onWindowSeconds: (Float) -> Unit,
    cooldownSeconds: Float, onCooldownSeconds: (Float) -> Unit,
    maxClicks: String, onMaxClicks: (String) -> Unit,
    minScore: Int, onMinScore: (Int) -> Unit,
    enabled: Boolean, onEnabled: (Boolean) -> Unit,
    onApplyTemplate: (RuleTemplate) -> Unit,
    clearError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("规则模板", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuleTemplates.all.forEach { template ->
                FilterChip(
                    selected = false,
                    onClick = { onApplyTemplate(template) },
                    label = { Text(template.name) },
                )
            }
        }
        Text(
            "模板仅预填关键词与区域，请补充包名并实测验证。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { onName(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("规则名称") },
            singleLine = true,
        )
        OutlinedTextField(
            value = pkgText,
            onValueChange = { onPkgText(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("目标包名（逗号分隔）") },
            supportingText = { Text("如 com.autonavi.minimap, com.tencent.mm") },
            singleLine = true,
        )
        OutlinedTextField(
            value = textKeywords,
            onValueChange = { onTextKeywords(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("按钮文本关键词（逗号分隔）") },
            supportingText = { Text("如：跳过, 跳过广告") },
            singleLine = true,
        )
        OutlinedTextField(
            value = descKeywords,
            onValueChange = { onDescKeywords(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("描述关键词（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = viewIdKeywords,
            onValueChange = { onViewIdKeywords(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("viewId 关键词（可选）") },
            singleLine = true,
        )
        Text("按钮区域", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AREA_OPTIONS.forEach { (value, label) ->
                FilterChip(
                    selected = area == value,
                    onClick = { onArea(value) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "冷启动窗口：${windowSeconds.roundToInt()} 秒",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = windowSeconds,
            onValueChange = onWindowSeconds,
            valueRange = 3f..15f,
            steps = 11,
        )
        Text(
            "点击冷却：${cooldownSeconds.roundToInt()} 秒",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = cooldownSeconds,
            onValueChange = onCooldownSeconds,
            valueRange = 1f..10f,
            steps = 8,
        )
        OutlinedTextField(
            value = maxClicks,
            onValueChange = { onMaxClicks(it); clearError() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("每次启动最多点击次数（1-5）") },
            singleLine = true,
        )
        Text(
            "最低命中得分：$minScore",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = minScore.toFloat(),
            onValueChange = { onMinScore(it.roundToInt()) },
            valueRange = 40f..90f,
            steps = 4,
        )
        Text(
            "条件得分 + 右上角20 + 可点击10，总分 ≥ 阈值才点击。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "启用",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        Text(
            "提示：关键词按字面匹配（自动转义）；复杂正则或自定义动作请使用 JSON 模式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 按逗号/换行拆分关键词。 */
private fun splitKeywords(s: String): List<String> =
    s.split(",", "，", "\n").map { it.trim() }.filter { it.isNotEmpty() }

/** 反转义字面量（Regex.escape 产生的 \Q...\E），无法反转义则原样返回。 */
private fun unescapeLiteral(pattern: String): String =
    if (pattern.startsWith("\\Q") && pattern.endsWith("\\E")) {
        pattern.substring(2, pattern.length - 2)
    } else {
        pattern
    }

/** 从规则条件中提取指定类型的关键词（表单回填用）。 */
private fun extractKeywords(rule: Rule?, type: String): String {
    if (rule == null) return ""
    return rule.conditions
        .filter { it.type == type }
        .map { unescapeLiteral(it.pattern) }
        .joinToString(", ")
}
