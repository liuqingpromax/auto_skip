package com.example.skipstart.service

import com.example.skipstart.engine.Rule
import com.example.skipstart.engine.RuleAction
import com.example.skipstart.engine.RuleCondition
import com.example.skipstart.engine.RuleFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 学习模式捕获的节点样本（说明书第 10 章字段）。 */
data class LearnedSample(
    val packageName: String,
    val activityName: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val bounds: String?,
    val clickable: Boolean,
    val capturedAt: Long,
)

/**
 * 学习模式控制器（阶段 6，说明书第 10 章）：
 * - 学习期间服务不自动点击，只记录用户手动点击的节点；
 * - 仅接受目标包、3 秒窗口内的点击样本；
 * - 捕获一次后自动停止学习，等待用户在 UI 确认候选规则。
 */
class LearningController {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _targetPackage = MutableStateFlow<String?>(null)
    val targetPackage: StateFlow<String?> = _targetPackage.asStateFlow()

    private val _sample = MutableStateFlow<LearnedSample?>(null)
    val sample: StateFlow<LearnedSample?> = _sample.asStateFlow()

    fun start(packageName: String) {
        _targetPackage.value = packageName
        _sample.value = null
        _enabled.value = true
    }

    fun stop() {
        _enabled.value = false
    }

    fun clearSample() {
        _sample.value = null
    }

    /** 服务回调：仅接受目标包、学习开启状态下的样本；捕获一次即自动停止。 */
    fun submit(sample: LearnedSample) {
        if (!_enabled.value) return
        if (sample.packageName != _targetPackage.value) return
        _sample.value = sample
        _enabled.value = false
    }

    /**
     * 依据样本生成候选规则：
     * - 自动附加右上角区域约束（top_right）；
     * - 文本/描述取关键词并剥离倒计时数字（"跳过 5" → "跳过"），保证下次匹配稳定；
     * - 兜底动作固定为右上角比例点击 (0.92, 0.08)。
     */
    fun buildCandidateRule(sample: LearnedSample): Rule? {
        val conditions = buildList {
            val textKeyword = keywordOf(sample.text)
            if (!textKeyword.isNullOrBlank()) {
                add(RuleCondition("text_regex", Regex.escape(textKeyword), "top_right", 50))
            }
            val descKeyword = keywordOf(sample.contentDescription)
            if (!descKeyword.isNullOrBlank()) {
                add(RuleCondition("desc_regex", Regex.escape(descKeyword), "top_right", 40))
            }
            if (!sample.viewIdResourceName.isNullOrBlank()) {
                add(RuleCondition("view_id", sample.viewIdResourceName, "top_right", 30))
            }
        }
        if (conditions.isEmpty()) return null

        return Rule(
            id = "learned_" + sample.capturedAt,
            name = "学习规则 · " + sample.packageName,
            packageNames = listOf(sample.packageName),
            conditions = conditions,
            action = RuleAction(
                type = "click_node_or_parent",
                fallback = RuleFallback("click_xy_ratio", 0.92f, 0.08f),
            ),
        )
    }

    /** 去掉尾部倒计时数字，生成稳定关键词。 */
    private fun keywordOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .replace(Regex("\\s*\\d+\\s*$"), "")
            .ifBlank { null }
    }
}
