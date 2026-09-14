package com.example.skipstart.data

import android.content.Context
import android.content.SharedPreferences
import com.example.skipstart.capture.NodeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一条日志（字段与说明书第 6 章 7 节对齐）。
 * nodes 仅用于节点调试快照（阶段 2），只保留在内存、不落盘、不上传。
 */
data class LogEntry(
    val id: Long,
    val ts: Long,
    val packageName: String,
    val activityName: String?,
    val eventType: String?,
    val ruleId: String?,
    val ruleName: String?,
    val score: Int? = null,        // 命中得分（阶段9）
    val matchedBy: String? = null, // 命中的条件明细（阶段9）
    val nodeText: String?,
    val nodeDesc: String?,
    val nodeViewId: String?,
    val bounds: String?,
    val actionType: String?,
    val success: Boolean,
    val failReason: String?,
    val nodes: List<NodeSnapshot>? = null,
)

/**
 * 日志仓库：
 * - 内存 StateFlow 实时驱动 UI（最新在前，环形上限 500 条）；
 * - 异步落盘到 SharedPreferences JSON（仅本机）；
 * - 调试快照（nodes != null）仅内存保留，重启即失效。
 */
class LogRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val persistExecutor = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var persistScheduled = false

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var nextId = 1L

    init {
        load()
    }

    fun add(entry: LogEntry) {
        val stamped = entry.copy(id = nextId++)
        _logs.update { current -> (listOf(stamped) + current).take(MAX_ENTRIES) }
        if (entry.nodes == null) {
            persistAsync()
        }
    }

    fun clear() {
        _logs.value = emptyList()
        persistAsync(immediate = true)
    }

    /** 今日成功点击次数（首页统计用，阶段 3 起产生数据）。 */
    fun todayClickCount(): Int {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return _logs.value.count { entry ->
            entry.success &&
                entry.ts >= startOfToday &&
                entry.actionType in CLICK_ACTIONS
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_JSON, null) ?: return
        val list = ArrayList<LogEntry>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                runCatching { list += fromJson(array.getJSONObject(i)) }
            }
            nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            _logs.value = list.take(MAX_ENTRIES)
        } catch (_: Exception) {
            // 本地数据损坏时直接丢弃，不影响启动
        }
    }

    /** 落盘去抖（阶段 7 耗电优化）：事件洪峰时合并写入，减少 IO 次数。 */
    private fun persistAsync(immediate: Boolean = false) {
        synchronized(this) {
            if (persistScheduled && !immediate) return
            persistScheduled = true
        }
        persistExecutor.schedule(
            {
                persistScheduled = false
                persistNow()
            },
            if (immediate) 0L else PERSIST_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun persistNow() {
        val snapshot = _logs.value
            .filter { it.nodes == null }
            .take(MAX_ENTRIES)
        val array = JSONArray()
        snapshot.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_JSON, array.toString()).apply()
    }

    private fun toJson(e: LogEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("ts", e.ts)
        put("pkg", e.packageName)
        put("act", e.activityName ?: "")
        put("evt", e.eventType ?: "")
        put("ruleId", e.ruleId ?: "")
        put("ruleName", e.ruleName ?: "")
        put("score", e.score ?: 0)
        put("matched", e.matchedBy ?: "")
        put("text", e.nodeText ?: "")
        put("desc", e.nodeDesc ?: "")
        put("viewId", e.nodeViewId ?: "")
        put("bounds", e.bounds ?: "")
        put("action", e.actionType ?: "")
        put("ok", e.success)
        put("reason", e.failReason ?: "")
    }

    private fun fromJson(o: JSONObject): LogEntry {
        fun s(key: String): String? = o.optString(key).takeIf { it.isNotEmpty() }
        return LogEntry(
            id = o.optLong("id"),
            ts = o.optLong("ts"),
            packageName = o.optString("pkg").ifEmpty { "?" },
            activityName = s("act"),
            eventType = s("evt"),
            ruleId = s("ruleId"),
            ruleName = s("ruleName"),
            score = o.optInt("score").takeIf { it > 0 },
            matchedBy = s("matched"),
            nodeText = s("text"),
            nodeDesc = s("desc"),
            nodeViewId = s("viewId"),
            bounds = s("bounds"),
            actionType = s("action"),
            success = o.optBoolean("ok"),
            failReason = s("reason"),
        )
    }

    companion object {
        private const val PREFS_NAME = "skipstart_logs"
        private const val KEY_JSON = "entries"
        private const val MAX_ENTRIES = 500
        private const val PERSIST_DEBOUNCE_MS = 500L
        private val CLICK_ACTIONS =
            setOf("click_node", "click_parent", "click_center", "click_ratio")
    }
}
