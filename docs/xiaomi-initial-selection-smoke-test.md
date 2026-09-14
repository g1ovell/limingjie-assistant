# Xiaomi Initial Character Single-Tap Live Smoke Test

日期：2026-09-14  
分支：`compat/xiaomi-ldplayer`  
基线：`475e939 feat: add manual imported labyrinth routes`

## 范围

本阶段只允许一次真实 Xiaomi 初始选人 tap，目标为 `characterId=1091` 静流（情人节）。不选择 `1171` 或 `1011`，不点击“去邀请”，不进入地图，不执行战斗、遗物、事件、商店或网络登录。测试开始前没有发送手势；实机 tap 等用户明确回复“已停在0/3，可以点击”后才执行。

## 上一阶段为何没有 tap

上一阶段的 imported dry-run 已经识别出 `1091`，但 `LabyrinthEntryRecognitionSession.start(dryRun=false)` 对所有 `EXTERNAL_IMPORTED` source 直接返回“仅允许只读预演”。这是有意保护：外部 AutoPCR 文本没有服务器 `blockId`、完整 `allNodes` 图、`questId`、拓扑和 `enterId`，不能伪造为 `LabyrinthRouteJson` 去执行地图。

这条限制不代表初始角色 tap 需要这些字段。现有 `LabyrinthEntryActionPlanner` 在 `INITIAL_CHARACTER_SELECTION` 中只消费页面锚点、角色识别结果、角色卡片 `screenRect`、策略配置和当前 viewport；Accessibility 后端只接收由该矩形计算出的 `AutomationAction.Tap`。角色选择本身不访问地图节点、checkpoint 或 Bilibili API。

## 本阶段最小 capability 修复

生产代码现在允许 imported source 建立非 dry-run session，但只开放一个严格能力：

`EXTERNAL_IMPORTED + INITIAL_CHARACTER_SELECTION + 0/3 + 9个可信唯一角色 + 策略第一目标1091 + 当前矩形/坐标在1920×1080内 → 允许现有 planner 派发一次 tap`

任何其他页面、地图页面、非目标动作、缺少角色、重复 `characterId`、不可信识别、非 0/3、低页面置信度、越界框或越界坐标都会被拒绝。一次 tap 成功后 capability 锁死；手势失败也不会 retry。imported live 不自动启动游戏，等待当前 Xiaomi 包在前台。

地图 live execution 仍然被安全阻止：每个 imported live frame 在进入 persistence、session recovery、route handoff 或 `LabyrinthNodeSession` 前都会被限定为初始选人页；tap 后的地图页不会触发任何地图动作。`LabyrinthRouteJson`、Room schema、parser、SharedPreferences store、OpeningRosterPolicy、TeamScorer 和路线文本没有被放开或改写。

## 点击前安全条件

开始真正 tap 前必须重新读取一帧并同时确认：

- resolver 唯一得到 `com.bilibili.priconne.mi`；
- Accessibility connected=true，且 foreground package 为该 Xiaomi 包；
- MediaProjection 原生帧为 1920×1080；
- source 为 `EXTERNAL_IMPORTED`，accountId 仍为 null；
- 页面为 `INITIAL_CHARACTER_SELECTION`，分类器已通过生产页面门槛；
- 0/3 锚点高于 3/3 锚点，邀请按钮为 disabled 状态；
- 9 个可见角色均 `trusted=true`、未选择、`characterId` 唯一；
- 现有配置仍给出 `1091 → 1171 → 1011`，本次 next target 为 `1091`；
- `1091` 唯一对应一个 slot 和完整 `screenRect`，矩形及 tap 点均在原生 viewport；
- 测试开始时 `actionCount=0`。

生产点击公式沿用 planner：`x = left + width × 0.16`，`y = top + height × 0.86`。这不是固定角色坐标，也不是 Windows/雷电窗口坐标。

## 初始选人策略审计

初始选人没有调用 `LabyrinthTeamScorer` 或三人组合搜索。`LabyrinthEntryActionPlanner.planInitialCharacterSelection` 使用 `LabyrinthOpeningRosterPolicy`，按当前公会配置的三个槽位逐槽寻找当前视口中第一个可用的 `characterId`；拉比林斯配置的顺序是 `1091 → 1171 → 1011`。因此本阶段的“策略排名”是配置槽位顺序，不是每个角色的数值评分；项目没有为这 9 个初始候选生成单角色 strategyScore。

点击前原生帧的识别置信度如下（按识别置信度降序；策略目标仍按上面的固定槽位顺序决定）：

| 识别排名 | characterId | displayName | recognition confidence | 策略位置 |
|---:|---:|---|---:|---|
| 1 | 1049 | 静流 | 0.761822 | 不在拉比林斯方案 |
| 2 | 1091 | 静流（情人节） | 0.716871 | 第 1 槽，实际目标 |
| 3 | 1212 | 菈比莉斯塔（超负荷） | 0.713413 | 不在拉比林斯方案 |
| 4 | 1200 | 静流（黑暗） | 0.709802 | 不在拉比林斯方案 |
| 5 | 1171 | 静流（夏日） | 0.687567 | 第 2 槽 |
| 6 | 1337 | 菈比莉斯塔（阿尔法） | 0.661296 | 不在拉比林斯方案 |
| 7 | 1129 | 璃乃（仙境） | 0.651726 | 不在拉比林斯方案 |
| 8 | 1011 | 璃乃 | 0.643655 | 第 3 槽 |
| 9 | 1193 | 璃乃（圣诞节） | 0.635104 | 不在拉比林斯方案 |

