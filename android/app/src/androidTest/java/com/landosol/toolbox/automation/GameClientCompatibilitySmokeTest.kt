package com.landosol.toolbox.automation

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.LandosolToolboxApplication
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.automation.accessibility.isExpectedGamePackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device verification: launches only, never starts automation or dispatches gestures. */
@RunWith(AndroidJUnit4::class)
class GameClientCompatibilitySmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun launchesResolvedClientAndChecksPackageGuard() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGameClientSmoke") == "true")
        val app = ApplicationProvider.getApplicationContext<LandosolToolboxApplication>()
        assertNull(app.automationSessionManager.current())
        val resolution = app.javaClass.getDeclaredMethod("getGameClientResolution").apply {
            isAccessible = true
        }.invoke(app)
        assertEquals(GameClientResolution.Available(GameClientProfiles.XIAOMI), resolution)
        record("resolver=$resolution")

        // Read the actual backend's configured target without invoking execute/perform.
        val backend = app.javaClass.getDeclaredMethod("getAccessibilityActionBackend").apply {
            isAccessible = true
        }.invoke(app)
        @Suppress("UNCHECKED_CAST")
        val expectedPackage = backend.javaClass.getDeclaredField("expectedPackageName").apply {
            isAccessible = true
        }.get(backend) as () -> String?
        assertEquals(GameClientProfiles.XIAOMI.packageName, expectedPackage())
        record("accessibilityConnected=${LandosolAccessibilityService.isConnected()}")

        val mainIntent = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        instrumentation.runOnMainSync { app.startActivity(mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        assertGuard(expectedPackage(), awaitForeground(app.packageName), false)

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val homePackage = requireNotNull(app.packageManager.resolveActivity(homeIntent, 0)).activityInfo.packageName
        instrumentation.runOnMainSync { app.startActivity(homeIntent) }
        assertGuard(expectedPackage(), awaitForeground(homePackage), false)

        // Invoke the project's existing launcher callback in isolation. Do not call startAutomation.
        instrumentation.runOnMainSync {
            val session = app.labyrinthEntryRecognitionSession
            @Suppress("UNCHECKED_CAST")
            val launcher = session.javaClass.getDeclaredField("gameLauncher").apply {
                isAccessible = true
            }.get(session) as () -> Boolean
            assertTrue("Project launch callback failed", launcher())
        }
        record("projectLauncher=true")
        assertGuard(expectedPackage(), awaitForeground(GameClientProfiles.XIAOMI.packageName), true)
        assertNull(app.automationSessionManager.current())
        record("automationSession=none; gestures=not invoked")
    }

    private fun assertGuard(expected: String?, observed: String, allowed: Boolean) {
        assertEquals(allowed, isExpectedGamePackage(expected, observed))
        if (LandosolAccessibilityService.isConnected()) {
            val tracked = LandosolAccessibilityService.foregroundPackage()
            assertEquals(observed, tracked)
            assertEquals(allowed, isExpectedGamePackage(expected, tracked))
        }
        assertFalse(isExpectedGamePackage(null, observed))
        record("foreground=$observed; allowed=$allowed")
    }

    private fun awaitForeground(expected: String): String {
        repeat(30) {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .executeShellCommand("dumpsys activity activities"),
            ).bufferedReader().use { it.readText() }
            val resumed = dump.lineSequence().firstOrNull {
                it.contains("mResumedActivity:") || it.contains("topResumedActivity=")
            }.orEmpty()
            val observed = Regex(" ([a-zA-Z0-9_.]+)/").find(resumed)?.groupValues?.get(1)
            if (observed == expected) return observed
            Thread.sleep(500)
        }
        error("Expected foreground package not observed: $expected")
    }

    private fun record(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nGameClientSmoke: $message\n") })
    }
}
