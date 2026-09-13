# Xiaomi Character Recognition

日期：2026-09-14；基线 `2c260f1`，分支 `compat/xiaomi-ldplayer`。离线回归和实机只读复核均已完成，9/9 角色版本正确；未复现需要修复的生产代码问题。没有自动点击、选人、战斗或网络登录。

## Root Cause

**当前未复现用户所述“具体版本识别失败”，不能编造根因或为了交差修改 matcher。** 使用基线的正式属性资料和 801 张头像模板，附件窗口截图提取游戏视口后，初始选择页和全部 9 张角色卡均识别成功。未修改阈值或业务代码。

诊断第一次遗漏正式应用的角色属性资料时，9 张中 8 张通过，璃乃（圣诞节）最高分 0.623741，但与第二候选的分差 0.014276 小于 0.045，被正确拒绝。加入 Application 本来就传入的角色属性后，分差变为 0.165663，9/9 通过。这是诊断配置差异，不是已证明的 Android 应用 Bug。随后正式 APK 的 MediaProjection 实测同样 9/9 通过；不能把资料缺失、动画或 UI 展示猜测写成历史失败根因。

附件是 1489×849 的 Windows 雷电窗口截图，带标题栏、工具栏和按键提示，并非原生 1920×1080。诊断显式提取 `(0,34,1448,815)` 游戏视口，再按最近邻重采样到 1920×1080；按键提示保留。未把缩放后的置信度宣称为原生帧置信度。原始图片、归一化图片和卡片裁剪均只保存在仓库外。

## Recognition Pipeline

1. `MediaProjectionCaptureService` → `CaptureFrameBus` → `LabyrinthEntryRecognitionSession`。
2. `AndroidLabyrinthEntryFrameProcessor.create` 从 `AndroidLabyrinthBattleTeamTemplateLoader` 加载角色图，创建共享 `LabyrinthCharacterIconMatcher`。
3. `LabyrinthEntryFrameProcessor.process` 检测页面；初始全角色列表进入 `LabyrinthBattleTeamRecognizer.recognizeOpening`。
4. `measureLayout` 检测卡片布局；`observeSlot` 检查可见区域与卡片存在，取头像矩形：卡片左上各内缩 6 px，边长减 14 px。
5. matcher 比较头像中心带（模板 y=20..99），屏蔽姓名/边缘/选中标记；先粗排，再结合颜色、亮度和梯度评分。属性徽章只过滤已知不匹配属性，不单独决定角色身份。
6. 同一 `characterId` 的星级图只保留最佳图；不同 `characterId` 竞争。通过 0.35 分数门槛及 0.045 对手分差后返回具体角色对象。
7. `LabyrinthRoleDecisionDataParser` 的 `profiles[characterId]` 将身份接入原有评分器。

战后 3 选 1 使用 `LabyrinthCharacterRecognizer.recognizeRoleRewardChoices` 的另一套固定区域，但共享同一头像 matcher。`AndroidLabyrinthRoleRewardNameResolver` 仅对该类奖励槽的模糊候选用 OCR 辅助校验；本初始列表不运行 OCR。文件 `AndroidLabyrinthCharacterTemplateLoader` 的 6 张姓名模板属于旧测试路径，不是当前生产头像库，不能误判为“只有 6 个角色模板”。

## Unit Identity Model

项目正式字段：`CharacterResource.id`、图标 `ownerId`、识别/评分 `characterId`（字符串）。master CN 的 `unit_data.unit_id` 是另一字段，项目导入器通过 `unit_id // 100` 对应 characterId；末两位不能与季节/具体角色版本混淆。实测读取助手公开 master CN 数据库，确认下面的 ID 映射。未读取账号数据库或网络凭据。

## Initial Selection Result

离线页：`INITIAL_CHARACTER_SELECTION`，confidence=0.6729095161。9 个可见槽位全部检测并接受；以下数值由实际 Kotlin 代码运行输出。

| slot | boundingBox (x,y,w,h) | characterId | master unit_id | displayName | bestScore | secondCandidate | secondScore |
|---|---|---|---|---|---|---|---|
| row1_1 | 125,281,195,195 | 1049 | 104901 | 静流 | 0.698265 | 铃奈 | 0.349371 |
| row1_2 | 336,281,195,195 | 1091 | 109101 | 静流（情人节） | 0.715196 | 美穗(少女与战车) | 0.469456 |
| row1_3 | 547,281,195,195 | 1171 | 117101 | 静流（夏日） | 0.679589 | 惠理子（夏日） | 0.424841 |
| row1_4 | 759,281,195,195 | 1200 | 120001 | 静流（黑暗） | 0.758937 | 樱 | 0.490504 |
| row1_5 | 970,281,195,195 | 1212 | 121201 | 菈比莉斯塔（超负荷） | 0.582707 | 静流 | 0.414389 |
| row1_6 | 1182,281,195,195 | 1337 | 133701 | 菈比莉斯塔（阿尔法） | 0.645267 | 美空(圣诞节) | 0.292446 |
| row1_7 | 1393,281,195,195 | 1011 | 101101 | 璃乃 | 0.634929 | 凯露（插班生） | 0.474252 |
| row1_8 | 1605,281,195,195 | 1129 | 112901 | 璃乃（仙境） | 0.683297 | 美里 | 0.538533 |
| row2_1 | 125,493,195,195 | 1193 | 119301 | 璃乃（圣诞节） | 0.623741 | 香澄 | 0.458078 |

