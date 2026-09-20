# SkipStart 验收清单、真机测试与合规说明

> 当前版本以 `app/build.gradle.kts` 的 `versionName` 为准。
> 本文档随版本更新；第 2 章的 A/B 两组用例自 v0.2.0 引入，补丁版本沿用。

## 1. 验收清单（对照说明书第十四章）

| # | 验收标准 | 实现位置 | 验证方法 |
|---|---|---|---|
| 1 | 安装后能引导开启无障碍服务 | `HomeScreen` 状态卡 + 「去开启无障碍服务」（逐级降级跳转）、分品牌路径提示 | 首启首页显示未开启 + 引导按钮 |
| 2 | 首页能正确显示服务状态 | `AccessibilityUtils` 三级判定（官方 API → Secure 宽容解析 → 服务连接态），`LifecycleResumeEffect` 回前台自动刷新 | 开启/关闭系统开关后返回首页状态实时变化 |
| 3 | 高德冷启动 8 秒内自动点击「跳过」 | 内置规则 `amap_skip` + `RuleMatcher`（评分≥60）+ `ActionExecutor` + `AntiTouchGuard` 窗口 | 见下方真机测试步骤 |
| 4 | 非目标 App 不触发点击 | `RuleRepository.isTargetPackage` 白名单，服务事件先过滤包名 | 打开任意非规则 App，日志无 AUTO_CLICK |
| 5 | 同一次开屏最多点击一次 | `AntiTouchGuard.maxClicksPerLaunch`（默认 1）+ 点击后立即停止本轮扫描 | 一次开屏内观察日志仅一条成功点击 |
| 6 | 日志记录包名/命中规则/节点文本/点击结果 | `LogEntry` 全字段 + 日志页（实时/筛选/复制/清空） | 日志页核对 AUTO_CLICK 条目 |
| 7 | 规则可启用/禁用 | 规则页 Switch → `RuleRepository.setEnabled`（持久化） | 关闭高德规则后不再触发 |
| 8 | 规则可导入导出 JSON | `RuleRepository.exportJson/importJson` + SAF 文件选择器 | 导出文件查看 → 修改 → 重新导入 |
| 9 | 无网络权限也能运行 | Manifest 未声明 INTERNET 等任何权限 | 断网环境全部功能正常 |
| 10 | 不收集、不上传屏幕内容 | 无网络权限；数据仅存本机 SharedPreferences/SAF 文件 | 设置页隐私说明 + 代码审计 |
| 11 | **（v0.2.0）学习模式能学会任意 App** | `LearningScreen` 三步引导 + `InstalledAppScanner` + `LearningController` + `SkipAccessibilityService.captureLearningClick` | 见第 2 章 A 组用例 |
| 12 | **（v0.2.0）无障碍状态在主流 ROM 上判断正确** | `AccessibilityUtils` 三级判定 + 三态 UI + 机型排查 | 见第 2 章 B 组用例 |

> 第 3/4/5/11/12 条需真机确认。

## 2. v0.2.0 验收用例

### A 组：学习模式

| # | 用例 | 前置 | 步骤 | 期望结果 |
|---|---|---|---|---|
| A1 | 免手敲包名 | 无障碍已开启 | 进入学习页 | 自动列出本机应用（应用名 + 包名），可搜索；开关「含系统应用」后列表变化 |
| A2 | 流程易懂 | 同上 | 观察学习页 | 页面同时给出顶部「学习模式怎么用（三步）」与进行中卡片的当下指令，不出现未解释的术语 |
| A3 | 自动拉起目标 App | 同上 | 在学习页点选某 App | 约 0.5 秒后自动跳到该 App；回到本应用时学习仍在进行 |
| A4 | 学习期间不自动点击 | 目标 App 已有规则 | 学习中手动操作目标 App 开屏 | 日志只有 `LEARNING` 条目，无 `AUTO_CLICK` |
| A5 | 无文字按钮也能学到 | 目标 App 开屏「跳过」为纯图标/容器 | 学习中点一下「跳过」 | 捕获成功，样本质量为「好（信息取自…可点击区域）」或「中（取自内部文字）」 |
| A6 | 误点被过滤 | 同上 | 学习中点开屏广告中间的内容区 | 该次点击不被采信；日志出现 `LEARN_IGNORE` 与原因 |
| A7 | 多候选 + 推荐度 | 捕获成功 | 观察结果页 | 候选规则按推荐度降序，至少 1 条标记「推荐」，每条有中文解释与命中阈值 |
| A8 | 保存前试跑 | 捕获成功且服务在线 | 点某条候选的「先试跑一下」 | 返回命中结论（得分 + 按钮文字）或「没有找到匹配节点」的说明 |
| A9 | 保存后生效 | 保存任意候选 | 把目标 App 从最近任务划掉再打开 | 开屏自动跳过；日志出现该 `learned_*` 规则的 AUTO_CLICK |
| A10 | 超时提示可操作 | 无障碍已开启 | 开始学习后什么都不做，等 60 秒 | 学习自动结束，页面显示「上次学习没有捕获到点击」及 3 条排查建议 |
| A11 | 点错了可重学 | 捕获错误按钮 | 点「重新学习一次」 | 回到进行中状态，覆盖旧样本，不产生两条规则 |
| A12 | 首页入口可达 | 无障碍已开启 | 首页点「进入学习模式 →」 | 直接切到学习页（应用列表可见） |
| A13 | 学习期间常规链路不受影响 | 学习目标 App 同时也是自动跳过的目标包 | 学习结束（捕获或取消）后重新冷启动该 App | 自动跳过恢复正常工作（学习期间的 `return` 不再屏蔽常规链路） |

