package com.example.skipstart.service

import com.example.skipstart.engine.Rule
import com.example.skipstart.engine.RuleAction
import com.example.skipstart.engine.RuleCondition
import com.example.skipstart.engine.RuleFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 学习模式捕获的节点样本（说明书第 10 章字段 + v0.2.0 增强字段）。
 *
 * v0.2.0 新增：
 * - [tapRatioX] / [tapRatioY]：用户手指点击位置（屏幕比例），用于生成位置兜底点击；
 * - [screenWidth] / [screenHeight]：捕获时屏幕尺寸，供区域判定与比例换算；
 * - [captureMethod]：命中方式（自身可点击 / 父节点 / 子节点 / 坐标回溯），
 *   在 UI 上显示成一句人话，帮助用户判断样本质量。
 */
data class LearnedSample(
    val packageName: String,
    val activityName: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val bounds: String?,
    val clickable: Boolean,
    val capturedAt: Long,
    val tapRatioX: Float = 0.92f,
    val tapRatioY: Float = 0.06f,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 1920,
    val captureMethod: String = METHOD_SELF,
    val captureNote: String? = null,
) {
    companion object {
        const val METHOD_SELF = "self"           // 点击的节点自身带信息
        const val METHOD_PARENT = "parent"       // 自身无信息，取自可点击父节点
        const val METHOD_CHILD = "child"         // 自身无信息，取自子节点文字
        const val METHOD_COORDINATE = "coord"    // 事件无 source，按点击坐标回溯
    }
}

/**
 * 一条候选规则 + 给用户看的解释（v0.2.0）。
 *
 * [confidence] 0-100 的推荐度：按「证据类型 + 是否有位置兜底」估算，
 * 越高代表下次自动点击越不容易失败，UI 用它排序并显示「推荐」标记。
 */
data class LearningCandidate(
    val rule: Rule,
    val title: String,
    val detail: String,
    val confidence: Int,
    val recommended: Boolean,
)

/**
 * 学习模式控制器（阶段 6；v0.2.0 重做）。
 *
 * 设计目标：**用户零门槛、失败可自愈**。
 * - 包名不用手敲：由「学习」页从已安装应用列表带入；
 * - 开始学习后自动拉开目标 App，用户只需点一下「跳过」；
 * - 学习期间服务只观察、绝不自动点击（[SkipAccessibilityService.tryAutoSkip] 直接返回）；
 * - 用户点一下就捕获一个样本并自动结束，同时生成多条候选规则供挑选；
 * - 超时未捕获会明确告知原因，而不是静默失败。
 */
class LearningController {

    /** 学习会话状态机。 */
    enum class Phase { IDLE, WAITING, CAPTURED, EXPIRED_TIME, EXPIRED_MANUAL }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _targetPackage = MutableStateFlow<String?>(null)
    val targetPackage: StateFlow<String?> = _targetPackage.asStateFlow()

    private val _sample = MutableStateFlow<LearnedSample?>(null)
    val sample: StateFlow<LearnedSample?> = _sample.asStateFlow()

    private val _candidates = MutableStateFlow<List<LearningCandidate>>(emptyList())
    val candidates: StateFlow<List<LearningCandidate>> = _candidates.asStateFlow()

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _captureCount = MutableStateFlow(0)
    val captureCount: StateFlow<Int> = _captureCount.asStateFlow()

    /** 一次性提示（Snackbar 消费后调用 [consumeFeedback] 清空）。 */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /** 会话起始时刻（elapsedRealtime），用于超时判断。 */
    private var startedAt = 0L

    /** 本次学习最多持续时长，防止用户忘记退出而一直待命。 */
    var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    fun start(packageName: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MS) {
        timeoutMs = timeoutMillis
        _targetPackage.value = packageName.trim()
        _sample.value = null
        _candidates.value = emptyList()
        _captureCount.value = 0
        startedAt = android.os.SystemClock.elapsedRealtime()
        _phase.value = Phase.WAITING
        _enabled.value = true
        _feedback.value = "已开始学习：请在目标 App 里手动点一下「跳过」按钮"
    }

    /** 用户主动取消。 */
    fun stop(reason: String? = null) {
        if (!_enabled.value) return
        _enabled.value = false
        if (_phase.value == Phase.WAITING) _phase.value = Phase.EXPIRED_MANUAL
        reason?.let { _feedback.value = it }
    }

    /** 超时检查（由 UI 定时器调用）；超时后停止学习并给出可操作提示。 */
    fun checkTimeout(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        if (!_enabled.value || startedAt == 0L) return false
        if (now - startedAt < timeoutMs) return false
        _enabled.value = false
        _phase.value = Phase.EXPIRED_TIME
        _feedback.value = "学习超时：这段时间没有捕获到点击。请确认目标 App 已打开且手动点了「跳过」"
        return true
    }

