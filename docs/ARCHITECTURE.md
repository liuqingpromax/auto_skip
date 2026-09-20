# SkipStart（开屏广告自动跳过助手）· 架构设计

> 项目代号 SkipStart · Android · Kotlin + Jetpack Compose + AccessibilityService
> 当前版本以 `app/build.gradle.kts` 的 `versionName` 为准（git 标签与之一致）
> 核心原则：本地运行、规则驱动、防误触、合规、不破解目标 App。
> 本文档依据《开屏广告自动跳过助手（Android）——AI 开发说明书》编写，接口与路线与说明书第 5、6、7、13 章对齐。

## 0. 版本与 CI 约定

| 项目 | 约定 |
|---|---|
| 版本号来源 | `app/build.gradle.kts` 的 `versionCode` / `versionName`，**唯一来源** |
| git 标签 | 必须与 `versionName` 相同（`v0.2.5` ↔ `"0.2.5"`） |
| CI | `.github/workflows/build-apk.yml`：推送 `v*` 标签或手动 `workflow_dispatch` 触发 |
| CI 失败定位 | 工作流头部列出各 step 失败的含义；`Verify toolchain` 步骤打印完整环境信息 |
| 发布产物 | `SkipStart-v{versionName}-debug.apk`，随 Release 发布 |

## 0.1 v0.3.0 变更摘要（匹配通用性）

> 触发原因（用户反馈）：**「跳过」不一定在右上角；关闭按钮也可能是叉号 ✕。**

| 问题 | 原因（v0.1.0~v0.2.5） | v0.3.0 做法 |
|---|---|---|
| 按钮不在右上角就点不到 | 内置规则三条条件全部写死 `"area": "top_right"`，`RuleMatcher` 把 area 当**硬过滤**；位置分也只在右上角给（`TOP_RIGHT_BONUS`）；学习模式的捕获兜底与误点过滤同样只认上方区域；位置兜底还有个「纵向 >45% 直接放弃」的硬编码 | `area` 留空 = 全屏匹配，位置改为**加权**：四角 25 / 顶部 22 / 底部 18 / 左右 10 / **中间 0**；四角一视同仁；学习捕获改为四角+上下边缘全扫；误点过滤放宽到任意边缘区；位置兜底按实际点击角落生成，删掉 45% 硬编码 |
| 叉号 ✕ 按钮学不到也点不到 | 关闭按钮常是纯 ✕ 图标：无 `text`，`contentDescription` 也可能为空；关键词里没有叉号系字符；也没有「纯图标按钮」这类条件 | 新增 `icon_button` 条件类型（可点击 + 无文字 + 无描述 + 宽 ≤22% 屏宽、高 ≤18% 屏高 + 位于边缘）；关键词扩充叉号系字符与英文词；学习模式新增「按纯图标按钮识别」候选 |
| 内置规则的修复送不到老用户 | `RuleRepository.load()` 只在内置规则**缺失**时补齐，已存在就原样保留 → 用户本机持久化的旧规则一直生效 | 内置规则带 `version`（`BuiltinRules.VERSION`），持久化版本偏低时自动升级为新内容并保留用户的启用/禁用选择；用户自建规则不受影响 |

## 0.2 v0.2.0 变更摘要

