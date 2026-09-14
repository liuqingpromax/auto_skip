package com.example.skipstart.engine

import com.example.skipstart.capture.NodeSnapshot

/**
 * 匹配与执行结果（说明书第 6 章 2 节）。
 * actionType / success / failReason 由 ActionExecutor 回填，供日志页展示点击结果。
 */
data class MatchResult(
    val rule: Rule,
    val snapshot: NodeSnapshot,
    val score: Int,             // 总分，需 ≥ 60 才进入候选
    val matchedBy: List<String>,
    val actionType: String? = null,
    val success: Boolean = false,
    val failReason: String? = null,
)
