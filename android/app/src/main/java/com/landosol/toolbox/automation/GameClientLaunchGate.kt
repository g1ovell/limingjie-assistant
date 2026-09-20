package com.landosol.toolbox.automation

enum class GameLaunchDecision { ALREADY_FOREGROUND, LAUNCH_GAME, BLOCKED }

/**
 * 启动闸门：游戏已在 MainActivity 前台时不得重发启动 Intent。
 * 重发会拉起 SplashActivity，PCR 随即弹出 PermissionActivity，
 * MainActivity 被 stop、Surface 消失 —— 即历史上的小米黑屏。
 * 实现照搬 v0.0.8 已验证版本。
 */
object GameClientLaunchGate {
    const val MAIN_ACTIVITY = "com.bilibili.priconne.MainActivity"
    const val PERMISSION_ACTIVITY = "com.bilibili.permission.PermissionActivity"

    fun decide(targetPackage: String?, foregroundPackage: String?, activity: String?): GameLaunchDecision {
        if (targetPackage == null || activity == PERMISSION_ACTIVITY) return GameLaunchDecision.BLOCKED
        if (foregroundPackage == targetPackage) {
            // Never re-enter the launcher while the game owns the foreground, even when
            // accessibility has not yet supplied an Activity name.
            return if (activity == MAIN_ACTIVITY) GameLaunchDecision.ALREADY_FOREGROUND
            else GameLaunchDecision.BLOCKED
        }
        return GameLaunchDecision.LAUNCH_GAME
    }

    fun execute(decision: GameLaunchDecision, launch: () -> Boolean): Boolean = when (decision) {
        GameLaunchDecision.ALREADY_FOREGROUND -> true
        GameLaunchDecision.LAUNCH_GAME -> launch()
        GameLaunchDecision.BLOCKED -> false
    }
}
