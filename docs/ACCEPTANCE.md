# SkipStart MVP 验收清单、真机测试与合规说明（阶段 7）

## 1. 验收清单（对照说明书第十四章）

| # | 验收标准 | 实现位置 | 验证方法 |
|---|---|---|---|
| 1 | 安装后能引导开启无障碍服务 | `HomeScreen` 状态卡 + 「去开启无障碍服务」（跳转 `Settings.ACTION_ACCESSIBILITY_SETTINGS`） | 首启首页显示未开启 + 引导按钮 |
| 2 | 首页能正确显示服务状态 | `AccessibilityUtils.isServiceEnabled`（读 `ENABLED_ACCESSIBILITY_SERVICES`），`LifecycleResumeEffect` 回前台自动刷新 | 开启/关闭系统开关后返回首页状态实时变化 |
| 3 | 高德冷启动 8 秒内自动点击「跳过」 | 内置规则 `amap_skip` + `RuleMatcher`（评分≥60）+ `ActionExecutor` + `AntiTouchGuard` 窗口 | 见下方真机测试步骤 |
| 4 | 非目标 App 不触发点击 | `RuleRepository.isTargetPackage` 白名单，服务事件先过滤包名 | 打开任意非规则 App，日志无 AUTO_CLICK |
| 5 | 同一次开屏最多点击一次 | `AntiTouchGuard.maxClicksPerLaunch`（默认 1）+ 点击后立即停止本轮扫描 | 一次开屏内观察日志仅一条成功点击 |
| 6 | 日志记录包名/命中规则/节点文本/点击结果 | `LogEntry` 全字段 + 日志页（实时/筛选/复制/清空） | 日志页核对 AUTO_CLICK 条目 |
| 7 | 规则可启用/禁用 | 规则页 Switch → `RuleRepository.setEnabled`（持久化） | 关闭高德规则后不再触发 |
| 8 | 规则可导入导出 JSON | `RuleRepository.exportJson/importJson` + SAF 文件选择器 | 导出文件查看 → 修改 → 重新导入 |
| 9 | 无网络权限也能运行 | Manifest 未声明 INTERNET 等任何权限 | 断网环境全部功能正常 |
| 10 | 不收集、不上传屏幕内容 | 无网络权限；数据仅存本机 SharedPreferences/SAF 文件 | 设置页隐私说明 + 代码审计 |

> 第 3/4/5 条需真机确认。本仓库生成环境无 JDK/Android SDK，未做本机构建；首次用 Android Studio 打开会补齐 Gradle wrapper。

## 2. 真机测试步骤（高德地图）

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

## 3. 阶段 7 优化记录

- **日志落盘去抖 500ms**：内容变化事件洪峰时合并写入，减少 IO 与耗电（`LogRepository`）；
- 扫描节流 300ms、节点采集超时 300ms、节点上限 2000、深度上限 30；
- 正则编译缓存（`RuleMatcher.regexCache`）；
- 节点统一回收清单（`NodeCollector`/`ActionExecutor`/学习捕获），无泄漏；
- 学习模式 3 秒时效校验（`event.eventTime`）；
- 点击失败连续 3 次自动停止本轮（防无效重试）；
- 评分阈值规则级可配（minScore 默认 60，范围 40-90），日志记录得分与命中条件明细（阶段 9）；
- release 暂未开启混淆（MVP 简化；后续开启需为无障碍服务补充 keep 规则）。

## 4. 已知限制（MVP 范围）

- 「冷启动」定义为目标包进入前台（`TYPE_WINDOW_STATE_CHANGED`），热启动同样计入 8 秒窗口；
- 学习模式目标包名需手动输入（避免 `QUERY_ALL_PACKAGES` 权限，可在日志页抓包名）；
- 日志环形上限 500 条；调试节点快照仅保留本次会话（不落盘、不上传）；
- OCR 兜底 / 图像模板匹配为 P2 未实现；
- 规则编辑器：可视化表单已实现（阶段 8，表单 + JSON 双模式）；复杂正则仍可切换 JSON 精确编辑。

## 5. 合规与风险（说明书第十五章摘录）

- 此类工具可能违反目标 App 用户协议；应用商店对无障碍权限审核极严，可能拒绝上架；
- 本项目定位：开源、个人学习与自用、不牟利；不破解会员、不修改目标 App、不去除 App 内所有广告；
- 不上传截图、节点树、日志（Manifest 无任何网络权限，纯本地）；
- 不承诺 100% 适配所有广告 SDK；
- 使用前请阅读目标 App 用户协议并遵守当地法律法规，风险自担。

## 6. 后续扩展（说明书第十七章）

OCR 兜底（MediaProjection + ML Kit）、图像模板匹配（OpenCV）、多语言规则（跳过/Skip/Close）、规则评分引擎细化、云规则（需严格脱敏）、桌面小组件一键开关、Tasker 集成。
