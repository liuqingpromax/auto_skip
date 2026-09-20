package com.example.skipstart.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则模型（JSON 字段与说明书第 7 章一一对应）。
 * 序列化在此实现，阶段 5 的导入导出直接复用。
 */

data class RuleCondition(
    val type: String,        // text_regex | desc_regex | view_id | class_name
    val pattern: String,
    val area: String? = null, // top_right / top_left / bottom_right / bottom_left / full
    val score: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("pattern", pattern)
        area?.let { put("area", it) }
        put("score", score)
    }

    companion object {
        fun fromJson(o: JSONObject): RuleCondition = RuleCondition(
            type = o.optString("type"),
            pattern = o.optString("pattern"),
            area = o.optString("area").takeIf { it.isNotEmpty() },
            score = o.optInt("score", 0),
        )
    }
}

data class RuleFallback(
    val type: String,        // click_xy_ratio
    val x: Float,
    val y: Float,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("x", x.toDouble())
        put("y", y.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): RuleFallback = RuleFallback(
            type = o.optString("type"),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
        )
    }
}

data class RuleAction(
    val type: String,        // click_node_or_parent
    val fallback: RuleFallback? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        fallback?.let { put("fallback", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): RuleAction = RuleAction(
            type = o.optString("type"),
            fallback = o.optJSONObject("fallback")?.let { RuleFallback.fromJson(it) },
        )
    }
}

data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val packageNames: List<String>,
    val activityPatterns: List<String> = listOf("*"),
    val launchWindowMs: Long = 8_000,      // 冷启动后多少毫秒内生效
    val maxClicksPerLaunch: Int = 1,       // 每次启动最多点击次数
    val cooldownMs: Long = 2_000,          // 两次点击冷却
    val matchMode: String = "any",         // any | all
    val minScore: Int = 60,                // 命中阈值：总分 ≥ 此值才执行（阶段9 规则级可配）
    val conditions: List<RuleCondition>,
    val action: RuleAction,
    /**
     * 内置规则版本号（v0.3.0 新增）。
     *
     * 背景：`RuleRepository.load()` 原先只在内置规则**缺失**时补齐，已存在就原样保留。
     * 结果是老用户升级后永远拿不到改好的内置规则——v0.2.x 修了匹配逻辑，
     * 但用户本机持久化的旧规则（写死 `top_right` 的那版）一直生效，改动等于白做。
     *
     * 现在：内置规则带版本号，持久化版本较低时**自动升级为新规则内容**
     * （保留用户的启用/禁用选择），用户自己新建的规则不受影响。
     */
    val version: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("packageNames", JSONArray(packageNames))
        put("activityPatterns", JSONArray(activityPatterns))
        put("launchWindowMs", launchWindowMs)
        put("maxClicksPerLaunch", maxClicksPerLaunch)
        put("cooldownMs", cooldownMs)
        put("matchMode", matchMode)
        put("minScore", minScore)
        put("conditions", JSONArray().apply { conditions.forEach { put(it.toJson()) } })
        put("action", action.toJson())
        // version == 0 表示用户规则或旧数据，不写入 JSON，避免污染导出的规则文件
        if (version > 0) put("version", version)
    }

    companion object {
        fun fromJson(o: JSONObject): Rule {
            fun strArray(key: String, default: List<String>): List<String> {
                val arr = o.optJSONArray(key) ?: return default
                return (0 until arr.length())
                    .mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
            }
            val conditions = o.optJSONArray("conditions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { RuleCondition.fromJson(it) }
                }
            } ?: emptyList()
            val action = o.optJSONObject("action")?.let { RuleAction.fromJson(it) }
                ?: RuleAction(type = "click_node_or_parent")
            return Rule(
                id = o.optString("id"),
                name = o.optString("name"),
                enabled = o.optBoolean("enabled", true),
                packageNames = strArray("packageNames", emptyList()),
                activityPatterns = strArray("activityPatterns", listOf("*")),
                launchWindowMs = o.optLong("launchWindowMs", 8_000),
                maxClicksPerLaunch = o.optInt("maxClicksPerLaunch", 1),
                cooldownMs = o.optLong("cooldownMs", 2_000),
                matchMode = o.optString("matchMode", "any"),
                minScore = o.optInt("minScore", 60),
                conditions = conditions,
                action = action,
                version = o.optInt("version", 0),
            )
        }
    }
}
