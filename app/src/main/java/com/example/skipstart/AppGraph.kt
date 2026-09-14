package com.example.skipstart

import android.content.Context
import android.content.SharedPreferences
import com.example.skipstart.data.LogRepository
import com.example.skipstart.data.RuleRepository
import com.example.skipstart.data.SettingsStore
import com.example.skipstart.engine.RuleEngine
import com.example.skipstart.service.LearningController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级依赖图：UI 与无障碍服务同进程，共享同一份仓库。
 * 阶段 2：LogRepository + 节点调试开关；
 * 阶段 3：RuleRepository + RuleEngine（内置高德规则，自动点击链路）；
 * 阶段 4：SettingsStore（总开关/全局硬上限）；
 * 阶段 5：规则持久化与导入导出在此扩展。
 */
object AppGraph {

    lateinit var logRepository: LogRepository
        private set

    lateinit var ruleRepository: RuleRepository
        private set

    lateinit var ruleEngine: RuleEngine
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var learningController: LearningController
        private set

    private lateinit var prefs: SharedPreferences
    private val _debugAutoDump = MutableStateFlow(false)

    /** 调试模式：每次窗口切换自动抓取节点树（阶段 2）。 */
    val debugAutoDump: StateFlow<Boolean> = _debugAutoDump.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        logRepository = LogRepository(appContext)
        ruleRepository = RuleRepository(appContext)
        ruleEngine = RuleEngine(ruleRepository)
        settingsStore = SettingsStore(appContext)
        learningController = LearningController()
        _debugAutoDump.value = prefs.getBoolean(KEY_DEBUG_AUTO_DUMP, false)
    }

    fun setDebugAutoDump(enabled: Boolean) {
        _debugAutoDump.value = enabled
        prefs.edit().putBoolean(KEY_DEBUG_AUTO_DUMP, enabled).apply()
    }

    private const val PREFS_NAME = "skipstart_settings"
    private const val KEY_DEBUG_AUTO_DUMP = "debug_auto_dump"
}
