package com.example.skipstart.capture

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点采集器（说明书第 6 章 3 节）：
 * - 广度优先遍历窗口节点树；
 * - 限制：最大深度 30、最大节点数 2000、超时 300ms；
 * - 输出 NodeSnapshot 纯数据快照，遍历完成后统一回收所有取得的系统节点。
 */
object NodeCollector {

    private const val MAX_DEPTH = 30
    private const val MAX_NODES = 2000
    private const val TIMEOUT_MS = 300L
    private const val TEXT_LIMIT = 200

    fun collect(root: AccessibilityNodeInfo?): List<NodeSnapshot> {
        if (root == null) return emptyList()

        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        val result = ArrayList<NodeSnapshot>(64)
        val queue = ArrayDeque<QueuedNode>()
        val owned = ArrayList<AccessibilityNodeInfo>() // 统一回收清单
        owned += root
        queue += QueuedNode(root, depth = 0, index = 0, parentIndex = -1)

        while (queue.isNotEmpty() && result.size + queue.size <= MAX_NODES) {
            if (SystemClock.uptimeMillis() > deadline) break

            val current = queue.removeFirst()
            val node = current.node
            val rect = Rect()
            node.getBoundsInScreen(rect)

            result += NodeSnapshot(
                text = node.text?.toString()?.take(TEXT_LIMIT),
                contentDescription = node.contentDescription?.toString()?.take(TEXT_LIMIT),
                viewIdResourceName = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                clickable = node.isClickable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                depth = current.depth,
                index = current.index,
                parentIndex = current.parentIndex,
            )

            if (current.depth >= MAX_DEPTH) continue

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (result.size + queue.size >= MAX_NODES) break
                val child = node.getChild(i) ?: continue
                owned += child
                queue += QueuedNode(child, current.depth + 1, i, current.index)
            }
        }

        owned.forEach { it.recycle() }
        return result
    }

    private data class QueuedNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val index: Int,
        val parentIndex: Int,
    )
}
