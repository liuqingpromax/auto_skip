package com.example.skipstart.data

import com.example.skipstart.engine.Rule
import org.json.JSONObject

/**
 * 内置规则（说明书第 7 章；v0.3.0 通用性改造）。
 *
 * ## 为什么改
 * 旧版三条条件全部写死 `"area": "top_right"` —— 只要开屏广告的「跳过」不在右上角，
 * 就永远匹配失败。同时完全没有覆盖「叉号 ✕」这类无文字按钮。
 *
 * ## 现在怎么匹配
 * 1. `area` 一律不再写死：留空表示全屏匹配，位置改为**加权**（四角 25 分、上下边缘 18~22 分、
 *    中间 0 分，见 `RuleMatcher.positionScore`）。这样按钮出现在任何角落都能点到，
 *    同时屏幕中间仍然拿不到位置分，防误触底线不破。
 * 2. 文本条件覆盖中文/英文/倒计时/叉号系字符：
 *    「跳过 / 跳過 / 略过 / skip / close / 关闭 / ✕ / ✖ / × / ❌ / 倒计时数字」。
 * 3. 描述条件覆盖无障碍描述里的「跳过 / 关闭 / close / skip / ✕」。
 * 4. 新增 `icon_button` 条件：兜住「既没文字也没描述」的纯图标关闭按钮
 *    （可点击 + 小尺寸 + 位于边缘才命中，得分 25 分，靠位置分与尺寸分凑够阈值）。
 * 5. `view_id` 条件按常见命名兜一层（close / skip / btn_close / iv_close …）。
 * 6. 兜底动作仍是比例点击，坐标从「固定 0.92, 0.08」改为右上角安全区，仅在节点点击全部失败时使用。
 */
object BuiltinRules {

    /**
     * 内置规则版本号。**每次修改下面这段 JSON 都必须 +1**，
     * 否则老用户本机持久化的旧规则不会被替换（`RuleRepository.load()` 按此版本号决定是否升级）。
     *
     * 版本历史：
     * - 1：v0.3.0 起，条件去掉 `top_right` 硬限制（改位置加权），补充叉号与 icon_button 覆盖。
     *      （v0.1.0~v0.2.5 的内置规则未带版本号，读出来是 0，因此会被版本 1 覆盖。）
     * - 2：v0.4.0 起，倒计时条件同时覆盖**数字在前**（`3跳过`）与**数字在后**（`跳过3`）
     *      两种排列，并兼容全角数字、`s/秒/后` 等连接符。
     */
    const val VERSION = 2

    const val AMAP_SPLASH_RULE_JSON: String = """
{
  "id": "amap_skip",
  "version": 2,
  "name": "高德地图开屏跳过",
  "enabled": true,
  "packageNames": ["com.autonavi.minimap"],
  "activityPatterns": ["*"],
  "launchWindowMs": 8000,
  "maxClicksPerLaunch": 1,
  "cooldownMs": 2000,
  "matchMode": "any",
  "minScore": 60,
  "conditions": [
    { "type": "text_regex", "pattern": "跳过|跳過|略过|跳过广告|关闭广告", "score": 55 },
    { "type": "text_regex", "pattern": "[0-9０-９]+\\s*[sS秒]?\\s*后?\\s*(跳过|关闭|跳過|關閉)|(跳过|关闭|跳過|關閉)\\s*(后|in|after)?\\s*[sS秒]?\\s*[0-9０-９]+", "score": 55 },
    { "type": "text_regex", "pattern": "skip|close|dismiss", "score": 45 },
    { "type": "text_regex", "pattern": "[0-9０-９]*\\s*[sS]?\\s*(skip|close|dismiss)|(skip|close|dismiss)\\s*(in|after)?\\s*[0-9０-９]+\\s*[sS]?", "score": 45 },
    { "type": "text_regex", "pattern": "✕|✖|✗|×|⨯|╳|❌|❎", "score": 50 },
    { "type": "desc_regex", "pattern": "跳过|关闭|skip|close|dismiss", "score": 45 },
    { "type": "desc_regex", "pattern": "✕|✖|✗|×|❌|关闭按钮", "score": 50 },
    { "type": "view_id", "pattern": "close", "score": 35 },
    { "type": "view_id", "pattern": "skip", "score": 35 },
    { "type": "icon_button", "pattern": "*", "score": 25 }
  ],
  "action": {
    "type": "click_node_or_parent",
    "fallback": { "type": "click_xy_ratio", "x": 0.92, "y": 0.06 }
  }
}
"""

    fun all(): List<Rule> = listOf(Rule.fromJson(JSONObject(AMAP_SPLASH_RULE_JSON)))
}
