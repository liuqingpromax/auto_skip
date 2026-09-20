# SkipStart · 开屏广告自动跳过助手（Android）

[![Build APK](https://github.com/liuqingpromax/auto_skip/actions/workflows/build-apk.yml/badge.svg)](https://github.com/liuqingpromax/auto_skip/actions/workflows/build-apk.yml)

基于无障碍服务的开屏广告自动跳过工具：目标 App（如高德地图）冷启动时，在开屏窗口内自动识别并点击“跳过/关闭”按钮。

**当前版本：v0.2.5（versionCode 3）**

- 技术栈：Kotlin + Jetpack Compose + AccessibilityService，minSdk 26 / targetSdk 35
- 原则：纯本地运行（无任何网络权限）、规则驱动、防误触、不破解目标 App
- 架构与阶段路线：见 `docs/ARCHITECTURE.md`
- 用户说明书：见 `docs/USER_GUIDE.md`
- **CI 构建失败排查**：见 `docs/CI_TROUBLESHOOTING.md`

> **版本号约定**：应用版本以 `app/build.gradle.kts` 的 `versionName` 为唯一来源；
> git 标签必须与之保持一致（`v0.2.5` ↔ `versionName = "0.2.5"`），
> 避免出现「标签是 v0.2.4、应用内显示 0.2.0」这种对不上的情况。

## v0.2.5 更新内容（CI 修复与发布链路）

| 方向 | 本次改动 |
|---|---|
| **CI 补装 SDK 组件** | 新增 `sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"`：`compileSdk 35` 需要 platform 35 与 build-tools 35.0.0，缺失时构建会在配置阶段就失败 |
| **CI 工具链体检** | 新增 `Verify toolchain` 步骤，先打印 JDK 版本、`JAVA_HOME`/`ANDROID_HOME`、`gradlew` 权限与 shebang 字节、wrapper 配置、`./gradlew --version`；构建失败时能一眼区分「环境问题」还是「代码问题」 |
| **失败可定位** | 构建改为 `--stacktrace`；工作流头部写明各 step 失败分别代表什么（工具链 / SDK / 编译 / 发布权限） |
| **版本号对齐** | 应用版本 0.2.0 → **0.2.5**（versionCode 2 → 3），与 git 标签统一；README 增加版本号约定与 CI 状态徽章 |

## v0.2.0 更新内容（功能）

| 方向 | 本次改动 |
|---|---|
| **学习模式流程更详细、更易懂** | 重做学习页：三步图文流程（选 App → 点一次跳过 → 保存规则）+ 已安装应用选择器（不用手敲包名）+ 自动拉起目标 App + 橙色进行中卡片（进度条 / 60 秒倒计时 / 当前该做什么）+ 结果页对每个字段和每条候选规则都用一句话解释 + 失败原因与下一步提示；首页新增「让新 App 也能自动跳过」入口卡，一键直达学习模式 |
| **学习模式效果提升** | ① 捕获从「只认事件源节点自身文字」升级为**四级取样**（自身 → 父链 ≤6 层 → 子节点文字 → 窗口快照右上角候选），无文字按钮也能学到；② 事件类型扩展到长按与多窗口变化；③ 一次学习产出**多条候选规则**（文字 / 描述 / viewId / 位置兜底），带推荐度、推荐标记与命中阈值，由用户挑；④ 位置兜底改用**实际捕获节点的中心比例**并钳制在右上安全区；⑤ 新增「**先试跑一下**」：保存前用最近窗口快照验证规则能否命中；⑥ 捕获与忽略都写日志（事件类型 `LEARNING`），可回溯；⑦ 移除旧的 3 秒 `eventTime` 误判逻辑；⑧ 非跳过的误点会被过滤，不污染样本 |
| **无障碍权限机型适配** | ① 状态判定从「Secure 字符串严格相等」升级为**三级判定**：官方 `AccessibilityManager` API → 宽容解析（全名 / 短名 / 仅包名 / 组件解析 + `:` `,` `;` 三种分隔符）→ 服务运行期**真实连接态**；② 首页服务状态改为三态（未开启 / 已开启待连接 / 运行中）并新增「刷新状态」与「机型排查」；③ 按品牌（小米 / 华为 / 荣耀 / OPPO / vivo / 三星…）给出无障碍设置路径；④ 设置跳转只使用系统保证存在的入口（`ACTION_ACCESSIBILITY_SETTINGS` → `ACTION_SETTINGS`），每一步先 `resolveActivity` 校验，不再依赖厂商隐藏 action；⑤ 服务 `onServiceConnected` 后主动读一次窗口作为激活探针；⑥ 无障碍配置补充 `flagIncludeNotImportantViews`、`flagRetrieveInteractiveWindows`、`typeViewLongClicked`、`typeWindowsChanged`，并新增 `summary` 说明 |
| **发布流程** | GitHub Actions 构建后按版本号重命名 APK（`SkipStart-vX.Y.Z-debug.apk`），发布 Release 时自动带上版本名与更新说明 |

## 环境要求

- Android Studio（Koala 或更新版本，自带 JDK 17）
- Android SDK Platform 35
- 首次打开时若提示 Gradle 版本，按提示使用项目自带的 Gradle 8.9 wrapper

> 已验证：本机使用 JDK 17 + Android SDK 35 + Gradle 8.9 完成真实构建（`:app:assembleDebug` → BUILD SUCCESSFUL）。

## 构建与运行

1. 用 Android Studio 打开本目录，等待 Gradle Sync 完成；
2. 连接 Android 8.0+（API 26+）真机（模拟器无法完整体验开屏广告链路）；
3. Run `app`；
4. 首页点击「去开启无障碍服务」→ 系统无障碍设置 → 开启「开屏跳过服务」；
   首页成功状态为「无障碍服务：运行中」，另有「机型排查」显示本机诊断信息；
5. 阶段 2 验证：进入「日志」页开启「自动抓取」，切换到任意 App 再切回，即可看到窗口节点树与事件日志；
6. 阶段 3 验证：冷启动高德地图，开屏出现含「跳过/关闭」的右上角按钮时，8 秒内自动点击一次；日志页可查看命中规则、节点文本、动作与点击结果。
7. 阶段 4 验证：首页或设置页关闭总开关后，冷启动高德不再触发点击；设置页可调整窗口（3-15 秒）与冷却（1-10 秒），全局参数只会更严格。
8. 阶段 5 验证：在「规则」页可开关/编辑/新建/删除规则（内置规则受保护）；导出 JSON 到文件管理器查看，再导入验证条数；重启应用后修改保留。
9. **v0.2.0 学习模式验证**：进入「学习」页 → 在应用列表里选目标 App（会显示应用名，不用输包名）→ 应用会自动打开目标 App → 等开屏广告出现后手动点一下「跳过」→ 切回应用，查看捕获信息、样本质量与候选规则 → 点「先试跑一下」确认能命中 → 保存任意一条 → 冷启动目标 App 验证自动点击。
10. **无障碍适配验证**：首页「机型排查」应列出当前机型、系统实际启用的无障碍服务、本应用服务组件名；关闭服务后状态应显示「未开启」；开启后显示「已开启（服务待连接）」或「运行中」。

命令行构建（可选）：

```bat
gradlew.bat :app:assembleDebug
```

## 获取 APK

- **本地构建**：`gradlew.bat :app:assembleDebug` → 产物 `app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装；本仓库 `dist/` 亦有现成拷贝，不入库）；
- **云端下载**：给仓库打 `v*` 标签（如 `v0.2.0`）后，GitHub Actions 自动构建 APK 并发布为 Release 附件，手机浏览器可直接下载安装。

安装提示：首次安装需在系统设置中允许该来源的「安装未知应用」。

## 调试命令（说明书第 16 章）

```bat
adb shell dumpsys activity top
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml
adb shell settings get secure enabled_accessibility_services
```

## 阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 项目初始化（工程骨架 + 无障碍服务声明 + 首页状态） | ✔ 完成 |
| 2 | 节点调试工具 + 日志页 | ✔ 完成 |
| 3 | 高德单规则自动点击（内置规则 + 匹配评分 + 点击链 + 二次校验） | ✔ 完成 |
| 4 | 防误触收口（总开关 + 设置页 + AntiTouchGuard 硬性校验） | ✔ 完成 |
| 5 | 规则管理（列表/启用禁用/JSON 编辑/导入导出/持久化） | ✔ 完成 |
| 6 | 学习模式（点击捕获→候选规则→确认保存） | ✔ 完成 |
| 7 | 验收清单与优化（docs/ACCEPTANCE.md、落盘去抖、合规收尾） | ✔ 完成 |
| 8 | P1：可视化规则编辑器（表单 + JSON 双模式） | ✔ 完成 |
| 9 | P1：点击评分细化（minScore 可配 + 日志明细）与规则模板 | ✔ 完成 |
| 10 | **v0.2.0：学习模式重做 + 学习效果提升 + 无障碍机型适配** | ✔ 本次 |

## 权限说明

| 权限 | 用途 |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE`（服务级） | 读取窗口节点、识别「跳过/关闭」并模拟一次点击 |
| `QUERY_ALL_PACKAGES`（v0.2.0 新增） | 仅用于在学习页列出**本机**已安装应用供用户选择，免去手敲包名；列表不出本机、不落盘、不上传 |

**本项目不申请任何网络权限。**

## 合规声明

本项目仅供个人学习与自用：不牟利、不上传截图/节点树/日志、不破解或修改目标 App、不承诺 100% 去广告。请遵守目标 App 用户协议与当地法律法规。
