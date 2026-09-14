# SkipStart（开屏广告自动跳过助手）· 架构设计

> 项目代号 SkipStart · Android · Kotlin + Jetpack Compose + AccessibilityService
> 核心原则：本地运行、规则驱动、防误触、合规、不破解目标 App。
> 本文档依据《开屏广告自动跳过助手（Android）——AI 开发说明书》编写，接口与路线与说明书第 5、6、7、13 章对齐。

## 1. 项目文件树

```
auto_skip/
├── settings.gradle.kts                  # 工程设置，rootProject = SkipStart
├── build.gradle.kts                     # 根构建脚本（仅声明插件版本）
├── gradle.properties
├── gradlew.bat                          # Windows 构建入口
├── gradle/
│   ├── libs.versions.toml               # 版本目录（AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM）
│   └── wrapper/gradle-wrapper.properties# Gradle 8.9
├── docs/ARCHITECTURE.md                 # 本文档
├── docs/ACCEPTANCE.md                   # 验收清单/真机测试/合规与已知限制（✔ 阶段7）
└── app/
    ├── build.gradle.kts                 # minSdk 26 / targetSdk 35 / compileSdk 35
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # 无任何网络权限；声明无障碍服务
        ├── res/
        │   ├── drawable/ic_launcher.xml
        │   ├── values/{strings,themes}.xml
        │   └── xml/accessibility_service_config.xml
        └── java/com/example/skipstart/
            ├── MainActivity.kt                  # 唯一 Activity（✔ 阶段1）
            ├── SkipStartApp.kt                  # Application：初始化 AppGraph（✔ 阶段2）
            ├── AppGraph.kt                      # 进程级依赖图：UI 与服务共享仓库（✔ 阶段2）
            ├── service/
            │   ├── SkipAccessibilityService.kt  # 无障碍服务（✔1声明 ✔3核心 ✔4守卫 ✔6学习捕获）
            │   ├── AntiTouchGuard.kt            # 防误触守卫：总开关/窗口/最多一次/冷却（✔ 阶段4）
            │   └── LearningController.kt        # 学习模式：捕获手动点击→候选规则（✔ 阶段6）
            ├── engine/                          # ✔ 阶段3（阶段4 防误触收口）
            │   ├── Rule.kt                      # 规则模型 + JSON 序列化（与说明书七章对齐）
            │   ├── RuleEngine.kt                # 事件→规则→匹配→执行 编排
            │   ├── RuleMatcher.kt               # 条件匹配+评分（阈值60）+敏感词负名单
            │   ├── ActionExecutor.kt            # 节点/父节点/bounds中心/比例兜底 + 二次校验
            │   └── MatchResult.kt               # 命中+执行结果（供日志）
            ├── capture/                         # ✔ 阶段2
            │   ├── NodeCollector.kt             # 节点树遍历（深度≤30/节点≤2000/超时300ms，统一回收）
            │   └── NodeSnapshot.kt              # 节点快照（不长期持有系统节点）
            ├── data/                            # ✔2/3 阶段2-5
            │   ├── RuleRepository.kt            # 内置规则+启用禁用+持久化+导入导出（✔ 阶段3/5）
            │   ├── BuiltinRules.kt              # 内置高德规则 JSON（✔ 阶段3）
            │   ├── RuleTemplates.kt             # 规则模板：通用跳过/倒计时/×关闭（✔ 阶段9/P1）
            │   ├── AppSettings.kt               # 总开关/窗口/冷却 + SettingsStore（✔ 阶段4）
            │   └── LogRepository.kt             # 日志环形存储（上限500条，✔ 阶段2）
            ├── ui/                              # Compose
            │   ├── MainScreen.kt                # 底部导航（首页/日志/规则/学习/设置，✔2/4/5/6）
            │   ├── HomeScreen.kt                # 首页：服务状态/总开关/今日跳过（✔ 阶段1）
            │   ├── RuleListScreen.kt            # 规则列表/启用禁用/删除/导入导出（✔ 阶段5）
            │   ├── RuleEditDialog.kt            # 可视化规则编辑器：表单+JSON 双模式（✔ 阶段8/P1）
            │   ├── LogScreen.kt                 # 日志页：实时/筛选/清空/复制 + 节点调试（✔ 阶段2）
            │   ├── LearningScreen.kt            # 学习模式：捕获预览/候选规则/确认保存（✔ 阶段6）
            │   ├── SettingsScreen.kt            # 设置：窗口/冷却/隐私/许可（✔ 阶段4）
            │   └── theme/Theme.kt               # M3 主题（✔ 阶段1）
            └── util/
                ├── Logger.kt                    # Logcat 统一入口（✔ 阶段1）
                ├── AccessibilityUtils.kt        # 服务状态查询/设置跳转（✔ 阶段1）
                └── ScreenUtils.kt               # 屏幕尺寸/坐标换算（✔ 阶段3）
                                                 # 注：规则 JSON 序列化已并入 engine/Rule.kt，导入导出复用
```

