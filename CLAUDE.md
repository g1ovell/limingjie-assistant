# 黎明界助手 — 小米服 / 雷电模拟器适配

本文件是给 Claude Code 的常驻说明。开工前整篇读完。

---

## 1. 这是什么项目

上游 `wbero/limingjie-assistant` 是一个安卓端的《公主连结》黎明界自动化助手，原生只支持 **B 服**（`com.bilibili.priconne`），只在 MuMu 模拟器测过。

我们做的是让它在**雷电模拟器 + 小米渠道服**（`com.bilibili.priconne.mi`）上跑起来。上游永远不会自带这个适配，每次升级都要我们自己维护。

**License：CC BY-NC-SA 4.0，不是 OSI 开源许可。**

## 2. 路径

| 项 | 路径 |
|---|---|
| 仓库根 | `C:\Users\ALEINWARE\Desktop\limingjie_clone` |
| **当前工作树** | `limingjie-upstream-v109`（detached HEAD @ `d0df39c`，v1.0.9） |
| 旧工作树 | `limingjie-patchstack-v103`（不要动） |
| APK 输出 | `android\app\build\outputs\apk\debug\app-debug.apk` |
| 历史交接包 | `limingjie-project-handoff-20260920.zip`（含全部历史报告与 v008/v103 源码快照） |
| 阶段A 日志 | `v109-stage-a-worklog-20260920.md` |
| 临时目录 | `_stageB\`（不在 git 内，可随时删） |

## 3. 构建环境（每开新 PowerShell 窗口都要重设）

工具链在任务目录里，**没有改全局 PATH**：

```powershell
$tc  = "$env:USERPROFILE\Documents\Codex\2026-09-13\fork-clone-android-re-dive-https\work\toolchain"
$jdk = "$tc\jdk-17.0.20.1+1"
$sdk = "$tc\android-sdk"
$adb = "$sdk\platform-tools\adb.exe"
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = "$jdk\bin;$env:PATH"
```

构建**只用** `.\gradlew.bat :app:assembleDebug`（在 `android\` 目录下）。
**不要**用 `build-local.ps1` —— 它带 lint + 单元测试，慢且会被历史失败项卡住。

设备名 `emulator-5554`。安装用 `& $adb install -r <apk>`（debug 签名一致，不用卸载）。

## 4. 雷电模拟器注意事项

- **ROOT**：需在「软件设置 → 其他设置 → ROOT权限」勾选并**重启模拟器**。`adb root` 无效（production build）。
- **`su` 必须嵌套引号**：`& $adb shell "su -c 'ls /data/...'"`。写成 `su -c "..."` 会被 PowerShell 剥掉引号，`su` 把路径当用户名，报 `Unknown id`。
- **Activity 事件在 events 缓冲区**：`adb logcat -d` 默认只给 main/system/crash，看不到 `am_create_activity`。要用 `adb logcat -b events -d`。
- 中文日志在 PowerShell 里可能因 codepage 匹配不上，过滤时优先用 ASCII 关键词，或先 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`。

## 5. 工作原则（用户明确要求，请严格遵守）

1. **先拿证据再动手。** 有 logcat 就先抓 logcat，不要凭推测改代码。
2. **优先复用历史已验证的修法**，不要自己发明新方案。交接包里有 v008/v103 两版源码快照可对照。
3. **一次只改一层**，改完立刻构建+安装+验证，不要一次堆一堆改动。
4. **不要基于旧快照假设当前代码结构。** v1.0.9 是上游独立开发线，历史补丁大多不在其中 —— 先读 v109 的当前实现再动手。
5. 给用户的 PowerShell 命令，**注释和命令分开写**，别粘在同一行。
6. **精准检索，别整文件通读。** 用户在意额度。
7. **改完文件必须核对行号再构建。** 曾多次出现「以为写了其实没写」，导致基于旧 APK 的日志做判断。构建后 `LandosolToolboxApplication.kt` 里两个 `LabyrinthDebugDashboardServer` 的编译告警行号是天然的版本指纹，每次改动都会位移。

