package com.example.skipstart.capture

/**
 * 节点快照（说明书第 6 章 3 节字段对齐）。
 * 从 AccessibilityNodeInfo 拷贝出的纯数据，避免长期持有系统节点对象。
 */
data class NodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val bounds: String?,       // "l,t,r,b"，屏幕坐标
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val depth: Int,
    val index: Int,
    val parentIndex: Int,
)