    /** 剩余时间（秒），未开启学习时返回 0。 */
    fun remainingSeconds(now: Long = android.os.SystemClock.elapsedRealtime()): Int {
        if (!_enabled.value || startedAt == 0L) return 0
        val left = timeoutMs - (now - startedAt)
        return if (left <= 0) 0 else (left / 1000).toInt()
    }

    /** 仅丢弃当前样本，不改动学习会话。 */
    fun clearSample() {
        _sample.value = null
        _candidates.value = emptyList()
        if (_phase.value == Phase.CAPTURED) _phase.value = Phase.IDLE
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    /**
     * 服务回调：捕获用户的手动点击。
     * 只接受「学习开启 + 目标包」的样本；捕获成功即自动结束学习（一次学习一个样本）。
     */
    fun submit(sample: LearnedSample) {
        if (!_enabled.value) return
        if (sample.packageName != _targetPackage.value) return
        _sample.value = sample
        _candidates.value = buildCandidates(sample)
        _captureCount.value = _captureCount.value + 1
        _enabled.value = false
        _phase.value = Phase.CAPTURED
        _feedback.value = "已捕获！返回应用确认规则即可"
    }

    /** 服务回调：识别到目标 App 打开（用于 UI 进度提示）。 */
    fun notifyTargetForeground() {
        if (_enabled.value && _phase.value == Phase.WAITING) {
            _feedback.value = "目标 App 已打开：等开屏广告出现后点一下「跳过」"
        }
    }

    /**
     * 生成候选规则（v0.2.0 由「一条」改为「多条 + 推荐度」）：
     * 1. 文字关键词（最稳，抗布局变化）；
     * 2. 内容描述关键词（无障碍描述，常见于纯图标 × 按钮）；
     * 3. viewId（同一 App 版本内最精确）；
     * 4. 位置兜底（节点读不到任何信息时的最后手段，安全区受限）。
     * 规则均带位置兜底动作，节点匹配失败时仍按用户实际点击位置点一次。
     */
    fun buildCandidates(sample: LearnedSample): List<LearningCandidate> {
        val conditions = LinkedHashMap<String, RuleCondition>()
        val textKeyword = keywordOf(sample.text) ?: buttonLikeKeyword(sample.text)
        val descKeyword = keywordOf(sample.contentDescription) ?: buttonLikeKeyword(sample.contentDescription)
        textKeyword?.let { conditions["text"] = RuleCondition("text_regex", Regex.escape(it), null, 50) }
        descKeyword?.let { conditions["desc"] = RuleCondition("desc_regex", Regex.escape(it), null, 40) }
        sample.viewIdResourceName
            ?.takeIf { it.isNotBlank() }
            ?.let { conditions["viewId"] = RuleCondition("view_id", it, null, 30) }

        val fallback = safeFallback(sample)
        val action = RuleAction(type = "click_node_or_parent", fallback = fallback)
        val activityPatterns = sample.activityName
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: listOf("*")

        val list = ArrayList<LearningCandidate>()
        val stamp = sample.capturedAt

        conditions["text"]?.let { cond ->
            list += LearningCandidate(
                rule = ruleOf(
                    id = "learned_text_$stamp",
                    name = "学习规则 · ${shortPkg(sample.packageName)} · 文字",
                    sample = sample,
                    conditions = listOf(cond),
                    action = action,
                    minScore = 50,
                    activityPatterns = activityPatterns,
                ),
                title = "按按钮文字匹配（推荐）",
                detail = "按钮文字含「${cond.pattern}」时点击，抗界面改版，最稳。",
                confidence = 92,
                recommended = true,
            )
        }
        conditions["desc"]?.let { cond ->
            list += LearningCandidate(
                rule = ruleOf(
                    id = "learned_desc_$stamp",
                    name = "学习规则 · ${shortPkg(sample.packageName)} · 描述",
                    sample = sample,
                    conditions = listOf(cond),
                    action = action,
                    minScore = 40,
                    activityPatterns = activityPatterns,
                ),
                title = "按内容描述匹配",
                detail = "按钮的无障碍描述含「${cond.pattern}」时点击，适合纯图标按钮。",
                confidence = 80,
                recommended = conditions["text"] == null,
            )
        }
        conditions["viewId"]?.let { cond ->
            list += LearningCandidate(
                rule = ruleOf(
                    id = "learned_viewid_$stamp",
                    name = "学习规则 · ${shortPkg(sample.packageName)} · viewId",
                    sample = sample,
                    conditions = listOf(cond),
                    action = action,
                    minScore = 30,
                    activityPatterns = activityPatterns,
                ),
                title = "按控件 ID 匹配",
                detail = "控件 ID 为 ${cond.pattern} 时点击，同版本最精确，但 App 更新后可能失效。",
                confidence = 70,
                // 只有文字、描述都读不到时，viewId 才是最佳选择
                recommended = conditions["text"] == null && conditions["desc"] == null,
            )
        }
        // 位置兜底：仅在「完全没有文字/描述线索」时才给（有线索时不需要盲点，避免误触）
        val hasTextEvidence = conditions["text"] != null || conditions["desc"] != null
        if (fallback != null && !hasTextEvidence) {
            list += LearningCandidate(
                rule = ruleOf(
                    id = "learned_pos_$stamp",
                    name = "学习规则 · ${shortPkg(sample.packageName)} · 位置",
                    sample = sample,
                    conditions = listOf(positionCondition(sample)),
                    action = RuleAction(
                        type = "click_node_or_parent",
                        fallback = RuleFallback("click_xy_ratio", fallback.x, fallback.y),
                    ),
                    minScore = 100, // 位置规则要求很高分，避免误点
                    activityPatterns = activityPatterns,
                ),
                title = "按位置兜底（保守）",
                detail = "在原来那个位置点一次（你点的是${areaHintLabelFor(sample)}）。" +
                    "最不容易误触，但界面变化后可能漏点。",
                confidence = 55,
                recommended = false,
            )
        }

        // 纯图标兜底（v0.3.0）：即使连 viewId 都没有，也能靠
        // 「可点击 + 无文字 + 小尺寸 + 位于边缘」认出 ✕ 图标按钮
        if (!hasTextEvidence && conditions["viewId"] == null) {
            list += LearningCandidate(
                rule = ruleOf(
                    id = "learned_icon_$stamp",
                    name = "学习规则 · ${shortPkg(sample.packageName)} · 图标",
                    sample = sample,
                    conditions = listOf(RuleCondition("icon_button", "*", null, 25)),
                    action = action,
                    minScore = 60,
                    activityPatterns = activityPatterns,
                ),
                title = "按纯图标按钮识别",
                detail = "按钮没有文字也没有描述时用这条：只要出现「可点击、小尺寸、" +
                    "位于屏幕边缘」的图标按钮就点它。",
                confidence = 60,
                recommended = true,
            )
        }

        // 过滤掉 pattern 为空导致必然失败的候选，并按推荐度降序
        return list
            .filter { candidate ->
                candidate.rule.conditions.any { it.pattern.isNotBlank() }
            }
            .sortedByDescending { it.confidence }
    }

    /** 兼容旧调用：返回最推荐的一条候选规则。 */
    fun buildCandidateRule(sample: LearnedSample): Rule? =
        buildCandidates(sample).firstOrNull()?.rule

    /**
     * 位置兜底候选的条件（v0.3.0）。
     *
     * 位置规则的价值在于「按用户实际点过的坐标点一次」，
     * 条件只需要能把开屏上的某个节点捞出来当作点击载体。
     * 这里按证据强度退化选条件；若确实一点线索都没有，
     * 直接返回 `icon_button` 条件 —— 它能匹配「可点击 + 无文字 + 小尺寸 + 位于边缘」的
     * 纯图标 ✕ 按钮，比留一个空白 pattern 让规则永久失效要好。
     */
    private fun positionCondition(sample: LearnedSample): RuleCondition {
        keywordOf(sample.text)?.let {
            return RuleCondition("text_regex", Regex.escape(it), null, 55)
        }
        keywordOf(sample.contentDescription)?.let {
            return RuleCondition("desc_regex", Regex.escape(it), null, 45)
        }
        sample.viewIdResourceName?.takeIf { it.isNotBlank() }?.let {
            return RuleCondition("view_id", it, null, 35)
        }
        return RuleCondition("icon_button", "*", null, 25)
    }

    /** 该样本的点击落点说明（用于候选规则的文案）。 */
    private fun areaHintLabelFor(sample: LearnedSample): String =
        areaHintLabel(areaHintOf(sample.tapRatioX, sample.tapRatioY))

    private fun ruleOf(
        id: String,
        name: String,
        sample: LearnedSample,
        conditions: List<RuleCondition>,
        action: RuleAction,
        minScore: Int,
        activityPatterns: List<String>,
    ): Rule = Rule(
        id = id,
        name = name,
        packageNames = listOf(sample.packageName),
        activityPatterns = activityPatterns,
        launchWindowMs = 8_000,
        maxClicksPerLaunch = 1,
        cooldownMs = 2_000,
        matchMode = "any",
        minScore = minScore,
        conditions = conditions,
        action = action,
    )

    /**
     * 位置兜底比例（v0.3.0 重写）。
     *
     * 旧版硬编码了一个「右上角安全区」：纵向超过 45% 就**直接放弃**位置兜底，
     * 等于假设「跳过按钮只可能在屏幕上半部分」——与实际情况不符
     * （底部横幅广告、左下角关闭按钮都很常见）。
     *
     * 现在：用户亲手点过的那一点就是最强证据，原样采用，只做基本的屏幕内钳制；
     * 真正的防误触由「规则条件 + 位置加权 + 阈值」负责，而不是靠砍掉半个屏幕。
     */
    private fun safeFallback(sample: LearnedSample): RuleFallback? {
        val x = sample.tapRatioX
        val y = sample.tapRatioY
        if (x.isNaN() || y.isNaN()) return null
        // 钳制到屏幕内，留 1% 边距避免点到屏幕物理边缘导致手势丢失
        return RuleFallback(
            type = "click_xy_ratio",
            x = x.coerceIn(0.01f, 0.99f),
            y = y.coerceIn(0.01f, 0.99f),
        )
    }

    /** 去掉尾部倒计时数字，生成稳定关键词（"跳过 5" → "跳过"）。 */
    internal fun keywordOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().replace(Regex("\\s*\\d+\\s*$"), "").trim()
        return trimmed.ifBlank { null }
    }