## 6. 安全红线

- **不要无人值守跑刷开局循环。** `labyrinth/retire` 会**真实撤退用户的开局**。曾经一次跑了 22 分钟、上千次 retire。任何会产生账号副作用的动作，先问用户。
- **凭据绝不进日志、脚本或提交。** 用户的 `uid` / `access_key` 只存在于 APP 的 Keystore 里。诊断日志只打长度和形态，不打值。
- 不要 `git reset --hard` / `git clean -fd`。
- 不要用旧 payload 覆盖最新文件。
- 不伪造 server ID，不清 pending。
- 不要把"任务当时测试通过"等同于"现在仍然通过"。

## 7. 已完成的工作（阶段A + 阶段B，全部实测通过）

### 阶段A：冷启动闪退（已修复）

首页 Compose 读 `labyrinthController` → 级联求值整条 lazy 链 → 最底层查 B 服游戏包 → `NameNotFoundException`。

修法：`LabyrinthController` 的 `loginCoordinator` 改为 `() -> BilibiliNativeLoginCoordinator` provider + `by lazy`；Application 传 `{ bilibiliNativeLoginCoordinator }`。共 2 文件 5 行。

### 阶段B：小米渠道适配 + 渠道服登录（已打通）

**最关键的结论：B 服与渠道服是两套完全独立的服务器。**

对照 `cc004/autopcr` 的 `autopcr/sdk/sdkclients.py`：

| | `bsdkclient`（B服） | `qsdkclient`（渠道服） |
|---|---|---|
| 网关 | `l3-prod-all-gs-gzlj.bilibiligame.net` | **`l1-prod-uo-gs-gzlj.bilibiligame.net`** |
| RES-KEY | `ab00a0a6dd915a052a2ef7fd649083e5` | **`d145b29050641dac2f8b19df0afe0e59`** |
| PLATFORM-ID | `2` | **`4`** |

其余不变：`PLATFORM=2`、`CHANNEL-ID=1`、sdk_login body 的 `channel=1`。

**登录模式：渠道服凭据直通。** 用户在账号页填的 loginId/password 就是 `uid` / `access_key`（和 AutoPCR 规则一致），助手跳过 B 服 SDK 登录那一层，直接构造 `SdkSession` 进入游戏服登录。`deviceSeed` 传 uid，使 `DEVICE-ID = md5(uid)` 与 AutoPCR 一致。

**已改动的文件**（均在 `limingjie-upstream-v109`）：

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | `<queries>` 增加小米包 |
| `automation/GameClientProfile.kt` | 新建（照搬 v008）：两包识别，零安装/双安装均拒绝 |
| `automation/GameClientLaunchGate.kt` | 新建（照搬 v008）：游戏已在 MainActivity 前台时不重发启动 Intent |
| `automation/accessibility/LandosolAccessibilityService.kt` | 缓存前台 Activity 类名（仅 TYPE_WINDOW_STATE_CHANGED 更新） |
| `LandosolToolboxApplication.kt` | 渠道解析、5 处包名取值替换、直通开关、网关端点选择、启动闸门、诊断日志 |
| `protocol/bilibili/AndroidGameProtocolProfileFactory.kt` | 包名 / resKey / platformId 改为构造参数 |
| `protocol/bilibili/BilibiliGameGatewayFactory.kt` | 新增 `ChannelEndpoint`（BILIBILI / CHANNEL_UO） |
| `protocol/bilibili/BilibiliNativeLoginCoordinator.kt` | 直通模式、SDK 依赖 provider 化、堵住回落、保留旧构造器 |
| `protocol/bilibili/BilibiliGameProtocolGateway.kt` | 诊断日志、`channel` 字段名、null 字段过滤、platform/channel 可配 |

