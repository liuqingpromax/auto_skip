package com.example.skipstart.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.example.skipstart.AppGraph
import com.example.skipstart.capture.NodeCollector
import com.example.skipstart.capture.NodeSnapshot
import com.example.skipstart.data.LogEntry
import com.example.skipstart.engine.Rule
import com.example.skipstart.util.Logger

/**
 * 开屏广告自动跳过 · 无障碍服务。
 *
 * 阶段路线（对应说明书第 13 章）：
 * - 阶段 1：系统声明与事件接通；
 * - 阶段 2：节点调试工具；
 * - 阶段 3：内置高德规则自动点击链路；
 * - 阶段 4（当前）：防误触收口——AntiTouchGuard 集中硬性校验（总开关/窗口/最多一次/冷却）。
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(dumpRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (AppGraph.ruleRepository.isTargetPackage(pkg)) {
                    tryAutoSkip()
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleLearningClick(event)
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

    /** 阶段 6：学习模式捕获手动点击（说明书第 10 章，仅记录、不自动点击）。 */
    private fun handleLearningClick(event: AccessibilityEvent) {
        if (!AppGraph.learningController.enabled.value) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != AppGraph.learningController.targetPackage.value) return
        // 最近 3 秒窗口：过期的点击事件不采信
        if (SystemClock.uptimeMillis() - event.eventTime > LEARNING_CAPTURE_WINDOW_MS) return
        val node = event.source ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val sample = LearnedSample(
            packageName = pkg,
            activityName = event.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            className = node.className?.toString(),
            bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
            clickable = node.isClickable,
            capturedAt = System.currentTimeMillis(),
        )
        node.recycle()
        AppGraph.learningController.submit(sample)
        Logger.i(
            TAG,
            "学习模式捕获点击：pkg=$pkg text=${sample.text} viewId=${sample.viewIdResourceName}"
        )
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
    ) {
        AppGraph.logRepository.add(
            LogEntry(
                ts = System.currentTimeMillis(),
                packageName = guard.currentLaunchPkg ?: lastPackage ?: "?",
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
        private const val DUMP_DELAY_MS = 200L
        private const val SCAN_THROTTLE_MS = 300L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val LEARNING_CAPTURE_WINDOW_MS = 3_000L
        private const val REASON_WINDOW_EXPIRED = "超过冷启动窗口"

        /** 同进程实例句柄，供 UI 调用 dumpCurrentWindow。 */
        @Volatile
        var instance: SkipAccessibilityService? = null
            private set
    }
}
