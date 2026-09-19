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
    val tapRatioY: Float = 0.08f,
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
        // 位置兜底：仅当读到可用信息（避免纯盲点）或用户确实点在上方区域时才给出
        if (fallback != null) {
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
                detail = "只在按钮出现在你点击过的位置时才动手，最不容易误触，但可能漏点。",
                confidence = 55,
                recommended = false,
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
     * 位置兜底候选的条件。
     *
     * 关键点：位置规则的价值在于「按用户实际点过的位置点一次」，
     * 条件只需要能把开屏上的某个节点捞出来即可（真正点击坐标由 action.fallback 决定）。
     * 因此这里按「证据强度」退化选条件；若确实一点线索都没有，
     * 返回空 pattern —— 调用方会过滤掉这条候选，UI 提示用户「没拿到可用线索」。
     */
    private fun positionCondition(sample: LearnedSample): RuleCondition {
        keywordOf(sample.text)?.let {
            return RuleCondition("text_regex", Regex.escape(it), null, 50)
        }
        keywordOf(sample.contentDescription)?.let {
            return RuleCondition("desc_regex", Regex.escape(it), null, 40)
        }
        sample.viewIdResourceName?.takeIf { it.isNotBlank() }?.let {
            return RuleCondition("view_id", it, null, 30)
        }
        sample.className?.takeIf { it.isNotBlank() }?.let {
            return RuleCondition("class_name", it, null, 10)
        }
        // 没有任何可读信息：位置兜底规则会因为达不到 minScore 而永不触发，
        // 此时不如不给这条候选，让 UI 明确告诉用户「这个按钮没有可用线索」。
        return RuleCondition("class_name", "", null, 0)
    }

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
     * 位置兜底比例，做安全区收口：
     * - 只允许屏幕上 45% 以内、横向 45%~100% 的「右上角安全区」，避免误点中部内容；
     * - 超出安全区时钳制回边界，而不是放弃（毕竟用户确实点在那里）。
     */
    private fun safeFallback(sample: LearnedSample): RuleFallback? {
        val x = sample.tapRatioX
        val y = sample.tapRatioY
        if (x.isNaN() || y.isNaN()) return null
        if (y > SAFE_MAX_Y) return null      // 明显点在页面中部/底部，不做位置兜底
        val clampedX = x.coerceIn(SAFE_MIN_X, 0.98f)
        val clampedY = y.coerceIn(0.01f, SAFE_MAX_Y)
        return RuleFallback("click_xy_ratio", clampedX, clampedY)
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

        private const val SAFE_MIN_X = 0.45f
        private const val SAFE_MAX_Y = 0.45f

        /** 学习页与捕获逻辑共用的「跳过类」关键词。 */
        val SKIP_HINTS = listOf(
            "跳过", "关闭", "跳過", "略过", "skip", "Skip", "SKIP", "close", "Close", "关闭广告",
        )

        /** 供 UI 显示的推荐度文案。 */
        fun confidenceLabel(confidence: Int): String = when {
            confidence >= 85 -> "很可靠"
            confidence >= 70 -> "较可靠"
            else -> "一般"
        }

        /** 点击位置 → 区域名，用于 UI 提示「你点的是右上角」。 */
        fun areaLabel(bounds: String?, w: Int, h: Int): String {
            val b = bounds?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return "未知"
            if (b.size != 4) return "未知"
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            val rightHalf = cx > 0.5f * w
            val bottomHalf = cy > 0.5f * h
            return when {
                !bottomHalf && rightHalf -> "右上角"
                !bottomHalf -> "左上角"
                rightHalf -> "右下角"
                else -> "左下角"
            }
        }

        /** 百分比文案，避免 UI 层重复算。 */
        fun percent(value: Float): String = "${(value * 100).roundToInt()}%"
    }
}
