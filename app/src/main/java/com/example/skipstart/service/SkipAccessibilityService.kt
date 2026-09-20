package com.example.skipstart.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skipstart.AppGraph
import com.example.skipstart.capture.NodeCollector
import com.example.skipstart.capture.NodeSnapshot
import com.example.skipstart.data.LogEntry
import com.example.skipstart.engine.Rule
import com.example.skipstart.util.AccessibilityUtils
import com.example.skipstart.util.Logger
import com.example.skipstart.util.ScreenUtils

/**
 * 开屏广告自动跳过 · 无障碍服务。
 *
 * v0.2.0 两类改动：
 * 1. **机型适配**：把「服务真实连接状态」上报给 [AccessibilityUtils]，
 *    不再只依赖 Secure 表的字符串判断；同时把点击类事件声明扩展到长按与多窗口；
 * 2. **学习模式效果**：点击捕获不再只认「事件源节点自身带文字」这一种情况，
 *    而是沿着「自身 → 父链 → 子节点 → 点击坐标回溯」四级兜底取样，
 *    并把窗口节点树缓存下来，供学习页做「规则试跑」验证。
 */
class SkipAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null
    private var lastActivity: String? = null

    /** 阶段 4：防误触集中守卫。 */
    private val guard = AntiTouchGuard()
    private var lastScanTime = 0L
    private var noMatchLogged = false
    private var consecutiveFailures = 0

    /** v0.2.0：最近一次窗口节点快照缓存（仅内存），供学习页「试跑规则」与坐标回溯使用。 */
    private val learningCache = NodeCache()

    private val ruleCache = NodeCache()

    /** 只读快照缓存：包名 + 时间戳 + 节点列表，过期即失效。 */
    private class NodeCache {
        @Volatile
        var packageName: String? = null

        @Volatile
        var at: Long = 0L

        @Volatile
        var nodes: List<NodeSnapshot> = emptyList()

        fun put(pkg: String, list: List<NodeSnapshot>) {
            packageName = pkg
            nodes = list
            at = System.currentTimeMillis()
        }

        fun get(pkg: String?): List<NodeSnapshot>? {
            if (nodes.isEmpty()) return null
            if (pkg != null && packageName != pkg) return null
            if (System.currentTimeMillis() - at > CACHE_TTL_MS) return null
            return nodes
        }

        fun clear() {
            nodes = emptyList()
            packageName = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityUtils.setServiceConnected(true)
        Logger.i(TAG, "无障碍服务已连接（版本 v$SERVICE_VERSION）")
        // 部分 ROM（MIUI / ColorOS）在授予无障碍后需要一次窗口事件才会激活，
        // 这里主动读一次窗口，避免「开了服务却毫无反应」。
        mainHandler.postDelayed({ refreshLearningCache() }, ACTIVATION_PROBE_MS)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        instance = null
        AccessibilityUtils.setServiceConnected(false)
        mainHandler.removeCallbacksAndMessages(null)
        learningCache.clear()
        ruleCache.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // 学习模式：只观察、不自动点击（真正的拦截点在 tryAutoSkip 首行）。
        // 注意这里**不 return**：目标 App 可能同时是自动跳过的目标包，
        // 学习结束后仍需正常走下面的常规链路。
        val learningActive = AppGraph.learningController.enabled.value &&
            pkg == AppGraph.learningController.targetPackage.value
        if (learningActive) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    lastPackage = pkg
                    lastActivity = event.className?.toString()
                    AppGraph.learningController.notifyTargetForeground()
                    refreshLearningCache()
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                -> refreshLearningCache()

                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                -> {
                    lastPackage = pkg
                    lastActivity = event.className?.toString()
                    captureLearningClick(event)
                }
                else -> Unit
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastPackage = pkg
                lastActivity = event.className?.toString()
                if (AppGraph.ruleRepository.isTargetPackage(pkg)) {
                    guard.onTargetLaunch(pkg, SystemClock.elapsedRealtime())
                    noMatchLogged = false
                    consecutiveFailures = 0
                    Logger.i(TAG, "目标包进入前台：$pkg")
                }
                if (AppGraph.debugAutoDump.value) {
                    scheduleDump()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                if (AppGraph.ruleRepository.isTargetPackage(pkg)) {
                    tryAutoSkip()
                }
            }
            else -> Unit
        }
    }

    /** 主流水线：守卫判定 → 节流扫描 → 引擎（匹配+点击）→ 日志。 */
    private fun tryAutoSkip() {
        // 学习模式期间不自动点击，只记录（说明书第 10 章）
        if (AppGraph.learningController.enabled.value) return
        val pkg = guard.currentLaunchPkg ?: return
        val rule = AppGraph.ruleRepository.enabledRulesFor(pkg).firstOrNull() ?: return
        val settings = AppGraph.settingsStore.settings.value
        val now = SystemClock.elapsedRealtime()

        when (val verdict = guard.canProceed(rule, now, settings)) {
            is AntiTouchGuard.Verdict.Denied -> {
                if (verdict.reason.startsWith(REASON_WINDOW_EXPIRED) &&
                    guard.clickCountThisLaunch == 0 && !noMatchLogged
                ) {
                    noMatchLogged = true
                    addLog(
                        rule = rule,
                        snapshot = null,
                        actionType = "NO_MATCH",
                        success = false,
                        failReason = "冷启动窗口内未匹配到目标节点",
                        eventType = "WINDOW_EXPIRED",
                    )
                }
                return
            }
            is AntiTouchGuard.Verdict.Allowed -> Unit
        }

        // 扫描节流，避免内容变化事件洪峰
        if (now - lastScanTime < SCAN_THROTTLE_MS) return
        lastScanTime = now

        val root = rootInActiveWindow ?: return
        val nodes = NodeCollector.collect(root)
        ruleCache.put(pkg, nodes)

        val result = AppGraph.ruleEngine.handleEvent(pkg, lastActivity, root, this) ?: return

        if (result.actionType != null || result.failReason != null) {
            if (result.success) {
                guard.recordClick(SystemClock.elapsedRealtime())
                consecutiveFailures = 0
            } else {
                guard.recordFailedAttempt(SystemClock.elapsedRealtime())
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    guard.markWindowExpired()
                }
            }
            addLog(
                rule = result.rule,
                snapshot = result.snapshot,
                actionType = result.actionType,
                success = result.success,
                failReason = result.failReason,
                eventType = "AUTO_CLICK",
                score = result.score,
                matchedBy = result.matchedBy.joinToString("; "),
            )
        }
    }

    // ---------------------------------------------------------------------
    // 学习模式（阶段 6 / v0.2.0 增强）
    // ---------------------------------------------------------------------

    /**
     * 学习模式捕获手动点击。
     *
     * 旧版只取 `event.source` 自身的文字，遇到「点击的是无文字的图片/容器」就直接失败，
     * 这是学习模式效果差的主因。现在按四级取信息：
     * 自身 → 也可点击的父节点 → 子节点文字 → 用点击坐标在当前节点树里回溯。
     */
    private fun captureLearningClick(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != AppGraph.learningController.targetPackage.value) return
        if (!isLookingLikeSkipButton(event)) return

        val (screenW, screenH) = ScreenUtils.screenSize(this)

        val source = event.source
        var method = LearnedSample.METHOD_SELF
        var note: String? = null

        var text = source?.text?.toString()?.takeIf { it.isNotBlank() }
        var desc = source?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        var viewId = source?.viewIdResourceName?.takeIf { it.isNotBlank() }
        val className = source?.className?.toString()?.takeIf { it.isNotBlank() }
        var clickable = source?.isClickable == true
        var bounds = source?.let { boundsString(it) }

        // 一级：自身信息不足，向上找语义更全的父节点
        if (isSparse(text, desc, viewId) && source != null) {
            var node: AccessibilityNodeInfo? = source.parent
            var hop = 0
            while (node != null && hop < PARENT_SCAN_LIMIT) {
                val pText = node.text?.toString()?.takeIf { it.isNotBlank() }
                val pDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val pId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
                if (!isSparse(pText, pDesc, pId)) {
                    text = pText
                    desc = pDesc
                    viewId = pId
                    clickable = node.isClickable
                    bounds = boundsString(node)
                    method = LearnedSample.METHOD_PARENT
                    note = "按钮文字取自上层节点（第 ${hop + 1} 层）"
                    break
                }
                node = node.parent
                hop++
            }
        }

        // 二级：父链也没有，看子节点文字（整块可点击的广告卡片常见）
        if (isSparse(text, desc, viewId) && source != null) {
            val fromChild = bestChildText(source)
            if (fromChild != null) {
                text = fromChild
                method = LearnedSample.METHOD_CHILD
                note = "按钮文字取自子节点"
                if (bounds == null) bounds = boundsString(source)
            }
        }

        // 三级：事件没有 source（部分 ROM 不提供），在缓存的节点树里找最像关闭按钮的候选
        if (isSparse(text, desc, viewId) && bounds == null) {
            val hit = bestSkipCandidate(pkg)
            if (hit != null) {
                text = hit.text?.takeIf { it.isNotBlank() }
                desc = hit.contentDescription?.takeIf { it.isNotBlank() }
                viewId = hit.viewIdResourceName?.takeIf { it.isNotBlank() }
                clickable = hit.clickable
                bounds = hit.bounds
                method = LearnedSample.METHOD_COORDINATE
                note = "系统未提供点击节点，已从窗口快照中取边缘候选"
            }
        }

        source?.recycle()

        // 点击位置：优先用命中节点 bounds 的中心，其次退回右上角默认位置
        val center = centerOf(bounds)
        val hasCenter = center != null && screenW > 0 && screenH > 0
        val fallbackX = if (hasCenter) center!!.first / screenW else DEFAULT_FALLBACK_X
        val fallbackY = if (hasCenter) center!!.second / screenH else DEFAULT_FALLBACK_Y

        if (bounds == null && !hasCenter) {
            method = LearnedSample.METHOD_COORDINATE
            note = "未拿到节点位置，规则将主要依赖右上角兜底点击"
        }

        val sample = LearnedSample(
            packageName = pkg,
            activityName = event.className?.toString() ?: lastActivity,
            text = text,
            contentDescription = desc,
            viewIdResourceName = viewId,
            className = className,
            bounds = bounds,
            clickable = clickable,
            capturedAt = System.currentTimeMillis(),
            tapRatioX = fallbackX.coerceIn(0f, 1f),
            tapRatioY = fallbackY.coerceIn(0f, 1f),
            screenWidth = screenW,
            screenHeight = screenH,
            captureMethod = method,
            captureNote = note,
        )

        AppGraph.learningController.submit(sample)
        // 学习捕获也写日志：用户在「日志」页能看到「学习模式确实在工作」
        addLog(
            rule = null,
            snapshot = NodeSnapshot(
                text = sample.text,
                contentDescription = sample.contentDescription,
                viewIdResourceName = sample.viewIdResourceName,
                className = sample.className,
                bounds = sample.bounds,
                clickable = sample.clickable,
                enabled = true,
                visible = true,
                depth = 0,
                index = 0,
                parentIndex = -1,
            ),
            actionType = "LEARN_CAPTURE",
            success = true,
            failReason = null,
            eventType = "LEARNING",
            matchedBy = "捕获方式=$method${note?.let { "（$it）" } ?: ""}",
            pkgOverride = pkg,
        )
        Logger.i(TAG, "学习捕获成功：method=$method text=$text viewId=$viewId")
    }

    private fun isSparse(text: String?, desc: String?, viewId: String?): Boolean =
        text.isNullOrBlank() && desc.isNullOrBlank() && viewId.isNullOrBlank()

    private fun boundsString(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    /** 子节点里第一个有文字的节点（优先命中「跳过/关闭」关键词，其次取最长文本）。 */
    private fun bestChildText(node: AccessibilityNodeInfo): String? {
        val candidates = ArrayList<String>()
        collectChildTexts(node, 0, candidates, MAX_CHILD_SCAN)
        if (candidates.isEmpty()) return null
        val hinted = candidates.firstOrNull { candidate ->
            LearningController.SKIP_HINTS.any { candidate.contains(it, ignoreCase = true) }
        }
        return hinted ?: candidates.maxByOrNull { it.length }
    }

    private fun collectChildTexts(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<String>,
        budget: Int,
    ) {
        if (depth > CHILD_SCAN_DEPTH || out.size >= budget) return
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size >= budget) return
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (t != null) out += t
            collectChildTexts(child, depth + 1, out, budget)
            child.recycle()
        }
    }

    /**
     * 四级兜底：系统没给出点击节点时，从缓存节点树里挑一个「最像跳过/关闭按钮」的节点。
     *
     * v0.3.0 从「只找右上角」改为**四角 + 上下边缘**全扫：
     * 旧版硬性要求 `cx > 0.5w && cy < 0.35h`，等于假设跳过按钮一定在右上角，
     * 导致左上角、底部横幅、右下角的关闭按钮完全学不到。
     * 现在按「关键词 > 可点击 > 小尺寸 > 靠近角落」综合打分，任何角落都能被选中。
     */
    private fun bestSkipCandidate(pkg: String): NodeSnapshot? {
        val nodes = ensureLearningCache(pkg) ?: return null
        val (screenW, screenH) = ScreenUtils.screenSize(this)
        if (screenW <= 0 || screenH <= 0) return null

        var best: NodeSnapshot? = null
        var bestScore = Int.MIN_VALUE
        for (node in nodes) {
            if (!node.visible) continue
            val b = node.bounds?.split(",")?.mapNotNull { it.toIntOrNull() }?.toIntArray()
                ?: continue
            if (b.size != 4) continue
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            val widthRatio = (b[2] - b[0]).toFloat() / screenW

            // 只考虑「边缘区」的节点：屏幕中间的内容块不会是关闭按钮
            if (!inEdgeZone(b, screenW, screenH)) continue
            // 过宽的节点不是关闭按钮（通栏整条广告不算）
            if (widthRatio > 0.6f) continue

            val label = (node.text ?: "") + " " + (node.contentDescription ?: "")
            var score = 0
            if (LearningController.SKIP_HINTS.any { label.contains(it, ignoreCase = true) }) score += 100
            if (node.clickable) score += 20
            if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) score += 15
            // 尺寸越小越像关闭按钮
            if (widthRatio <= 0.12f) score += 15 else if (widthRatio <= 0.22f) score += 10
            // 越靠近四角越优先（四个角一视同仁，不再只偏向右上）
            score += (30 - (distToNearestCorner(cx, cy, screenW, screenH) /
                kotlin.math.hypot(screenW.toFloat(), screenH.toFloat()) * 30).toInt())
                .coerceAtLeast(0)

            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    /** 边缘安全区：四角小块、上下边缘带、左右边缘的中上部（关闭按钮的实际分布范围）。 */
    private fun inEdgeZone(b: IntArray, w: Int, h: Int): Boolean {
        val cx = (b[0] + b[2]) / 2f
        val cy = (b[1] + b[3]) / 2f
        val nearTop = cy < h * 0.20f
        val nearBottom = cy > h * 0.80f
        val nearSide = cx < w * 0.25f || cx > w * 0.75f
        return nearTop || nearBottom || (nearSide && (cy < h * 0.35f || cy > h * 0.65f))
    }

    private fun distToNearestCorner(cx: Float, cy: Float, w: Int, h: Int): Float = minOf(
        kotlin.math.hypot(cx, cy),
        kotlin.math.hypot(w - cx, cy),
        kotlin.math.hypot(cx, h - cy),
        kotlin.math.hypot(w - cx, h - cy),
    )

    /**
     * 学习捕获的过滤：目标包里任何点击都会被上报，但开屏期间用户也可能真的在点广告内容。
     * 这里只放行「像跳过/关闭按钮」的点击（v0.3.0 放宽区域、新增叉号与纯图标判定）：
     * 1. 文字/描述命中「跳过 / 关闭 / ✕ / skip / close」类关键词 → 放行；
     * 2. 落在**任意边缘区**（四角 / 上下边缘 / 左右边缘）且是小尺寸可点击节点 → 放行；
     * 3. 其余（页面中部的大块内容、列表项等）→ 不采信，并在日志里留说明。
     *
     * 旧版第 2 条是「只在上方 35%」，导致底部的关闭按钮点了也不算数。
     */
    private fun isLookingLikeSkipButton(event: AccessibilityEvent): Boolean {
        val node = event.source
        val label = buildString {
            node?.text?.let { append(it) }
            append(' ')
            node?.contentDescription?.let { append(it) }
        }
        if (LearningController.SKIP_HINTS.any { label.contains(it, ignoreCase = true) }) {
            node?.recycle()
            return true
        }
        var allowed = false
        if (node != null) {
            val (screenW, screenH) = ScreenUtils.screenSize(this)
            val rect = Rect()
            node.getBoundsInScreen(rect)
            allowed = screenW > 0 && screenH > 0 &&
                node.isClickable &&
                inEdgeZone(
                    intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                    screenW,
                    screenH,
                ) &&
                rect.width() < screenW * 0.6f
        }
        node?.recycle()
        if (!allowed) {
            addLog(
                rule = null,
                snapshot = null,
                actionType = "LEARN_IGNORE",
                success = false,
                failReason = "这次点击不像「跳过 / 关闭 / ✕」按钮，已忽略（避免学到广告内容）",
                eventType = "LEARNING",
                matchedBy = "过滤规则：关键词 + 上方区域",
                pkgOverride = event.packageName?.toString(),
            )
        }
        return allowed
    }

    private fun centerOf(bounds: String?): Pair<Float, Float>? {
        val b = bounds?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        if (b.size != 4) return null
        if (b[2] <= b[0] || b[3] <= b[1]) return null
        return ((b[0] + b[2]) / 2f) to ((b[1] + b[3]) / 2f)
    }

    private fun refreshLearningCache() {
        val pkg = AppGraph.learningController.targetPackage.value ?: return
        val root = rootInActiveWindow ?: return
        val actual = root.packageName?.toString()
        if (actual != null && actual != pkg) return
        val nodes = NodeCollector.collect(root)
        if (nodes.isNotEmpty()) learningCache.put(pkg, nodes)
    }

    private fun ensureLearningCache(pkg: String): List<NodeSnapshot>? {
        learningCache.get(pkg)?.let { return it }
        refreshLearningCache()
        return learningCache.get(pkg)
    }

    private fun addLog(
        rule: Rule?,
        snapshot: NodeSnapshot?,
        actionType: String?,
        success: Boolean,
        failReason: String?,
        eventType: String,
        score: Int? = null,
        matchedBy: String? = null,
        pkgOverride: String? = null,
    ) {
        AppGraph.logRepository.add(
            LogEntry(
                ts = System.currentTimeMillis(),
                packageName = pkgOverride ?: guard.currentLaunchPkg ?: lastPackage ?: "?",
                activityName = lastActivity,
                eventType = eventType,
                ruleId = rule?.id,
                ruleName = rule?.name,
                score = score,
                matchedBy = matchedBy,
                nodeText = snapshot?.text,
                nodeDesc = snapshot?.contentDescription,
                nodeViewId = snapshot?.viewIdResourceName,
                bounds = snapshot?.bounds,
                actionType = actionType,
                success = success,
                failReason = failReason,
            )
        )
    }

    /** 阶段 2 保留：立即抓取当前窗口节点树（供日志页「立即抓取」按钮调用）。 */
    fun dumpCurrentWindow() {
        val root = rootInActiveWindow
        if (root == null) {
            AppGraph.logRepository.add(
                LogEntry(
                    ts = System.currentTimeMillis(),
                    packageName = lastPackage ?: "?",
                    activityName = lastActivity,
                    eventType = "DEBUG_DUMP",
                    success = false,
                    failReason = "rootInActiveWindow 为空",
                )
            )
            return
        }
        val pkg = root.packageName?.toString() ?: lastPackage ?: "?"
        val snapshots = NodeCollector.collect(root)
        if (snapshots.isNotEmpty()) {
            ruleCache.put(pkg, snapshots)
            // 学习会话进行中抓的树，同时作为学习回溯依据
            if (AppGraph.learningController.targetPackage.value == pkg) {
                learningCache.put(pkg, snapshots)
            }
        }
        AppGraph.logRepository.add(
            LogEntry(
                ts = System.currentTimeMillis(),
                packageName = pkg,
                activityName = lastActivity,
                eventType = "DEBUG_DUMP",
                nodeText = "窗口快照：${snapshots.size} 个节点",
                success = snapshots.isNotEmpty(),
                failReason = if (snapshots.isEmpty()) "未采集到节点" else null,
                nodes = snapshots,
            )
        )
        Logger.i(TAG, "dump: pkg=$pkg nodes=${snapshots.size}")
    }

    /**
     * 学习页「试跑规则」：用最近一次缓存的节点树在当前进程内评估规则命中情况，
     * 让用户在保存前就知道规则能不能用（v0.2.0 新增）。
     */
    fun evaluateRule(rule: Rule): RuleEngineTrial {
        val (w, h) = ScreenUtils.screenSize(this)
        val targetPkg = rule.packageNames.firstOrNull()
        val nodes = learningCache.get(targetPkg) ?: ruleCache.get(targetPkg)
        if (nodes.isNullOrEmpty()) {
            return RuleEngineTrial(
                available = false,
                nodeCount = 0,
                hitScore = null,
                hitText = null,
                hitBounds = null,
                message = "还没有可用的窗口快照：请到「日志」页点「立即抓取」，或重新学习一次",
            )
        }
        val match = com.example.skipstart.engine.RuleMatcher.match(rule, nodes, w, h)
        return if (match == null) {
            RuleEngineTrial(
                available = true,
                nodeCount = nodes.size,
                hitScore = null,
                hitText = null,
                hitBounds = null,
                message = "在最近一次窗口快照（${nodes.size} 个节点）中没有找到匹配节点。" +
                    "这通常说明开屏已经结束——保存后在真实开屏时验证即可。",
            )
        } else {
            RuleEngineTrial(
                available = true,
                nodeCount = nodes.size,
                hitScore = match.score,
                hitText = match.snapshot.text ?: match.snapshot.contentDescription,
                hitBounds = match.snapshot.bounds,
                message = "命中成功：得分 ${match.score}，按钮「" +
                    "${match.snapshot.text ?: match.snapshot.contentDescription ?: "无文字"}」",
            )
        }
    }

    /** 试跑结果（纯数据，直接给 UI 展示）。 */
    data class RuleEngineTrial(
        val available: Boolean,
        val nodeCount: Int,
        val hitScore: Int?,
        val hitText: String?,
        val hitBounds: String?,
        val message: String,
    )

    /** 延迟抓取：等窗口内容稳定后再取树。 */
    private fun scheduleDump() {
        mainHandler.removeCallbacks(dumpRunnable)
        mainHandler.postDelayed(dumpRunnable, DUMP_DELAY_MS)
    }

    private val dumpRunnable = Runnable { dumpCurrentWindow() }

    override fun onInterrupt() {
        Logger.w(TAG, "onInterrupt")
    }

    companion object {
        private const val TAG = "SkipAccessibilityService"
        private const val SERVICE_VERSION = "0.3.0"
        private const val DUMP_DELAY_MS = 200L
        private const val ACTIVATION_PROBE_MS = 600L
        private const val SCAN_THROTTLE_MS = 300L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val PARENT_SCAN_LIMIT = 6
        private const val CHILD_SCAN_DEPTH = 3
        private const val MAX_CHILD_SCAN = 12
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val DEFAULT_FALLBACK_X = 0.92f
        private const val DEFAULT_FALLBACK_Y = 0.06f
        private const val REASON_WINDOW_EXPIRED = "超过冷启动窗口"

        /** 同进程实例句柄，供 UI 调用 dumpCurrentWindow / evaluateRule。 */
        @Volatile
        var instance: SkipAccessibilityService? = null
            private set
    }
}
