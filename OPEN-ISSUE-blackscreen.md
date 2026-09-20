# 未解决问题：点击「执行入口流程」后游戏黑屏

**状态：** 已修复并实测通过（2026-09-21 04:07）
**日期：** 2026-09-21 凌晨

---

## 结论（2026-09-21 04:07 实测）

**根因：`getLaunchIntentForPackage()` 返回的 Intent 带 `package=` 字段。**

桌面启动器建的游戏任务，其根 Intent 是 `act=MAIN cat=[LAUNCHER] cmp=…/SplashActivity`，**没有 package**。
AMS 在 `setTaskFromIntentActivity` 里用 `TaskRecord.isSameIntentFilter()`（即 `Intent.filterEquals`，会比 package）判定
助手的 Intent「不是同一个入口」→ `mAddingToTask=true` → 在既有任务顶上**新建 SplashActivity**（events 里 flags=0x10400000 =
NEW_TASK|BROUGHT_TO_FRONT 就是这条路径的指纹）→ PCR 弹 PermissionActivity 压在活着的 MainActivity 上，永不退出 → 黑屏。

adb 直接复现（游戏在后台、录屏与否无关）：

```
am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10000000 -p com.bilibili.priconne.mi -n com.bilibili.priconne.mi/com.bilibili.priconne.bili.SplashActivity
```
带 `-p` → 新建 Splash → Permission → 黑屏；去掉 `-p` → `Activity not started, its current task has been brought to the front`，正常。

**修法：** `LandosolToolboxApplication.gameLaunchIntent()` —— `getLaunchIntentForPackage(target)?.setPackage(null)?.addFlags(NEW_TASK)`，
两处启动点（`gameLauncher` 与 `Relauncher`）统一走它。实测「执行入口流程」：LAUNCH_GAME → 只有 `am_resume_activity MainActivity`，
无 Splash/Permission，画面正常，识别置信度 0.708。

**实验结果汇总：**

| 实验 | 结果 |
|---|---|
| E：录屏已开 + 只把任务提前（无 package 的 Intent） | **不黑**。录屏不是元凶 |
| G：MiActivity 在栈顶时启动 | 未复现出需要它；带 package 的 Intent 在干净栈上（只有 MainActivity）也黑。MiActivity 只是游戏 MainActivity 从 stop 恢复后小米 SDK 的例行弹窗，3~6 秒自行 destroy，无害 |
| 黑屏恢复 | 不用 force-stop，`input keyevent BACK` 一次即让 PermissionActivity finish，MainActivity 恢复显示 |

以下为修复前的分析记录，保留供参考。

---

## 症状

- 刷开局 ✅ 成功（纯协议，不涉及视觉）
- 读取开局 ✅ 成功
- 只读识别 ✅ 成功
- **执行入口流程 ❌ 一点就黑屏**

黑屏时游戏进程仍存活，`mResumedActivity` 停在 `com.bilibili.permission.PermissionActivity`，它 resumed 之后再也没有 finish / destroy。

## 历史同类故障

交接包 `03_reports/black-screen-diagnosis-original.md` 记录过 v007 时期的同一现象，结论是：

> 助手重开 launcher → Splash → PermissionActivity，MainActivity STOPPED 且 Surface 消失
> 修复边界：精确 MainActivity 前台时不重开；Permission/不明 Activity 拒绝；**不能归因 GPU 或录屏权限掉了**

当时也没查出 PermissionActivity 为何不退出。

## 已做的修复（已生效，但没解决本问题）

新增 `automation/GameClientLaunchGate.kt`（照搬 v008）+ 无障碍服务缓存前台 Activity 名。
实测闸门工作正常，但用户点按钮时前台是**助手自己的** MainActivity，所以判 `LAUNCH_GAME` 是正确的 —— 闸门挡不住这个场景。

## 实测排除的可能（不要重复）

| 实验 | 结果 |
|---|---|
| 手动启动游戏 | PermissionActivity **每次都出现**，存活约 2.9 秒后自行 finish。这是正常启动序列的一环，不是异常 |
| `monkey -p ... -c LAUNCHER` 把后台游戏调回前台 | 黑屏 |
| 手动点雷电桌面图标把后台游戏调回前台 | **正常**，日志是干净的 pause→stop→restart→resume，不新建任何 Activity |
| `am start -f 0x10000000`（助手用的 flag） | **正常**，`Activity not started, its current task has been brought to the front` |
| `am start -f 0x10200000`（桌面启动器的 flag，多 RESET_TASK_IF_NEEDED） | **正常** |

