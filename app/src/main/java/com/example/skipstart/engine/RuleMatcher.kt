package com.example.skipstart.engine

import com.example.skipstart.capture.NodeSnapshot
import kotlin.math.hypot

/**
 * 规则匹配器（说明书第 6 章 4 节）：
 * - 条件类型：text_regex / desc_regex / view_id / class_name；
 * - 区域过滤：条件自带 area 时生效（top_right = centerX > 0.6w && centerY < 0.25h）；
 * - 评分：条件得分累加 + 右上角 +20 + 可点击 +10，总分 ≥ 规则 minScore（默认 60）才进入候选；
 * - 敏感词负名单：支付/登录/权限/同意/下载等直接排除；
 * - 候选按得分降序、到右上角距离升序（右上角优先）。
 */
object RuleMatcher {

    private const val TOP_RIGHT_BONUS = 20
    private const val CLICKABLE_BONUS = 10

    private val SENSITIVE_KEYWORDS = listOf(
        "支付", "登录", "权限", "同意", "下载", "购买", "充值", "绑定", "实名", "开通",
    )

    private val regexCache = HashMap<String, Regex>()

    fun match(
        rule: Rule,
        nodes: List<NodeSnapshot>,
        screenW: Int,
        screenH: Int,
    ): MatchResult? {
        val candidates = ArrayList<ScoredNode>()
        val threshold = rule.minScore.coerceIn(0, 200)
        for (node in nodes) {
            if (!node.visible) continue
            val text = node.text ?: ""
            val desc = node.contentDescription ?: ""
            if (isSensitive(text) || isSensitive(desc)) continue

            val matched = ArrayList<String>()
            var score = 0
            for (cond in rule.conditions) {
                if (cond.area != null && !inArea(node, cond.area, screenW, screenH)) continue
                val hit = when (cond.type) {
                    "text_regex" -> regex(cond.pattern).containsMatchIn(text)
                    "desc_regex" -> regex(cond.pattern).containsMatchIn(desc)
                    "view_id" -> node.viewIdResourceName?.contains(cond.pattern) == true
                    "class_name" -> node.className?.contains(cond.pattern) == true
                    else -> false
                }
                if (hit) {
                    score += cond.score
                    matched += "${cond.type}:${cond.pattern}"
                }
            }
            if (matched.isEmpty()) continue
            if (rule.matchMode == "all" && matched.size < rule.conditions.size) continue

            if (inTopRight(node, screenW, screenH)) score += TOP_RIGHT_BONUS
            if (node.clickable) score += CLICKABLE_BONUS
            if (score >= threshold) {
                candidates += ScoredNode(node, score, matched)
            }
        }
        if (candidates.isEmpty()) return null

        val best = candidates.maxWithOrNull(
            compareBy<ScoredNode> { it.score }
                .thenBy { distToTopRight(it.node, screenW, screenH) }
        ) ?: return null

        return MatchResult(
            rule = rule,
            snapshot = best.node,
            score = best.score,
            matchedBy = best.matched,
        )
    }

    private fun regex(pattern: String): Regex =
        regexCache.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }

    private fun isSensitive(s: String): Boolean = SENSITIVE_KEYWORDS.any { s.contains(it) }

    private fun boundsOf(node: NodeSnapshot): IntArray? =
        node.bounds?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 }?.toIntArray()

    private fun inArea(node: NodeSnapshot, area: String, w: Int, h: Int): Boolean {
        val b = boundsOf(node) ?: return false
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        return when (area) {
            "top_right" -> cx > 0.6f * w && cy < 0.25f * h
            "top_left" -> cx < 0.4f * w && cy < 0.25f * h
            "bottom_right" -> cx > 0.6f * w && cy > 0.75f * h
            "bottom_left" -> cx < 0.4f * w && cy > 0.75f * h
            "full", "" -> true
            else -> true
        }
    }

    private fun inTopRight(node: NodeSnapshot, w: Int, h: Int): Boolean =
        inArea(node, "top_right", w, h)

    private fun distToTopRight(node: NodeSnapshot, w: Int, h: Int): Float {
        val b = boundsOf(node) ?: return Float.MAX_VALUE
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        return hypot(w - cx, 0f - cy)
    }

    private data class ScoredNode(
        val node: NodeSnapshot,
        val score: Int,
        val matched: List<String>,
    )
}