### B 组：无障碍权限机型适配

| # | 用例 | 步骤 | 期望结果 |
|---|---|---|---|
| B1 | 短名格式识别 | 在返回 `包名/.短类名` 的 ROM（华为/荣耀/三星）上开启服务 | 首页显示「运行中」（不再误判为未开启） |
| B2 | 仅包名格式识别 | 在只写入包名的 ROM（MIUI/ColorOS 被安全中心拦截时）上开启服务 | 首页不显示「未开启」 |
| B3 | 三态提示 | 授权已开但服务未连上 | 首页显示「已开启（服务待连接）」并给出 4 步处理办法，不误报故障 |
| B4 | 刷新状态 | 点「刷新状态」或从设置页返回 | 状态立即重新检测，无需重启应用 |
| B5 | 机型排查信息 | 首页点「机型排查」 | 显示当前机型、系统实际启用的无障碍服务列表、本应用服务组件名 |
| B6 | 分品牌路径 | 首页未开启时查看引导文案 | 按本机 `Build.MANUFACTURER` 显示对应设置路径 |
| B7 | 跳转降级 | 点「去开启」 | 先 `resolveActivity` 校验再启动；无障碍列表不可达时退到系统设置首页，不崩不静默 |
| B8 | 更新后重授权 | 覆盖安装新版本后打开 | 首页能正确反映系统重置后的状态，并引导重新开启 |

### 真机测试步骤（高德地图，回归）

1. 构建安装（Android Studio Sync → Run 到 Android 8.0+ 真机）；
2. 首页开启无障碍服务（设置 → 无障碍 → 已安装的应用 → 开屏跳过服务）；
3. **完全退出高德地图**（最近任务划掉）→ 重新打开；
4. 开屏出现含「跳过/关闭」的右上角按钮 → 8 秒内应自动点击一次进入主页；
5. 「日志」页核对：包名 `com.autonavi.minimap`、规则「高德地图开屏跳过」、节点文本、动作与结果；
6. 负向验证：连续开屏仅一条成功点击（最多一次）；打开非目标 App 无 AUTO_CLICK 日志。

调试命令（说明书第十六章）：

```bat
adb shell dumpsys activity top
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml
adb shell settings get secure enabled_accessibility_services
adb logcat -s SkipStart
```

## 3. v0.2.0 优化记录

- **学习捕获四级取样**（`SkipAccessibilityService.captureLearningClick`）：事件源自身 → 可点击父链（≤6 层）→ 子节点文字（≤3 层，关键词优先）→ 窗口快照右上角候选；彻底摆脱旧版「节点无文字即学习失败」；
- **捕获过滤**（`isLookingLikeSkipButton`）：关键词命中，或位于屏幕上方 35% 且宽度占比 <60% 的节点才采信，避免学到广告内容；被忽略的点击写 `LEARN_IGNORE` 日志；
- **移除误判逻辑**：旧版用 `SystemClock.uptimeMillis() - event.eventTime > 3000` 判断「3 秒时效」，两个时钟基准不同，实际会把正常点击误判为过期，已删除；
- **学习会话状态机 + 60 秒超时**（`LearningController`）：超时/取消都保留目标包与原因，UI 给出可操作提示；
- **多候选规则 + 推荐度**：一条样本产出文字（50 分阈值）/ 描述（40）/ viewId（30）/ 位置兜底（100）四类候选，按证据强度排序并在 UI 标注推荐；
- **位置兜底安全区**：兜底坐标取捕获节点中心比例，横向钳制到 45%~98%、纵向不超过 45%，超出安全区直接放弃位置兜底；
- **保存前试跑**（`SkipAccessibilityService.evaluateRule`）：用 `NodeCache` 缓存的窗口快照跑一次 `RuleMatcher`，把「能不能命中」提前告诉用户；
- **节点缓存**（`NodeCache`，5 分钟 TTL，仅内存，不上传不落盘）：学习回溯与规则试跑共用，服务释放时清空；
- **事件类型扩展**：新增 `typeViewLongClicked`（长按式跳过）、`typeWindowsChanged`（多窗口/悬浮窗开屏）；
- **无障碍配置增强**：`flagIncludeNotImportantViews`（部分 ROM 把广告节点标记为不重要）、`flagRetrieveInteractiveWindows`（多窗口取活动窗口）、`summary` 说明、`notificationTimeout` 100→150ms；
- **激活探针**：`onServiceConnected` 后 600ms 主动读一次窗口，缓解 MIUI/ColorOS「开了开关没反应」；
- 保留阶段 7-9 优化：日志落盘去抖 500ms、扫描节流 300ms、节点采集超时 300ms/上限 2000/深度 30、正则缓存、节点统一回收、点击失败连续 3 次停止本轮、规则级 minScore 可配。

