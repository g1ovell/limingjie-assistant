# Xiaomi Vision Smoke Test

日期：2026-09-13。基线：`2c260f1`，分支 `compat/xiaomi-ldplayer`。

## Status

已完成 Xiaomi 黎明界只读识别 smoke test。全程未发送 tap/swipe/back，未启动完整自动执行。

| 项目 | 当前证据 |
|---|---|
| Accessibility 是否启用 | 用户已手动开启；secure settings 中有 LandosolAccessibilityService |
| isConnected | 手动关闭再开启后，系统服务绑定恢复：PID=7159、received=true、hasBound=true、无 DEAD；系统已注册服务。助手权限页也显示“已授权且已连接” |
| Xiaomi accessibility event | 识别期间游戏为当前交互窗口，服务已连接并启用窗口状态/内容事件；未向服务增加日志，因此没有把内部 tracked package 字段冒充为独立事件日志。前台包的独立证据来自系统窗口与项目 resolver/guard；运行时 guard 对 Xiaomi=true |
| MediaProjection | 成功。现有 `MediaProjectionCaptureService` 建立 VirtualDisplay，`CaptureFrameBus` 连续收到帧；识别状态显示 received=75、recognized=75 |
| frame 尺寸 / timestamp / pixel format | 1920×1080；timestamp=1789301052304；ImageReader 输入 `RGBA_8888`，项目转换为 `ARGB_8888` Bitmap |
| screenshot 路径 | `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\xiaomi-vision-smoke\frame.jpg`，仓库外；文件大小 172273 bytes |
| 截图范围 | 已用图像检查确认只含 Android 模拟器内部游戏画面；没有雷电标题栏、右侧工具栏或 Windows 边框 |
| Vision 入口 | 现有 UI「只读识别」调用 `LabyrinthEntryRecognitionSession.start()`，默认 `dryRun=true`；现有 `AndroidLabyrinthEntryFrameProcessor` |
| 测试页面 / recognition / score | 识别日志确认 Xiaomi《黎明界》地图节点选择页 `NODE_SELECTION`；页面置信度 0.979739（UI 显示 0.980），处理耗时 2581 ms；匹配 `entry.node.return_standard`、`entry.node.retreat_standard`、`entry.node.relics_standard`、`entry.node.header_standard`、`entry.node.characters_standard` |
| 其他识别结果 | 节点：普通战斗×2；地图遗物：弱体2（2 层，confidence=1）；识别帧 75/75；actionCount=0；dryRun=true |
| cn-bilibili 模板直接复用 | 是。本次实际入口模板来自 `assets/resource-packs/cn-bilibili/vision`，未新增 `cn-xiaomi`；至少该 Xiaomi 页面可直接复用 |
| 渠道 UI 差异 | 本页面未发现阻碍识别的 Xiaomi 差异；不能据此推断所有页面完全一致 |
| 是否修改代码 | 未修改业务、截图或视觉代码；仅新增本文档 |

`recognition.json` 与 `frame.jpg` 是诊断服务器的两个异步端点：JSON 的最后一次识别结果为 `NODE_SELECTION`，而抓取到的 JPEG 在读取时已显示同一黎明界流程的「角色选择」页面。因此不能把 JPEG 宣称为该次 `NODE_SELECTION` 计算的逐帧配对图；它仍然证明 MediaProjection 输出的是纯 Android 游戏画面。用户提供的地图截图和助手界面中的 `NODE_SELECTION` 状态，与识别日志相互印证了节点页识别。

## Accessibility connection investigation

用户截图显示服务开关开启，但列表为「无法运行」。设备 crash buffer 记录 21:57:08、PID 6988、进程 com.landosol.toolbox 的测试运行器异常：

```text
FATAL EXCEPTION: Instr: androidx.test.runner.AndroidJUnitRunner
java.lang.IllegalStateException: Cannot call disconnect() while connecting!
    at android.app.UiAutomation.disconnect(UiAutomation.java:283)
    at android.app.Instrumentation.finish(Instrumentation.java:244)
    at androidx.test.runner.MonitoringInstrumentation.finish(MonitoringInstrumentation.java:409)
    at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:474)
    at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2145)
```

异常位于 instrumentation 结束/UiAutomation 断开阶段，栈中没有视觉处理或 LandosolAccessibilityService 回调。当前检查没有启动 instrumentation，无法仅凭此日志确定该次运行由谁触发。服务绑定曾显示 DEAD；用户手动关闭再开启后，系统重新绑定成功，`accessibility-reconnected.txt` 中有 `received=true`、`hasBound=true`。测试结束后已主动停止助手，使服务和投影释放；不写 secure settings、不绕过授权，也未修改视觉代码。

原始日志位于 `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\xiaomi-vision-smoke\accessibility-initial-crash.txt` 和 `accessibility-initial-service.txt`。

用户重新开启后，系统绑定成功，见同目录 `accessibility-reconnected.txt`。随后用户通过现有 UI 完成录屏授权并手动进入 Xiaomi 黎明界页面；捕获与识别使用既有流程。

## Remaining validation

成功标准已达到：Accessibility 系统绑定、MediaProjection 帧捕获和至少一个 Xiaomi 黎明界页面的现有 Vision 识别均通过。识别状态通过现有诊断接口 `/api/state` 保存为 `recognition.json`，截图通过 `/frame.jpg` 保存为 `frame.jpg`。本轮没有执行完整黎明界流程；下一步如需继续，只能另行授权安全点击 smoke test。未 commit、未 push。
