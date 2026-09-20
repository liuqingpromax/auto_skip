package com.example.skipstart.data

/**
 * 规则模板（阶段 9 / P1；v0.3.0 通用性改造）。
 *
 * 仅预填关键词与区域等关键字段，不含包名——用户需自行补充目标包名并实测验证。
 * 不内置未经验证的第三方 App 规则，避免误触风险。
 *
 * ## v0.3.0 变化
 * 旧版三个模板的区域全部写死 `top_right`，等于把「跳过按钮只可能在右上角」这个
 * 错误假设固化给了用户。现在区域默认给空字符串（**全屏匹配 + 位置加权**），
 * 另提供「顶部通栏」「底部横幅」两个限定模板供特殊布局精确使用。
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

    /** 区域留空 = 全屏匹配，位置只作加权（推荐）。 */
    const val AREA_ANY = ""

    val all: List<RuleTemplate> = listOf(
        RuleTemplate(
            id = "generic_skip",
            name = "通用跳过",
            hint = "「跳过 / 跳过广告 / 关闭」文字按钮，位置不限（推荐先用这个）",
            textKeywords = "跳过, 跳过广告, 关闭, 关闭广告",
            descKeywords = "跳过, 关闭",
            viewIdKeywords = "skip, close, btn_skip",
            area = AREA_ANY,
        ),
        RuleTemplate(
            id = "countdown",
            name = "倒计时跳过",
            hint = "「跳过 5 / 5 秒后跳过」等倒计时文本（字面匹配即可命中）",
            textKeywords = "跳过",
            descKeywords = "跳过, 关闭",
            viewIdKeywords = "",
            area = AREA_ANY,
        ),
        RuleTemplate(
            id = "close_x",
            name = "× 关闭按钮",
            hint = "叉号按钮：靠 ✕ 字符、描述或 viewId 定位，位置不限",
            textKeywords = "✕, ✖, ✗, ×, ❌, 关闭",
            descKeywords = "关闭, close, ✕",
            viewIdKeywords = "close, skip, btn_close, iv_close, img_close",
            area = AREA_ANY,
        ),
        RuleTemplate(
            id = "icon_only",
            name = "纯图标关闭",
            hint = "既无文字也无描述的图标按钮：靠「可点击 + 小尺寸 + 位于边缘」识别",
            textKeywords = "",
            descKeywords = "",
            viewIdKeywords = "",
            area = AREA_ANY,
        ),
        RuleTemplate(
            id = "top_banner",
            name = "顶部通栏广告",
            hint = "开屏顶部整条横幅广告，关闭按钮位于顶部区域",
            textKeywords = "跳过, 关闭, ✕",
            descKeywords = "跳过, 关闭",
            viewIdKeywords = "close, skip",
            area = "top",
        ),
        RuleTemplate(
            id = "bottom_banner",
            name = "底部横幅广告",
            hint = "底部横幅广告，关闭按钮位于底部区域",
            textKeywords = "跳过, 关闭, ✕",
            descKeywords = "跳过, 关闭",
            viewIdKeywords = "close, skip",
            area = "bottom",
        ),
    )

    /** 供规则编辑器判断「纯图标关闭」模板需要注入 icon_button 条件。 */
    const val ICON_ONLY_TEMPLATE_ID = "icon_only"
}