结论：**「把游戏调回前台」这个动作本身没有问题**，Intent flag 不是原因。

## 真实失败的完整时序（events 缓冲区实测）

```
03:37:20.555  游戏 MainActivity resumed（用户手动切到游戏）
03:37:21.778  创建 com.xiaomi.gamecenter.sdk.ui.MiActivity   ← 小米 SDK 界面
03:37:21.889  又创建一个 MiActivity
03:37:22.280  助手 MainActivity resumed（用户切回助手）
03:37:22.432  游戏 MainActivity + 两个 MiActivity 全部 stop
03:37:24.937  一个 MiActivity destroy
03:37:26.406  START com.android.systemui/.media.MediaProjectionPermissionActivity from uid 10069（助手）
03:37:26.441  助手 onActivityResult ← 录屏授权结果
03:37:26.456  notification channel=landosol-capture ← 录屏服务启动
03:37:27.387  助手日志：游戏启动决策：LAUNCH_GAME 前台Activity=com.landosol.toolbox.MainActivity
03:37:27.393  am_create_activity SplashActivity  flags=0x10400000
03:37:27.436  am_create_activity PermissionActivity
03:37:27.438  SplashActivity finish（app-request）
03:37:27.439  PermissionActivity resumed
03:37:31.498  剩下那个 MiActivity 才 finish/destroy
              ——— PermissionActivity 此后再无 finish / destroy ———
```

## 两个关键差异（对比 adb 测试时的正常表现）

**1. 这次是「新建 Splash」而非「热恢复」。**
adb 测试时系统只是把任务提到前台，不新建 Activity。这次却走了完整的 Splash→Permission。
原因：游戏任务栈顶被两个 `com.xiaomi.gamecenter.sdk.ui.MiActivity` 占着（03:37:21 创建，到 03:37:31 才清完），系统匹配不上启动器 Intent，只能重走启动序列。

**2. 录屏在游戏启动前 1 秒就开始了。**
助手是**先申请并开启 MediaProjection，再去启动游戏**。而只读识别时游戏本来就在前台，顺序相反 —— 所以只读没事。

权限页在手动启动时 2.9 秒自行退出，这次永远不退。**录屏先于游戏启动**是目前唯一对得上的变量。

## 下一步该做的实验

**实验 E（判断录屏是否是元凶，不用改代码）**
1. 手动启动游戏到正常画面
2. 切到助手，跑一次只读识别（会申请录屏但不发启动 Intent），等录屏起来
3. 手动从雷电桌面切回游戏
4. 黑不黑屏？黑 → 录屏本身是元凶；不黑 → 是「录屏已开 + 助手发启动 Intent」的组合

**实验 F（土办法，验证临时工作流）**
黑屏状态下 `am force-stop com.bilibili.priconne.mi`，然后手动打开游戏（录屏仍在跑）。
若游戏正常起来且助手流程能继续 → 可用的临时工作流是「助手先起录屏，人工把游戏弄到前台」。

**实验 G（小米 SDK 的 MiActivity 是否是元凶）**
MiActivity 在游戏切前台时出现、十秒后才清完。它占据栈顶是导致「新建 Splash」的直接原因。
可测：等 MiActivity 完全 destroy 之后再触发流程，看是否还黑屏。

## 如果确认是时序问题，修改方向

`LabyrinthEntryRecognitionSession` 的非 dryRun 分支里，`gameLauncher` 与 capture 启动的先后顺序需要调整：
**先把游戏拉到前台并等它稳定（MainActivity resumed 且 Surface 就绪），再申请/启动 MediaProjection。**

注意：只读分支跳过 gameLauncher，所以它一直是好的 —— 这本身就是「顺序有问题」的旁证。

## 提醒

- 每次复现都要 `adb logcat -b all -c` 清缓冲，并用 `adb logcat -b events -d | Select-String "priconne|landosol"` 取证（Activity 事件只在 events 缓冲区）
- 复现前确认刷开局循环已停止，避免 retire 持续消耗账号开局
