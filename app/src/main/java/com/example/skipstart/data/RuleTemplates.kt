package com.example.skipstart.data

/**
 * 规则模板（阶段 9 / P1）：
 * 仅预填关键词与区域等关键字段，不含包名——用户需自行补充目标包名并实测验证。
 * 不内置未经验证的第三方 App 规则，避免误触风险。
 */
data class RuleTemplate(
    val id: String,
    val name: String,
    val hint: String,
    val textKeywords: String,
    val descKeywords: String,
    val viewIdKeywords: String,
    val area: String,
)

object RuleTemplates {

    val all: List<RuleTemplate> = listOf(
        RuleTemplate(
            id = "generic_skip",
            name = "通用跳过",
            hint = "右上角「跳过 / 跳过广告」文本按钮（推荐）",
            textKeywords = "跳过, 跳过广告",
            descKeywords = "关闭",
            viewIdKeywords = "",
            area = "top_right",
        ),
        RuleTemplate(
            id = "countdown",
            name = "倒计时跳过",
            hint = "「跳过 5 / 5 秒后跳过」等倒计时文本（字面匹配即可命中）",
            textKeywords = "跳过",
            descKeywords = "跳过, 关闭",
            viewIdKeywords = "",
            area = "top_right",
        ),
        RuleTemplate(
            id = "close_x",
            name = "× 关闭",
            hint = "无文本的关闭按钮：靠描述或 viewId 定位",
            textKeywords = "",
            descKeywords = "关闭",
            viewIdKeywords = "close, skip, btn_close",
            area = "top_right",
        ),
    )
}