## 2. 模块职责

| 模块 | 职责 | 落地阶段 |
|---|---|---|
| `service` | 系统层：监听 `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`，判断前台包名，触发引擎，写日志 | 1 声明 / 3-4 核心 |
| `engine` | 核心引擎：规则模型、条件匹配与评分、动作执行（点击策略链） | 3 |
| `capture` | 节点采集：DFS/BFS 遍历并转成快照，避免长期持有 `AccessibilityNodeInfo` | 2 |
| `data` | 仓库层：规则（内置+用户）、日志（环形）、设置（总开关等）；MVP 用 SharedPreferences 存 JSON，接口抽象可换 Room/DataStore | 2-5 |
| `ui` | Compose 五页：首页 / 规则 / 日志 / 学习 / 设置 | 1 首页，其余按阶段 |
| `util` | 日志、服务状态、屏幕换算、JSON 工具 | 1 起 |

## 3. 关键接口

```kotlin
// ===== engine/Rule.kt（阶段3；JSON 字段与说明书七章一致）=====
data class RuleCondition(
    val type: String,        // text_regex | desc_regex | view_id | class_name
    val pattern: String,
    val area: String? = null, // top_right / top_left / bottom_right ...
    val score: Int = 0
)
data class RuleFallback(val type: String, val x: Float, val y: Float) // click_xy_ratio

data class Rule(
    val id: String,                       // 如 amap_skip
    val name: String,                     // 如「高德地图开屏跳过」
    val enabled: Boolean = true,
    val packageNames: List<String>,       // 如 ["com.autonavi.minimap"]
    val activityPatterns: List<String> = listOf("*"),
    val launchWindowMs: Long = 8_000,     // 冷启动后多少毫秒内生效
    val maxClicksPerLaunch: Int = 1,      // 每次启动最多点几次
    val cooldownMs: Long = 2_000,         // 两次点击冷却
    val matchMode: String = "any",        // any | all
    val minScore: Int = 60,               // 命中阈值，总分 ≥ 此值才执行（阶段9 规则级可配）
    val conditions: List<RuleCondition>,
    val action: RuleAction
)

// ===== engine/MatchResult.kt（阶段3）=====
data class MatchResult(
    val rule: Rule,
    val snapshot: NodeSnapshot,
    val score: Int,              // 总分，需 ≥ 60 才执行
    val matchedBy: List<String>, // 命中的条件，写入日志
    val actionType: String? = null,  // click_node/click_parent/click_center/click_ratio
    val success: Boolean = false,
    val failReason: String? = null,
)

// ===== capture/NodeSnapshot.kt（阶段2）=====
data class NodeSnapshot(
    val text: String?, val contentDescription: String?,
    val viewIdResourceName: String?, val className: String?,
    val bounds: String?,          // "l,t,r,b"
    val clickable: Boolean, val enabled: Boolean, val visible: Boolean,
    val depth: Int, val index: Int, val parentIndex: Int
)

// ===== capture/NodeCollector.kt（阶段2）=====
object NodeCollector {
    // 深度≤30、节点数≤2000、超时300ms
    fun collect(root: AccessibilityNodeInfo): List<NodeSnapshot>
}

// ===== engine/RuleMatcher.kt（阶段3）=====
object RuleMatcher {
    fun match(rule: Rule, nodes: List<NodeSnapshot>, screenW: Int, screenH: Int): MatchResult?
}

// ===== engine/ActionExecutor.kt（阶段3）=====
object ActionExecutor {
    // 1)点击命中节点本身 2)向上找可点击父节点 3)点击 bounds 中心 4)fallback 屏幕比例(0.92,0.08)
    // 执行前基于快照在当前节点树重定位存活节点（二次校验），避免点击失效节点
    fun execute(
        service: AccessibilityService,
        snapshot: NodeSnapshot,
        action: RuleAction,
        screenW: Int,
        screenH: Int,
    ): ClickOutcome  // (actionType, success, failReason)
}

// ===== engine/RuleEngine.kt（阶段3）=====
class RuleEngine(private val repository: RuleRepository) {
    // 事件 → 规则过滤（包名/Activity 通配）→ 采集 → 匹配 → 执行；无匹配返回 null
    fun handleEvent(
        packageName: String,
        activityName: String?,
        root: AccessibilityNodeInfo?,
        service: AccessibilityService,
    ): MatchResult?
}

// ===== data/RuleRepository.kt（阶段3+5 完成）=====
class RuleRepository(context: Context) {
    val rules: StateFlow<List<Rule>>           // 内置规则（重新种子） + 用户规则（持久化）
    fun isTargetPackage(pkg: String): Boolean
    fun enabledRulesFor(pkg: String): List<Rule>
    fun isBuiltin(id: String): Boolean          // 内置规则不可删除
    fun setEnabled(id: String, enabled: Boolean)
    fun addOrUpdate(rule: Rule)
    fun remove(id: String): Boolean
    fun exportJson(): String                    // 全部规则 → JSON 数组（SAF 导出）
    fun importJson(json: String): Result<Int>   // 单条/数组，按 id 增改（SAF 导入）
}

// ===== data/LogRepository.kt（阶段2）=====
data class LogEntry(
    val id: Long, val ts: Long,
    val packageName: String, val activityName: String?,
    val eventType: String?,
    val ruleId: String?, val ruleName: String?,
    val score: Int?, val matchedBy: String?,  // 命中得分与条件明细（阶段9）
    val nodeText: String?, val nodeDesc: String?, val nodeViewId: String?,
    val bounds: String?,
    val actionType: String?,                 // click_node / click_parent / click_center / click_ratio
    val success: Boolean,
    val failReason: String?
)
interface LogRepository {
    val logs: StateFlow<List<LogEntry>>   // 最新在前，环形上限 500
    fun add(entry: LogEntry)
    fun clear()
    fun todayClickCount(): Int
}

// ===== data/AppSettings.kt（阶段4）=====
data class AppSettings(
    val masterEnabled: Boolean = true,     // 总开关（一键停用自动点击）
    val launchWindowMs: Long = 8_000,      // 冷启动窗口（全局硬上限：取规则/全局较小值）
    val cooldownMs: Long = 2_000,          // 点击冷却（全局硬上限：取规则/全局较大值）
)
class SettingsStore(context: Context) {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

// ===== service/AntiTouchGuard.kt（阶段4 防误触收口）=====
class AntiTouchGuard {
    fun onTargetLaunch(pkg: String, now: Long)
    fun canProceed(rule: Rule, now: Long, settings: AppSettings): Verdict // Allowed / Denied(reason)
    fun recordClick(now: Long)          // 成功：计入本轮次数 + 刷新冷却
    fun recordFailedAttempt(now: Long)  // 失败：刷新冷却，避免立即重试
    fun markWindowExpired()
}

// ===== service/LearningController.kt（阶段6）=====
data class LearnedSample(packageName, activityName, text, contentDescription,
    viewIdResourceName, className, bounds, clickable, capturedAt)
class LearningController {
    val enabled: StateFlow<Boolean>
    val targetPackage: StateFlow<String?>
    val sample: StateFlow<LearnedSample?>
    fun start(packageName: String); fun stop(); fun clearSample()
    fun submit(sample: LearnedSample)       // 服务回调：仅目标包；捕获一次即自动停止
    fun buildCandidateRule(sample: LearnedSample): Rule?  // 自动附加右上角+关键词约束
}
```