| 问题 | 原因（v0.1.0） | v0.2.0 做法 |
|---|---|---|
| 学习模式流程不够详细、不够易懂 | 页面只有 4 行文字说明，且要求用户自己敲包名、自己猜该做什么 | 三步图文流程 + 已安装应用选择器 + 自动打开目标 App + 橙色进行中卡片（进度条 / 60 秒倒计时 / 当前该做什么）+ 结果页字段逐条解释 |
| 学习模式效果差 | 只认 `TYPE_VIEW_CLICKED`，且**只取事件源节点自身**的文字；无文字就判定失败。另有 3 秒 `eventTime` 窗口误判、无候选挑选、无法验证 | 事件类型扩展；四级取样（自身 → 父链 → 子节点 → 点击坐标回溯）；一次学习产出多条带推荐度的候选规则；保存前可「试跑」；捕获写日志 |
| 无障碍权限部分机型不适配 | 只用 `Settings.Secure` 字符串**严格相等**判断，遇到华为短名、MIUI 只写包名、ColorOS 拦截等一律误判为「未开启」；设置跳转无兜底 | 三级判定（官方 API → 宽容解析 → 服务真实连接态）+ 三态 UI + 分品牌路径提示 + 跳转逐级降级（先 `resolveActivity` 校验）+ 服务侧激活探针 |

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
            │   ├── SkipAccessibilityService.kt  # 无障碍服务（✔1声明 ✔3核心 ✔4守卫 ✔6/v0.2 学习捕获+连接态上报）
            │   ├── AntiTouchGuard.kt            # 防误触守卫：总开关/窗口/最多一次/冷却（✔ 阶段4）
            │   └── LearningController.kt        # 学习模式：会话状态机+多候选规则+推荐度（✔ 阶段6 / v0.2 重做）
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
            │   ├── InstalledAppScanner.kt       # 已安装应用扫描（v0.2：学习页选目标 App 用）
            │   └── LogRepository.kt             # 日志环形存储（上限500条，✔ 阶段2）
            ├── ui/                              # Compose
            │   ├── MainScreen.kt                # 底部导航（首页/日志/规则/学习/设置，✔2/4/5/6）
            │   ├── HomeScreen.kt                # 首页：三态服务状态+机型排查（✔ 阶段1 / v0.2 增强）
            │   ├── RuleListScreen.kt            # 规则列表/启用禁用/删除/导入导出（✔ 阶段5）
            │   ├── RuleEditDialog.kt            # 可视化规则编辑器：表单+JSON 双模式（✔ 阶段8/P1）
            │   ├── LogScreen.kt                 # 日志页：实时/筛选/清空/复制 + 节点调试（✔ 阶段2）
            │   ├── LearningScreen.kt            # 学习模式：三步引导/应用选择/进度/候选规则/试跑（v0.2 重做）
            │   ├── SettingsScreen.kt            # 设置：窗口/冷却/隐私/许可（✔ 阶段4）
            │   └── theme/Theme.kt               # M3 主题（✔ 阶段1）
            └── util/
                ├── Logger.kt                    # Logcat 统一入口（✔ 阶段1）
                ├── AccessibilityUtils.kt        # 服务三态判定/多格式解析/设置跳转兜底（✔ 阶段1 / v0.2 适配）
                └── ScreenUtils.kt               # 屏幕尺寸/坐标换算/启动目标App（✔ 阶段3 / v0.2 扩展）
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
    val type: String,        // text_regex | desc_regex | view_id | class_name | icon_button
    val pattern: String,
    val area: String? = null, // null/空/full = 全屏匹配（位置改加权）；否则硬过滤，见 3.1 节
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
    fun buildCandidateRule(sample: LearnedSample): Rule?  // 兼容旧调用：返回最推荐的一条
    fun buildCandidates(sample: LearnedSample): List<LearningCandidate>  // v0.2.0 起：多候选 + 推荐度
}
```

## 3.1 规则条件类型（v0.3.0）

| type | 语义 | 关键字段 | 备注 |
|---|---|---|---|
| `text_regex` | 节点 `text` 正则匹配 | `pattern`、`score` | 关键词按字面量转义后写入 |
| `desc_regex` | 节点 `contentDescription` 正则匹配 | `pattern`、`score` | 纯图标按钮常只有描述 |
| `view_id` | `viewIdResourceName` 包含匹配 | `pattern`、`score` | 同版本最精确 |
| `class_name` | `className` 包含匹配 | `pattern`、`score` | 少用 |
| `icon_button` | **v0.3.0 新增**：纯图标关闭按钮 | `score`（pattern 仅占位） | 要求可点击 + 无文字 + 无描述 + 宽 ≤22% 屏宽、高 ≤18% 屏高 + 位于边缘区 |

**`area` 字段语义（v0.3.0 变更）**：

- 省略 / 空 / `"full"` → **全屏匹配**，位置只作加分（**推荐，内置规则与模板默认**）；
- `top_right` / `top_left` / `bottom_right` / `bottom_left` / `top` / `bottom` / `left` / `right` → 仍作**硬过滤**，仅供 JSON 模式精确控制或特殊布局使用。

## 4. 核心链路

### 4.1 自动跳过

```
监听前台 App → 判断是否目标包名/Activity → 抓取无障碍节点树
→ 识别「跳过 / 关闭 / ✕ / 倒计时」→ 位置与尺寸加权评分 → 执行点击 → 冷却防误触
```

**评分模型（v0.3.0）**：

```
总分 = Σ条件分 + 位置分(0..25) + 可点击(10) + 尺寸分(0..15)     ≥ minScore(默认60) 才点击
位置分：四角 25 / 顶部边缘 22 / 底部边缘 18 / 左右边缘 10 / 屏幕中间 0
尺寸分：宽 ≤12% 25→15分、≤22%→10分、≤35%→5分、更大 0
```

因此：四角的纯 ✕ 图标 = 25+25+10+15 = 75 达标；屏幕中间的纯图标拿不到位置分，无法达标；
带「跳过」文字的中部按钮靠文字分（55）也能达标 —— 兼顾「任意位置」与「防误触」。

### 4.2 学习模式（v0.2.0 重做，v0.3.0 扩展识别范围）

```
[UI] 学习页选目标 App（InstalledAppScanner 列本机应用）
      ↓
