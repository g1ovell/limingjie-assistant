package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

internal enum class LabyrinthBattleWaitDecision { NONE, WAIT, TIMED_OUT }

/** A fixed deadline, not an inactivity timer: recognition jitter cannot extend a battle forever. */
internal class LabyrinthBattleWaitPolicy {
    private var startedAt: Long? = null
    private var startPageGrace = false

    @Synchronized
    fun onStartExecuted(now: Long) {
        if (startedAt == null) {
            startedAt = now
            startPageGrace = true
            android.util.Log.i(LOG_TAG, "armed at $now")
        }
    }

    @Synchronized
    fun observe(page: LabyrinthEntryPageState, now: Long): LabyrinthBattleWaitDecision {
        if (page == LabyrinthEntryPageState.BATTLE_IN_PROGRESS && startedAt == null) {
            // Also support resuming automation during an already recognized battle.
            startedAt = now
        }
        val start = startedAt ?: return LabyrinthBattleWaitDecision.NONE
        val elapsed = (now - start).coerceAtLeast(0L)
        val waitingPage = when (page) {
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
            LabyrinthEntryPageState.BATTLE_IN_PROGRESS -> true
            // Permit a short stale editor/challenge frame after injection, but do not hide a failed start.
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
            LabyrinthEntryPageState.BATTLE_CHALLENGE -> startPageGrace && elapsed < 3_000L
            else -> false
        }
        if (!waitingPage) {
            android.util.Log.i(LOG_TAG, "cleared by page ${page.name} after ${elapsed}ms")
            reset()
            return LabyrinthBattleWaitDecision.NONE
        }
        if (page != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION &&
            page != LabyrinthEntryPageState.BATTLE_CHALLENGE
        ) startPageGrace = false
        return if (elapsed >= 300_000L) LabyrinthBattleWaitDecision.TIMED_OUT
        else LabyrinthBattleWaitDecision.WAIT
    }

    @Synchronized
    fun reset() {
        if (startedAt != null) {
            val caller = Throwable().stackTrace.getOrNull(1)?.let { "${it.fileName}:${it.lineNumber}" }
            android.util.Log.i(LOG_TAG, "reset while armed, caller=$caller")
        }
        startedAt = null
        startPageGrace = false
    }

    private companion object {
        const val LOG_TAG = "LabyrinthBattleWait"
    }
}
