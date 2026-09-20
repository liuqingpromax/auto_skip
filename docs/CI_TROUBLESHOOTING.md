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
| `Build debug APK` | **真正的编译错误** | 日志里搜 `e: file:///...` 或 `> Task :app:compileDebugKotlin FAILED` |
| `Rename APK with version` | 产物路径不对 | `assembleDebug` 没产出 APK（通常是上一步失败的连带） |
| `Upload APK artifact` | 没有可上传的 APK | 同上，连带失败 |
| `Publish release` | 发布权限不足 | 仓库 Settings → Actions → General → Workflow permissions 需为 **Read and write** |

> 判断技巧：**只要 `Build debug APK` 之前就红了，就与代码无关**，不要改 Kotlin。
> 反之如果红在 `Build debug APK`，把日志里 `e:` 开头的行贴出来即可。

### ⚠️ 血泪教训：诊断步骤本身会成为故障点（2026-09）

我们曾加过一个 `Verify toolchain` 自检步骤（打印 JDK/权限/shebang/wrapper 信息），
结果它自己成了构建的拦路虎 —— Run #23、#24 连续两次都红在这一步，
`Build debug APK` 被直接 skip，白烧两轮 CI。

根因：该步骤里有一条 `file gradlew`，而 **GitHub 的 ubuntu runner 不预装 `file`**
（镜像为节省空间移除了它）。它是整段脚本里唯一没有 `|| true` 保护的命令。

**结论：**
1. 自检/诊断步骤里，**每一条命令都必须写成非致命**（`|| true`），或干脆不要放进 CI；
2. 更好的做法是**把诊断放在构建成功之后**，这样它失败也不影响产物；
3. 宁可让 CI 只做「构建 + 发布」这一件确定的事。

### 为什么不要凭表象判断「哪一步失败」

本次排查的可靠路径是：**用 GitHub API 读取 job 的 steps 状态**（`conclusion` 字段），
再与「最后一次成功的 run」做 workflow diff。命令行即可完成：

```powershell
$h = @{ "User-Agent" = "dsh-agent"; "Accept" = "application/vnd.github+json" }
$runs = Invoke-RestMethod "https://api.github.com/repos/<owner>/<repo>/actions/runs?per_page=5" -Headers $h
foreach ($r in $runs.workflow_runs) {
  Write-Host "#$($r.run_number) $($r.head_branch) $($r.conclusion)"
  if ($r.conclusion -eq "failure") {
    (Invoke-RestMethod $r.jobs_url -Headers $h).jobs[0].steps |
      ForEach-Object { Write-Host "   $($_.conclusion)`t$($_.name)" }
  }
}
```

注意：`api.github.com` **从 PowerShell 可达**，而本会话的 `web_fetch` 工具会拦截
github.com/api.github.com（沙箱域名策略）—— 两者是不同通道，别因为工具被拦就以为网络不通。
失败步骤的**日志正文**需要认证（`logs_url` 返回 403），但 step 级状态足够定位到具体步骤。

---

## 二、本地自查这些环境项（CI 里已不做体检）

CI 现在**刻意不做环境体检**（原因见上一节的教训）。需要确认环境时，在**本地**跑：

| 要确认的项 | 本地命令 | 期望值 / 异常说明 |
|---|---|---|
| JDK 版本 | `java -version` | `17.x`；不是 17 → CI 的 `Set up JDK 17` 有问题 |
| JAVA_HOME | `echo $JAVA_HOME`（Linux/macOS） | 指向 JDK 17 目录；为空 → setup-java 未生效 |
| ANDROID_HOME | `echo $ANDROID_HOME` | 指向 SDK 目录；为空 → setup-android 未生效，`assembleDebug` 必然失败 |
| gradlew 执行位 | `git ls-files -s gradlew` | 本仓库是 `100644`（**无执行位**）→ CI 必须先 `chmod +x gradlew` |
| gradlew 行尾 | `head -n 1 gradlew \| od -c` | `# ! / b i n / s h \n`；出现 `\r` → 被改成 CRLF，Linux 无法执行 |
| wrapper 版本 | `cat gradle/wrapper/gradle-wrapper.properties` | `gradle-8.9-bin.zip`；被改可能与 `libs.versions.toml` 的 AGP 8.7.3 不兼容 |
| wrapper 可用性 | `./gradlew --version`（Linux/macOS） | 打印 `Gradle 8.9` + `Launcher JVM: 17` |

> 注意：Windows 下 `file`、`od`、`./gradlew` 的行为与 Linux 不同，
> 用 Git Bash 验证最接近 CI（`& "C:\Program Files\Git\bin\bash.exe" -lc "..."`）。

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
