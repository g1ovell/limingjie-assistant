package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedOpeningLiveActionGateTest {
    @Test
    fun `valid stable 0 of 3 roster allows only 1091 and uses production tap anchor`() {
        val result = frame()
        val allowed = ImportedOpeningLiveActionGate.resolve(result, openingGuildId = 5)
        assertTrue(allowed is ImportedOpeningLiveActionResult.Allowed)
        allowed as ImportedOpeningLiveActionResult.Allowed
        assertEquals(listOf("1091", "1171", "1011"), allowed.rosterIds)
        assertEquals("1091", allowed.target.characterId)
        assertEquals("available_row_1_2", allowed.target.slotId)
        assertEquals(ScreenPoint(335.6f, 434.8f), allowed.target.tapPoint)
    }

    @Test
    fun `map page cannot use opening capability`() {
        val rejected = ImportedOpeningLiveActionGate.resolve(
            frame(state = LabyrinthEntryPageState.NODE_SELECTION), openingGuildId = 5,
        ) as ImportedOpeningLiveActionResult.Rejected
        assertTrue(rejected.reason.contains("只允许初始选人页"))
    }

    @Test
    fun `duplicate target, missing target, untrusted target and nonzero selection are rejected`() {
        val duplicate = frame(matches = frameMatches() + frameMatches().first())
        assertTrue(ImportedOpeningLiveActionGate.resolve(duplicate, 5) is ImportedOpeningLiveActionResult.Rejected)
        val missing = frame(matches = frameMatches().filterNot { it.characterId == "1091" })
        assertTrue(ImportedOpeningLiveActionGate.resolve(missing, 5) is ImportedOpeningLiveActionResult.Rejected)
        val untrusted = frame(matches = frameMatches().map { it.copy(trusted = it.characterId != "1091") })
        assertTrue(ImportedOpeningLiveActionGate.resolve(untrusted, 5) is ImportedOpeningLiveActionResult.Rejected)
        val selected = frame(matches = frameMatches().map { it.copy(selected = it.characterId == "1091") })
        assertTrue(ImportedOpeningLiveActionGate.resolve(selected, 5) is ImportedOpeningLiveActionResult.Rejected)
        val nonzero = frame(scores = anchorScores(none = 0.10, complete = 0.99, disabled = 0.10, enabled = 0.99))
        assertTrue(ImportedOpeningLiveActionGate.resolve(nonzero, 5) is ImportedOpeningLiveActionResult.Rejected)
    }

    @Test
    fun `unbounded target box and weak page are rejected`() {
        val outOfBounds = frame(matches = frameMatches().map {
            if (it.characterId == "1091") it.copy(screenRect = EntryPixelRect(1850, 900, 120, 120)) else it
        })
        assertTrue(ImportedOpeningLiveActionGate.resolve(outOfBounds, 5) is ImportedOpeningLiveActionResult.Rejected)
        val weak = frame(confidence = 0.20)
        assertTrue(ImportedOpeningLiveActionGate.resolve(weak, 5) is ImportedOpeningLiveActionResult.Rejected)
    }

    private fun frame(
        state: LabyrinthEntryPageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        confidence: Double = 0.994,
        scores: LabyrinthAnchorScores = anchorScores(),
        matches: List<LabyrinthBattleCharacterMatch> = frameMatches(),
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(state, confidence, emptyMap(), scores),
        matchedFeatures = emptyList(), elapsedMillis = 200,
        openingCharacterMatches = matches,
        openingCharacterSelection = LabyrinthBattleTeamObservation(
            currentFilter = LabyrinthBattleElementFilter.ALL,
            filters = emptyList(), visibleCharacters = matches, selectedCharacters = emptyList(),
            scrollbar = LabyrinthBattleScrollbarObservation(
                trackRect = EntryPixelRect(1850, 200, 20, 700),
                thumbRect = EntryPixelRect(1850, 200, 20, 120),
                visible = true, canScroll = true, position = 0.5,
            ),
            recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
        ),
        frameWidth = 1920, frameHeight = 1080,
    )

    private fun frameMatches() = listOf("1049", "1091", "1171", "1200", "1212", "1337", "1011", "1129", "1193")
        .mapIndexed { index, id ->
            LabyrinthBattleCharacterMatch(
                slotId = "available_row_${if (index < 8) 1 else 2}_${if (index < 8) index + 1 else 1}",
                characterId = id, displayName = id, iconVariant = "default", confidence = 0.70,
                screenRect = EntryPixelRect(120 + index * 190, 280, 160, 180), selected = false,
            )
        }

    private fun anchorScores(none: Double = 0.99, complete: Double = 0.10, disabled: Double = 0.98, enabled: Double = 0.12) =
        LabyrinthAnchorScores(mapOf(
            EntryAnchorId.SELECTION_HEADER to 0.99,
            EntryAnchorId.SELECTION_COUNT_NONE to none,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to complete,
            EntryAnchorId.INVITE_DISABLED to disabled,
            EntryAnchorId.INVITE_ENABLED to enabled,
        ))
}
