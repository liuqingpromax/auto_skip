package com.example.skipstart.engine

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skipstart.capture.NodeCollector
import com.example.skipstart.data.RuleRepository
import com.example.skipstart.util.ScreenUtils

/**
 * 规则引擎（说明书第 6 章 2 节）：
 * 事件 → 规则过滤（包名/Activity）→ 节点采集 → 匹配评分 → 动作执行。
 * 无匹配返回 null（不记日志，避免刷屏）；有匹配返回含执行结果的 MatchResult。
 */
class RuleEngine(private val repository: RuleRepository) {

    fun handleEvent(
        packageName: String,
        activityName: String?,
        root: AccessibilityNodeInfo?,
        service: AccessibilityService,
    ): MatchResult? {
        if (root == null) return null
        val rule = repository.enabledRulesFor(packageName)
            .firstOrNull { activityMatches(it, activityName) }
            ?: return null

        val (w, h) = ScreenUtils.screenSize(service)
        val nodes = NodeCollector.collect(root)
        val match = RuleMatcher.match(rule, nodes, w, h) ?: return null

        val outcome = ActionExecutor.execute(service, match.snapshot, rule.action, w, h)
        return match.copy(
            actionType = outcome.actionType,
            success = outcome.success,
            failReason = outcome.failReason,
        )
    }

    /** activityPatterns 支持 * 通配：含 "*" 视为匹配全部，否则通配匹配。 */
    private fun activityMatches(rule: Rule, activityName: String?): Boolean {
        if (rule.activityPatterns.isEmpty() || "*" in rule.activityPatterns) return true
        if (activityName == null) return false
        return rule.activityPatterns.any { pattern ->
            val regex = Regex(Regex.escape(pattern).replace("\\*", ".*"))
            regex.containsMatchIn(activityName)
        }
    }
}