## 4. 核心链路

```
监听前台 App → 判断是否目标包名/Activity → 抓取无障碍节点树
→ 识别“跳过/关闭/×/倒计时” → 执行点击 → 冷却防误触
```

## 5. 防误触与安全策略（说明书第 9 章，必须实现）

- 只处理目标包名（规则 `packageNames` 白名单，非目标包直接忽略）；
- 只在冷启动后 8 秒内（`launchWindowMs` 默认 8000）；
- 每次启动最多点一次（`maxClicksPerLaunch` 默认 1）；
- 两次点击冷却 2 秒（`cooldownMs` 默认 2000）；
- 优先右上角：`area = top_right`，判定 `centerX > 0.6w && centerY < 0.25h`，命中 +20 分；
- 文本必须命中“跳过/关闭/倒计时”等关键词；总分 ≥ 60 才点击；
- 不点击“支付/登录/权限/同意/下载”等敏感文本（负名单）；
- 点击后立即停止本轮扫描，避免连点；
- 点击前二次校验节点可见、可点击、未消失；
- 用户可一键关闭总开关。
- 阶段 4 收口：以上硬性校验集中在 AntiTouchGuard；全局设置（总开关/窗口/冷却）为硬性上限——窗口取规则与全局的较小值、冷却取较大值，只会更严格。

## 6. 内置规则 JSON（阶段 3 落地，与说明书七章示例一致）