    /** 节点文字较杂时，从中抠出「跳过 / 关闭」这类关键片段。 */
    private fun buttonLikeKeyword(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val hit = SKIP_HINTS.firstOrNull { raw.contains(it) } ?: return null
        return hit
    }

    private fun shortPkg(pkg: String): String = pkg.substringAfterLast('.')

    companion object {
        /** 默认学习时长：60 秒足够「冷启动 → 开屏 → 点一下」。 */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /**
         * 学习页与捕获逻辑共用的「跳过类」关键词（v0.3.0 扩展）。
         *
         * 旧版只有中文「跳过/关闭」+ 两个英文词，导致两种情况学不到：
         * 1. **叉号按钮**：广告关闭按钮经常就是 ✕ / × / ❌，文字层没有「跳过」二字；
         * 2. 英文界面 / 繁体界面。
         */
        val SKIP_HINTS = listOf(
            // 中文（简繁）
            "跳过", "跳過", "略过", "略過", "关闭", "關閉", "关闭广告", "跳过广告", "跳过按钮",
            // 英文
            "skip", "close", "dismiss", "cancel", "skip ad", "close ad",
            // 叉号系字符（本身就是关闭语义）
            "✕", "✖", "✗", "❌", "❎", "×", "⨯", "╳",
        )

        /** 供 UI 显示的推荐度文案。 */
        fun confidenceLabel(confidence: Int): String = when {
            confidence >= 85 -> "很可靠"
            confidence >= 70 -> "较可靠"
            else -> "一般"
        }

        /**
         * 按屏幕比例判定点击落在哪个区域（v0.3.0）。
         *
         * 旧版有个硬编码的安全区：纵向超过 45% 直接放弃位置兜底 —— 这等价于
         * 「跳过按钮只可能在屏幕上半部分」，与实际情况不符（底部横幅广告的关闭按钮在下方）。
         * 现在按真实落点给出区域名，位置兜底只在真正「无文字线索」时才启用。
         */
        fun areaHintOf(xRatio: Float, yRatio: Float): String {
            val vertical = when {
                yRatio < 0.25f -> "top"
                yRatio > 0.75f -> "bottom"
                else -> "middle"
            }
            val horizontal = when {
                xRatio < 0.35f -> "left"
                xRatio > 0.65f -> "right"
                else -> "center"
            }
            return if (vertical == "middle") {
                when (horizontal) {
                    "left" -> "left"
                    "right" -> "right"
                    else -> "center"
                }
            } else {
                "${vertical}_$horizontal"
            }
        }

        /** 区域名 → 中文说明，用于 UI 展示「你点的是右下角」。 */
        fun areaHintLabel(area: String): String = when (area) {
            "top_left" -> "左上角"
            "top_right" -> "右上角"
            "top_center" -> "顶部中间"
            "bottom_left" -> "左下角"
            "bottom_right" -> "右下角"
            "bottom_center" -> "底部中间"
            "left" -> "左侧边缘"
            "right" -> "右侧边缘"
            "top" -> "顶部区域"
            "bottom" -> "底部区域"
            else -> "屏幕中间"
        }

        /** 点击位置 → 区域名，用于 UI 提示（基于节点 bounds）。 */
        fun areaLabel(bounds: String?, w: Int, h: Int): String {
            val b = bounds?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return "未知"
            if (b.size != 4 || w <= 0 || h <= 0) return "未知"
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            return areaHintLabel(areaHintOf(cx / w, cy / h))
        }

        /** 百分比文案，避免 UI 层重复算。 */
        fun percent(value: Float): String = "${(value * 100).roundToInt()}%"
    }
}
