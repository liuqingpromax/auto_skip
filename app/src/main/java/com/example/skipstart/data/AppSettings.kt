package com.example.skipstart.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 应用设置（阶段 4）：总开关 + 防误触全局硬性上限。 */
data class AppSettings(
    val masterEnabled: Boolean = true,     // 总开关：关闭后不执行任何自动点击
    val launchWindowMs: Long = 8_000,      // 冷启动窗口（全局硬上限）
    val cooldownMs: Long = 2_000,          // 点击冷却（全局硬上限）
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("masterEnabled", masterEnabled)
        put("launchWindowMs", launchWindowMs)
        put("cooldownMs", cooldownMs)
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings = AppSettings(
            masterEnabled = o.optBoolean("masterEnabled", true),
            launchWindowMs = o.optLong("launchWindowMs", 8_000),
            cooldownMs = o.optLong("cooldownMs", 2_000),
        )
    }
}

/** 设置存储：SharedPreferences 存 JSON（仅本机），StateFlow 驱动 UI 与服务。 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit().putString(KEY_JSON, updated.toJson().toString()).apply()
    }

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY_JSON, null) ?: return AppSettings()
        return try {
            AppSettings.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            AppSettings()
        }
    }

    companion object {
        private const val PREFS_NAME = "skipstart_settings"
        private const val KEY_JSON = "app_settings_json"
    }
}
