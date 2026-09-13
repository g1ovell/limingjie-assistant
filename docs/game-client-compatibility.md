# Game Client Compatibility - Part 1

检查日期：2026-09-13（Australia/Sydney）

本轮范围只覆盖 Android 游戏客户端识别、启动包、前台包检测、Accessibility 目标包和 Manifest 查询。没有实现 Xiaomi 登录、游戏网络协议、MediaProjection、视觉识别、黎明界自动执行或其他渠道适配。

## Hardcoded package audit

在上一阶段提交 `a030a3a` 上执行 `rg -n "com\\.bilibili\\.priconne" android`，共找到 19 行：

| 分类 | 位置 | 处理 |
|---|---|---|
| A. Bilibili 网络协议 | `protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt`、`AndroidGameProtocolProfileFactory.kt` | 保留 Bilibili-only；这些代码读取 B 服包版本/签名并组装 B 服协议 profile |
| B/C/D. 通用 Android 启动、前台、Accessibility | `LandosolToolboxApplication.kt`、`automation/accessibility/LandosolAccessibilityService.kt` | 改为解析后的 `GameClientProfile.packageName` |
| E. Manifest queries | `AndroidManifest.xml` | 保留 Bilibili，并加入 Xiaomi 包 |
| F. 测试夹具 | `ForegroundWindowTrackingTest.kt`、`AdbSessionResetBackendTest.kt` | 保留固定 Bilibili 值；这些测试描述 B 服/伪 ADB 命令，不是运行时渠道选择 |

本轮没有对协议目录做全局替换，也没有把测试夹具伪装成 Xiaomi 测试。

## GameClientProfile

新增 `automation/GameClientProfile.kt`：

```text
GameChannel.BILIBILI -> com.bilibili.priconne
GameChannel.XIAOMI   -> com.bilibili.priconne.mi
```

`GameClientProfileResolver` 只认识这两个已经确认的包名，不加入 Huawei、OPPO 或其他猜测包名。

解析规则：

| 已安装客户端 | 结果 |
|---|---|
| 只有 Bilibili | `Available(BILIBILI)` |
| 只有 Xiaomi | `Available(XIAOMI)` |
| 都没有 | `Unavailable` |
| 两个都有 | `Ambiguous`，要求显式选择 |

解析器没有随机选择，也没有默认强制 Xiaomi。当前应用还没有新增选择 UI，因此双装或都未安装时，通用启动和动作 guard 会安全拒绝；本轮不扩大 UI 范围。

## Runtime integration

`LandosolToolboxApplication` 在首次需要时通过 `PackageManager` 解析当前设备上的 profile：

- 视觉自动化的 `gameLauncher` 使用解析后的包名调用 `getLaunchIntentForPackage`。
- 会话失效重启使用同一解析结果。
- `actionTargetReady` 复用 package guard 比较当前前台包；未解析或前台未知时拒绝，避免 null == null 误判。
- `AccessibilityForegroundPresenceObserver` 使用解析后的包名。
- `AndroidAccessibilityActionBackend` 接收一个动态期望包名 provider；解析失败或双装时 provider 返回 null，动作被拒绝，不会向任意前台应用派发手势。

Manifest `<queries>` 现在同时声明 `com.bilibili.priconne` 和 `com.bilibili.priconne.mi`，没有改变其他权限或组件。

## Pure JVM tests

新增：

- `GameClientProfileResolverTest.kt`：覆盖仅 Bilibili、仅 Xiaomi、都未安装、双装歧义和两个包名常量。
- `GameClientPackageGuardTest.kt`：覆盖 Xiaomi 前台通过、Bilibili 前台被 Xiaomi profile 拒绝、未解析时拒绝。

没有引入 Robolectric 或新测试框架。

新增显式启用的 `androidTest/.../GameClientCompatibilitySmokeTest.kt`，仅在传入 `runGameClientSmoke=true` 时运行：读取 Application 实际 resolver、读取后端实际配置的期望包名、通过反射调用现有 `gameLauncher` 回调，并检查不同前台包的 guard。反射仅用于测试，不改变生产接口/UI；不调用 startAutomation、execute 或 perform，不发送手势。读取前台使用系统 dumpsys；若无障碍已连接，同时检查服务跟踪结果。读取前台不压制原有无障碍服务。

## Validation status

已完成的静态检查：

- `git diff --check` 通过。
- 协议目录的两处 Bilibili 包名保持不变。
- 通用 Application 启动/重启路径不再使用旧 `GAME_PACKAGE_NAME` 常量。
- Accessibility backend 不再硬编码单一运行时目标包。
- Manifest 同时声明两个已确认游戏包。

2026-09-13 本轮构建结果：

- 工作区检查：通过；当前未提交修改仍只属于 Game Client Compatibility - Part 1。
- `git diff --check`：通过。
- 首次真正编译发现 Application 两处启动回调把 GameClientProfile 传给只接受 String 的 getLaunchIntentForPackage；已最小修复为传入 profile.packageName。
- 同时修复 actionTargetReady 在未解析且前台未知时 null == null 返回 true 的问题，复用已存在 guard；补充 null/null 和已解析/null 测试断言。
- lintDebug：0 errors、51 warnings、1 information。原有警告保留。
- GameClientProfileResolverTest：5 tests，0 failures，0 errors。
- GameClientPackageGuardTest：3 tests，0 failures，0 errors。
- assembleDebug：成功；组合构建耗时 203.63 秒（Gradle 3m 23s），包含独立设备测试 APK 的构建。
- 生产 APK：android/app/build/outputs/apk/debug/app-debug.apk，90,924,030 bytes。
- SHA-256：cc16d6aecc112e4b4ad9864fd8754a7736e9447a45057e0b27635e2b9aadc5a2。
- 打包 Manifest：applicationId=com.landosol.toolbox、minSdk=26、targetSdk=35；queries 同时存在 Bilibili 与 Xiaomi 包，启动 Activity=MainActivity。

