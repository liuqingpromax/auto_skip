package com.example.skipstart.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.example.skipstart.service.SkipAccessibilityService

/**
 * 无障碍服务状态查询与设置页跳转。
 * 阶段 1 供首页使用；后续阶段复用同一判断口径。
 */
object AccessibilityUtils {

    /** 本应用的无障碍服务是否已在系统设置中开启。 */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SkipAccessibilityService::class.java)
            .flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            // 系统返回格式为 "包名/服务类名"，用冒号分隔多条
            val entry = splitter.next()
            if (entry == expected) return true
        }
        return false
    }

    /** 跳转系统无障碍设置页。 */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