[Controller] LearningController.start(pkg)：进入 WAITING，60s 倒计时
      ↓
[UI] ScreenUtils.launchApp(pkg)：自动把目标 App 拉到前台
      ↓
[Service] 学习期间 tryAutoSkip() 首行直接 return —— 只观察，绝不自动点击
      ↓
[Service] 目标包窗口事件 → refreshLearningCache() 缓存节点树（供回溯与试跑）
      ↓
[Service] 用户手动点「跳过 / ✕」→ TYPE_VIEW_CLICKED / TYPE_VIEW_LONG_CLICKED
      ↓
[Service] isLookingLikeSkipButton()：关键词，或「任意边缘区 + 可点击 + 小尺寸」过滤，避免学到广告内容
      ↓
[Service] 四级取样：自身 → 父链（≤6 层）→ 子节点文字 → 快照边缘区候选（四角+上下边缘全扫）
      ↓
[Controller] LearningController.submit(sample)：进入 CAPTURED，自动结束学习
      ↓
[Controller] buildCandidates(sample)：产出多条候选规则 + 推荐度 + 推荐标记
      ↓
[UI] 结果页：样本字段解释 + 候选规则逐条说明 + 「先试跑一下」
      ↓
[UI] 保存 → RuleRepository.addOrUpdate(rule) → 下次冷启动自动跳过
```

**学习会话状态机**：`IDLE → WAITING →（用户点跳过）CAPTURED /（60s 到点）EXPIRED_TIME /（用户取消）EXPIRED_MANUAL`。
超时与取消都保留目标包信息，UI 会给出「为什么没学到 + 下一步怎么做」的提示，而不是静默回到初始态。

**候选规则来源（v0.3.0）**：文字 / 描述 / viewId / **纯图标（icon_button）** / 位置兜底（按实际点击角落），带推荐度排序。

## 5. 防误触与安全策略（说明书第 9 章，必须实现）

- 只处理目标包名（规则 `packageNames` 白名单，非目标包直接忽略）；
- 只在冷启动后 8 秒内（`launchWindowMs` 默认 8000）；
- 每次启动最多点一次（`maxClicksPerLaunch` 默认 1）；
- 两次点击冷却 2 秒（`cooldownMs` 默认 2000）；
- **位置加权而非位置白名单（v0.3.0）**：四角 +25、顶部边缘 +22、底部边缘 +18、左右边缘 +10、**屏幕中间 +0**；四个角一视同仁，中间拿不到位置分；
- **纯图标按钮严格门槛（v0.3.0）**：`icon_button` 要求可点击 + 无文字 + 无描述 + 宽 ≤22% 屏宽、高 ≤18% 屏高 + 位于边缘区，大块内容区一律不算；
- 文本需命中「跳过 / 关闭 / ✕ / skip / close」等关键词；总分 ≥ `minScore`（默认 60）才点击；
- 不点击「支付 / 登录 / 权限 / 同意 / 下载 / 立即 / 领取」等敏感文本（负名单，v0.3.0 扩充）；
- 点击后立即停止本轮扫描，避免连点；
- 点击前二次校验节点可见、可点击、未消失；
- 用户可一键关闭总开关。
- 阶段 4 收口：以上硬性校验集中在 AntiTouchGuard；全局设置（总开关/窗口/冷却）为硬性上限——窗口取规则与全局的较小值、冷却取较大值，只会更严格。
- **v0.2.0 学习模式侧防误触**：学习期间服务只观察不动作；学习会话 60 秒自动超时；位置兜底候选要求 `minScore = 100` 才动手。
- **v0.3.0 调整**：误点过滤从「上方 35%」放宽到「任意边缘区」（否则底部关闭按钮点了也不被采信）；位置兜底删除「纵向 >45% 直接放弃」的硬编码，改为按实际点击角落生成规则 —— 防误触改由「条件分 + 位置加权 + 阈值」共同保证，而非砍掉半个屏幕。

## 6. 内置规则 JSON（v0.3.0：全屏匹配 + 叉号覆盖）

> **修改这段 JSON 后必须把 `BuiltinRules.VERSION` 加 1**，否则老用户本机持久化的旧规则不会被替换。

```json
{
  "id": "amap_skip",
  "version": 1,
  "name": "高德地图开屏跳过",
  "enabled": true,
  "packageNames": ["com.autonavi.minimap"],
  "activityPatterns": ["*"],
  "launchWindowMs": 8000,
  "maxClicksPerLaunch": 1,
  "cooldownMs": 2000,
  "matchMode": "any",
  "minScore": 60,
  "conditions": [
    { "type": "text_regex", "pattern": "跳过|跳過|略过|跳过广告|关闭广告", "score": 55 },
    { "type": "text_regex", "pattern": "\\d+\\s*秒?\\s*(后)?\\s*(跳过|关闭)", "score": 50 },
    { "type": "text_regex", "pattern": "skip|close|dismiss", "score": 45 },
    { "type": "text_regex", "pattern": "✕|✖|✗|×|⨯|╳|❌|❎", "score": 50 },
    { "type": "desc_regex", "pattern": "跳过|关闭|skip|close|dismiss", "score": 45 },
    { "type": "desc_regex", "pattern": "✕|✖|✗|×|❌|关闭按钮", "score": 50 },
    { "type": "view_id", "pattern": "close", "score": 35 },
    { "type": "view_id", "pattern": "skip", "score": 35 },
    { "type": "icon_button", "pattern": "*", "score": 25 }
  ],
  "action": {
    "type": "click_node_or_parent",
    "fallback": { "type": "click_xy_ratio", "x": 0.92, "y": 0.06 }
  }
}
```

**与旧版的关键差异**：全部条件**不再带 `area`**（旧版三条都写死 `"area": "top_right"`），
因此按钮出现在任何角落都能匹配，位置改为由 `RuleMatcher.positionScore` 加权。

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
| 9 | 点击评分细化（规则级 minScore 可配 40-90 + 日志得分/命中条件明细）与规则模板（通用跳过/倒计时/×关闭，仅预填） | ✔ 完成 |
| 10 | **v0.2.0 版本更新**：学习模式重做（三步引导 / 应用选择器 / 自动拉起 / 倒计时 / 多候选 + 推荐度 / 试跑验证）、学习捕获四级取样、无障碍权限机型适配（三级判定 + 三态 UI + 分品牌路径 + 跳转降级） | ✔ 完成 |
| 11 | **v0.3.0 通用性更新**：位置硬过滤改加权（四角/上下边缘/左右边缘）、新增 `icon_button` 纯图标/叉号 ✕ 识别、关键词扩充叉号系与英文、学习模式分角落识别、内置规则版本化升级、规则模板与区域选项细化、新增评分模型自检脚本 | ✔ 本次 |
| P1 后续 | 前台服务通知提高存活率（评估中，收益有限）、更多 App 实测模板 | 待开发 |

## 8. 关键设计决策

1. **纯本地**：Manifest 不申请任何网络权限；截图/节点树/日志均不落盘外发。
   v0.2.0 新增的 `QUERY_ALL_PACKAGES` 只用于读取**本机**应用列表供用户选择，不涉及网络。
2. **存储**：MVP 用 SharedPreferences 存 JSON 字符串（说明书允许），仓库接口抽象，后续可平滑替换 Room/DataStore。
3. **冷启动定义**：服务观察到目标包名的 `TYPE_WINDOW_STATE_CHANGED` 时记录 `launchTime` 并重置本轮点击标记，窗口 = `launchWindowMs`。
4. **节点采集**：转成 `NodeSnapshot` 快照后匹配，不长期持有系统节点，规避 `AccessibilityNodeInfo` 回收问题。
5. **点击策略链**：节点本身 → 可点击父节点 → bounds 中心 → 比例坐标兜底（0.92, 0.06）；点击前基于快照在当前节点树重定位存活节点（二次校验），避免点击失效节点。
6. **学习模式（v0.2.0，v0.3.0 扩展）**：
   - 会话由 `LearningController` 状态机管理，**一次学习只产出一个样本**，避免多按钮互相污染；
   - 捕获走四级取样（自身 → 父链 → 子节点文字 → **四角/上下边缘候选**），彻底摆脱「无文字即失败」；
   - 一条样本产出多条候选规则（文字 / 描述 / viewId / **纯图标 icon_button** / 位置兜底），带推荐度与解释，由用户挑；
   - 位置兜底取自**用户实际点击坐标**（仅做屏幕内钳制），不再限定右上安全区；
   - 节点快照缓存（`NodeCache`，5 分钟 TTL，仅内存）支撑「保存前试跑」。
7. **位置只加权、不白名单（v0.3.0）**：把位置做成「加分项」而不是「准入条件」，
   是同时满足「按钮可能在任意角落」与「不能乱点」的关键——四角加分、中间 0 分，
   于是角落的纯 ✕ 图标能达标、中间的图标达不到阈值，无需为每个位置写规则。
8. **内置规则版本化（v0.3.0）**：内置规则是可迭代的**代码资产**而非一次性种子数据，
   带版本号才能在修复后真正送达老用户；升级时保留用户的启用/禁用选择，不覆盖用户自建规则。
9. **无障碍状态判定（v0.2.0）**：不信任单一数据源。
   一级 `AccessibilityManager.getEnabledAccessibilityServiceList()`，
   二级宽容解析 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`（全名 / 短名 / 仅包名 / 组件解析四种写法），
   三级服务运行期真实连接态 `AccessibilityUtils.serviceConnected`（由服务 onServiceConnected / onUnbind 直接写入）。
   UI 由此呈现三态，把 ROM 差异暴露成可操作的提示，而不是一句「未开启」。

