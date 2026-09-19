package com.example.skipstart.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.example.skipstart.service.SkipAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务状态查询与设置页跳转（v0.2.0 机型适配版）。
 *
 * 旧版只做 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的**严格字符串相等**判断，
 * 在部分机型上必然误判为「未开启」，原因包括：
 * 1. 华为 / 荣耀 / 三星等返回短名 `包名/.service.XxxService`，而本应用拼的是全名
 *    `包名/com.example...XxxService`，字符串不相等；
 * 2. MIUI / ColorOS / OriginOS 在服务被安全中心拦截时，只写入 `包名` 而不是组件全名；
 * 3. 部分 ROM 写入 `包名/服务类名`（真实类名）而非 `.` 缩写；
 * 4. 个别 ROM 的 Secure 表读取受限，字符串方案整体失效。
 *
 * 因此改为三级判定：
 * - 一级：`AccessibilityManager.getEnabledAccessibilityServiceList()`（系统官方 API，优先）；
 * - 二级：宽容解析 Secure 字符串（全名 / 短名 / 包名 / 类名四种写法）；
 * - 三级：服务运行期真实连接状态 `isServiceConnected`（最权威，UI 以它兜底）。
 */
object AccessibilityUtils {

    /**
     * 服务真实运行状态：由 [SkipAccessibilityService] 在 onServiceConnected / onUnbind
     * 时直接写入，不依赖 Secure 表，任何机型都准确。
     */
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    internal fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
    }

    /** 无障碍开关总闸（系统级「无障碍」是否启用，某些 ROM 关闭后所有服务都失效）。 */
    fun isAccessibilityEnabled(context: Context): Boolean =
        runCatching { manager(context).isEnabled }.getOrDefault(false)

    /** 本应用的无障碍服务是否已在系统设置中开启（多格式判定）。 */
    fun isServiceEnabled(context: Context): Boolean {
        if (isEnabledViaManager(context)) return true
        if (isEnabledViaSecureSettings(context)) return true
        // 兜底：Secure 表读不到但服务真的连上了，同样算已开启
        return _serviceConnected.value
    }

    /** 服务已开启且**真的绑定成功**（真正能工作的状态）。 */
    fun isServiceReady(context: Context): Boolean =
        _serviceConnected.value || isEnabledViaManager(context)

    /**
     * 一级判定：系统官方 API。返回的是系统解析后的 ServiceInfo，不受字符串格式影响。
     */
    private fun isEnabledViaManager(context: Context): Boolean {
        val infos = runCatching {
            manager(context).getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
        }.getOrNull() ?: return false
        val self = context.packageName
        return infos.any { info ->
            val flat = info.id ?: info.resolveInfo?.serviceInfo?.let {
                ComponentName(it.packageName, it.name).flattenToString()
            }
            flat != null && packageOf(flat) == self
        }
    }

    /**
     * 二级判定：宽容解析 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。
     * 兼容 `包名/全类名`、`包名/.短类名`、`包名/类名`、仅 `包名` 四种写法。
     */
    private fun isEnabledViaSecureSettings(context: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        if (raw.isNullOrBlank()) return false

        val self = context.packageName
        val fullClass = SkipAccessibilityService::class.java.name          // com.example.skipstart.service.Xxx
        val shortClass = fullClass.substringAfterLast('.')                 // Xxx

        // 分隔符：标准 ROM 用 ':'，少数 ROM 用 ',' 或 ';'
        val entries = raw.split(':', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        for (entry in entries) {
            if (entryMatches(entry, self, fullClass, shortClass)) return true
        }
        // 极端情况下整串就是包名
        return raw.trim() == self
    }

    private fun entryMatches(
        entry: String,
        pkg: String,
        fullClass: String,
        shortClass: String,
    ): Boolean {
        val normalized = entry.replace('/', '.').trim()
        // 1) 原样 / 归一化后完全相等（覆盖 .短名 与全名两种拼法）
        if (normalized == "$pkg.$fullClass") return true
        if (normalized == "$pkg.$shortClass") return true
        // 2) 仅包名（部分 ROM 被安全中心截断后只写包名）
        if (normalized == pkg) return true
        // 3) 解析出组件做精确比对
        val component = ComponentName.unflattenFromString(entry)
        if (component != null && component.packageName == pkg) {
            val cls = component.className.removePrefix(".")
            if (cls == fullClass || cls == shortClass || cls == SkipAccessibilityService::class.java.simpleName) {
                return true
            }
        }
        // 4) 冒号分隔异常时退化为「包名 + 类名」双包含
        return normalized.contains(pkg) &&
            (normalized.endsWith(fullClass) || normalized.endsWith(shortClass))
    }

    private fun packageOf(flattened: String): String = flattened.substringBefore('/')

    /**
     * 跳转系统无障碍设置页。
     *
     * 跳转策略（v0.2.0 修正）：只用**系统保证存在**的入口，逐级降级，
     * 且每一步都先 `resolveActivity` 校验可解析再启动，避免个别 ROM 上直接抛异常：
     * 1. `ACTION_ACCESSIBILITY_SETTINGS`（AOSP 标准无障碍列表页）；
     * 2. `ACTION_SETTINGS`（系统设置首页，用户可自行进入「无障碍」）。
     *
     * 说明：`android.settings.ACCESSIBILITY_DETAILS_SETTINGS` 是厂商/隐藏 action，
     * 语义随 ROM 而异（部分 ROM 下 EXTRA_COMPONENT_NAME 会被忽略甚至打开错误的详情页），
     * 因此不再作为首选，改由 UI 明确给出「分品牌路径 + 本应用服务组件名」让用户自助定位。
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 不能解析就换下一个，不要靠捕获异常来判断
            val resolvable = intent.resolveActivity(context.packageManager) != null
            if (!resolvable) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 打开本应用的系统详情页（权限 / 电池优化等兜底入口）。 */
    fun openAppDetailsSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /** 本应用在系统无障碍设置里显示的完整服务名，用于文案展示。 */
    fun serviceComponentName(context: Context): String =
        component(context).flattenToString()

    /** 厂商/ROM 品牌，用于给出对应的设置路径提示。 */
    fun romBrand(): String = runCatching {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        (brand.ifBlank { manufacturer }).replaceFirstChar { it.uppercase() }
    }.getOrDefault("Android")

    /**
     * 按品牌给出设置入口路径（说明书第 3 章 / FAQ Q6 使用）。
     * 只给「最省事的说法」，把通用路径兜在前面。
     */
    fun settingsPathHint(): String {
        val brand = Build.MANUFACTURER.orEmpty().lowercase()
        val brandName = Build.BRAND.orEmpty().lowercase()
        return when {
            brand.contains("xiaomi") || brandName.contains("redmi") || brandName.contains("poco") ->
                "小米/红米：设置 → 更多设置 → 无障碍 → 已下载的应用 →「开屏跳过服务」"
            brand.contains("huawei") || brandName.contains("honor") ->
                "华为/荣耀：设置 → 辅助功能 → 无障碍 → 已安装的服务 →「开屏跳过服务」"
            brand.contains("oppo") || brandName.contains("realme") || brandName.contains("oneplus") ->
                "OPPO/一加/realme：设置 → 系统设置 → 无障碍 → 已安装的服务 →「开屏跳过服务」"
            brand.contains("vivo") || brandName.contains("iqoo") ->
                "vivo/iQOO：设置 → 快捷与辅助 → 无障碍 → 已安装的服务 →「开屏跳过服务」"
            brand.contains("meizu") ->
                "魅族：设置 → 辅助功能 → 无障碍 →「开屏跳过服务」"
            brand.contains("samsung") ->
                "三星：设置 → 辅助功能 → 已安装的应用 →「开屏跳过服务」"
            else ->
                "通用路径：设置 → 无障碍 → 已安装的应用 / 已下载的服务 →「开屏跳过服务」"
        }
    }

    /** 部分 ROM 把服务列表藏在「无障碍」二级页，这里说明找不到时怎么排查。 */
    fun troubleshootingTips(): List<String> = listOf(
        "找不到本应用：在无障碍页把「已下载的应用 / 已安装的服务」展开，或搜索“开屏跳过服务”。",
        "开关打开又自动关闭：系统安全中心拦截了辅助功能，请在「手机管家 / 安全中心 → 权限管理」中允许本应用使用无障碍。",
        "开启后仍不生效：把本应用加入电池优化白名单（设置 → 电池 → 应用启动管理 → 允许后台运行）。",
        "升级/重装后失效：系统会重置无障碍授权，重新打开一次开关即可。",
    )

    private fun component(context: Context): ComponentName =
        ComponentName(context, SkipAccessibilityService::class.java)

    private fun manager(context: Context): AccessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    /** 供调试：列出系统当前认为已启用的无障碍服务（日志页 / 设置页展示）。 */
    fun enabledServiceIds(context: Context): List<String> = runCatching {
        manager(context).getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.id }
    }.getOrDefault(emptyList())

    /** 应用是否已安装（学习页包名校验用）。 */
    fun isPackageInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