```json
{
  "id": "amap_skip",
  "name": "高德地图开屏跳过",
  "enabled": true,
  "packageNames": ["com.autonavi.minimap"],
  "activityPatterns": ["*"],
  "launchWindowMs": 8000,
  "maxClicksPerLaunch": 1,
  "cooldownMs": 2000,
  "matchMode": "any",
  "conditions": [
    { "type": "text_regex", "pattern": ".*跳过\\s*\\d*.*", "area": "top_right", "score": 50 },
    { "type": "desc_regex", "pattern": "跳过|关闭", "area": "top_right", "score": 40 },
    { "type": "text_regex", "pattern": "\\d+\\s*秒?\\s*跳过", "area": "top_right", "score": 30 }
  ],
  "action": {
    "type": "click_node_or_parent",
    "fallback": { "type": "click_xy_ratio", "x": 0.92, "y": 0.08 }
  }
}
```

## 7. 阶段路线（对应说明书第 13 章）

| 阶段 | 内容 | 验收点 |
|---|---|---|
| 1 ✔ 完成 | 项目初始化：Compose 工程、无障碍服务声明、首页服务状态 + 跳转设置 | 可安装、首页正确显示服务状态、可跳转无障碍设置 |
| 2 ✔ 本次 | 节点调试工具 + 日志页：NodeCollector 遍历当前窗口，展示 text/desc/viewId/bounds，自动/手动抓取 | 日志页能看到当前窗口节点，筛选/清空/复制可用 |
| 3 ✔ 本次 | 高德单规则：内置规则、8 秒窗口、右上角过滤、评分阈值 60、点击节点或父节点（含二次校验与比例兜底）；8 秒/最多一次/冷却 2 秒随规则字段生效 | 高德冷启动 8 秒内自动点击一次 |
| 4 ✔ 本次 | 防误触收口：AntiTouchGuard 集中硬性校验（总开关/窗口/最多一次/冷却）、设置页（窗口 3-15s/冷却 1-10s）、首页总开关与今日跳过统计 | 总开关关闭即完全停用；全局参数只能更严格 |
| 5 ✔ 本次 | 规则管理：规则列表（启用/禁用/删除，内置保护）、JSON 新建/编辑对话框、SAF 文件导入导出、用户规则本地持久化 | 规则可开关、可导入导出、重启保留 |
| 6 ✔ 本次 | 学习模式：TYPE_VIEW_CLICKED 捕获（3 秒窗口）、候选规则生成（右上角+关键词，倒计时数字剥离）、预览确认保存；学习期间不自动点击 | 手动点一次可生成并保存规则 |
| 7 ✔ 本次 | 验收与优化：验收清单（docs/ACCEPTANCE.md）、日志落盘去抖（耗电）、合规说明与已知限制 | 对照说明书第 14 章清单逐条核对 |

### 阶段 8 起（P1，说明书第 4 章 2 节）

| 阶段 | 内容 | 状态 |
|---|---|---|
| 8 | 可视化规则编辑器（表单 + JSON 双模式，可互相切换回填） | ✔ 完成 |
| 9 | 点击评分细化（规则级 minScore 可配 40-90 + 日志得分/命中条件明细）与规则模板（通用跳过/倒计时/×关闭，仅预填） | ✔ 本次 |
| P1 后续 | 前台服务通知提高存活率（评估中，收益有限）、更多 App 实测模板 | 待开发 |

## 8. 关键设计决策

1. **纯本地**：Manifest 不申请任何网络权限；截图/节点树/日志均不落盘外发。
2. **存储**：MVP 用 SharedPreferences 存 JSON 字符串（说明书允许），仓库接口抽象，后续可平滑替换 Room/DataStore。
3. **冷启动定义**：服务观察到目标包名的 `TYPE_WINDOW_STATE_CHANGED` 时记录 `launchTime` 并重置本轮点击标记，窗口 = `launchWindowMs`。
4. **节点采集**：转成 `NodeSnapshot` 快照后匹配，不长期持有系统节点，规避 `AccessibilityNodeInfo` 回收问题。
5. **点击策略链**：节点本身 → 可点击父节点 → bounds 中心 → 比例坐标兜底（0.92, 0.08）；点击前基于快照在当前节点树重定位存活节点（二次校验），避免点击失效节点。
6. **学习模式**：期间只监听 `TYPE_VIEW_CLICKED` 记录节点，不自动点击；取最近 3 秒窗口内节点生成候选规则。

## 9. 合规与风险（摘录说明书第 15 章）

- 可能违反目标 App 用户协议；应用商店对无障碍权限审核极严，可能拒绝上架。
- 本工具仅用于个人学习与自用：不牟利、不上传数据、不破解、不修改目标 App、不承诺 100% 适配。