## 9. 匹配通用性设计（v0.3.0 新增）

| 现象 | 根因（旧版） | 我方对策 |
|---|---|---|
| 按钮不在右上角就永远点不到 | 内置规则条件写死 `area: top_right`（硬过滤）；位置分只在右上角给；学习捕获兜底与误点过滤只认上方区域；位置兜底还有「纵向 >45% 放弃」硬编码 | `area` 留空 = 全屏匹配；位置改加权（四角 25 / 顶部 22 / 底部 18 / 左右 10 / 中间 0）；学习捕获四角+上下边缘全扫；误点过滤放宽到任意边缘区；删掉 45% 硬编码 |
| 叉号 ✕ 按钮点不到 | 关闭按钮常是纯图标：无 `text`、描述可能为空；关键词无叉号系字符；无「纯图标按钮」条件 | 新增 `icon_button` 条件（可点击 + 无文字 + 无描述 + 小尺寸 + 位于边缘）；关键词补 ✕✖✗×⨯╳❌❎ 与 skip/close/dismiss/cancel |
| 中间区域被误点 | 若简单地把位置白名单放开到全屏，中间的图标/内容也会被点 | 中间位置**不给位置分**：纯图标在中间最多 35 分（<60 阈值）→ 不点；而带「跳过」文字的按钮靠文字分 55 仍可达标 → 位置放开但不失控 |
| 内置规则改了却不见效 | `load()` 只在内置规则缺失时补齐，已存在则原样保留 → 旧规则永远生效 | 内置规则带 `version`，偏低自动升级（保留用户启用/禁用选择）；改 JSON 必须 `BuiltinRules.VERSION + 1` |
| 评分逻辑无法验证 | 本仓库无单元测试基建（离线环境无法获取 JUnit 依赖） | 新增 `tools/verify_matcher_model.py`：等价模型覆盖四角/边缘/中间/尺寸/防误触底线，可随时复跑 |

