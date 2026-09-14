package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

data class ImportedOpeningTapTarget(
    val characterId: String,
    val displayName: String,
    val slotId: String,
    val screenRect: EntryPixelRect,
    val tapPoint: ScreenPoint,
)

sealed interface ImportedOpeningLiveActionResult {
    data class Allowed(val target: ImportedOpeningTapTarget, val rosterIds: List<String>) : ImportedOpeningLiveActionResult
    data class Rejected(val reason: String) : ImportedOpeningLiveActionResult
}

/** Strict capability gate for the one imported-route opening-selection tap. */
object ImportedOpeningLiveActionGate {
    private const val MIN_PRODUCTION_SCORE = 0.45
    private const val TARGET_CHARACTER_ID = "1091"

    fun resolve(
        result: LabyrinthEntryFrameResult,
        openingGuildId: Int,
    ): ImportedOpeningLiveActionResult {
        if (result.observation.state != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            return ImportedOpeningLiveActionResult.Rejected("外部路线 live 只允许初始选人页，地图执行仍被禁止")
        }
        if (result.observation.confidence < MIN_PRODUCTION_SCORE) {
            return ImportedOpeningLiveActionResult.Rejected("初始选人页面置信度未达到生产门槛")
        }
        val scores = result.observation.anchorScores
        val none = maxOf(scores[EntryAnchorId.SELECTION_COUNT_NONE], scores[EntryAnchorId.SELECTION_COUNT_NONE_STANDARD])
        val complete = maxOf(scores[EntryAnchorId.SELECTION_COUNT_COMPLETE], scores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD])
        val disabled = maxOf(scores[EntryAnchorId.INVITE_DISABLED], scores[EntryAnchorId.INVITE_DISABLED_STANDARD])
        val enabled = maxOf(scores[EntryAnchorId.INVITE_ENABLED], scores[EntryAnchorId.INVITE_ENABLED_STANDARD])
        if (none < MIN_PRODUCTION_SCORE || none <= complete || disabled < MIN_PRODUCTION_SCORE || disabled <= enabled) {
            return ImportedOpeningLiveActionResult.Rejected("当前选择计数不是可确认的0/3")
        }
        val viewport = result.openingCharacterSelection
        if (viewport == null || viewport.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            return ImportedOpeningLiveActionResult.Rejected("初始角色视口尚未稳定")
        }
        val matches = result.openingCharacterMatches
        if (matches.size != 9) return ImportedOpeningLiveActionResult.Rejected("当前可见角色不是完整9人")
        if (matches.any { !it.trusted || it.characterId.isNullOrBlank() || it.selected }) {
            return ImportedOpeningLiveActionResult.Rejected("存在不可信或已选择的角色识别结果")
        }
        if (matches.mapNotNull(LabyrinthBattleCharacterMatch::characterId).distinct().size != matches.size) {
            return ImportedOpeningLiveActionResult.Rejected("角色characterId不唯一，拒绝点击")
        }
        val policy = LabyrinthOpeningRosterCatalog.policyFor(openingGuildId)
            ?: return ImportedOpeningLiveActionResult.Rejected("初始公会方案无效")
        val roster = policy.choose(matches.mapNotNull(LabyrinthBattleCharacterMatch::characterId).toSet())
        if (roster !is LabyrinthOpeningRosterDecision.Ready) {
            return ImportedOpeningLiveActionResult.Rejected((roster as LabyrinthOpeningRosterDecision.Missing).reason)
        }
        if (roster.characters.firstOrNull()?.characterId != TARGET_CHARACTER_ID) {
            return ImportedOpeningLiveActionResult.Rejected("当前策略下一目标不是$TARGET_CHARACTER_ID")
        }
        val target = matches.singleOrNull { it.characterId == TARGET_CHARACTER_ID }
            ?: return ImportedOpeningLiveActionResult.Rejected("目标characterId $TARGET_CHARACTER_ID 不存在或不唯一")
        if (target.displayName.isNullOrBlank()) return ImportedOpeningLiveActionResult.Rejected("目标角色名称未可靠识别")
        val rect = target.screenRect
        if (rect.left + rect.width > result.frameWidth || rect.top + rect.height > result.frameHeight) {
            return ImportedOpeningLiveActionResult.Rejected("目标角色卡片超出原生截图viewport")
        }
        val point = ScreenPoint(
            x = rect.left + rect.width * 0.16f,
            y = rect.top + rect.height * 0.86f,
        )
        if (point.x !in 0f..result.frameWidth.toFloat() || point.y !in 0f..result.frameHeight.toFloat()) {
            return ImportedOpeningLiveActionResult.Rejected("目标Accessibility坐标超出原生截图viewport")
        }
        return ImportedOpeningLiveActionResult.Allowed(
            target = ImportedOpeningTapTarget(
                characterId = TARGET_CHARACTER_ID,
                displayName = target.displayName,
                slotId = target.slotId,
                screenRect = rect,
                tapPoint = point,
            ),
            rosterIds = roster.characters.map(LabyrinthOpeningCharacter::characterId),
        )
    }
}