诊断 JSON 包含每槽的 ACCEPT/reason、阈值、前 5 个候选和评分资料绑定。候选探针按检测框重放，明确区别于生产代码可能使用的几何重试；本截图的分数可对应。输出目录：`C:\Users\ALEINWARE\Desktop\limingjie_clone\work\xiaomi-character-recognition\baseline\`。

## Character Variant Handling

没有发现按中文基础名覆盖的逻辑。静流 1049、情人节 1091、夏日 1171、黑暗 1200 均独立输出。新增测试刻意把 1091/1200 的 displayName 都设为“静流”，验证 matcher 仍同时保留并正确识别两个身份；并将真实 1091/1200 评分记录同时输入 TeamScorer，验证不被重复身份检查拒绝。

## Resource Coverage

正式图标索引 801 张 / 369 个身份，795 PNG、6 WebP；本截图 9 个身份全部有资源和评分记录。普通静流、普通璃乃各 3 张星级图，其余各 2 张。JVM ImageIO 不支持 WebP，因此只在仓库外用现有 Pillow 将 6 张 WebP 无损转为 PNG 缓存；Android loader 自身未改。来源及复现过程已有 `icons.json` 和 `tools/import_game_icons.py`，没有从用户截图制造正式模板，也没有新增渠道资源包。

## Changes

- 新增 `XiaomiCharacterDiagnosticTest.kt`：可选本地截图回归，输出 JSON/裁剪；同基础名不同角色身份测试。
- 新增本文档；上一阶段 `xiaomi-vision-smoke-test.md` 原有未跟踪内容保留。
- 仓库外新增运行脚本、WebP 缓存、原始截图、诊断 JSON、公开 master DB 副本及其 9 个 unit 映射。
- 无生产代码、评分权重、阈值、网络协议或正式模板修改。

## Tests

完整正式配置的离线诊断已通过（9/9）。lintDebug：0 errors、51 warnings、1 information。定向测试 24/24 通过：LabyrinthBattleTeamRecognitionTest 18、LabyrinthCharacterRecognitionTest 4、XiaomiCharacterDiagnosticTest 2（含真实截图逐槽 ID 断言和同名不同身份回归）。assembleDebug 成功，组合执行 1m37s。没有生产改动，因此生产构建任务复用未变的输出。

复用原 JDK17/SDK/Gradle 缓存；仓库外入口 `work/verify-xiaomi-character.ps1 -Validate`，保持 `--no-daemon --max-workers=1 --no-parallel` 和 1536 MB JVM 上限。环境变量 `XIAOMI_CHARACTER_SCREENSHOT`、`XIAOMI_CHARACTER_OUTPUT` 指定截图与仓库外输出；`XIAOMI_CHARACTER_VIEWPORT` 显式描述窗口裁剪；`XIAOMI_CHARACTER_WEBP_CACHE` 指定缓存；`XIAOMI_CHARACTER_EXPECTED_IDS` 可选断言逐槽身份。不指定截图时仅跳过可选图片诊断，身份回归仍运行。

## Device Validation

emulator-5554 在线，Android 画面为 1920×1080。已执行 install -r，返回 Success；正常启动 MainActivity，Status: ok，TotalTime=1600ms。用户手动开启只读识别并停留初始选人 0/3 页面后，系统 resumed activity 为 `com.bilibili.priconne.mi/com.bilibili.priconne.MainActivity`，MediaProjection 所有者为助手。现有诊断接口通过本地 18765 端口读取；未清游戏数据或登录状态，未使用页面点击命令。

实测 timestamp=1789319287489，page=`INITIAL_CHARACTER_SELECTION`，pageConfidence=0.9945115076222313；openingViewport=`STABLE`，visibleCharacterCount=9。全部 `trusted=true`、`selected=false`，身份顺序与附件离线结果完全一致，`dryRun=true`、`actionCount=0`。

| characterId | 具体版本 | 实机 confidence | 实机 rivalMargin |
|---|---|---|---|
| 1049 | 静流 | 0.761822 | 0.429164 |
| 1091 | 静流（情人节） | 0.716871 | 0.214044 |
| 1171 | 静流（夏日） | 0.687567 | 0.240792 |
| 1200 | 静流（黑暗） | 0.709802 | 0.167432 |
| 1212 | 菈比莉斯塔（超负荷） | 0.713413 | 0.335394 |
| 1337 | 菈比莉斯塔（阿尔法） | 0.661296 | 0.367412 |
| 1011 | 璃乃 | 0.643655 | 0.191721 |
| 1129 | 璃乃（仙境） | 0.651726 | 0.121545 |
| 1193 | 璃乃（圣诞节） | 0.635104 | 0.138547 |

仓库外 `work/xiaomi-character-recognition/device-recognition.json` 保存实测值，`device-frame.jpg` 保存原生尺寸截图。已视觉检查：初始选择 0/3、全部 9 卡可见、没有窗口边框或工具栏。JSON 与 JPEG 为分别读取的端点，页面内容相符，但不宣称帧级原子配对。日志检查未匹配本次助手进程的 FATAL、派发点击、手势完成/取消或 SecurityException。

达到本截图验证目标后已 force-stop 助手，系统投影状态为 null；未停止游戏。此操作也结束无障碍服务，本报告的运行时结果描述停止前状态，下次测试应重新确认服务连接。没有为“修复”而修改已正常工作的识别逻辑。

## Remaining Unknowns

- 指定截图与同页实机均成功，没有复现历史识别失败，不能宣布某个生产 Bug 已被修复。若再次发生，需保存失败时原生帧与对应识别日志。
- 战后角色奖励页面没有本轮截图，尚未验证。
- 识别身份已具备交给评分器的基础；可以另行审查“选择一个角色”的评分与安全点击方案，但本轮未验证动作坐标/手势，也未运行自动选人。
