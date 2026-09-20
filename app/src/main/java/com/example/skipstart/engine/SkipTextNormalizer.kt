package com.example.skipstart.engine

/**
 * 跳过按钮文本归一化（v0.4.0 新增）。
 *
 * ## 要解决的问题
 * 开屏「跳过」按钮上常带倒计时数字，且**两种排列都存在**：
 * - 数字在后：`跳过5`、`跳过 5`、`跳过5s`、`跳过 5 秒`
 * - 数字在前：`3跳过`、`3 跳过`、`5秒后跳过`、`3s跳过`、`3s后跳过`
 *
 * 学习模式拿到的样本是**某一瞬间**的文本（比如倒计时正好是 3）。若直接把这串文字
 * 当关键词写进规则，等倒计时变成 5/2/1 就再也匹配不上 —— 学到的规则是"死"的。
 *
 * 旧版只剥尾部数字（`\s*\d+\s*$`），所以 `跳过3` 能处理，`3跳过` 不能。
 *
 * ## 做法
 * 1. 若文本里命中「跳过 / 关闭 / skip / close / ✕」等语义词，直接取该词作为关键词；
 * 2. 否则把两端的数字及倒计时连接符（空格、`s`、`秒`、`后`、`·`、括号等）剥掉，
 *    剩下的作为关键词；
 * 3. 用 [patternForCountdownKeyword] 把关键词变成**能同时匹配两种排列**的正则，
 *    保证下一次倒计时数字不同也能命中。
 *
 * 本对象是纯函数、无 Android 依赖，便于在 JVM/脚本侧做等价验证。
 */
object SkipTextNormalizer {

    /** 语义关键词：命中即取它本身（顺序敏感，长词在前，避免「跳过广告」被「跳过」截断）。 */
    val KEYWORDS = listOf(
        "跳过广告", "跳過廣告", "关闭广告", "關閉廣告", "跳过按钮",
        "跳过", "跳過", "略过", "略過", "关闭", "關閉",
        "skip ad", "close ad", "skip", "close", "dismiss", "cancel",
        "✕", "✖", "✗", "×", "⨯", "╳", "❌", "❎",
    )

    /** 全角数字与全角空格（部分 ROM 的广告用全角渲染倒计时）。 */
    private const val FULL_WIDTH_DIGITS = "０-９"
    private const val FULL_WIDTH_SPACE = "\u3000"

    /** 数字部分（含全角）。 */
    private const val DIGITS = "0-9$FULL_WIDTH_DIGITS"

    /**
     * 倒计时连接符：空格、全角空格、s/S、秒、后、·、.、:、括号、顿号、连字符、竖线、反斜杠。
     *
     * ⚠️ 字符类里**连字符必须放末尾**：否则 `】-—` 会被解析成一个字符范围，
     * 而 `】`(U+3011) 到 `—`(U+2014) 是逆序范围，Java/Python 正则会直接抛异常。
     * 这个坑正是「3跳过」这种数字在前的文本才会走到的分支（已由验证脚本抓出）。
     */
    private const val CONNECTORS =
        "\\s${FULL_WIDTH_SPACE}sS秒后·\\.:：,，、（）\\(\\)\\[\\]【】|/\\\\-—"

    /** 两端需要剥掉的字符：数字 + 连接符。 */
    private val TRIM_EDGE = Regex("^[$DIGITS$CONNECTORS]+|[$DIGITS$CONNECTORS]+$")

    /**
     * 生成稳定关键词。
     * - `"跳过3"` → `"跳过"`；`"3跳过"` → `"跳过"`；`"5秒后跳过"` → `"跳过"`
     * - `"跳过"` → `"跳过"`；`"✕"` → `"✕"`
     * - `"3"`（纯数字、无任何语义词）→ `null`（不该单独当关键词，否则会误点）
     */
    fun keywordOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        // 1) 语义词优先（大小写不敏感，兼容英文）
        val lower = text.lowercase()
        KEYWORDS.firstOrNull { text.contains(it) || lower.contains(it.lowercase()) }
            ?.let { return it }

        // 2) 剥掉两端倒计时痕迹
        val stripped = text.replace(TRIM_EDGE, "").trim()
        return stripped.ifBlank { null }
    }

    /**
     * 关键词 → 匹配正则。
     *
     * 对**纯数字无关**的普通关键词（如 `跳过`、`✕`），生成
     * `"\\Q跳过\\E|\\d*\\s*\\Q跳过\\E|\\Q跳过\\E\\s*\\d+"`
     * 三段交替，于是 `跳过`、`跳过 5`、`5跳过`、`跳过5s`、`5秒后跳过` 全部命中。
     *
     * 关键词本身含数字时（少见，例如 `跳过2` 这类把数字当名字一部分的按钮），
     * 退化为精确字面量匹配，避免过度放宽导致误点。
     */
    fun patternForCountdownKeyword(keyword: String): String {
        val escaped = Regex.escape(keyword)
        if (keyword.any { it.isDigit() }) return escaped
        return listOf(
            escaped,                              // 跳过
            "[$DIGITS]*[$CONNECTORS]*$escaped",   // 3跳过 / 5秒后跳过 / 3s跳过
            "$escaped[$CONNECTORS]*[$DIGITS]+",   // 跳过5 / 跳过 5s / 跳过5秒
        ).joinToString("|")
    }

    /** 该文本是否带倒计时数字（供 UI 提示「已识别为倒计时按钮」）。 */
    fun hasCountdown(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return Regex("[$DIGITS]").containsMatchIn(raw)
    }
}
