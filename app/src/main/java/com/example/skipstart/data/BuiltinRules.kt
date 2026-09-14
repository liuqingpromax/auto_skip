package com.example.skipstart.data

import com.example.skipstart.engine.Rule
import org.json.JSONObject

/**
 * 内置规则（说明书第 7 章）。
 * 高德地图开屏跳过规则与说明书 JSON 示例逐字一致，
 * 阶段 5 的导出功能可直接复用该 JSON 作为样例。
 */
object BuiltinRules {

    const val AMAP_SPLASH_RULE_JSON: String = """
{
  "id": "amap_skip",
  "name": "高德地图开屏跳过",
  "enabled": true,
  "packageNames": ["com.autonavi.minimap"],
  "activityPatterns": ["*"],
  "launchWindowMs": 8000,
  "maxClicksPerLaunch": 1,
  "cooldownMs": 2000,
  "matchMode": "any",
  "conditions": [
    { "type": "text_regex", "pattern": ".*跳过\\s*\\d*.*", "area": "top_right", "score": 50 },
    { "type": "desc_regex", "pattern": "跳过|关闭", "area": "top_right", "score": 40 },
    { "type": "text_regex", "pattern": "\\d+\\s*秒?\\s*跳过", "area": "top_right", "score": 30 }
  ],
  "action": {
    "type": "click_node_or_parent",
    "fallback": { "type": "click_xy_ratio", "x": 0.92, "y": 0.08 }
  }
}
"""

    fun all(): List<Rule> = listOf(Rule.fromJson(JSONObject(AMAP_SPLASH_RULE_JSON)))
}