**安全边界**：`AndroidAccessibilityActionBackend(expectedPackageName: String?)` 中 `null` 的语义是「不校验前台包」。渠道解析失败时**不能传 null**（会导致自动化对任意前台应用点击），要传永不匹配的哨兵包名 `com.landosol.toolbox.no-game-client`。

### 阶段C：「执行入口流程」黑屏（已修复，2026-09-21）

根因不是录屏、不是 MiActivity、不是 Intent flag，而是 `getLaunchIntentForPackage()` 返回的 Intent 带 `package=` 字段：
AMS 用 `filterEquals` 比对任务根 Intent，package 不等 → 在既有任务顶上新建 Splash → PermissionActivity 压住活的 MainActivity 永不退出。
修法：`LandosolToolboxApplication.gameLaunchIntent()` 对 Intent `setPackage(null)`，两处启动点共用。详见 `OPEN-ISSUE-blackscreen.md`。
黑屏恢复只需 `adb shell input keyevent BACK`。

### 阶段D：「执行入口流程」跑通全程（2026-09-21 05:23 实测：从标题页到「已完成最终结算并返回黎明界主页」）

黑屏修好后又撞到三个上游逻辑问题，均已修并实测：

| 症状 | 根因 | 修法 |
|---|---|---|
| 长战斗（Boss 三连战 >30 s）中「路线阶段未知页面超过限定时间」停机 | 战斗等待器只在编组页 `START_BATTLE` 武装；经 `BATTLE_START_CHALLENGE`（挑战按钮）开打的战斗没武装，撞 30 s UNKNOWN 超时 | `dispatchPostEntryTap` 里 `BATTLE_START_CHALLENGE` 执行成功后也 `battleWait.onStartExecuted()` |
| 冷启动在标题页「TITLE_WAITING_TAP 动作连续失败」停机 | 小米 SDK 冷启动登录让标题画面停留 10 s+，1.2 s 间隔 × 3 次在 4 s 内耗尽 | `LabyrinthEntryActionPlannerConfig.titleTapIntervalMillis = 5_000` |
| 挑战点击后 0.4 s 的陈旧 BATTLE_CHALLENGE 帧把刚武装的等待器清掉 | `LabyrinthBattleWaitPolicy` 的 3 s 宽限只认 BATTLE_TEAM_SELECTION | 宽限同时认 BATTLE_CHALLENGE（09:23 实测：挑战帧期间显示「等待战斗结算」，生效） |
| 会话在 Boss 三连战 WIN 汇总页上启动时卡死：「未知页面禁止启动点击」 | 汇总页识别为 UNKNOWN；入口阶段未完成，规划器拒绝点击；Boss「下一步」只在路线阶段处理 | `labyrinthBossSummaryOwnsResume()`：`battle.result.boss_summary.next_button` ≥ 0.68 即视为中途接管，置 `entryPhaseComplete` + BOSS 上下文（09:23 实测接管并点下一步） |

| 「入口识别失败：Failed to allocate … max allowed footprint 201326592」 | 会话运行期 Java 堆稳态 165~180 MB（模板/模型/角色库常驻，**不是泄漏**：12 分钟采样无单调增长，会话停止后回落到 13 MB），顶到 192 MB 默认上限 | `AndroidManifest.xml` `android:largeHeap="true"`（本机上限变 384 MB） |
| 会话直接在中途的角色加入页启动：「未经过初始邀请流程却进入角色加入页面」 | `existingRunResumeHandoffArmed` 只在「继续挑战」动作后武装 | `start()` 里 `resetNodeExecutionState()` **之后**武装（放前面会被它清掉——踩过），入口规划器执行第一个非继续挑战动作时解除 |
| Boss 三连战结束瞬间被「路线阶段未知页面超过限定时间」杀掉 | `handleBattleWaitFrame` 一看到 `nodeMoveConfirmation`/`relicDetailObservation` 就 reset 等待器；这两个颜色覆盖率启发式在 WIN 汇总页（白面板+蓝按钮）误报 | 等待器武装且页面 UNKNOWN 时忽略这两个观察（日志 `battle-wait: ignoring dialog false positive`）。**未经实战验证**，需要再打一场 Boss |

