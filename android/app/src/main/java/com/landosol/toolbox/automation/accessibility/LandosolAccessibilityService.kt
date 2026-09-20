package com.landosol.toolbox.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionBackend
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val GAME_NOT_FOREGROUND_REASON = "游戏不在前台"

/**
 * TYPE_APPLICATION_OVERLAY 会以宿主包名和 android.view.View 上报窗口事件。
 * 它不是前台 Activity，不能覆盖真正的前台游戏包名。
 */
internal fun shouldTrackForegroundWindow(
    servicePackageName: String,
    eventPackageName: String?,
    eventClassName: String?,
): Boolean {
    if (eventPackageName.isNullOrBlank()) return false
    if (eventPackageName != servicePackageName) return true
    return eventClassName?.startsWith("$servicePackageName.") == true
}

internal fun shouldRefreshForegroundWindow(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

object AccessibilityConnectionRegistry {
    private val connected = MutableStateFlow(false)

    fun observe(): StateFlow<Boolean> = connected.asStateFlow()

    fun isConnected(): Boolean = connected.value

    internal fun update(value: Boolean) {
        connected.value = value
    }
}

class LandosolAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        AccessibilityConnectionRegistry.update(true)
        refreshForegroundPackageFromRoot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (shouldRefreshForegroundWindow(event.eventType)) {
            val eventPackageName = event.packageName?.toString()
            val eventClassName = event.className?.toString()
            if (
                shouldTrackForegroundWindow(
                    servicePackageName = packageName,
                    eventPackageName = eventPackageName,
                    eventClassName = eventClassName,
                )
            ) {
                foregroundPackageName = eventPackageName
                // 只有窗口状态变化事件携带 Activity 类名；内容变化事件给的是 View 类名，
                // 拿它覆盖会把 MainActivity 冲掉，导致启动闸门误判。
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    foregroundActivityName = eventClassName
                }
            }
        }
    }

    private fun refreshForegroundPackageFromRoot(): String? {
        val packageFromRoot = runCatching {
            rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (packageFromRoot != null) foregroundPackageName = packageFromRoot
        return packageFromRoot
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeService === this) {
            activeService = null
            foregroundPackageName = null
            foregroundActivityName = null
            AccessibilityConnectionRegistry.update(false)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
            foregroundPackageName = null
            foregroundActivityName = null
            AccessibilityConnectionRegistry.update(false)
        }
        super.onDestroy()
    }

    internal suspend fun perform(
        action: AutomationAction,
        gestureDisplayId: Int = Display.DEFAULT_DISPLAY,
    ): AutomationBackendResult = when (action) {
        is AutomationAction.Tap -> {
            Log.i(GESTURE_LOG_TAG, "派发点击 display=$gestureDisplayId x=${action.point.x} y=${action.point.y}")
            dispatchGesture(
                path = Path().apply { moveTo(action.point.x, action.point.y) },
                durationMillis = TAP_DURATION_MILLIS,
                displayId = gestureDisplayId,
            )
        }
        is AutomationAction.Swipe -> {
            val hold = action.holdMillis.coerceAtLeast(0L)
            if (action.durationMillis !in 1..MAX_GESTURE_DURATION_MILLIS ||
                action.durationMillis + hold > MAX_GESTURE_DURATION_MILLIS
            ) {
                AutomationBackendResult.Rejected("滑动时长超出范围")
            } else {
                dispatchGesture(
                    path = Path().apply {
                        moveTo(action.start.x, action.start.y)
                        lineTo(action.end.x, action.end.y)
                    },
                    durationMillis = action.durationMillis,
                    displayId = gestureDisplayId,
                    holdMillis = hold,
                    holdX = action.end.x,
                    holdY = action.end.y,
                )
            }
        }
        AutomationAction.Back -> performGlobalBack()
    }

    private suspend fun performGlobalBack(): AutomationBackendResult = onMainThread {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            AutomationBackendResult.Completed
        } else {
            AutomationBackendResult.Rejected("系统拒绝返回操作")
        }
    }

    private suspend fun dispatchGesture(
        path: Path,
        durationMillis: Long,
        displayId: Int,
        holdMillis: Long = 0L,
        holdX: Float = 0f,
        holdY: Float = 0f,
    ): AutomationBackendResult = withTimeoutOrNull(durationMillis + holdMillis + GESTURE_CALLBACK_GRACE_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                if (!continuation.isActive) return@post
                // A drag that lifts at travel speed is read by the game as a fling and the
                // map keeps sliding well past the gesture. Continue the same stroke with a
                // 1 px, holdMillis-long tail so the velocity tracker samples ~0 before the
                // pointer goes up, turning the same travel into a plain drag.
                val mainStroke =
                    GestureDescription.StrokeDescription(path, 0, durationMillis, holdMillis > 0)
                val builder = GestureDescription.Builder().addStroke(mainStroke)
                if (holdMillis > 0) {
                    val holdPath = Path().apply {
                        moveTo(holdX, holdY)
                        lineTo(holdX + 1f, holdY)
                    }
                    builder.addStroke(
                        mainStroke.continueStroke(holdPath, durationMillis, holdMillis, false),
                    )
                }
                // GestureDescription already targets the default display when no id is set.
                // MuMu exposes the captured game through an additional cloned display and can
                // acknowledge an explicitly-set display 0 gesture without delivering it to the
                // real game window. Only opt into setDisplayId for a genuinely secondary target.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
                    builder.setDisplayId(displayId)
                }
                val gesture = builder.build()
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.i(GESTURE_LOG_TAG, "手势完成 display=$displayId duration=${durationMillis}ms")
                            if (continuation.isActive) continuation.resume(AutomationBackendResult.Completed)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(GESTURE_LOG_TAG, "手势取消 display=$displayId duration=${durationMillis}ms")
                            if (continuation.isActive) {
                                continuation.resume(
                                    AutomationBackendResult.Rejected(
                                        "显示器 $displayId 的无障碍手势被系统取消",
                                    ),
                                )
                            }
                        }
                    },
                    null,
                )
                if (!accepted && continuation.isActive) {
                    Log.w(GESTURE_LOG_TAG, "系统拒绝手势 display=$displayId duration=${durationMillis}ms")
                    continuation.resume(
                        AutomationBackendResult.Rejected(
                            "系统拒绝显示器 $displayId 的无障碍手势",
                        ),
                    )
                }
            }
        }
    } ?: AutomationBackendResult.Rejected("显示器 $displayId 的无障碍手势回调超时")

    private suspend fun onMainThread(action: () -> AutomationBackendResult): AutomationBackendResult =
        suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                if (continuation.isActive) continuation.resume(action())
            }
        }

    companion object {
        const val GLOBAL_ACTION_RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS
        private const val TAP_DURATION_MILLIS = 60L
        private const val MAX_GESTURE_DURATION_MILLIS = 5_000L
        private const val GESTURE_CALLBACK_GRACE_MILLIS = 2_000L
        private const val GESTURE_LOG_TAG = "LandosolGesture"
        private const val SWIPE_UP_DURATION_MILLIS = 400L
        private const val SWIPE_X = 540f
        private const val SWIPE_Y_START = 900f
        private const val SWIPE_Y_END = 300f
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: LandosolAccessibilityService? = null

        @Volatile
        private var foregroundPackageName: String? = null

        /** 前台 Activity 类名，仅由 TYPE_WINDOW_STATE_CHANGED 更新。 */
        @Volatile
        private var foregroundActivityName: String? = null

        internal fun current(): LandosolAccessibilityService? = activeService
        fun isConnected(): Boolean = activeService != null && AccessibilityConnectionRegistry.isConnected()
        internal fun foregroundPackage(): String? {
            activeService?.refreshForegroundPackageFromRoot()
            return foregroundPackageName
        }

        /** 前台 Activity 类名；未观察到窗口状态变化时为 null。 */
        internal fun foregroundActivity(): String? = foregroundActivityName

        /** 通过无障碍全局动作派发（例如最近任务），返回是否被系统接受 */
        suspend fun dispatchGlobalAction(action: Int): Boolean = onMainThreadCompat {
            current()?.performGlobalAction(action) == true
        }

        /** 从屏幕上方往下滑（用于划掉最近任务卡片里的游戏卡片） */
        suspend fun dispatchSwipeUp(durationMillis: Long = SWIPE_UP_DURATION_MILLIS): Boolean {
            val service = current() ?: return false
            val path = Path().apply {
                moveTo(SWIPE_X, SWIPE_Y_START)
                lineTo(SWIPE_X, SWIPE_Y_END)
            }
            return service.dispatchGesture(
                path = path,
                durationMillis = durationMillis,
                displayId = Display.DEFAULT_DISPLAY,
            ) == AutomationBackendResult.Completed
        }

        private suspend fun onMainThreadCompat(action: () -> Boolean): Boolean =
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    if (continuation.isActive) continuation.resume(action())
                }
            }
    }
}

class AndroidAccessibilityActionBackend(
    private val expectedPackageName: String? = GAME_PACKAGE_NAME,
) : AutomationActionBackend {
    override suspend fun execute(action: AutomationAction): AutomationBackendResult {
        if (!action.hasValidCoordinates()) return AutomationBackendResult.Rejected("动作坐标无效")
        val service = LandosolAccessibilityService.current()
            ?: return AutomationBackendResult.Rejected("无障碍服务未连接")
        val foregroundPackage = LandosolAccessibilityService.foregroundPackage()
        if (expectedPackageName != null && foregroundPackage != expectedPackageName) {
            return AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON)
        }
        return service.perform(
            action = action,
            gestureDisplayId = CaptureStateRegistry.gestureDisplayId(),
        )
    }

    private fun AutomationAction.hasValidCoordinates(): Boolean = when (this) {
        is AutomationAction.Tap -> point.x >= 0f && point.y >= 0f
        is AutomationAction.Swipe -> start.x >= 0f && start.y >= 0f && end.x >= 0f && end.y >= 0f
        AutomationAction.Back -> true
    }

    private companion object {
        const val GAME_PACKAGE_NAME = "com.bilibili.priconne"
    }
}
