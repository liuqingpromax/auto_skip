package com.example.skipstart.data

import android.content.Context
import android.content.SharedPreferences
import com.example.skipstart.engine.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则仓库（阶段 3 + 阶段 5）：
 * - 内置规则（启动时重新种子，保证关键规则不丢失）；
 * - 用户规则与启用/禁用状态本地持久化（SharedPreferences JSON，仅本机）；
 * - JSON 导入导出（阶段 5）。
 */
class RuleRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow(load())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    fun isTargetPackage(packageName: String): Boolean =
        _rules.value.any { it.enabled && packageName in it.packageNames }

    fun enabledRulesFor(packageName: String): List<Rule> =
        _rules.value.filter { it.enabled && packageName in it.packageNames }

    fun isBuiltin(id: String): Boolean = BuiltinRules.all().any { it.id == id }

    fun setEnabled(id: String, enabled: Boolean) {
        _rules.update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        persist()
    }

    fun addOrUpdate(rule: Rule) {
        _rules.update { list -> list.filterNot { it.id == rule.id } + rule }
        persist()
    }

    /** 删除规则；内置规则受保护不可删除，返回是否成功。 */
    fun remove(id: String): Boolean {
        if (isBuiltin(id)) return false
        _rules.update { list -> list.filterNot { it.id == id } }
        persist()
        return true
    }

    /** 导出全部规则（内置 + 用户）为 JSON 数组（美化格式）。 */
    fun exportJson(): String {
        val array = JSONArray()
        _rules.value.forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    /**
     * 导入 JSON：支持单条规则对象或规则数组；按 id 新增/覆盖。
     * 返回导入条数；解析或校验失败返回 Result.failure（含原因）。
     */
    fun importJson(json: String): Result<Int> = runCatching {
        val trimmed = json.trim()
        val list: List<Rule> = when {
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { i ->
                    arr.optJSONObject(i) ?: error("第 ${i + 1} 条不是 JSON 对象")
                }.map { parseValidated(it) }
            }
            trimmed.startsWith("{") -> listOf(parseValidated(JSONObject(trimmed)))
            else -> error("不是合法的 JSON")
        }
        list.forEach { addOrUpdate(it) }
        list.size
    }

    private fun parseValidated(o: JSONObject): Rule {
        val rule = Rule.fromJson(o)
        require(rule.id.isNotBlank()) { "缺少 id" }
        require(rule.name.isNotBlank()) { "缺少 name" }
        require(rule.packageNames.isNotEmpty()) { "packageNames 不能为空" }
        require(rule.conditions.isNotEmpty()) { "conditions 不能为空" }
        return rule
    }

    private fun load(): List<Rule> {
        val raw = prefs.getString(KEY_JSON, null) ?: return BuiltinRules.all()
        val persisted = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Rule.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
        // 内置规则按版本号升级（v0.3.0）：
        // - 缺失      → 补回；
        // - 版本偏低  → 用新内容替换（保留用户的启用/禁用选择）；
        // - 已是最新  → 原样保留（用户可能用规则编辑器改过参数，不能覆盖）。
        //
        // 旧版只做「缺失才补」，导致老用户升级后仍跑着写死 top_right 的旧规则，
        // 内置规则的修复永远送不到用户手上——这正是「改了匹配逻辑却不见效」的原因之一。
        var upgraded = false
        val result = persisted.toMutableList()
        BuiltinRules.all().forEach { builtin ->
            val index = result.indexOfFirst { it.id == builtin.id }
            when {
                index < 0 -> {
                    result += builtin
                    upgraded = true
                }
                result[index].version < builtin.version -> {
                    result[index] = builtin.copy(enabled = result[index].enabled)
                    upgraded = true
                }
                else -> Unit
            }
        }
        if (upgraded) {
            persistList(result)
        }
        return result
    }

    /** 直接落盘给定列表（供 load 阶段的内置规则升级使用，此时 _rules 尚未就绪）。 */
    private fun persistList(list: List<Rule>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_JSON, array.toString()).apply()
    }

    private fun persist() {
        val array = JSONArray()
        _rules.value.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_JSON, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "skipstart_rules"
        private const val KEY_JSON = "rules_json"
    }
}
