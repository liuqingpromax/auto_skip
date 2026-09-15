package com.example.skipstart.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skipstart.capture.NodeSnapshot

/**
 * 动作执行器（说明书第 6 章 5 节），动作优先级：
 * 1) 点击命中节点本身；
 * 2) 节点不可点击，向上找可点击父节点；
 * 3) 仍不可点击，按节点 bounds 中心手势点击；
 * 4) 最后 fallback：按屏幕比例点击（默认 0.92w, 0.08h）。
 *
 * 执行前基于快照在“当前”节点树中重定位存活节点（二次校验，说明书第 9 章建议项），
 * 避免点击已失效节点。
 */
object ActionExecutor {

    data class ClickOutcome(
        val actionType: String?,
        val success: Boolean,
        val failReason: String?,
    )

    private const val MAX_DEPTH = 30
    private const val MAX_NODES = 2000
    private const val PARENT_LIMIT = 10
    private const val TAP_DURATION_MS = 50L

    fun execute(
        service: AccessibilityService,
        snapshot: NodeSnapshot,
        action: RuleAction,
        screenW: Int,
        screenH: Int,
    ): ClickOutcome {
        val root = service.rootInActiveWindow
            ?: return ClickOutcome(null, false, "rootInActiveWindow 为空")

        val live = findLiveNode(root, snapshot)
        if (live == null) {
            root.recycle()
            return ClickOutcome(null, false, "二次校验失败：节点已消失")
        }

        // 1) 节点本身可点击
        if (live.isClickable) {
            val ok = live.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycle(live, root)
            return ClickOutcome("click_node", ok, if (ok) null else "ACTION_CLICK 返回失败")
        }

        // 2) 向上找可点击父节点
        val chain = ArrayList<AccessibilityNodeInfo>()
        chain += live
        var parent = live.parent
        while (parent != null && chain.none { it === parent } && chain.size < PARENT_LIMIT) {
            chain += parent
            parent = parent.parent
        }
        for (node in chain) {
            if (node.isClickable) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycleAll(chain, root)
                return ClickOutcome("click_parent", ok, if (ok) null else "ACTION_CLICK 返回失败")
            }
        }
        recycleAll(chain, root)

        // 3) bounds 中心手势点击
        val b = snapshot.bounds?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 }
        if (b != null && canGesture(service)) {
            val cx = (b[0] + b[2]) / 2f
            val cy = (b[1] + b[3]) / 2f
            val ok = tap(service, cx, cy)
            return ClickOutcome("click_center", ok, if (ok) null else "手势派发失败")
        }

        // 4) fallback：屏幕比例点击
        val fb = action.fallback
        if (fb != null && canGesture(service)) {
            val ok = tap(service, fb.x * screenW, fb.y * screenH)
            return ClickOutcome("click_ratio", ok, if (ok) null else "手势派发失败")
        }

        return ClickOutcome(null, false, "无可点击节点且无可用兜底动作")
    }

    /**
     * 在“当前”节点树中重定位快照对应的存活节点。
     * 身份判定：bounds 完全一致 且（viewId 一致 或 text 前缀一致 或 className 一致）。
     * 除根节点与命中节点外统一回收。
     */
    private fun findLiveNode(
        root: AccessibilityNodeInfo,
        snapshot: NodeSnapshot,
    ): AccessibilityNodeInfo? {
        val targetBounds = snapshot.bounds?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 }?.toIntArray() ?: return null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue += root to 0
        val fetched = ArrayList<AccessibilityNodeInfo>()
        var count = 0

        while (queue.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            count++
            if (matches(node, snapshot, targetBounds)) {
                fetched.forEach { if (it !== node) it.recycle() }
                return node
            }
            if (depth >= MAX_DEPTH) continue
            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (count + queue.size >= MAX_NODES) break
                node.getChild(i)?.let { child ->
                    fetched += child
                    queue += child to depth + 1
                }
            }
        }
        fetched.forEach { it.recycle() }
        return null
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        s: NodeSnapshot,
        targetBounds: IntArray,
    ): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.left != targetBounds[0] || rect.top != targetBounds[1] ||
            rect.right != targetBounds[2] || rect.bottom != targetBounds[3]
        ) {
            return false
        }
        val viewIdMatch = s.viewIdResourceName != null &&
            s.viewIdResourceName == node.viewIdResourceName
        val textMatch = !s.text.isNullOrBlank() &&
            node.text?.toString()?.startsWith(s.text) == true
        if (viewIdMatch || textMatch) return true
        val classMatch = s.className != null && s.className == node.className?.toString()
        return classMatch
    }

    private fun canGesture(service: AccessibilityService): Boolean =
        (service.serviceInfo?.capabilities ?: 0) and
            AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0

    private fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun recycle(live: AccessibilityNodeInfo, root: AccessibilityNodeInfo) {
        if (live !== root) live.recycle()
        root.recycle()
    }

    private fun recycleAll(chain: List<AccessibilityNodeInfo>, root: AccessibilityNodeInfo) {
        chain.forEach { it.recycle() }
        if (chain.none { it === root }) root.recycle()
    }
}
