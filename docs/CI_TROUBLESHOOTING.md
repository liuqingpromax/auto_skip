# CI 排查指南（GitHub Actions 构建失败怎么定位）

> 面向本仓库：`.github/workflows/build-apk.yml`（构建 debug APK 并发布 Release）。
> 目的：**一次就能定位失败原因**，不要再靠反复提交试探。

---

## 一、先看「哪一步红了」

打开 https://github.com/liuqingpromax/auto_skip/actions ，点最新一次运行，
左侧步骤列表中第一个红色 ✗ 就是根因所在。对照下表：

| 失败的 step | 含义 | 常见原因 |
|---|---|---|
| `Set up JDK 17` | JDK 环境未就绪 | 网络抖动；`distribution`/`java-version` 写错 |
| `Set up Android SDK` | SDK 命令行工具未安装 | action 版本问题；GitHub 网络抖动 |
| `Install required SDK components` | 缺 SDK 组件或 license 未接受 | `sdkmanager` 不在 PATH；组件名写错；license 未接受 |
| `Set up Gradle` | Gradle 缓存/安装失败 | 网络抖动 |
| `Verify toolchain` | **环境问题**（不是代码问题） | 见下方「体检输出怎么读」 |
| `Build debug APK` | **真正的编译错误** | 日志里搜 `e: file:///...` 或 `> Task :app:compileDebugKotlin FAILED` |
| `Rename APK with version` | 产物路径不对 | `assembleDebug` 没产出 APK（通常是上一步失败的连带） |
| `Upload APK artifact` | 没有可上传的 APK | 同上，连带失败 |
| `Publish release` | 发布权限不足 | 仓库 Settings → Actions → General → Workflow permissions 需为 **Read and write** |

> 判断技巧：**只要 `Build debug APK` 之前就红了，就与代码无关**，不要改 Kotlin。
> 反之如果红在 `Build debug APK`，把日志里 `e:` 开头的行贴出来即可。

---

## 二、`Verify toolchain` 的输出怎么读

该步骤会打印环境全貌，逐项对照：

| 输出 | 期望值 | 异常说明 |
|---|---|---|
| `java -version` | `17.x` | 不是 17 → `Set up JDK 17` 那步有问题 |
| `JAVA_HOME` | 指向 JDK 17 目录 | 为空 → setup-java 未生效 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | 指向 SDK 目录 | 为空 → setup-android 未生效，后续 `assembleDebug` 必然失败 |
| `ls -l gradlew` | 有 `x` 权限（`-rwxr-xr-x`） | 无 `x` → 构建命令需用 `sh gradlew` 兜底 |
| `head -n 1 gradlew \| od -c` | `# ! / b i n / s h \n` | 出现 `\r` → 行尾被改成 CRLF，Linux 无法执行 |
| `gradle-wrapper.properties` | `distributionUrl=...gradle-8.9-bin.zip` | 版本被改 → 与 `libs.versions.toml` 的 AGP 8.7.3 可能不兼容 |
| `./gradlew --version` | 打印 `Gradle 8.9` + `Launcher JVM: 17` | 报错 → wrapper 下载失败或 JVM 不匹配 |

---

## 三、拿到日志后怎么贴

在失败的 step 里点开，**只需要三类信息**：

1. 失败的 step 名称；
2. 最后 20～40 行（GitHub 折叠的命令原文可忽略）；
3. 有 `error:` / `e: file:///` / `FAILURE:` / `Caused by:` 的行。

把这三样发给开发者即可精确定位，不需要整段复制。

---

## 四、本地等价复现（Windows）

CI 用的就是仓库里的 wrapper，本地等价命令：

```bat
set JAVA_HOME=<JDK 17 路径>
set ANDROID_HOME=<Android SDK 路径>
gradlew.bat :app:assembleDebug --stacktrace
```

**本机已知限制（2026-09 记录）**：E 盘为 pagefile 所在盘且仅剩约 0.2GB，
G1 GC 无法预留内存，会报：

```
os::commit_memory(...) failed; error='页面文件太小，无法完成操作。' (DOS error/errno=1455)
# Native memory allocation (mmap) failed to map ... Error detail: G1 virtual space
```

这不是代码问题。两种解法：

1. 清理 E 盘空间（推荐，根治）；
2. 临时改用低内存配置构建：

```bat
set GRADLE_OPTS=-Xmx768m -XX:+UseSerialGC -XX:MaxMetaspaceSize=256m
gradlew.bat :app:assembleDebug --no-daemon --max-workers=1
```

---

## 五、根治建议（可选）

如果 wrapper 相关问题反复出现，可以彻底绕开它：
在 `Build debug APK` 步骤改用 CI 已安装的 Gradle，而不是仓库里的 `gradlew`：

```yaml
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'

      - name: Build debug APK
        run: gradle :app:assembleDebug --no-daemon --stacktrace
```

这样 `gradlew` 的权限与行尾问题都不再影响构建；代价是本地与 CI 的启动方式不一致。

---

## 六、版本号约定（避免再次对不上）

- **唯一来源**：`app/build.gradle.kts` 的 `versionCode` / `versionName`；
- **git 标签必须与 `versionName` 一致**（`v0.2.5` ↔ `"0.2.5"`）；
- 发布前自检：
  ```bat
  gradlew.bat :app:assembleDebug
  aapt2 dump badging app\build\outputs\apk\debug\app-debug.apk | findstr /C:"package:"
  ```
  输出的 `versionName` 必须与标签相同。