复用原工具链环境，执行命令：

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --no-parallel '-Dorg.gradle.jvmargs=-Xms64m -Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' lintDebug testDebugUnitTest --tests '*GameClientProfileResolverTest' --tests '*GameClientPackageGuardTest' assembleDebug assembleDebugAndroidTest
```

没有重新下载 JDK、SDK、Gradle，未修改全局配置或历史全量测试。

2026-09-13 重启电脑后完成设备验证（复用上述 APK，SHA-256 再次核对一致）：

| 项目 | 实测结果 |
|---|---|
| 设备 | emulator-5554；Android 9 / API 28；ABI list=x86_64,x86,arm64-v8a,armeabi-v7a,armeabi；1920×1080，density 280 |
| 客户端安装情况 | pm list packages priconne 只返回 com.bilibili.priconne.mi |
| APK 安装 | 助手和独立测试 APK 均使用 install -r，返回 Success |
| 助手冷启动 | force-stop、清日志、启动 MainActivity；Status: ok，TotalTime 1631 ms；等待 6 秒后 PID=3568，MainActivity 仍为 resumed activity，crash buffer 为空 |
| 实际 resolver | 读取当前 Application 的实际 resolver：Available(profile=GameClientProfile(channel=XIAOMI, packageName=com.bilibili.priconne.mi)) |
| 项目启动回调 | 单独调用现有 labyrinthEntryRecognitionSession.gameLauncher，返回 true；未使用 adb monkey 替代，未启动自动执行会话 |
| 实际前台包 | 测试中系统 resumed activity 的包名为 com.bilibili.priconne.mi；结束后复查仍是该包，Activity 为 com.bilibili.permission.PermissionActivity；未操作其权限页面或登录 |
| Accessibility guard | 读取实际 backend 的目标包 provider，调用生产代码 guard：Xiaomi=true，助手=false，com.android.launcher3=false；未解析目标也拒绝 |
| 无障碍服务边界 | enabled_accessibility_services 前后均为 null，isConnected=false；未启用或绕过权限。因此验证了运行时 package guard 和系统前台包，尚未验证服务自身的事件跟踪链路 |
| 设备测试 | GameClientCompatibilitySmokeTest：OK (1 test)，0.904 秒；automationSession=none，gestures=not invoked |
| 异常 | 最终 crash buffer 为空；AndroidRuntime/TestRunner 日志无 FATAL；本次助手测试 PID 5874 的日志未匹配 AndroidRuntime、FATAL EXCEPTION、NameNotFoundException、IllegalStateException |

首次启动 instrumentation 时，系统 events 日志显示新助手测试 PID 3650 在启动约 32 ms 后被以 `remove task` 原因终止，测试尚未输出结果，ADB 等待未结束；不是已捕获的 Java crash。该日志不能证明是谁触发任务移除。终止该次挂起的测试、停止助手并返回 Launcher 后，重新运行同一测试 APK 即通过，没有为此修改生产代码。首次观察到游戏前台也没有被用作通过证据，以上结果来自成功的第二次测试。

设备测试命令（先从桌面运行，助手已停止）：

```powershell
& 'C:\leidian\LDPlayer9\adb.exe' -s emulator-5554 shell am instrument -w -e class com.landosol.toolbox.automation.GameClientCompatibilitySmokeTest -e runGameClientSmoke true com.landosol.toolbox.test/androidx.test.runner.AndroidJUnitRunner
```

原始证据保存在仓库外 `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\game-client-evidence\`：install.txt、cold-start.txt、cold-pid.txt、cold-foreground.txt、cold-crash.txt、device-test.txt、game-foreground.txt、runtime-test-log.txt、exception-review.txt、first-instrumentation-exit.txt 和 accessibility-before/after.txt。未清除游戏数据、未重新登录、未发送游戏手势。

Manifest 内 Xiaomi queries 已核对，但本设备 API 28 不实施 Android 11 起的 package visibility 限制；较新 Android 上的可见性行为仍需另行实机验证。

## Scope boundary

本轮没有修改：

- `protocol/bilibili/` 中的 Bilibili SDK、游戏登录、AES、MessagePack、SID、REQUEST-ID
- Xiaomi SDK 或 Xiaomi token/session
- 账号数据库 schema
- MediaProjection、截图、OCR、视觉识别和战斗算法
- 黎明界 API、路线算法、Boss/遗物/角色策略
- Huawei、OPPO 或其他渠道

## Recommended next step

Part 1 在本次雷电环境的构建、冷启动、客户端解析、项目启动回调和无手势 package guard 验证已完成。已具备规划下一阶段 MediaProjection + 视觉识别测试的基础；下一阶段需要用户正常授予相应权限，若依赖无障碍前台跟踪，还需验证服务实际事件链路。当前游戏停留于其权限 Activity，不能宣称已进入游戏主界面。本轮到此停止，不执行下一阶段，不 commit、不 push。
