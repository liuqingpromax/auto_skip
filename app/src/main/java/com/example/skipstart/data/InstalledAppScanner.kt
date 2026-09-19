package com.example.skipstart.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** 已安装应用条目（学习页选择目标 App 用）。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val systemApp: Boolean,
) {
    val searchKey: String get() = "$label $packageName".lowercase()
}

/**
 * 已安装应用扫描（v0.2.0 新增）。
 *
 * 目的是让用户**不用手敲包名**——这是学习模式最容易劝退的一步。
 * 只读取本机应用列表，不联网、不上传；调用方需在后台线程执行（列表可能上千项）。
 */
object InstalledAppScanner {

    fun load(context: Context, includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val apps = ArrayList<InstalledApp>()
        val self = context.packageName

        val infos: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        for (info in infos) {
            val pkg = info.packageName ?: continue
            if (pkg == self) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            apps += InstalledApp(packageName = pkg, label = label, systemApp = isSystem)
        }
        // 中文标签按拼音以外的简单规则排序不现实，这里按「标签首字符码点 + 包名」稳定排序，
        // 并把系统应用排在后面，保证列表稳定、可预期。
        return apps.sortedWith(
            compareBy<InstalledApp> { it.systemApp }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )
    }

    /** 取单个应用的显示名（学习进行中展示「正在学习：高德地图」）。 */
    fun labelOf(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrNull()
}