诊断日志新增 tag：`LandosolCapture`（录屏停止原因）、`LabyrinthBattleWait`（armed / cleared / reset+caller）。

**全自动循环（批量自动执行）是下一目标。** 前提：内存稳（已 largeHeap）、中途任意页面重启会话都能接管（已覆盖：地图、路线页、Boss 汇总、角色加入/奖励）。批量流程含 retire，开跑前必须问用户。

**运维经验**
- 助手自带调试面板：`adb forward tcp:8765 tcp:8765` 后 `GET http://127.0.0.1:8765/api/state`，不用切前台就能看页面/置信度/动作数/消息。
- `adb install -r` 后无障碍服务可能不重绑（`dumpsys accessibility` 里 `services:{}`），settings/pm 各种切换都无效，只有 `adb reboot` 能救。**安装时让助手自己在前台**，实测三次都正常重绑。
- 游戏 MainActivity 从 stop 恢复后小米 SDK 会弹 1~2 个透明 `MiActivity`，3~6 s 自毁，无害。

### 现在可用的功能

**刷开局已完全可用** —— 纯协议功能，不依赖视觉识别。登录五步全通：
`source_ini/index → get_maintenance_status → tool/sdk_login → check/game_start → load/index`。

**执行入口流程已跑通全程**（阶段D）：冷启动 → 登录 → 进迷宫 → 节点/战斗/自动编组/遗物/商店/角色获得 → 最终结算 → 返回主页，236 次动作无人工干预。

## 8. 诊断日志

网关原本一行日志都没有，这是排查初期最大障碍。现有 tag `LandosolGameProto`（分步骤 / stage / 服务器响应 / 请求字段名 / 凭据形态）和 `LandosolToolbox`（渠道、APP-VER、登录模式、启动决策）。

```powershell
& $adb logcat -d -s LandosolToolbox LandosolGameProto
```

失败信息里 uid / access_key 会被自动替换成 `<redacted>`。

## 9. 已被证伪的假设（不要重复尝试）

| 假设 | 结果 |
|---|---|
| `sdk_login` 字段名 `channel` vs `channel_id` | 改名无效（但已对齐参考实现，保留） |
| 6 个 null 验证码字段干扰 | 过滤掉，无效（已保留过滤） |
| APP-VER 应从维护响应纠正 | 维护响应里根本没这字段 |
| access_key 是 URL 编码态 | 解码是对的，但单独解码不足以成功 |
| 凭据含粘贴脏字符 | 是真的（整行 XML 被粘入），清理后仍失败 |
| SID 算法不一致 | `md5(sid + "c!SID!n")`，与生态一致 |
| 启动 Intent 缺 `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` | 0x10000000 与 0x10200000 实测都正常，排除 |
| 黑屏是录屏先于启动 / MiActivity 占栈顶 | 实验 E/G 均排除；真因是 Intent 的 package 字段（阶段C） |

**方法论教训**：前期只读了 `cc004/pcrjjc2`（**纯 B 服项目**）和 AutoPCR 的 README，没读 AutoPCR 的 `sdk/sdkclients.py`，渠道差异只存在于后者。**遇到"渠道/环境"类问题，先确认参考实现是否覆盖该渠道。**

GitHub API 限流时，用 `https://data.jsdelivr.com/v1/packages/gh/<owner>/<repo>@<ref>?structure=flat` 列文件树，再用 `raw.githubusercontent.com` 取单文件。

## 10. 未偿技术债

1. `AccountInputValidator` 对 alias / loginId / gameUid 做了 `trim()`，**唯独 password 没有** —— 应补上。
2. 双安装歧义分支无实机覆盖（设备上只装了小米包），应补单元测试。v008 快照里有现成的 `GameClientProfileResolverTest.kt` 可移植。
3. 诊断日志目前常开，发布前可考虑加开关。
