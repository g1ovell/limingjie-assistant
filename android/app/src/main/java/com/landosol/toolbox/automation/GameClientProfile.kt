package com.landosol.toolbox.automation

import android.content.pm.PackageManager

enum class GameChannel {
    BILIBILI,
    XIAOMI,
}

data class GameClientProfile(
    val channel: GameChannel,
    val packageName: String,
)

sealed interface GameClientResolution {
    data class Available(val profile: GameClientProfile) : GameClientResolution
    data class Ambiguous(val profiles: List<GameClientProfile>) : GameClientResolution
    data object Unavailable : GameClientResolution
}

object GameClientProfiles {
    val BILIBILI = GameClientProfile(GameChannel.BILIBILI, "com.bilibili.priconne")
    val XIAOMI = GameClientProfile(GameChannel.XIAOMI, "com.bilibili.priconne.mi")
    val ALL: List<GameClientProfile> = listOf(BILIBILI, XIAOMI)
}

object GameClientProfileResolver {
    fun resolve(installedPackageNames: Set<String>): GameClientResolution {
        val installed = GameClientProfiles.ALL.filter { it.packageName in installedPackageNames }
        return when (installed.size) {
            0 -> GameClientResolution.Unavailable
            1 -> GameClientResolution.Available(installed.single())
            else -> GameClientResolution.Ambiguous(installed)
        }
    }

    fun resolve(packageManager: PackageManager): GameClientResolution = resolve(
        GameClientProfiles.ALL
            .filter { profile -> isInstalled(packageManager, profile.packageName) }
            .map(GameClientProfile::packageName)
            .toSet(),
    )

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