## 9.1 无障碍机型适配策略（v0.2.0 新增）

| 现象 | 根因 | 我方对策 |
|---|---|---|
| 服务明明开着，首页却显示「未开启」 | 华为/荣耀/三星返回 `包名/.短类名`，MIUI/ColorOS 被安全中心拦截时只写 `包名`，与全名严格相等必然失配 | 三级判定：官方 API → 宽容解析（4 种写法 + 3 种分隔符）→ 服务真实连接态 |
| 开了开关但服务不工作 | 系统记下授权却没拉起服务；应用更新后系统重置授权 | 服务 `onServiceConnected` 后 600ms 主动读一次窗口作为激活探针；首页三态提示 + 「刷新状态」按钮 + 关掉再打开/电池白名单/重启三步指引 |
| 找不到「开屏跳过服务」在哪 | 各 ROM 无障碍层级与命名不同（已下载的应用 / 已安装的服务 / 辅助功能…） | `AccessibilityUtils.settingsPathHint()` 按 `Build.MANUFACTURER` 给出分品牌路径；首页「机型排查」显示当前机型与系统实际启用的服务列表 |
| 点「去开启」没反应 | 个别 ROM 没有标准 `ACTION_ACCESSIBILITY_SETTINGS` 入口 | 跳转逐级降级：`ACTION_ACCESSIBILITY_SETTINGS` → `ACTION_SETTINGS`，每一步先 `resolveActivity` 校验再启动，全部失败则提示手动进入；不使用厂商/隐藏 action（语义随 ROM 而异） |
| 用户不知道该从哪进入学习 | 学习页只挂在底部导航第 4 项 | 首页新增「让新 App 也能自动跳过」入口卡，一键跳到学习页 |
| 开屏广告节点读不到 | 部分 ROM 把广告节点标记为「不重要」而 `visible = false` | `flagIncludeNotImportantViews`；多窗口/悬浮窗场景开启 `flagRetrieveInteractiveWindows` 并监听 `typeWindowsChanged` |
| 长按式「跳过」学不到 | 只声明了 `typeViewClicked` | 事件类型扩展到 `typeViewLongClicked` / `typeWindowsChanged` |

## 10. 合规与风险（摘录说明书第 15 章）

- 可能违反目标 App 用户协议；应用商店对无障碍权限审核极严，可能拒绝上架。
- 本工具仅用于个人学习与自用：不牟利、不上传数据、不破解、不修改目标 App、不承诺 100% 适配。
- 学习模式读取本机已安装应用列表仅用于让用户选择目标 App，列表不出本机、不落盘、不上传。
