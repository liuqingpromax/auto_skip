# SkipStart · 开屏广告自动跳过助手（Android MVP）

基于无障碍服务的开屏广告自动跳过工具：目标 App（如高德地图）冷启动时，在开屏窗口内自动识别并点击“跳过/关闭”按钮。

- 技术栈：Kotlin + Jetpack Compose + AccessibilityService，minSdk 26 / targetSdk 35
- 原则：纯本地运行（无任何网络权限）、规则驱动、防误触、不破解目标 App
- 架构与阶段路线：见 `docs/ARCHITECTURE.md`
- 用户说明书：见 `docs/USER_GUIDE.md`

## 环境要求

- Android Studio（Koala 或更新版本，自带 JDK 17）
- Android SDK Platform 35
- 首次打开时若提示 Gradle 版本，按提示使用项目自带的 Gradle 8.9 wrapper

> 已验证：本机使用 JDK 17 + Android SDK 35 + Gradle 8.9 完成真实构建（`gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL）。

## 构建与运行

1. 用 Android Studio 打开本目录，等待 Gradle Sync 完成；
2. 连接 Android 8.0+（API 26+）真机（模拟器无法完整体验开屏广告链路）；
3. Run `app`；
4. 首页点击「去开启无障碍服务」→ 系统无障碍设置 → 开启「开屏跳过服务」；
5. 阶段 2 验证：进入「日志」页开启「自动抓取」，切换到任意 App 再切回，即可看到窗口节点树与事件日志；
6. 阶段 3 验证：冷启动高德地图，开屏出现含「跳过/关闭」的右上角按钮时，8 秒内自动点击一次；日志页可查看命中规则、节点文本、动作与点击结果。
7. 阶段 4 验证：首页或设置页关闭总开关后，冷启动高德不再触发点击；设置页可调整窗口（3-15 秒）与冷却（1-10 秒），全局参数只会更严格。
8. 阶段 5 验证：在「规则」页可开关/编辑/新建/删除规则（内置规则受保护）；导出 JSON 到文件管理器查看，再导入验证条数；重启应用后修改保留。
9. 阶段 6 验证：「学习」页输入目标包名开始学习 → 切到目标 App 手动点「跳过」→ 返回应用查看样本与候选规则 → 保存后冷启动目标 App 验证自动点击。

命令行构建（可选）：

```bat
gradlew.bat :app:assembleDebug
```

## 获取 APK

- **本地构建**：`gradlew.bat :app:assembleDebug` → 产物 `app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装；本仓库 `dist/` 亦有现成拷贝，不入库）；
- **云端下载**：给仓库打 `v*` 标签（如 `v0.1.0`）后，GitHub Actions 自动构建 APK 并发布为 Release 附件，手机浏览器可直接下载安装。

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

## 合规声明

本项目仅供个人学习与自用：不牟利、不上传截图/节点树/日志、不破解或修改目标 App、不承诺 100% 去广告。请遵守目标 App 用户协议与当地法律法规。
