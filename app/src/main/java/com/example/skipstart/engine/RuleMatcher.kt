package com.example.skipstart.engine

import com.example.skipstart.capture.NodeSnapshot
import kotlin.math.hypot

/**
 * 规则匹配器（说明书第 6 章 4 节；v0.3.0 通用性改造）。
 *
 * ## v0.3.0 的两个关键变化
 *
 * ### 1. 位置从「硬过滤」改为「加权」
 * 旧版用 `area` 把条件**硬性限制**在某个区域（内置规则全部写死 `top_right`），
 * 结果是「跳过按钮不在右上角就永远点不到」——这是漏点的主因。
 * 现在：
 * - `area` 为空 / `"full"` → 全屏匹配，位置只作为**加分**（四角 > 上下边缘 > 左右边缘 > 中间）；
 * - `area` 指定具体区域 → 仍作硬过滤，供高级用户在 JSON 模式精确控制；
 * - 内置规则与规则模板一律不再写死 `top_right`。
 *
 * ### 2. 新增 `icon_button` 条件类型：认「叉号 / 纯图标」按钮
 * 很多开屏广告的关闭按钮是一个 **✕ 图标**：没有 `text`，`contentDescription`
 * 也可能是空。旧版遇到这种节点完全无能为力。
 * `icon_button` 专门匹配「可点击 + 无文字 + 小尺寸 + 位于边缘」的图标节点：
 * - 无文字要求：有文字就不该走这条，交由 text/desc 条件处理，避免误点；
 * - 小尺寸要求：宽度 ≤ 22% 屏宽（关闭按钮都小，大块内容不是按钮）；
 * - 边缘要求：中心落在四角或上下边缘带内。
 *
 * ## 评分模型
 * ```
 * 总分 = 条件分之和 + 位置分(0..25) + 可点击分(10) + 尺寸分(0..15)
 * ```
 * 总分 ≥ 规则 `minScore`（默认 60）才执行点击。因此：
 * - 四角/边缘的无文字小图标：25 + 25 + 10 = 60，刚好可达标；
 * - **屏幕中间的图标拿不到位置分**，无法达标——这是防误触底线；
 * - 带「跳过 / 关闭 / ✕」文字的节点即使在中部也因条件分高而可达标（用户明确告知可能在任意位置）。
 */
object RuleMatcher {

    /** 可点击节点加分。 */
    private const val CLICKABLE_BONUS = 10

    /** 「叉号系」字符：这些字形本身就是关闭语义。 */
    val CROSS_KEYWORDS = listOf("✕", "✖", "✗", "❌", "❎", "×", "⨯", "╳")

    /** 负名单：命中即整个节点排除，任何位置都不点。 */
    private val SENSITIVE_KEYWORDS = listOf(
        "支付", "登录", "权限", "同意", "下载", "购买", "充值", "绑定", "实名", "开通",
        "立即", "安装", "领取", "授权", "允许", "升级", "更新", "查看详情", "了解更多",
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
                // area 为空 / full 代表不限制区域（位置改为加权）；仅在显式指定时才硬过滤
                val hardArea = cond.area?.takeIf { it.isNotEmpty() && it != "full" }
                if (hardArea != null && !inArea(node, hardArea, screenW, screenH)) continue

                val hit = when (cond.type) {
                    "text_regex" -> regex(cond.pattern).containsMatchIn(text)
                    "desc_regex" -> regex(cond.pattern).containsMatchIn(desc)
                    "view_id" -> node.viewIdResourceName?.contains(cond.pattern) == true
                    "class_name" -> node.className?.contains(cond.pattern) == true
                    "icon_button" -> isIconButtonCandidate(node, screenW, screenH)
                    else -> false
                }
                if (hit) {
                    score += cond.score
                    matched += describeHit(cond)
                }
            }
            if (matched.isEmpty()) continue
            if (rule.matchMode == "all" && matched.size < rule.conditions.size) continue

            score += positionScore(node, screenW, screenH)
            if (node.clickable) score += CLICKABLE_BONUS
            score += sizeScore(node, screenW)