这解释了为什么实际目标是 `1091` 而不是识别置信度最高的 `1049`：目标由已确认的公会方案 `characterId` 决定，绝不按显示名或识别置信度替换。

## 设备准备与证据

设备：`emulator-5554`。用户必须手动确认外部路线有效、开启无障碍和屏幕捕获，并让 Xiaomi PCR 停在 `INITIAL_CHARACTER_SELECTION 0/3`，保持游戏前台。证据目录位于仓库外：

`C:\Users\ALEINWARE\Desktop\limingjie_clone\work\xiaomi-single-tap\`

实际证据目录保存了 `before.json`、`before.jpg`、`planned-action.json`、`gesture.json`、`after-select.json`、`after-deselect.json`、`after-deselect.jpg`。证据目录为 Git 仓库外的 `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\xiaomi-single-tap\`；其中只记录页面、角色识别、source、目标框、坐标、动作计数和后续状态，不记录账号、密码、Cookie、token 或 session。仓库内已有的 `work/` 构建日志仍保持未跟踪。

## 当前代码验证

- targeted tests：**55/55 通过**，包括新增 `ImportedOpeningLiveActionGateTest` 4 项、`ManualLabyrinthRouteTest` 19 项以及上一阶段相关测试；覆盖 opening capability、地图页拒绝、重复/缺失/不可信角色、越界框、非 0/3、外部 live session 和零动作地图安全门。
- lint：**0 errors、12 warnings**；warnings are pre-existing manifest/overlay/capture/accessibility items and no new warning from the capability gate after the final cleanup.
- assembleDebug：**成功**；APK `android/app/build/outputs/apk/debug/app-debug.apk` generated with the low-memory Gradle parameters.
- Installation: **Success** with `adb -s emulator-5554 install -r`; no app/game data was cleared. MainActivity cold start returned `Status: ok`, process remained present, and crash buffer was empty. Accessibility enabled-setting still contains `LandosolAccessibilityService`.
- 既有 dry-run 证据已证明：9/9 识别、页面置信度约 `0.9945115076`、计划 `1091 → 1171 → 1011`、actionCount=0。

## 实机单次点击结果

- 设备：`emulator-5554`；点击前台包：`com.bilibili.priconne.mi`；Accessibility 服务已连接；MediaProjection 原生帧为 `1920×1080`。
- 点击前状态：`INITIAL_CHARACTER_SELECTION`，页面置信度 `0.9945115076222313`，用户确认 `0/3`；source=`EXTERNAL_IMPORTED`；actionCount=0。
- 现有策略计划：`1091 → 1171 → 1011`，本次唯一目标为 `characterId=1091`、显示名 `静流（情人节）`，slot=`available_row_1_2`。
- 目标框：`left=335, top=283, width=195, height=195`；Accessibility 手势坐标：`(366.2, 450.7)`，来自 `left + width×0.16`、`top + height×0.86`，位于 `1920×1080` 原生 viewport 内。
- `LandosolAccessibilityService` 日志显示恰好一次 `派发点击` 和一次 `手势完成`；项目状态为 `actionCount=1`、`openingGestureDispatched=true`。没有第二次项目手势，也没有 adb input。
- 用户现场观察到游戏从 `0/3` 变为选中情人节静流（即 `1/3`），随后手动再次点击同一卡片取消，回到 `0/3`。这次取消不是助手发送的动作。
- 取消后的最新 MediaProjection 帧仍为 `INITIAL_CHARACTER_SELECTION`、`0/3`；截图仅含 Android 游戏 viewport（无 Windows 标题栏、雷电工具栏或窗口边框）。由于用户在下一次 dashboard 轮询前完成了取消，仓库外证据未捕获中间 `1/3` 帧；`after-select.json` 明确标记了这一限制，没有伪造截图或识别结果。
- 项目会话随后被停止；游戏保持在 0/3，未点击“去邀请”，未进入地图或后续流程。crash buffer 无 `FATAL` / `AndroidRuntime` 新异常。

## 结论与剩余风险

本阶段修复只解除 imported source 的初始角色单次动作能力，未解除地图执行能力。实机已证明目标映射和一次 Accessibility tap 生效；中间 `1/3` 画面由用户现场确认，未被 dashboard 轮询保存。取消后的状态恢复为 0/3，且没有第二次项目手势。

真实 tap 完成后已确认页面仍为 `INITIAL_CHARACTER_SELECTION`、用户观察到 `0/3 → 1/3` 且目标为 `1091`，随后人工取消回到 `0/3`；没有“去邀请”或地图动作，项目只派发一次手势并已停止。

本阶段不 commit、不 push。