## 4. 已知限制

- 「冷启动」定义为目标包进入前台（`TYPE_WINDOW_STATE_CHANGED`），热启动同样计入 8 秒窗口；
- 学习模式需 `QUERY_ALL_PACKAGES` 权限才能列出全部本机应用；该权限仅用于**本机**列表展示，不联网、不落盘；若渠道限制该权限，可退回「手动输入包名」入口；
- 位置兜底候选只在按钮「节点信息完全不可读」时才值得使用，可能出现漏点（保守优先于误触）；
- 试跑依赖最近一次窗口快照：开屏结束后试跑会提示「没有找到匹配节点」，属正常，不代表规则无效；
- 日志环形上限 500 条；调试节点快照仅保留本次会话（不落盘、不上传）；
- OCR 兜底 / 图像模板匹配为 P2 未实现；
- 规则编辑器：可视化表单已实现（表单 + JSON 双模式）；复杂正则仍可切换 JSON 精确编辑。

## 4.1 构建产物核对（每次发版必做）

自动化只能证明「编译通过」，还要证明**配置真的进了 APK**。以下命令可直接复现：

```bat
:: 1) 版本号必须与 git 标签一致
aapt2 dump badging app\build\outputs\apk\debug\app-debug.apk | findstr /C:"package:"

:: 2) 无障碍服务配置的实际打包值（不是看源码 XML，而是看 APK 里的编译结果）
aapt2 dump xmltree app\build\outputs\apk\debug\app-debug.apk --file res/xml/accessibility_service_config.xml
```

v0.2.5 实测结果（2026-09 核对，android.jar 常量真值比对）：

| 配置项 | APK 内值 | 解码 |
|---|---|---|
| `accessibilityEventTypes` | `0x00400823` | viewClicked(0x1) + viewLongClicked(0x2) + windowStateChanged(0x20) + windowContentChanged(0x800) + windowsChanged(0x400000) |
| `accessibilityFlags` | `0x00000052` | reportViewIds(0x10) + includeNotImportantViews(0x2) + retrieveInteractiveWindows(0x40) |
| `notificationTimeout` | `150` | ✅ 与源码一致 |
| `canPerformGestures` | `true` | ✅ 手势点击可用（ActionExecutor 第 3/4 级依赖） |
| `canRetrieveWindowContent` | `true` | ✅ 可读节点树 |
| `versionCode` / `versionName` | `3` / `0.2.5` | ✅ 与标签 `v0.2.5` 一致 |

> 事件类型常量真值可用 `javap -constants -classpath <sdk>/platforms/android-35/android.jar android.view.accessibility.AccessibilityEvent` 查得，
> 不要凭记忆推算位掩码（本仓库曾因此误判 `typeWindowsChanged` 未生效）。

## 5. 合规与风险（说明书第十五章摘录）

- 此类工具可能违反目标 App 用户协议；应用商店对无障碍权限审核极严，可能拒绝上架；
- 本项目定位：开源、个人学习与自用、不牟利；不破解会员、不修改目标 App、不去除 App 内所有广告；
- 不上传截图、节点树、日志（Manifest 无任何网络权限，纯本地）；
- 学习模式读取本机应用列表仅用于让用户选择目标 App，列表不出本机；
- 不承诺 100% 适配所有广告 SDK；
- 使用前请阅读目标 App 用户协议并遵守当地法律法规，风险自担。

## 6. 后续扩展（说明书第十七章）

OCR 兜底（MediaProjection + ML Kit）、图像模板匹配（OpenCV）、多语言规则（跳过/Skip/Close）、规则评分引擎细化、云规则（需严格脱敏）、桌面小组件一键开关、Tasker 集成、学习样本本地留存与批量管理。