            if (score >= threshold) {
                candidates += ScoredNode(node, score, matched)
            }
        }
        if (candidates.isEmpty()) return null

        // 得分优先；同分时取更靠近屏幕四角者（关闭按钮的常见落点）
        val best = candidates.maxWithOrNull(
            compareBy<ScoredNode> { it.score }
                .thenBy { -distToNearestCorner(it.node, screenW, screenH) }
        ) ?: return null

        return MatchResult(
            rule = rule,
            snapshot = best.node,
            score = best.score,
            matchedBy = best.matched,
        )
    }

    private fun describeHit(cond: RuleCondition): String =
        when (cond.type) {
            "text_regex" -> "text:${cond.pattern}"
            "desc_regex" -> "desc:${cond.pattern}"
            "view_id" -> "viewId:${cond.pattern}"
            "class_name" -> "class:${cond.pattern}"
            "icon_button" -> "icon_button(无文字小图标)"
            else -> cond.type
        }

    private fun regex(pattern: String): Regex =
        regexCache.getOrPut(pattern) { Regex(pattern, RegexOption.IGNORE_CASE) }

    private fun isSensitive(s: String): Boolean = SENSITIVE_KEYWORDS.any { s.contains(it) }

    private fun boundsOf(node: NodeSnapshot): IntArray? =
        node.bounds?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 }?.toIntArray()

    // ------------------------------------------------------------------
    // 位置加权（替代旧的「仅右上角 +20」）
    // ------------------------------------------------------------------

    /**
     * 位置分 0..25：四角最高、上下边缘次之、左右边缘再次、中间为 0。
     *
     * 之所以不再「只有右上角给分」，是因为开屏广告关闭按钮的实际分布很广：
     * 右上角最常见，但左上角、右下/左下角、顶部通栏右侧、底部横幅角落都很常见。
     * 中间为 0 分是刻意保留的防误触底线：屏幕中央的小图标几乎不可能是关闭按钮。
     */
    private fun positionScore(node: NodeSnapshot, w: Int, h: Int): Int {
        val b = boundsOf(node) ?: return 0
        if (w <= 0 || h <= 0) return 0
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val atLeft = cx < w * 0.28f
        val atRight = cx > w * 0.72f
        val atTop = cy < h * 0.18f
        val atBottom = cy > h * 0.82f

        return when {
            (atTop || atBottom) && (atLeft || atRight) -> 25   // 四角
            atTop -> 22                                        // 顶部边缘（通栏广告）
            atBottom -> 18                                     // 底部边缘
            atLeft || atRight -> 10                            // 左右边缘中部
            else -> 0                                          // 屏幕中间：不给位置分
        }
    }

    /** 尺寸分 0..15：关闭按钮通常是小按钮，越紧凑越可信。 */
    private fun sizeScore(node: NodeSnapshot, w: Int): Int {
        val b = boundsOf(node) ?: return 0
        if (w <= 0) return 0
        val widthRatio = (b[2] - b[0]).toFloat() / w
        return when {
            widthRatio <= 0.12f -> 15
            widthRatio <= 0.22f -> 10
            widthRatio <= 0.35f -> 5
            else -> 0
        }
    }

    /**
     * `icon_button` 条件判定：可点击 + 无文字 + 无描述 + 小尺寸 + 位于边缘安全区。
     * 全部满足才命中，避免把内容区的大图、列表项误当成关闭按钮。
     */
    private fun isIconButtonCandidate(node: NodeSnapshot, w: Int, h: Int): Boolean {
        if (!node.clickable) return false
        if (!node.text.isNullOrBlank()) return false
        // 有可见描述时交给 desc_regex 条件处理，更精确，这里不重复命中
        if (!node.contentDescription.isNullOrBlank()) return false
        if (w <= 0 || h <= 0) return false
        val b = boundsOf(node) ?: return false
        val widthRatio = (b[2] - b[0]).toFloat() / w
        if (widthRatio > ICON_MAX_WIDTH_RATIO) return false
        val heightRatio = (b[3] - b[1]).toFloat() / h
        if (heightRatio > ICON_MAX_HEIGHT_RATIO) return false
        return inEdgeZone(b, w, h)
    }

    /** 边缘安全区：上下边缘带，或左右边缘的中上半部。 */
    private fun inEdgeZone(b: IntArray, w: Int, h: Int): Boolean {
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val nearTop = cy < h * 0.20f
        val nearBottom = cy > h * 0.80f
        val nearSide = cx < w * 0.25f || cx > w * 0.75f
        return nearTop || nearBottom || (nearSide && (cy < h * 0.35f || cy > h * 0.65f))
    }

    // ------------------------------------------------------------------
    // 区域判定（仅在条件显式指定 area 时作为硬过滤使用）
    // ------------------------------------------------------------------

    private fun inArea(node: NodeSnapshot, area: String, w: Int, h: Int): Boolean {
        val b = boundsOf(node) ?: return false
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        return when (area) {
            "top_right" -> cx > 0.6f * w && cy < 0.25f * h
            "top_left" -> cx < 0.4f * w && cy < 0.25f * h
            "bottom_right" -> cx > 0.6f * w && cy > 0.75f * h
            "bottom_left" -> cx < 0.4f * w && cy > 0.75f * h
            // 通栏边缘带（顶部/底部横幅广告）
            "top" -> cy < 0.25f * h
            "bottom" -> cy > 0.75f * h
            "left" -> cx < 0.3f * w
            "right" -> cx > 0.7f * w
            "full", "" -> true
            else -> true
        }
    }

    private fun distToNearestCorner(node: NodeSnapshot, w: Int, h: Int): Float {
        val b = boundsOf(node) ?: return Float.MAX_VALUE
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        return minOf(
            hypot(cx, cy),                 // 左上
            hypot(w - cx, cy),             // 右上
            hypot(cx, h - cy),             // 左下
            hypot(w - cx, h - cy),         // 右下
        )
    }

    /** 标签文字里是否含叉号系字符（供 UI 提示与调试用）。 */
    fun looksLikeCrossButton(node: NodeSnapshot): Boolean {
        val label = (node.text ?: "") + " " + (node.contentDescription ?: "")
        return CROSS_KEYWORDS.any { label.contains(it) }
    }

    private data class ScoredNode(
        val node: NodeSnapshot,
        val score: Int,
        val matched: List<String>,
    )
}

/** `icon_button` 条件的尺寸上限：宽度 ≤22% 屏宽、高度 ≤18% 屏高。 */
private const val ICON_MAX_WIDTH_RATIO = 0.22f
private const val ICON_MAX_HEIGHT_RATIO = 0.18f
