package com.landosol.toolbox.labyrinth

import android.util.Log
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.overlay.AutomationOverlayBox
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeScanPlan
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeViewportScanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.finalBossPlatformClickRect
import com.landosol.toolbox.labyrinth.node.NodeAction
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeDebugLogger
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LabyrinthEntryRecognitionStatus { IDLE, RUNNING, STOPPED, ERROR }

/** 按已保存路线执行/回放的进度快照，供 UI 与悬浮窗展示。 */
data class LabyrinthRouteProgress(
    val currentArea: Int,
    val visitedCount: Int,
    val routeNodeCount: Int,
    val nextNodeLabel: String? = null,
    val nextNodeRect: EntryPixelRect? = null,
    val complete: Boolean = false,
)

enum class LabyrinthCombatKind { NORMAL, EX, BOSS }

/** Context carried across challenge, team editor, battle and result pages. */
data class LabyrinthCombatContext(
    val kind: LabyrinthCombatKind,
    val teamIndex: Int = 1,
    /** Values greater than three share the 3+ target combat model. */
    val targetCount: Int = 1,
) {
    init {
        require(teamIndex >= 1)
        require(targetCount >= 1)
    }
}

internal data class LabyrinthEffectiveScanFrameEvidence(
    val confirmedCharacterIds: Set<String>,
    val unsafeVisibleCharacters: List<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>,
    val toleratedSelectedDimmedCount: Int,
)

/**
 * Selected roster cards are rendered dimmed by the game. Their portrait match can therefore fall
 * below the normal safety threshold even though the yellow checkmark and the authoritative current
 * member strip prove that this is an already-selected card. Such a dimmed card must not deadlock
 * the official "有效效果" scan. We only assign an identity when its id/suspected id reconciles to
 * a member that is independently present in the bottom strip; otherwise it is tolerated but not
 * added to the effective-role set.
 */
internal fun labyrinthEffectiveScanFrameEvidence(
    observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation,
): LabyrinthEffectiveScanFrameEvidence {
    val selectedMemberIds = observation.selectedCharacters.mapNotNull { match ->
        match.characterId?.let(::canonicalLabyrinthRoleId)
    }.toSet()
    val hasAuthoritativeSelectedMembers = observation.selectedCharacters.isNotEmpty()
    val confirmed = linkedSetOf<String>()
    val unsafe = mutableListOf<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>()
    var toleratedSelectedDimmedCount = 0

    observation.visibleCharacters.forEach { match ->
        val id = match.characterId?.let(::canonicalLabyrinthRoleId)
        val safelyIdentified = id != null && match.trusted &&
            match.confidence >= LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
        if (safelyIdentified) {
            confirmed += requireNotNull(id)
            return@forEach
        }
        if (match.selected && hasAuthoritativeSelectedMembers) {
            toleratedSelectedDimmedCount++
            val reconciledId = sequenceOf(match.characterId, match.suspectedCharacterId)
                .filterNotNull()
                .map(::canonicalLabyrinthRoleId)
                .firstOrNull { it in selectedMemberIds }
            if (reconciledId != null) confirmed += reconciledId
            return@forEach
        }

        unsafe += match
    }

    return LabyrinthEffectiveScanFrameEvidence(
        confirmedCharacterIds = confirmed,
        unsafeVisibleCharacters = unsafe,
        toleratedSelectedDimmedCount = toleratedSelectedDimmedCount,
    )
}

/**
 * The node session advances the persisted cursor to the next area's START as soon as an area Boss
 * is entered. After an app restart the game may still be on that Boss challenge/team page, so the
 * immediately preceding selected-route Boss is still the active encounter until a later node is
 * confirmed.
 */
internal fun labyrinthPersistedCurrentNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val currentBlockId = route.currentBlockId ?: return null
    return route.nodes.firstOrNull { it.blockId == currentBlockId }
}

internal fun labyrinthPersistedBossNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val current = labyrinthPersistedCurrentNode(route) ?: return null
    val currentIndex = route.nodes.indexOfFirst { it.blockId == current.blockId }
    if (currentIndex < 0) return null
    if (current.blockType == LabyrinthNodeTypes.BOSS) return current
    if (current.blockType != LabyrinthNodeTypes.START || currentIndex == 0) return null
    val previous = route.nodes[currentIndex - 1]
    return previous.takeIf {
        it.blockType == LabyrinthNodeTypes.BOSS && it.area + 1 == current.area
    }
}

internal fun labyrinthBossEncounterStrategy(route: LabyrinthRouteJson?): LabyrinthExEncounterStrategy? {
    val boss = labyrinthPersistedBossNode(route) ?: return null
    val unitId = LabyrinthBossCatalog.unitIdForQuest(boss.area, boss.questId)
    return LabyrinthBossEncounterCatalog.forUnitId(unitId)
}

private enum class LabyrinthEffectiveCharacterScanStage {
    IDLE,
    SWITCH_TO_EFFECTIVE,
    SCANNING,
    RETURN_TO_ALL,
    COMPLETE,
}

internal fun labyrinthBattleRetryLimit(kind: LabyrinthCombatKind): Int = when (kind) {
    LabyrinthCombatKind.BOSS -> 1
    LabyrinthCombatKind.NORMAL,
    LabyrinthCombatKind.EX,
    -> 2
}

/** Number of automatic retry taps allowed before the current failed page becomes terminal. */
internal fun labyrinthEffectiveBattleRetryLimit(
    kind: LabyrinthCombatKind,
    rerollAfterThreeFailures: Boolean,
): Int = if (rerollAfterThreeFailures) {
    maxOf(labyrinthBattleRetryLimit(kind), 2)
} else {
    labyrinthBattleRetryLimit(kind)
}

/** battleRetryCount counts successful "重新挑战" taps; retryCount=2 means the third attempt failed. */
internal fun labyrinthShouldRerollAfterBattleFailure(
    rerollAfterThreeFailures: Boolean,
    battleRetryCount: Int,
): Boolean = rerollAfterThreeFailures && battleRetryCount >= 2

internal fun labyrinthBattleKindLabel(kind: LabyrinthCombatKind): String = when (kind) {
    LabyrinthCombatKind.NORMAL -> "普通战"
    LabyrinthCombatKind.EX -> "EX战"
    LabyrinthCombatKind.BOSS -> "Boss战"
}

/** A non-null message is also the safety gate that forbids post-entry auto clicks on this page. */
internal fun labyrinthManualInputMessage(state: LabyrinthEntryPageState): String? = when (state) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> "等待人工选择角色并确认"
    else -> null
}

/**
 * A number of reward flows show only a tappable character portrait between two recognized
 * pages. The portrait itself is intentionally allowed to be UNKNOWN, but only when the
 * previous page proves that a reward/animation chain is in progress.
 */
internal fun shouldArmCharacterAcquisitionFallback(
    previousPage: LabyrinthEntryPageState?,
    currentPage: LabyrinthEntryPageState,
    activeNodeType: Int? = null,
): Boolean {
    if (currentPage != LabyrinthEntryPageState.UNKNOWN) return false
    return when (previousPage) {
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        -> true

        // Link rewards show an ITEM_REWARD popup containing the three granted characters and
        // then enter the portrait sequence. Normal/EX/Boss rewards are followed by a role
        // selection. A relic or plain event item reward can instead return directly to the map,
        // so those node types must not arm the fallback here.
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.BATTLE_RESULT,
        -> activeNodeType in setOf(
            LabyrinthNodeTypes.LINK,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
            LabyrinthNodeTypes.BOSS,
        )

        else -> false
    }
}

/**
 * UNKNOWN frames reached from an event must never tap the center-lower choice-button band.
 * Keep the bounded fallback, but alternate between two edge-safe points so a transient event
 * misclassification can advance generic animation/portrait frames without silently selecting
 * a central event option.
 */
internal fun labyrinthEventUnknownFallbackPoint(attempt: Int): Pair<Int, Int> {
    require(attempt >= 0)
    return if (attempt % 2 == 0) 160 to 780 else 1760 to 780
}

/**
 * Some event nodes open the same full-roster shell used by the initial 3-character selector, but
 * ask for exactly one free recruit. Route context owns this ambiguity: once the node we actually
 * tapped is EVENT, an INITIAL_CHARACTER_SELECTION frame with an opening-style viewport must never
 * be delegated to the opening-roster planner.
 */
internal fun labyrinthEventFreeRoleSelectionOwnsFrame(
    pageState: LabyrinthEntryPageState,
    activeNodeType: Int?,
    roleRewardPage: Boolean,
    hasOpeningViewport: Boolean,
): Boolean =
    pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
        activeNodeType == LabyrinthNodeTypes.EVENT &&
        !roleRewardPage &&
        hasOpeningViewport

private const val MAP_SCROLL_DURATION_MILLIS = 450L

/** Drag one viewport-sized segment while staying clear of the persistent bottom controls. */
internal fun labyrinthMapSwipe(
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthMapScanDirection,
): AutomationAction.Swipe? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    val y = frameHeight * 0.58f
    val (startX, endX) = when (direction) {
        LabyrinthMapScanDirection.FORWARD -> 0.76f to 0.36f
        LabyrinthMapScanDirection.BACKWARD -> 0.36f to 0.76f
    }
    return AutomationAction.Swipe(
        start = ScreenPoint(frameWidth * startX, y),
        end = ScreenPoint(frameWidth * endX, y),
        durationMillis = MAP_SCROLL_DURATION_MILLIS,
    )
}

/** Compatibility helper retained for policy tests and callers that explicitly need later columns. */
internal fun labyrinthForwardMapSwipe(frameWidth: Int, frameHeight: Int): AutomationAction.Swipe? =
    labyrinthMapSwipe(frameWidth, frameHeight, LabyrinthMapScanDirection.FORWARD)

/**
 * Bounded fallback when no visual node survives classification and therefore no viewport
 * signature exists. Follow the protocol column first, then reverse for the second half of the
 * retry budget so an unknown camera position cannot leave execution waiting forever.
 */
internal fun labyrinthBlindMapScanPlan(
    currentNodeId: Long,
    targetLogicalColumn: Int,
    completedAttempts: Int,
    reverseAfterAttempts: Int,
): LabyrinthNodeScanPlan {
    require(completedAttempts >= 0)
    require(reverseAfterAttempts > 0)
    val currentLogicalColumn = (currentNodeId / 100L % 100L).toInt()
    val preferred = when {
        targetLogicalColumn > currentLogicalColumn -> LabyrinthMapScanDirection.FORWARD
        targetLogicalColumn < currentLogicalColumn -> LabyrinthMapScanDirection.BACKWARD
        else -> LabyrinthMapScanDirection.FORWARD
    }
    val direction = if (completedAttempts < reverseAfterAttempts) {
        preferred
    } else {
        when (preferred) {
            LabyrinthMapScanDirection.FORWARD -> LabyrinthMapScanDirection.BACKWARD
            LabyrinthMapScanDirection.BACKWARD -> LabyrinthMapScanDirection.FORWARD
        }
    }
    return LabyrinthNodeScanPlan(
        direction = direction,
        reason = "blind-empty-classifications currentColumn=$currentLogicalColumn " +
            "targetColumn=$targetLogicalColumn completed=$completedAttempts",
    )
}

/** Only a recognized destination page proves that the game accepted a map-node tap. */
internal fun labyrinthConfirmsNodeEntry(state: LabyrinthEntryPageState): Boolean = when (state) {
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
    LabyrinthEntryPageState.LINK_CHOICE,
    LabyrinthEntryPageState.RELIC_CHOICE,
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.ITEM_REWARD,
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> true

    else -> false
}

/**
 * A generic recognized page is not enough to advance the route: the destination must also be
 * compatible with the node that was clicked. This protects the route cursor from stale popups
 * and from a map false positive that happens to be followed by another page.
 */
internal fun labyrinthNodeEntryMatchesExpectedType(
    blockType: Int,
    pageState: LabyrinthEntryPageState,
): Boolean = when (blockType) {
    LabyrinthNodeTypes.NORMAL_BATTLE,
    LabyrinthNodeTypes.EX_BATTLE,
    LabyrinthNodeTypes.BOSS,
    -> pageState in setOf(
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_FAILED,
        LabyrinthEntryPageState.BATTLE_RESULT,
    )

    LabyrinthNodeTypes.LINK -> pageState == LabyrinthEntryPageState.LINK_CHOICE
    LabyrinthNodeTypes.RELIC -> pageState == LabyrinthEntryPageState.RELIC_CHOICE
    LabyrinthNodeTypes.EVENT -> pageState in setOf(
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.CHARACTER_JOINED,
        LabyrinthEntryPageState.ITEM_REWARD,
    )
    LabyrinthNodeTypes.SHOP -> pageState in setOf(
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    )
    else -> false
}

/** Coarse signature used to detect that a map swipe produced no viewport movement. */
internal fun labyrinthNodeViewportSignature(classifications: List<NodeClassification>): String =
    classifications
        .mapNotNull { node ->
            node.screenRect?.let { rect ->
                listOf(
                    node.blockType,
                    if (node.isClickable) 1 else 0,
                    (rect.left + rect.width / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                    (rect.top + rect.height / 2) / NODE_VIEWPORT_SIGNATURE_BUCKET_PX,
                ).joinToString(":")
            }
        }
        .sorted()
        .joinToString("|")

/** Only read-only diagnostics may draw the normal target box into frames captured by MediaProjection. */
internal fun labyrinthShowsNextNodeOverlayBox(dryRun: Boolean): Boolean = dryRun

/**
 * Node diagnostics are drawn only while the session is paused. While running, MediaProjection
 * can capture the annotation layer and feed its colored boxes back into node recognition.
 */
internal fun labyrinthShowsPausedNodeDiagnostics(paused: Boolean): Boolean = paused

private const val MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY = 2

/**
 * Node recognition can take roughly as long as the transition timeout on a dense map. One stale
 * map frame captured around the tap must not erase the pending transition before the next frame
 * can observe the movement dialog.
 */
internal fun labyrinthKeepsPendingNodeTransition(
    elapsedMillis: Long,
    timeoutMillis: Long,
    nodeSelectionFramesSinceAction: Int,
): Boolean =
    elapsedMillis < timeoutMillis ||
        nodeSelectionFramesSinceAction < MIN_NODE_SELECTION_FRAMES_BEFORE_TRANSITION_RETRY

/**
 * After the movement-confirmation button was accepted, the destination can render one or more
 * transitional frames before its semantic page becomes recognizable. Those frames must not erase
 * the pending route transition; otherwise the game can enter/finish a node while the local route
 * cursor remains one step behind.
 *
 * This does not authorize any click. It only keeps the already-dispatched semantic transition
 * alive until a real destination page confirms it or the bounded entry timeout expires.
 */
internal fun labyrinthPreservesConfirmedNodeTransitionAcrossPage(
    pageState: LabyrinthEntryPageState,
    confirmationDispatchedAtMillis: Long?,
    nowMillis: Long,
    timeoutMillis: Long,
): Boolean {
    val confirmedAt = confirmationDispatchedAtMillis ?: return false
    val elapsed = (nowMillis - confirmedAt).coerceAtLeast(0L)
    if (elapsed >= timeoutMillis) return false
    return pageState in setOf(
        LabyrinthEntryPageState.UNKNOWN,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
    )
}

private data class PendingNodeTransition(
    val blockId: Long,
    val blockType: Int,
    val label: String,
    val dispatchedAtMillis: Long,
    val confirmationDispatchedAtMillis: Long? = null,
    val confirmationAttempts: Int = 0,
    val nodeSelectionFramesSinceAction: Int = 0,
)

private enum class LabyrinthPostBossStage {
    NONE,
    BEFORE_SCORE,
    SCORE_RESULT,
    CHEST_SEQUENCE,
    FINAL_ITEM_REWARD,
    WAITING_FOR_DAWN_HOME,
}

/** A session popup always takes priority over the normal page classifier and route actions. */
internal data class LabyrinthSessionBlockObservation(
    val kind: SessionBlockKind,
    val returnTitleRect: EntryPixelRect?,
    val actionLabel: String = "返回标题",
) {
    val blocksNormalActions: Boolean get() = kind != SessionBlockKind.NONE
}

internal fun labyrinthSessionBlockObservation(
    result: LabyrinthEntryFrameResult,
    classifier: SessionBlockClassifier = SessionBlockClassifier(),
): LabyrinthSessionBlockObservation {
    // The reconnect detector uses a deliberately broad blue-title template. A normal labyrinth
    // movement dialog shares that chrome, so its more specific two-button detector must win.
    if (result.nodeMoveConfirmation != null) {
        return LabyrinthSessionBlockObservation(SessionBlockKind.NONE, returnTitleRect = null)
    }
    val dateChangeTitleScore =
        result.observation.anchorScores[EntryAnchorId.SESSION_DATE_CHANGE_TITLE]
    if (dateChangeTitleScore >= SESSION_DATE_CHANGE_TITLE_MIN_SCORE) {
        val confirmRect = result.anchorMatches[EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM]
            ?.takeIf { it.score >= SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE }
            ?.rect
        return LabyrinthSessionBlockObservation(
            kind = SessionBlockKind.RELOGIN_REQUIRED,
            returnTitleRect = confirmRect,
            actionLabel = "确认",
        )
    }
    val kind = classifier.classify(
        scores = result.observation.anchorScores.toSessionBlockScores(),
        pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
    )
    val returnTitleRect = if (
        kind == SessionBlockKind.RECONNECT_PROMPTED || kind == SessionBlockKind.RELOGIN_REQUIRED
    ) {
        result.anchorMatches[EntryAnchorId.SESSION_RETURN_TITLE]
            ?.takeIf { it.score >= SESSION_RETURN_TITLE_MIN_SCORE }
            ?.rect
    } else {
        null
    }
    return LabyrinthSessionBlockObservation(kind, returnTitleRect)
}

data class LabyrinthEntryRecognitionSessionState(
    val status: LabyrinthEntryRecognitionStatus = LabyrinthEntryRecognitionStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val dryRun: Boolean = true,
    val paused: Boolean = false,
    val receivedFrameCount: Long = 0,
    val frameCount: Long = 0,
    val actionCount: Int = 0,
    val lastActionLabel: String? = null,
    val executionSource: LabyrinthRouteSourceKind? = null,
    val importedRouteAreas: Int? = null,
    val plannedNextAction: String? = null,
    val plannedCharacterId: String? = null,
    val openingPlanIds: List<String> = emptyList(),
    val openingTarget: ImportedOpeningTapTarget? = null,
    val openingGestureDispatched: Boolean = false,
    val lastResult: LabyrinthEntryFrameResult? = null,
    val joinedCharacters: List<LabyrinthJoinedCharacter> = emptyList(),
    val observedRelics: List<LabyrinthObservedRelic> = emptyList(),
    val routeProgress: LabyrinthRouteProgress? = null,
    val combatContext: LabyrinthCombatContext? = null,
    val battleTeamRecommendation: LabyrinthBattleTeamRecommendation? = null,
    val battleTeamRecommendationUnavailableReason: String? = null,
    val battleTeamSelectionPlan: LabyrinthBattleTeamSelectionPlan? = null,
    val roleRewardChoiceDecision: LabyrinthRoleRewardChoiceDecision? = null,
    val eventChoiceDecision: LabyrinthEventChoiceDecision? = null,
    val pendingAcquiredCharacterId: String? = null,
    val message: String? = null,
) {
    val running: Boolean get() = status == LabyrinthEntryRecognitionStatus.RUNNING
}

sealed interface LabyrinthEntryRecognitionStartResult {
    data class Started(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : LabyrinthEntryRecognitionStartResult
    data class Blocked(val reason: String) : LabyrinthEntryRecognitionStartResult
}

/** Entry recognition session with an explicitly selected read-only or guarded action mode. */
class LabyrinthEntryRecognitionSession(
    private val sessionManager: AutomationSessionManager,
    private val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val captureStop: () -> Unit = {},
    private val processorFactory: (finalBossOnly: () -> Boolean) -> (CapturedFrame) -> LabyrinthEntryFrameResult,
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    private val actionExecutor: SessionBoundActionExecutor? = null,
    private val actionsAvailable: () -> Boolean = { actionExecutor != null },
    private val actionTargetReady: () -> Boolean = { true },
    private val gameLauncher: () -> Boolean = { true },
    private val actionPlannerFactory: () -> LabyrinthEntryActionPlanner = { LabyrinthEntryActionPlanner() },
    private var relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    private var shopPolicy: LabyrinthShopPolicy = LabyrinthShopPolicy(relicChoicePolicy),
    private var roleRewardChoicePlanner: LabyrinthRoleRewardChoicePlanner? = null,
    private val roleRewardChoiceUnavailableReason: String? = null,
    private var eventChoicePlanner: LabyrinthEventChoicePlanner? = null,
    private var battleTeamRecommendationPlanner: LabyrinthBattleTeamRecommendationPlanner? = null,
    private val battleTeamRecommendationUnavailableReason: String? = null,
    private var battleTeamSelectionPlanner: LabyrinthBattleTeamSelectionPlanner? = null,
    private val runStateStore: LabyrinthRunStateStore? = null,
    private val routeSource: LabyrinthRouteSource? = null,
    private val nodeTemplateLoader: (() -> NodeTemplateSet)? = null,
    private val nodeSessionFactory: () -> LabyrinthNodeSession = {
        LabyrinthNodeSession(
            debugLogger = NodeDebugLogger { message ->
                Log.d(NODE_LOG_TAG, "node-mapping $message")
            },
        )
    },
    private val debugFramePublisher: ((CapturedFrame, LabyrinthEntryRecognitionSessionState, LabyrinthEntryFrameResult, AutomationOverlayPresentation) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val strategyProvider: (() -> LabyrinthStrategySnapshot)? = null,
    private val rerollRequester: (suspend (Long) -> Boolean)? = null,
) {
    private data class QueuedFrame(
        val sessionId: AutomationSessionId,
        val processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        val frame: CapturedFrame,
    )

    private val mutex = Mutex()
    private val persistenceMutex = Mutex()
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actionInFlight = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val queuedFrame = AtomicReference<QueuedFrame?>(null)
    private val frameWorkerRunning = AtomicBoolean(false)
    private val actionPlannerLock = Any()
    private val nodeActionPlanner = LabyrinthNodeActionPlanner()
    private val nodeViewportScanner = LabyrinthNodeViewportScanner()
    private val sessionBlockClassifier = SessionBlockClassifier()
    private val _state = MutableStateFlow(LabyrinthEntryRecognitionSessionState())
    val state: StateFlow<LabyrinthEntryRecognitionSessionState> = _state.asStateFlow()

    @Volatile
    private var activeSessionId: AutomationSessionId? = null
    @Volatile
    private var activeRunAccountId: Long? = null
    private var lease: CaptureFrameConsumerLease? = null
    private var firstFrameWatchdog: Job? = null
    private var lastProcessedAt = Long.MIN_VALUE
    private var actionPlanner: LabyrinthEntryActionPlanner? = null
    @Volatile
    private var importedOpeningLiveTapDispatched = false
    @Volatile
    private var pendingImportedOpeningTapTarget: ImportedOpeningTapTarget? = null
    @Volatile
    private var validatedExecutionContext: LabyrinthExecutionContext? = null
    private var validatedRoute: LabyrinthRouteJson? = null
    private val openingStateReset = AtomicBoolean(false)
    @Volatile
    private var lastPersistedObservationSignature: String? = null
    @Volatile
    private var lastBattleTeamRecommendationKey: String? = null
    @Volatile
    private var lastBattleTeamSelectionPlanLog: String? = null
    @Volatile
    private var lastBattleTeamExecutionKey: String? = null
    @Volatile
    private var battleTeamExecutionAttempts = 0
    @Volatile
    private var battleTeamScrollActions = 0
    private val battleRosterSearch = LabyrinthBattleRosterSearch()
    @Volatile
    private var lastBattleTeamActionAt = Long.MIN_VALUE
    private val committedBattleCharacterIds = linkedSetOf<String>()
    /** Teams actually committed during the current combat attempt, grouped by team signature. */
    private val currentBattleTeamSignatures = linkedSetOf<String>()
    /** Exact team signatures observed in failed attempts for this node. Never submit them again. */
    private val failedBattleTeamSignatures = linkedSetOf<String>()
    @Volatile
    private var battleRetryCount = 0
    private var bossEditorTeamIndex: Int? = null
    private var pendingBossTeamAdvance: Pair<Int, List<String>>? = null
    private var bossEditorBlocked = false
    private var bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
    private var preparedBossFirstTeamIds: List<String> = emptyList()
    private var bossTeamMode: LabyrinthBossTeamMode = LabyrinthBossTeamMode.MULTI_TEAM
    private var preferPureBossDamageSystem: Boolean = true
    private var rerollAfterThreeBattleFailures: Boolean = false
    @Volatile
    private var lastObservedPageState: LabyrinthEntryPageState? = null
    @Volatile
    private var pendingAcquiredCharacterId: String? = null
    /**
     * Role rewards can be selected in a batch and only materialize as CHARACTER_JOINED popups
     * after every selection has finished. Keep every selected id until its joined popup is
     * positively observed; the public single id remains only a compact diagnostic head.
     */
    private val pendingAcquiredCharacterIds = linkedSetOf<String>()
    @Volatile
    private var pendingShopPurchase: LabyrinthRelicMatch? = null
    @Volatile
    private var plannedShopPurchaseRelicId: String? = null
    @Volatile
    private var plannedShopRoleImprintLabel: String? = null
    @Volatile
    private var plannedShopPurchaseStartedAt = Long.MIN_VALUE
    @Volatile
    private var plannedShopPurchaseCounted = false
    @Volatile
    private var shopRelicPurchasesThisCycle = 0
    @Volatile
    private var pendingRelicSelection: LabyrinthRelicMatch? = null
    @Volatile
    private var relicChoiceCommitted = false
    private val relicAcquisitionGate = LabyrinthRelicAcquisitionGate()
    @Volatile
    private var eventChoiceCommitted = false
    @Volatile
    private var relicFocusMark: LabyrinthRelicMark? = null
    /** Latest game-visible "当前：N" values. These override history-derived stacks when known. */
    private val relicStackLedger = LabyrinthRelicStackLedger()
    private val relicMarkStackCalibration get() = relicStackLedger.stacks
    /** A single OCR read is evidence, not truth; require repeated/same-frame corroboration. */
    @Volatile
    private var lastShopDialogState: LabyrinthShopDialogState = LabyrinthShopDialogState.NONE
    @Volatile
    private var nodeSession: LabyrinthNodeSession? = null
    private val nodeInitStarted = AtomicBoolean(false)
    @Volatile
    private var nodeInitFailedReason: String? = null
    @Volatile
    private var finalBossLocalizationStartedAt = Long.MIN_VALUE
    @Volatile
    private var entryPhaseComplete = false
    @Volatile
    private var pendingNodeClickBlockId: Long = Long.MIN_VALUE
    @Volatile
    private var pendingNodeClickRect: EntryPixelRect? = null
    @Volatile
    private var pendingNodeClickStableFrames = 0
    @Volatile
    private var nodeScrollAttempts = 0
    @Volatile
    private var pendingNodeScrollBlockId = Long.MIN_VALUE
    @Volatile
    private var pendingNodeScrollViewportSignature: String? = null
    @Volatile
    private var pendingNodeScrollStableFrames = 0
    @Volatile
    private var pendingNodeScrollStartedAt = Long.MIN_VALUE
    @Volatile
    private var lastNodeActionAt = Long.MIN_VALUE
    @Volatile
    private var pendingNodeTransition: PendingNodeTransition? = null
    @Volatile
    private var nodeTapAttempts = 0
    @Volatile
    private var nodeMoveConfirmationStableFrames = 0
    @Volatile
    private var lastNodeMoveConfirmationRect: EntryPixelRect? = null
    @Volatile
    private var lastNodeScrollSourceSignature: String? = null
    @Volatile
    private var nodeErrorStreak = 0
    @Volatile
    private var routeNodeCount = 0
    @Volatile
    private var lastPostEntryState: LabyrinthEntryPageState? = null
    @Volatile
    private var postEntryStableFrames = 0
    @Volatile
    private var postEntryAttempts = 0
    @Volatile
    private var lastPostEntryActionAt = Long.MIN_VALUE
    @Volatile
    private var postEntryUnknownSince = Long.MIN_VALUE
    private val portraitRecovery = LabyrinthPortraitRecovery()
    private val battleWait = LabyrinthBattleWaitPolicy()
    @Volatile
    private var characterAcquisitionActive = false
    @Volatile
    private var characterAcquisitionClicks = 0
    @Volatile
    private var characterAcquisitionStartedAt = Long.MIN_VALUE
    @Volatile
    private var lastCharacterAcquisitionActionAt = Long.MIN_VALUE
    @Volatile
    private var waitingForManualRoleSelection = false
    @Volatile
    private var roleRewardChoiceCommitted = false
    @Volatile
    private var existingRunResumeHandoffArmed = false
    @Volatile
    private var roleRewardBatchActive = false
    @Volatile
    private var committedRoleRewardSelectionSignature: String? = null
    @Volatile
    private var roleRewardJoinedSequenceStarted = false
    @Volatile
    private var lastObservedRoleRewardSelectionSignature: String? = null
    @Volatile
    private var postBossStage = LabyrinthPostBossStage.NONE
    @Volatile
    private var postBossUnknownAttempts = 0
    @Volatile
    private var postBossStartedAt = Long.MIN_VALUE
    @Volatile
    private var combatContext: LabyrinthCombatContext? = null
    @Volatile
    private var currentExEncounterStrategy: LabyrinthExEncounterStrategy? = null
    @Volatile
    private var exSlot3ProbePending = false
    @Volatile
    private var exSlot3ProbeAttempts = 0
    @Volatile
    private var exEncounterProbeStartedAt = Long.MIN_VALUE
    @Volatile
    private var exIdentityProbeDescription = "EX识别目标"
    /** Official “有效效果” roles for the current encounter; populated by the later filter scan. */
    private val effectiveExCharacterIds = linkedSetOf<String>()
    private val effectiveRosterSearch = LabyrinthBattleRosterSearch()
    @Volatile
    private var effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
    @Volatile
    private var effectiveCharacterScanScrollActions = 0
    @Volatile
    private var effectiveCharacterScanNoScrollStableFrames = 0
    @Volatile
    private var lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
    @Volatile
    private var effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
    @Volatile
    private var effectiveCharacterScanSkippedUnsafe = false
    @Volatile
    private var eventFreeRoleSelectedCharacterId: String? = null
    @Volatile
    private var activeNodeType: Int? = null
    @Volatile
    private var activeNodeArea: Int? = null
    @Volatile
    private var eventActionAttempts = 0
    @Volatile
    private var lastSessionBlockKind = SessionBlockKind.NONE
    @Volatile
    private var sessionBlockStableFrames = 0
    @Volatile
    private var sessionReturnTitleAttempts = 0
    @Volatile
    private var lastSessionReturnTitleActionAt = Long.MIN_VALUE

    suspend fun start(
        dryRun: Boolean = true,
        accountId: Long? = null,
        executionSourceOverride: LabyrinthRouteSource? = null,
    ): LabyrinthEntryRecognitionStartResult = mutex.withLock {
        activeSessionId?.let { return LabyrinthEntryRecognitionStartResult.AlreadyRunning(it) }
        if ((!dryRun || executionSourceOverride != null) && (actionExecutor == null || !actionsAvailable())) {
            val reason = "无障碍服务未连接；若系统开关显示已开启，请关闭后重新开启"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        if (!captureActive()) {
            val reason = "请先在权限中心开启屏幕捕获"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        var executionGateMessage: String? = null
        val executionContext = if (executionSourceOverride != null || (!dryRun && nodeExecutionConfigured())) {
            when (val result = runCatching {
                resolveLabyrinthExecutionContext(executionSourceOverride ?: routeSource, accountId)
            }.getOrElse { failure ->
                LabyrinthExecutionContextResult.Blocked(
                    "执行前路线来源校验失败：${failure.message ?: "未知错误"}",
                )
            }) {
                is LabyrinthExecutionContextResult.Ready -> {
                    executionGateMessage = result.context.message
                    result.context
                }
                is LabyrinthExecutionContextResult.Blocked -> {
                    _state.value = _state.value.copy(
                        status = LabyrinthEntryRecognitionStatus.ERROR,
                        message = result.message,
                    )
                    return LabyrinthEntryRecognitionStartResult.Blocked(result.message)
                }
            }
        } else {
            null
        }
        val strategySnapshot = try {
            strategyProvider?.invoke()
        } catch (failure: Exception) {
            val reason = "策略配置加载失败：${failure.message}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val session = when (val started = sessionManager.start(AutomationMode.LABYRINTH, dryRun = dryRun)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                val reason = "已有${started.session.mode.name}任务运行"
                _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
                return LabyrinthEntryRecognitionStartResult.Blocked(reason)
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        rerollAfterThreeBattleFailures = false
        strategySnapshot?.let { snapshot ->
            bossTeamMode = snapshot.settings.bossTeamMode
            preferPureBossDamageSystem = snapshot.settings.preferPureBossDamageSystem
            rerollAfterThreeBattleFailures = snapshot.settings.rerollAfterThreeBattleFailures
            relicChoicePolicy = snapshot.settings.relicPolicy()
            shopPolicy = LabyrinthShopPolicy(
                relicChoicePolicy = relicChoicePolicy,
                finalArea = snapshot.settings.refreshFromArea,
                buyRelics = snapshot.settings.buyRelics,
                refreshEnabled = snapshot.settings.refreshShop,
            )
            roleRewardChoicePlanner = snapshot.roleRuntime?.rewardChoicePlanner
            eventChoicePlanner = snapshot.roleRuntime?.eventChoicePlanner
            battleTeamRecommendationPlanner = snapshot.roleRuntime?.battleTeamRecommendationPlanner
            battleTeamSelectionPlanner = snapshot.roleRuntime?.battleTeamSelectionPlanner
        }
        val initialRunSnapshot = try {
            if (executionContext?.importedOpening != null) null else runStateStore?.begin(accountId, clock())
        } catch (failure: Throwable) {
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedExecutionContext = null
            validatedRoute = null
            val reason = "加载本局队伍状态失败：${failure.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val processor = runCatching {
            processorFactory { nodeSession?.directFinalBossTarget() != null }
        }.getOrElse { error ->
            sessionManager.stop(session.id)
            requestCaptureStop()
            validatedExecutionContext = null
            validatedRoute = null
            val reason = "加载入口识别模板失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            enqueueLatestFrame(session.id, processor, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            sessionManager.stop(session.id)
            validatedExecutionContext = null
            validatedRoute = null
            val reason = "截图帧正在由${registration.owner}使用"
            _state.value = _state.value.copy(status = LabyrinthEntryRecognitionStatus.ERROR, message = reason)
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        activeSessionId = session.id
        activeRunAccountId = accountId
        validatedExecutionContext = executionContext
        validatedRoute = executionContext?.route
        lastProcessedAt = Long.MIN_VALUE
        actionInFlight.set(false)
        stopRequested.set(false)
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        resetBattleRetryTracking()
        lastObservedPageState = null
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        existingRunResumeHandoffArmed = false
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
        shopRelicPurchasesThisCycle = 0
        plannedShopPurchaseCounted = false
        shopRelicPurchasesThisCycle = 0
        pendingRelicSelection = null
        relicChoiceCommitted = false
        roleRewardChoiceCommitted = false
        relicFocusMark = null
        relicStackLedger.clear()
        relicStackLedger.clearPending()
        lastShopDialogState = LabyrinthShopDialogState.NONE
        resetNodeExecutionState()
        resetExEncounterTracking()
        resetExEncounterTracking()
        importedOpeningLiveTapDispatched = false
        pendingImportedOpeningTapTarget = null
        actionPlanner = if (dryRun && executionContext?.importedOpening == null) null else createActionPlanner()
        relicStackLedger.seedAcquisitions(relicChoicePolicy.markStacks(initialRunSnapshot?.observedRelics.orEmpty()))
        _state.value = LabyrinthEntryRecognitionSessionState(
            status = LabyrinthEntryRecognitionStatus.RUNNING,
            sessionId = session.id,
            dryRun = dryRun,
            executionSource = executionContext?.source,
            importedRouteAreas = executionContext?.importedOpening?.plan?.areas?.size,
            joinedCharacters = initialRunSnapshot?.joinedCharacters.orEmpty(),
            observedRelics = initialRunSnapshot?.observedRelics.orEmpty(),
            message = if (dryRun) {
                "入口只读识别已启动"
            } else {
                listOfNotNull(
                    executionGateMessage,
                    "半自动流程已启动，等待游戏切到前台",
                ).joinToString("；")
            },
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean =
                    this@LabyrinthEntryRecognitionSession.setPaused(paused)

                override suspend fun stop(): Boolean =
                    this@LabyrinthEntryRecognitionSession.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            validatedExecutionContext = null
            validatedRoute = null
            sessionManager.stop(session.id)
            requestCaptureStop()
            val reason = "任务控制通知不可用或已有任务占用，请检查通知权限与频道"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        armFirstFrameWatchdog(session.id)
        if (!dryRun && executionContext?.importedOpening == null && !gameLauncher()) {
            firstFrameWatchdog?.cancel()
            firstFrameWatchdog = null
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            validatedExecutionContext = null
            validatedRoute = null
            actionPlanner = null
            sessionManager.stop(session.id)
            overlayCoordinator.detach(session.id)
            requestCaptureStop()
            val reason = "无法启动公主连结"
            _state.value = _state.value.copy(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return LabyrinthEntryRecognitionStartResult.Blocked(reason)
        }
        LabyrinthEntryRecognitionStartResult.Started(session.id)
    }

    suspend fun startAutomation(accountId: Long? = null): LabyrinthEntryRecognitionStartResult =
        start(dryRun = false, accountId = accountId)

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        if (!sessionManager.setPaused(sessionId, paused)) return false
        finalBossLocalizationStartedAt = Long.MIN_VALUE
        resetPendingNodeClickStability()
        if (paused) clearQueuedFrame()
        _state.value = _state.value.copy(
            paused = paused,
            message = if (paused) "入口识别已暂停" else "入口仅识别已继续",
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        true
    }

    suspend fun stop(reason: String = "用户停止入口识别"): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: run {
            // 启动阶段失败或异常处理可能已经释放了会话，但录屏服务仍在运行。
            requestCaptureStop()
            return false
        }
        stopRequested.set(true)
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        activeSessionId = null
        activeRunAccountId = null
        validatedExecutionContext = null
        validatedRoute = null
        actionPlanner = null
        actionInFlight.set(false)
        openingStateReset.set(false)
        lastPersistedObservationSignature = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        lastObservedPageState = null
        pendingAcquiredCharacterId = null
        synchronized(pendingAcquiredCharacterIds) { pendingAcquiredCharacterIds.clear() }
        roleRewardBatchActive = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        existingRunResumeHandoffArmed = false
        pendingShopPurchase = null
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        lastShopDialogState = LabyrinthShopDialogState.NONE
        relicStackLedger.clearPending()
        resetNodeExecutionState()
        clearQueuedFrame()
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        requestCaptureStop()
        _state.value = _state.value.copy(
            status = LabyrinthEntryRecognitionStatus.STOPPED,
            sessionId = null,
            paused = false,
            message = reason,
        )
        stopped
    }

    /**
     * CaptureFrameBus dispatches synchronously on the capture callback thread. Heavy recognition
     * must therefore never run directly inside that callback: one slow OCR/template pass would
     * otherwise stop fresh frames from reaching the state machine and make the whole session
     * appear seconds behind the game.
     *
     * Keep at most one waiting frame while a single recognition worker is busy. Newer frames
     * replace older waiting frames (latest-frame-wins), so an occasional expensive frame can add
     * at most one recognition-duration of latency instead of creating an unbounded stale queue.
     */
    private fun enqueueLatestFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        if (lastProcessedAt != Long.MIN_VALUE && frame.timestampMillis - lastProcessedAt < FRAME_INTERVAL_MILLIS) {
            recycle(frame)
            return
        }
        lastProcessedAt = frame.timestampMillis

        val replaced = queuedFrame.getAndSet(QueuedFrame(sessionId, processor, frame))
        replaced?.let { recycle(it.frame) }
        ensureFrameWorker()
    }

    private fun ensureFrameWorker() {
        if (!frameWorkerRunning.compareAndSet(false, true)) return
        actionScope.launch {
            try {
                while (true) {
                    val next = queuedFrame.getAndSet(null) ?: break
                    onFrame(next.sessionId, next.processor, next.frame)
                }
            } finally {
                frameWorkerRunning.set(false)
                // Close the race where a frame arrives after the loop observes null but before
                // frameWorkerRunning becomes false.
                if (queuedFrame.get() != null) ensureFrameWorker()
            }
        }
    }

    private fun clearQueuedFrame() {
        queuedFrame.getAndSet(null)?.let { recycle(it.frame) }
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        val sessionState = _state.value
        if ((!sessionState.dryRun || validatedExecutionContext?.importedOpening != null) && !actionTargetReady()) {
            val message = "等待游戏切到前台，自动会话保持运行"
            if (sessionState.message != message) {
                _state.value = sessionState.copy(message = message)
                runCatching {
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }.onFailure { error ->
                    handleFrameFailure(sessionId, error)
                }
            }
            recycle(frame)
            return
        }
        val frameWidth = frame.bitmap.width
        val frameHeight = frame.bitmap.height
        val beforeProcessing = _state.value
        _state.value = beforeProcessing.copy(
            receivedFrameCount = beforeProcessing.receivedFrameCount + 1,
        )
        runCatching {
            val result = processor(frame)
            // stop()/pause may run while an expensive frame is being recognized. Never let that
            // stale result mutate a stopped/paused session or publish an old dashboard frame.
            if (activeSessionId != sessionId || _state.value.paused) return@runCatching
            processFrameResult(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = frame.timestampMillis,
            )
            debugFramePublisher?.invoke(
                frame,
                _state.value,
                result,
                buildOverlayPresentation(sessionId),
            )
        }.onFailure { error ->
            handleFrameFailure(sessionId, error)
        }
            .also { recycle(frame) }
    }

    private fun handleFrameFailure(sessionId: AutomationSessionId, error: Throwable) {
        if (activeSessionId != sessionId) return
        val reason = "入口识别失败：${error.message ?: "未知错误"}"
        _state.value = _state.value.copy(message = reason)
        requestStopAfterFailure(sessionId, reason)
    }

    private fun processFrameResult(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId) return
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        if (result.frameWidth != frameWidth || result.frameHeight != frameHeight) {
            nodeLog(
                "frame-size-mismatch callback=${frameWidth}x${frameHeight} " +
                    "result=${result.frameWidth}x${result.frameHeight}",
                warning = true,
            )
        }

        val current = _state.value
        _state.value = current.copy(
            frameCount = current.frameCount + 1,
            lastResult = result,
            message = if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                "未知或低置信度页面，动作已禁用"
            } else {
                "页面识别已更新"
            },
        )
        // Imported plans do not contain the full protocol DAG. Dry-run previews opening selection;
        // live mode has a separately gated single opening tap and returns before persistence,
        // recovery, route handoff, or map execution.
        if (validatedExecutionContext?.importedOpening != null && current.dryRun) {
            val preview = importedOpeningPreview(result, timestampMillis)
            _state.value = _state.value.copy(
                plannedNextAction = preview.actionLabel,
                plannedCharacterId = preview.characterId,
                openingPlanIds = preview.rosterIds,
                message = preview.message,
            )
            return
        }
        if (validatedExecutionContext?.importedOpening != null && !current.dryRun) {
            if (importedOpeningLiveTapDispatched) return
            val opening = validatedExecutionContext?.importedOpening ?: return
            when (val gate = ImportedOpeningLiveActionGate.resolve(result, opening.openingGuildId)) {
                is ImportedOpeningLiveActionResult.Rejected -> {
                    _state.value = _state.value.copy(
                        plannedNextAction = null,
                        plannedCharacterId = null,
                        message = gate.reason,
                    )
                    return
                }
                is ImportedOpeningLiveActionResult.Allowed -> {
                    pendingImportedOpeningTapTarget = gate.target
                    _state.value = _state.value.copy(
                        plannedNextAction = "选择初始角色1/3：${gate.target.displayName}",
                        plannedCharacterId = gate.target.characterId,
                        openingPlanIds = gate.rosterIds,
                        openingTarget = gate.target,
                        message = "已通过单次初始选人动作安全门，等待现有策略派发",
                    )
                }
            }
            handleActionDecision(sessionId, result, frameWidth, frameHeight, timestampMillis)
            return
        }
        if (
            handleSessionBlockFrame(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
                dryRun = current.dryRun,
            )
        ) {
            return
        }
        val pageState = result.observation.state
        if (!current.dryRun &&
            !entryPhaseComplete &&
            existingRunResumeHandoffArmed &&
            labyrinthResumedRunRewardPageOwnsRoute(result)
        ) {
            // Resuming an existing run can restore directly into an unfinished reward batch.
            // Do not let the opening invitation planner interpret that CHARACTER_JOINED page.
            entryPhaseComplete = true
            existingRunResumeHandoffArmed = false
            roleRewardBatchActive = true
            if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED) {
                roleRewardJoinedSequenceStarted = true
                characterAcquisitionActive = true
                characterAcquisitionClicks = 0
                characterAcquisitionStartedAt = timestampMillis
                lastCharacterAcquisitionActionAt = Long.MIN_VALUE
            }
            _state.value = _state.value.copy(
                message = if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED) {
                    "继续挑战后恢复到角色加入奖励，已交接连续奖励链"
                } else {
                    "继续挑战后恢复到角色奖励选择，已交接连续奖励链"
                },
            )
            nodeLog("existing-run resume handed off directly to role reward page: ${pageState.name}")
        }
        if (!current.dryRun && labyrinthShouldResumeOpeningSelection(entryPhaseComplete, result)) {
            entryPhaseComplete = false
            existingRunResumeHandoffArmed = false
            battleWait.reset()
            portraitRecovery.reset()
            clearCharacterAcquisitionContext()
            clearRoleRewardBatchTracking()
            synchronized(actionPlannerLock) {
                actionPlanner = createActionPlanner()
            }
            nodeLog("opening-selection reclaimed entry flow after map handoff")
        }
        if (!current.dryRun) {
            // Destination recognition may confirm the just-dispatched node before its page-local
            // proposal is invalidated below.
            confirmPendingNodeTransition(sessionId, pageState)
        }
        val preservePendingNodeMoveConfirmation = !current.dryRun &&
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = pageState,
                hasPendingNodeTransition = pendingNodeTransition != null,
                hasMoveConfirmation = result.nodeMoveConfirmation != null,
            )
        val preserveConfirmedNodeTransition = !current.dryRun &&
            pendingNodeTransition?.let { pending ->
                labyrinthPreservesConfirmedNodeTransitionAcrossPage(
                    pageState = pageState,
                    confirmationDispatchedAtMillis = pending.confirmationDispatchedAtMillis,
                    nowMillis = timestampMillis,
                    timeoutMillis = NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS,
                )
            } == true
        val confirmedTransitionTimedOut = !current.dryRun &&
            pendingNodeTransition?.confirmationDispatchedAtMillis?.let { confirmedAt ->
                !labyrinthConfirmsNodeEntry(pageState) &&
                    pageState in setOf(
                        LabyrinthEntryPageState.UNKNOWN,
                        LabyrinthEntryPageState.NODE_MAP_VIEW,
                        LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
                        LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
                    ) &&
                    (timestampMillis - confirmedAt).coerceAtLeast(0L) >= NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            } == true
        if (confirmedTransitionTimedOut) {
            val pending = pendingNodeTransition
            if (pending != null) {
                nodeLog(
                    "node-entry-timeout target=${pending.label} after-confirmation page=${pageState.name} " +
                        "elapsed=${(timestampMillis - requireNotNull(pending.confirmationDispatchedAtMillis)).coerceAtLeast(0L)}ms",
                    warning = true,
                )
                pendingNodeTransition = null
                resetNodeMoveConfirmationTracking()
                resetPendingNodeClickStability()
                finishFromPlanner(
                    sessionId,
                    "已确认移动到${pending.label}，但在限定时间内未识别到对应节点页面；已停止并保留诊断状态",
                )
                return
            }
        }
        invalidatePreviousPageState(
            pageState = pageState,
            preservePendingNodeMoveConfirmation =
                preservePendingNodeMoveConfirmation || preserveConfirmedNodeTransition,
        )
        synchronizeBossEditor(result)
        refreshRelicStackCalibration(result)
        refreshRoleRewardChoiceDecision(sessionId, result, frameWidth, frameHeight)
        refreshEventChoiceDecision(sessionId, result)
        refreshEffectiveCharacterScan(sessionId, result, frameWidth, frameHeight, timestampMillis)
        refreshBattleTeamRecommendation(sessionId, pageState)
        refreshBattleTeamSelectionPlan(sessionId, pageState, result.battleTeamSelection)
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        persistObservation(sessionId, result, timestampMillis)
        if (!current.dryRun && entryPhaseComplete &&
            handleBattleWaitFrame(sessionId, result, timestampMillis)
        ) return
        if (result.relicDetailObservation != null) {
            portraitRecovery.reset()
            _state.value = _state.value.copy(message = if (result.relicDetailObservation.titleConfirmed) {
                "系列详情核对中：只更新已确认的可见系列；可手动滚动，关闭后继续路线"
            } else "正在确认系列详情弹窗，暂不执行点击")
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            return
        }
        if (!current.dryRun && labyrinthNodeMoveConfirmationOwnsFrame(
                pageState = pageState,
                hasPendingNodeTransition = pendingNodeTransition != null,
            )
        ) {
            if (handleNodeMoveConfirmationFrame(sessionId, result, timestampMillis)) {
                return
            }
        }
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val effectiveNodeType = activeNodeType ?: persistedCurrentNode?.blockType
        val eventFreeRoleSelection = labyrinthEventFreeRoleSelectionOwnsFrame(
            pageState = pageState,
            activeNodeType = effectiveNodeType,
            roleRewardPage = postEntryIsRoleRewardPage(result),
            hasOpeningViewport = result.openingCharacterSelection != null,
        )
        if (eventFreeRoleSelection) {
            if (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode.area
            }
            if (current.dryRun) {
                if (_state.value.message != "事件自由选角页：Dry Run 不点击") {
                    _state.value = _state.value.copy(message = "事件自由选角页：Dry Run 不点击")
                }
            } else {
                handleEventFreeRoleSelection(sessionId, result, timestampMillis)
            }
            return
        }
        val nodeReplayActive = nodeExecutionConfigured() &&
            (if (current.dryRun) pageState == LabyrinthEntryPageState.NODE_SELECTION else entryPhaseComplete)
        when {
            nodeReplayActive && pageState == LabyrinthEntryPageState.NODE_SELECTION ->
                handleNodeSelectionFrame(sessionId, result, frameWidth, frameHeight, timestampMillis)

            // 入口流程已交接给路线执行：点击节点后出现的事件页在这里处理，
            // 处理完回到节点页继续走路线。
            nodeReplayActive ->
                if (!current.dryRun) {
                    handlePostEntryPage(sessionId, result, frameWidth, frameHeight, timestampMillis)
                }

            !current.dryRun ->
                handleActionDecision(sessionId, result, frameWidth, frameHeight, timestampMillis)
        }
    }

    private fun armFirstFrameWatchdog(sessionId: AutomationSessionId) {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = actionScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MILLIS)
            if (activeSessionId != sessionId) return@launch
            val current = _state.value
            if (current.lastResult != null || current.frameCount > 0) return@launch
            val captureRunning = captureActive()
            val currentOwner = CaptureFrameBus.currentOwner()
            val reason = when {
                current.receivedFrameCount > 0 ->
                    "5秒未完成首帧识别：已收到${current.receivedFrameCount}个原始截图，首帧处理卡住或过慢"

                !captureRunning ->
                    buildString {
                        append("5秒未收到首帧：屏幕捕获已停止")
                        CaptureStateRegistry.lastStopReason()?.let { append("（$it）") }
                        append("，请停止Dry Run后重新开启屏幕捕获")
                    }

                currentOwner != OWNER ->
                    "5秒未收到首帧：截图消费者异常（${currentOwner ?: "无消费者"}）"

                else ->
                    "5秒未收到首帧：屏幕捕获仍运行但没有图像帧，请重新开启屏幕捕获"
            }
            _state.value = current.copy(message = reason)
            runCatching {
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
        }
    }

    private fun requestStopAfterFailure(sessionId: AutomationSessionId, reason: String) {
        if (!stopRequested.compareAndSet(false, true)) return
        actionScope.launch {
            if (activeSessionId == sessionId) stop(reason)
        }
    }

    private fun requestCaptureStop() {
        runCatching { captureStop() }
    }

    /**
     * Handles account-session interruption before normal page actions. The popup can retain a
     * recognizable HOME page behind it, so allowing the regular planner to run here would tap
     * through the modal instead of returning to the title screen.
     */
    private fun handleSessionBlockFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        dryRun: Boolean,
    ): Boolean {
        val block = labyrinthSessionBlockObservation(result, sessionBlockClassifier)
        if (!block.blocksNormalActions) {
            resetSessionRecoveryTracking()
            return false
        }
        battleWait.reset()
        if (lastSessionBlockKind != block.kind) {
            lastSessionBlockKind = block.kind
            sessionBlockStableFrames = 0
            sessionReturnTitleAttempts = 0
            lastSessionReturnTitleActionAt = Long.MIN_VALUE
        }
        sessionBlockStableFrames++

        val message = when {
            dryRun -> "检测到账号会话失效；只读模式不会点击${block.actionLabel}"
            block.kind == SessionBlockKind.UNKNOWN_PROMPT -> "检测到未知会话弹窗，已暂停普通点击"
            block.returnTitleRect == null -> "检测到账号会话失效，等待识别“${block.actionLabel}”按钮"
            sessionBlockStableFrames < SESSION_BLOCK_STABLE_FRAMES ->
                "检测到账号会话失效，正在确认“${block.actionLabel}”按钮"
            sessionReturnTitleAttempts >= MAX_SESSION_RETURN_TITLE_ATTEMPTS ->
                "${block.actionLabel}多次无响应，请手动点击；自动会话仍保持运行"
            else -> null
        }
        if (message != null) {
            publishSessionRecoveryMessage(sessionId, message)
            return true
        }

        val rect = block.returnTitleRect ?: return true
        if (
            rect.left < 0 || rect.top < 0 || rect.width <= 0 || rect.height <= 0 ||
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight
        ) {
            publishSessionRecoveryMessage(sessionId, "${block.actionLabel}按钮坐标越界，等待重新识别")
            return true
        }
        if (actionInFlight.get()) return true
        if (
            lastSessionReturnTitleActionAt != Long.MIN_VALUE &&
            timestampMillis - lastSessionReturnTitleActionAt < SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS
        ) {
            return true
        }
        dispatchSessionReturnTitle(sessionId, rect, block.actionLabel, timestampMillis)
        return true
    }

    private fun dispatchSessionReturnTitle(
        sessionId: AutomationSessionId,
        rect: EntryPixelRect,
        actionLabel: String,
        timestampMillis: Long,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        sessionReturnTitleAttempts++
        lastSessionReturnTitleActionAt = timestampMillis
        actionScope.launch {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (activeSessionId == sessionId) {
                        restartEntryNavigationAfterSessionError()
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "会话失效：$actionLabel",
                            message = "已点击$actionLabel，等待标题页后重新登录",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        sessionReturnTitleAttempts = (sessionReturnTitleAttempts - 1).coerceAtLeast(0)
                        lastSessionReturnTitleActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("$actionLabel 点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    private fun clearPlannedShopPurchase() {
        plannedShopPurchaseRelicId = null
        plannedShopRoleImprintLabel = null
        plannedShopPurchaseStartedAt = Long.MIN_VALUE
        plannedShopPurchaseCounted = false
    }

    /** Restart login/entry navigation while retaining any already-loaded route progress. */
    private fun restartEntryNavigationAfterSessionError() {
        battleWait.reset()
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        pendingNodeTransition = null
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        nodeErrorStreak = 0
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
        existingRunResumeHandoffArmed = false
        activeNodeType = null
        activeNodeArea = null
        activeNodeArea = null
        eventActionAttempts = 0
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        synchronized(actionPlannerLock) {
            actionPlanner = createActionPlanner()
        }
    }

    private data class OpeningPreview(
        val message: String, val actionLabel: String? = null,
        val characterId: String? = null, val rosterIds: List<String> = emptyList(),
    )

    private fun importedOpeningPreview(result: LabyrinthEntryFrameResult, timestamp: Long): OpeningPreview {
        if (result.observation.state != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            return OpeningPreview("外部路线仅预演初始选人；请人工停在初始角色选择0/3")
        }
        val matches = result.openingCharacterMatches
        if (matches.isEmpty() || matches.any { !it.trusted || it.characterId == null } ||
            matches.map { it.characterId }.distinct().size != matches.size || matches.any { it.selected }) {
            return OpeningPreview("等待可信、无重复且未选择的初始角色")
        }
        val policy = LabyrinthOpeningRosterCatalog.policyFor(validatedExecutionContext?.importedOpening?.openingGuildId)
            ?: return OpeningPreview("未配置初始公会方案")
        val roster = policy.choose(matches.mapNotNull { it.characterId }.toSet())
        if (roster !is LabyrinthOpeningRosterDecision.Ready) return OpeningPreview("当前画面未满足既有初始方案")
        val decision = synchronized(actionPlannerLock) {
            actionPlanner?.decide(
                state = result.observation.state, frameWidth = result.frameWidth,
                frameHeight = result.frameHeight, nowMillis = timestamp,
                anchorScores = result.observation.anchorScores, anchorMatches = result.anchorMatches,
                openingCharacterMatches = matches, openingCharacterSelection = result.openingCharacterSelection,
            )
        }
        val ids = roster.characters.map { it.characterId }
        return if (decision is LabyrinthEntryActionDecision.Execute &&
            decision.kind == LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER) {
            OpeningPreview("只读预演：${decision.label}；未发送手势", decision.label, ids.first(), ids)
        } else {
            OpeningPreview((decision as? LabyrinthEntryActionDecision.Wait)?.reason ?: "仅预演单角色选择，不执行其他动作",
                rosterIds = ids)
        }
    }

    private fun createActionPlanner(): LabyrinthEntryActionPlanner = actionPlannerFactory().also { planner ->
        planner.configureOpeningRoster(validatedExecutionContext?.importedOpening?.openingGuildId ?: validatedRoute?.guildId)
        planner.start(clock())
    }

    private fun publishSessionRecoveryMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId) return
        if (_state.value.message != message) {
            _state.value = _state.value.copy(message = message)
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    private fun resetSessionRecoveryTracking() {
        lastSessionBlockKind = SessionBlockKind.NONE
        sessionBlockStableFrames = 0
        sessionReturnTitleAttempts = 0
        lastSessionReturnTitleActionAt = Long.MIN_VALUE
    }

    /**
     * Reconcile history-derived mark stacks with the value printed by the game itself.
     * Multiple cards with the same mark must agree before that mark is accepted as calibration.
     */
    private fun refreshRelicStackCalibration(result: LabyrinthEntryFrameResult) {
        result.relicDetailObservation?.let { detail ->
            if (detail.titleConfirmed) detail.entries.forEach { (mark, value) ->
                relicStackLedger.observe(mark, value, LabyrinthRelicStackLedger.Source.DETAIL,
                    mark.name, detail.evidenceId)
            }
        }
        result.relicChoiceSelection?.choices.orEmpty().filter { it.recognized }
            .groupBy { LabyrinthRelicMark.fromLabel(it.attribute) }.forEach { (mark, choices) ->
            if (mark == null) return@forEach
            val conflicting = choices.mapNotNull { it.currentMarkStacks }.distinct().size > 1
            choices.forEach { choice ->
                relicStackLedger.observe(mark, choice.currentMarkStacks.takeUnless { conflicting },
                    LabyrinthRelicStackLedger.Source.CHOICE, choice.slotId, choice.currentMarkStacksEvidenceId)
            }
        }
        result.nodeRelicStackObservation?.entries.orEmpty().forEach { entry ->
            val mark = LabyrinthRelicMark.fromLabel(entry.attribute) ?: return@forEach
            relicStackLedger.observe(mark, entry.stacks, LabyrinthRelicStackLedger.Source.MAP,
                entry.slotId, entry.evidenceId)
        }
    }

    private fun advanceRelicStackCalibrationAfterAcquisition(relic: LabyrinthRelicMatch) {
        val mark = LabyrinthRelicMark.fromLabel(relic.attribute) ?: return
        val bonus = relic.attributeBonus?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val fallback = relicChoicePolicy.markStacks(_state.value.observedRelics).getValue(mark)
        relicStackLedger.acquired(mark, bonus, fallback)
    }

    /** Evaluates role reward identity/choice in both dry-run and live sessions. */
    private fun refreshRoleRewardChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (activeSessionId != sessionId) return
        if (!labyrinthIsRoleRewardPage(result)) {
            if (_state.value.roleRewardChoiceDecision != null) {
                _state.value = _state.value.copy(roleRewardChoiceDecision = null)
            }
            return
        }
        val planner = roleRewardChoicePlanner
        val decision = if (planner == null) {
            LabyrinthRoleRewardChoiceDecision.Wait(
                roleRewardChoiceUnavailableReason ?: "角色三选一决策资料未加载",
            )
        } else {
            val defenseStacks = relicChoicePolicy.markStacks(
                _state.value.observedRelics,
                relicMarkStackCalibration,
            )
                .getValue(LabyrinthRelicMark.DEFENSE)
            val pendingSelectedIds = synchronized(pendingAcquiredCharacterIds) {
                pendingAcquiredCharacterIds.toSet()
            }
            planner.decide(
                candidates = result.characterMatches,
                acquiredCharacterIds = _state.value.joinedCharacters
                    .map(LabyrinthJoinedCharacter::characterId)
                    .plus(pendingSelectedIds)
                    .toSet(),
                context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            )
        }
        val message = when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Select -> buildString {
                append("角色三选一建议：${decision.displayName}")
                if (!decision.actionSafe) append("（仅推荐，不自动点击）")
                if (decision.explanation.isNotEmpty()) {
                    append("；${decision.explanation.joinToString("；")}")
                }
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthRoleRewardChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(
            roleRewardChoiceDecision = decision,
            message = message,
        )
    }

    /** Evaluates a catalog-resolved event in both Dry Run and guarded live sessions. */
    private fun refreshEventChoiceDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
    ) {
        if (activeSessionId != sessionId) return
        val observation = result.eventChoiceSelection
        if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE || observation == null) {
            if (_state.value.eventChoiceDecision != null) {
                _state.value = _state.value.copy(eventChoiceDecision = null)
            }
            return
        }
        val planner = eventChoicePlanner
        val decision = if (planner == null) {
            LabyrinthEventChoiceDecision.Wait("事件推荐资料未加载")
        } else {
            val current = _state.value
            val stacks = relicChoicePolicy.markStacks(current.observedRelics, relicMarkStackCalibration)
            runCatching {
                planner.decide(
                    observation = observation,
                    acquiredCharacterIds = current.joinedCharacters
                        .map(LabyrinthJoinedCharacter::characterId)
                        .toSet(),
                    context = LabyrinthRoleDecisionContext(
                        defenseMarkStacks = stacks.getValue(LabyrinthRelicMark.DEFENSE),
                        targetCount = combatContext?.targetCount ?: 1,
                    ),
                    relicStacks = stacks,
                )
            }.getOrElse { failure ->
                LabyrinthEventChoiceDecision.Wait(
                    "事件${observation.event.id}推荐失败：${failure.message ?: "未知错误"}",
                )
            }
        }
        val message = when (decision) {
            is LabyrinthEventChoiceDecision.Select -> buildString {
                append("事件${decision.eventId}建议：${decision.label}")
                if (!decision.actionSafe) append("（仅推荐）")
                append("；${decision.explanation}")
                decision.safetyNote?.let { append("；$it") }
            }
            is LabyrinthEventChoiceDecision.Wait -> decision.reason
        }
        _state.value = _state.value.copy(eventChoiceDecision = decision, message = message)
    }

    private fun persistObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        val store = runStateStore ?: return
        val accountId = activeRunAccountId
        if (
            result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
                !labyrinthIsRoleRewardPage(result) &&
                openingStateReset.compareAndSet(false, true)
        ) {
            lastPersistedObservationSignature = null
            relicFocusMark = null
            relicStackLedger.clear()
            pendingRelicSelection = null
            relicChoiceCommitted = false
            actionScope.launch {
                runCatching {
                    persistenceMutex.withLock { store.startNew(accountId, timestampMillis) }
                }
                    .onSuccess { snapshot ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(
                                joinedCharacters = snapshot.joinedCharacters,
                                observedRelics = snapshot.observedRelics,
                            )
                            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                        }
                    }
                    .onFailure { failure ->
                        if (activeSessionId == sessionId) {
                            _state.value = _state.value.copy(message = "清理旧队伍状态失败：${failure.message ?: "未知错误"}")
                        }
                    }
            }
            return
        }
        val shopDialogState = result.shopObservation?.dialogState
        val purchasedRelic = when (shopDialogState) {
            LabyrinthShopDialogState.PURCHASE_CONFIRMATION -> {
                // Only a transaction explicitly planned as a relic is eligible for relic
                // persistence. Role-imprint icons can resemble relic templates and must never be
                // written into the relic ledger after their purchase-complete modal.
                pendingShopPurchase = plannedShopPurchaseRelicId?.let { plannedId ->
                    result.shopObservation.purchaseCandidate?.takeIf { it.relicId == plannedId }
                }
                null
            }

            LabyrinthShopDialogState.PURCHASE_COMPLETE -> {
                pendingShopPurchase.also { pendingShopPurchase = null }
            }

            LabyrinthShopDialogState.NONE -> {
                if (lastShopDialogState == LabyrinthShopDialogState.PURCHASE_CONFIRMATION) {
                    // Returning directly to the shop means the confirmation was cancelled.
                    pendingShopPurchase = null
                    clearPlannedShopPurchase()
                }
                null
            }

            LabyrinthShopDialogState.EXIT_CONFIRMATION -> {
                pendingShopPurchase = null
                null
            }
            null -> {
                pendingShopPurchase = null
                null
            }
        }
        lastShopDialogState = shopDialogState ?: LabyrinthShopDialogState.NONE
        val characters = labyrinthRosterReconciliationMatches(result)
        val confirmedRelic = if (relicAcquisitionGate.observe(relicChoiceCommitted, result.observation.state)) {
            pendingRelicSelection.also {
                pendingRelicSelection = null
                relicChoiceCommitted = false
                relicAcquisitionGate.reset()
            }
        } else {
            null
        }
        val relics = when {
            confirmedRelic != null -> listOf(confirmedRelic)
            purchasedRelic != null -> listOf(purchasedRelic)
            else -> emptyList()
        }
        relics.forEach(::advanceRelicStackCalibrationAfterAcquisition)
        val signature = buildString {
            append(result.observation.state.name)
            characters.forEach { character ->
                append('|')
                append(character.slotId)
                append(':')
                append(character.characterId)
                append(':')
                append(character.displayName)
            }
            relics.forEach { relic ->
                append('|')
                append(relic.slotId)
                append(':')
                append(relic.relicId)
                append(':')
                append(relic.attribute)
            }
        }
        if (signature == lastPersistedObservationSignature) return
        lastPersistedObservationSignature = signature
        actionScope.launch {
            runCatching {
                persistenceMutex.withLock {
                    store.recordPage(
                        accountId = accountId,
                        page = result.observation.state.name,
                        characters = characters,
                        nowMillis = timestampMillis,
                        relics = relics,
                        replaceCharacters = labyrinthRosterReplacesExisting(result, characters),
                    )
                }
            }.onSuccess { snapshot ->
                if (activeSessionId == sessionId) {
                    if (characters.isNotEmpty()) {
                        val confirmedIds = characters.mapNotNull { it.characterId }
                            .map(::canonicalLabyrinthRoleId)
                            .toSet()
                        synchronized(pendingAcquiredCharacterIds) {
                            pendingAcquiredCharacterIds.removeAll { pendingId ->
                                canonicalLabyrinthRoleId(pendingId) in confirmedIds
                            }
                            pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                        }
                    }
                    _state.value = _state.value.copy(
                        joinedCharacters = snapshot.joinedCharacters,
                        observedRelics = snapshot.observedRelics,
                        pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                        message = confirmedRelic?.displayName?.let { "选择遗物已入库：$it" }
                            ?: purchasedRelic?.displayName?.let { "购买遗物已入库：$it" }
                            ?: "页面识别已更新",
                    )
                    pendingAcquiredCharacterId = _state.value.pendingAcquiredCharacterId
                    refreshBattleTeamRecommendation(
                        sessionId,
                        _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                    )
                    refreshBattleTeamSelectionPlan(
                        sessionId = sessionId,
                        pageState = _state.value.lastResult?.observation?.state ?: LabyrinthEntryPageState.UNKNOWN,
                        observation = _state.value.lastResult?.battleTeamSelection,
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
            }.onFailure { failure ->
                lastPersistedObservationSignature = null
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "保存本局队伍/遗物失败：${failure.message ?: "未知错误"}")
                }
            }
        }
    }

    /** A Boss tab change is a team boundary even though the page type stays the same. */
    private fun synchronizeBossEditor(result: LabyrinthEntryFrameResult) {
        if (actionInFlight.get()) return
        val observation = result.battleTeamSelection ?: return
        if (observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE) return
        val index = observation.bossTeamIndex ?: return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage !in setOf(
                LabyrinthBossEditorPreparationStage.INACTIVE,
                LabyrinthBossEditorPreparationStage.COMPLETE,
            )
        ) {
            // Preparation deliberately visits tabs out of the normal 1 -> 2 -> 3 commit flow.
            // Treat those transitions as cleanup navigation, never as a submitted team boundary.
            bossEditorTeamIndex = index
            pendingBossTeamAdvance = null
            bossEditorBlocked = false
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
            _state.value = _state.value.copy(combatContext = combatContext)
            return
        }
        if (bossEditorTeamIndex == index) return
        val pending = pendingBossTeamAdvance
        if (bossEditorTeamIndex == null) {
            bossEditorBlocked = index != 1
        } else if (pending?.first == index) {
            val advance = labyrinthBossTeamAdvanceDisposition(bossTeamMode, pending.second)
            if (advance.committedIds.isNotEmpty()) {
                synchronized(currentBattleTeamSignatures) {
                    currentBattleTeamSignatures += labyrinthBattleTeamSignature(advance.committedIds)
                }
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds += advance.committedIds
                }
            }
            preparedBossFirstTeamIds = advance.preparedSingleTeamIds
        } else {
            // Untracked manual tab changes may hide other teams' members. Never silently reuse
            // the first team's recommendation against a different editor.
            bossEditorBlocked = true
        }
        pendingBossTeamAdvance = null
        bossEditorTeamIndex = index
        combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
            .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = index)
        lastBattleTeamRecommendationKey = null
        resetBattleTeamExecutionTracking()
        _state.value = _state.value.copy(combatContext = combatContext)
    }

    /** Clear viewport-local proposals on page changes; route and acquisition context may survive. */
    private fun invalidatePreviousPageState(
        pageState: LabyrinthEntryPageState,
        preservePendingNodeMoveConfirmation: Boolean = false,
    ) {
        val previous = lastObservedPageState
        if (previous == pageState) return
        lastObservedPageState = pageState

        if (
            pageState == LabyrinthEntryPageState.SHOP &&
            labyrinthPageUiOwner(previous ?: LabyrinthEntryPageState.UNKNOWN) != LabyrinthPageUiOwner.SHOP
        ) {
            // Fresh shop node. Returning from purchase/confirmation dialogs keeps the same cycle.
            shopRelicPurchasesThisCycle = 0
        }
        if (
            pageState == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE &&
            plannedShopPurchaseRelicId != null &&
            !plannedShopPurchaseCounted
        ) {
            // The completed-purchase page is the visual commit point. Count it exactly once;
            // do not infer success merely because a tap was dispatched.
            shopRelicPurchasesThisCycle++
            plannedShopPurchaseCounted = true
        }

        if (pageState != LabyrinthEntryPageState.NODE_SELECTION) {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            resetPendingNodeClickStability()
            resetNodeScrollSearchGate()
            if (!preservePendingNodeMoveConfirmation) {
                pendingNodeTransition = null
                nodeMoveConfirmationStableFrames = 0
                lastNodeMoveConfirmationRect = null
            }
            _state.value.routeProgress?.let { progress ->
                if (progress.nextNodeLabel != null || progress.nextNodeRect != null) {
                    _state.value = _state.value.copy(
                        routeProgress = progress.copy(nextNodeLabel = null, nextNodeRect = null),
                    )
                }
            }
        }
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            lastBattleTeamRecommendationKey = null
            lastBattleTeamSelectionPlanLog = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
            )
        }
        if (pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            _state.value = _state.value.copy(roleRewardChoiceDecision = null)
        }
        if (pageState != LabyrinthEntryPageState.EVENT_CHOICE) {
            _state.value = _state.value.copy(eventChoiceDecision = null)
            eventChoiceCommitted = false
        }
        if (labyrinthPageUiOwner(pageState) != LabyrinthPageUiOwner.SHOP) {
            pendingShopPurchase = null
            val keepPurchaseTransition = labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = pageState,
                plannedRelicId = plannedShopPurchaseRelicId ?: plannedShopRoleImprintLabel,
                startedAt = plannedShopPurchaseStartedAt,
                now = clock(),
                timeoutMillis = SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS,
            )
            if (!keepPurchaseTransition) {
                clearPlannedShopPurchase()
                lastShopDialogState = LabyrinthShopDialogState.NONE
            }
        }
    }

    /**
     * EX/Boss-only discovery pass for the game's official “有效效果” filter.
     *
     * The filter is useful encounter-specific evidence, but it is not allowed to become a blind
     * team selector. We first switch to the filter, rewind/scan the complete roster with the same
     * visual scrollbar gates used by normal auto-team search, record only safely identified cards,
     * then restore “全部”. Only after that visual round-trip is confirmed may team scoring run.
     */
    private fun refreshEffectiveCharacterScan(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId || _state.value.dryRun) return
        if (result.observation.state != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) return
        if (combatContext?.kind !in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS)) return
        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) return
        val observation = result.battleTeamSelection ?: return

        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.IDLE) {
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            effectiveRosterSearch.reset()
            effectiveCharacterScanScrollActions = 0
            effectiveCharacterScanNoScrollStableFrames = 0
            effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
            effectiveCharacterScanSkippedUnsafe = false
            effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
        }
        if (effectiveCharacterScanStage == LabyrinthEffectiveCharacterScanStage.COMPLETE) return
        if (actionInFlight.get()) return
        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            _state.value = _state.value.copy(message = "有效效果扫描：等待编组页稳定")
            return
        }
        if (lastEffectiveCharacterScanActionAt != Long.MIN_VALUE &&
            timestampMillis - lastEffectiveCharacterScanActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) return

        when (effectiveCharacterScanStage) {
            LabyrinthEffectiveCharacterScanStage.IDLE -> Unit

            LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanNoScrollStableFrames = 0
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SCANNING
                    _state.value = _state.value.copy(message = "有效效果扫描：已进入筛选，开始从顶部完整扫描")
                    return
                }
                val target = observation.filters.firstOrNull {
                    it.filter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT
                }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描：尚未可靠识别“有效效果”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：切换有效效果",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.SCANNING -> {
                if (observation.currentFilter != LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                    effectiveRosterSearch.reset()
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.SWITCH_TO_EFFECTIVE
                    _state.value = _state.value.copy(message = "有效效果扫描：筛选状态变化，重新确认有效效果页")
                    return
                }

                val frameEvidence = labyrinthEffectiveScanFrameEvidence(observation)
                val unsafe = frameEvidence.unsafeVisibleCharacters
                synchronized(effectiveExCharacterIds) {
                    frameEvidence.confirmedCharacterIds.forEach(effectiveExCharacterIds::add)
                }

                if (unsafe.isNotEmpty()) {
                    if (effectiveCharacterUnsafeStartedAt == Long.MIN_VALUE) {
                        effectiveCharacterUnsafeStartedAt = timestampMillis
                    }
                    if (timestampMillis - effectiveCharacterUnsafeStartedAt <
                        EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS
                    ) {
                        _state.value = _state.value.copy(
                            message = "有效效果扫描：当前页有${unsafe.size}个角色身份未过安全线；短暂等待稳定后仍会继续滚动",
                        )
                        return
                    }
                    // An unresolved portrait must never be promoted into an effective-role id,
                    // but it also must not deadlock the whole Boss/EX preparation. Keep every
                    // independently confirmed role from this viewport and continue traversing the
                    // filtered roster. A later overlapping viewport may identify the same card
                    // reliably; otherwise the unresolved card simply contributes no bonus.
                    effectiveCharacterScanSkippedUnsafe = true
                } else {
                    effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                }

                if (!observation.scrollbar.canScroll) {
                    effectiveCharacterScanNoScrollStableFrames++
                    if (effectiveCharacterScanNoScrollStableFrames >= EFFECTIVE_SCAN_END_STABLE_FRAMES) {
                        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                        _state.value = _state.value.copy(
                            message = "有效效果扫描：列表无需滚动，已记录" +
                                synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                                "名角色" +
                                if (effectiveCharacterScanSkippedUnsafe) "；未可靠身份已跳过；准备恢复全部筛选"
                                else "；准备恢复全部筛选",
                        )
                    }
                    return
                }
                effectiveCharacterScanNoScrollStableFrames = 0

                val searchDecision = effectiveRosterSearch.observe(
                    sessionId = sessionId,
                    observation = observation,
                    recommendedIds = EFFECTIVE_SCAN_CONTEXT_IDS,
                )
                when (searchDecision) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "有效效果扫描：等待滚动回弹并稳定")
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL
                        _state.value = _state.value.copy(
                            message = "有效效果扫描到底：共确认" +
                                synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                                "名" +
                                if (effectiveCharacterScanSkippedUnsafe) "；未可靠身份已跳过；准备恢复全部筛选"
                                else "；准备恢复全部筛选",
                        )
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        finishFromPlanner(sessionId, "有效效果扫描无法可靠确认滚动条位置；停止而不盲目滑动")
                    }
                    LabyrinthBattleRosterSearchDecision.TO_TOP,
                    LabyrinthBattleRosterSearchDecision.NEXT_PAGE,
                    -> {
                        if (effectiveCharacterScanScrollActions >= MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS) {
                            finishFromPlanner(sessionId, "有效效果扫描滚动达到上限，未能可靠遍历完整列表")
                            return
                        }
                        val toTop = searchDecision == LabyrinthBattleRosterSearchDecision.TO_TOP
                        // Match the normal roster-search gesture: the Boss editor has a shorter
                        // viewport, so use a smaller drag there and keep at least one full card
                        // height overlapped between consecutive captures.  Without this, the old
                        // 0.67H -> 0.43H drag could move ~259 px on a 1080p frame while the Boss
                        // viewport is only ~386 px tall, leaving less than one ~195 px card of
                        // overlap and making a skipped row possible if the list moves as far as
                        // the gesture.
                        val upper = ScreenPoint(
                            frameWidth * 0.75f,
                            frameHeight * if (observation.bossTeamIndex != null) 0.50f else 0.43f,
                        )
                        val lower = ScreenPoint(frameWidth * 0.75f, frameHeight * 0.67f)
                        dispatchEffectiveCharacterScanAction(
                            sessionId = sessionId,
                            label = if (toTop) "有效效果扫描：返回列表顶部" else "有效效果扫描：查看下一页",
                            action = AutomationAction.Swipe(
                                start = if (toTop) upper else lower,
                                end = if (toTop) lower else upper,
                                durationMillis = EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS,
                            ),
                            timestampMillis = timestampMillis,
                            scrollDirection = if (toTop) {
                                LabyrinthBattleRosterScrollDirection.TO_TOP
                            } else {
                                LabyrinthBattleRosterScrollDirection.NEXT_PAGE
                            },
                            scrollOriginPosition = observation.scrollbar.position,
                        )
                    }
                }
            }

            LabyrinthEffectiveCharacterScanStage.RETURN_TO_ALL -> {
                if (observation.currentFilter == LabyrinthBattleElementFilter.ALL) {
                    effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.COMPLETE
                    effectiveRosterSearch.reset()
                    lastBattleTeamRecommendationKey = null
                    _state.value = _state.value.copy(
                        message = "有效效果扫描完成：确认" +
                            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.size } +
                            "名对策角色；开始生成阵容",
                    )
                    return
                }
                val target = observation.filters.firstOrNull { it.filter == LabyrinthBattleElementFilter.ALL }
                if (target == null) {
                    _state.value = _state.value.copy(message = "有效效果扫描完成，等待可靠识别“全部”筛选按钮")
                    return
                }
                dispatchEffectiveCharacterScanAction(
                    sessionId = sessionId,
                    label = "有效效果扫描：恢复全部",
                    action = AutomationAction.Tap(
                        ScreenPoint(
                            target.screenRect.left + target.screenRect.width / 2f,
                            target.screenRect.top + target.screenRect.height / 2f,
                        ),
                    ),
                    timestampMillis = timestampMillis,
                )
            }

            LabyrinthEffectiveCharacterScanStage.COMPLETE -> Unit
        }
    }

    private fun dispatchEffectiveCharacterScanAction(
        sessionId: AutomationSessionId,
        label: String,
        action: AutomationAction,
        timestampMillis: Long,
        scrollDirection: LabyrinthBattleRosterScrollDirection? = null,
        scrollOriginPosition: Double? = null,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        lastEffectiveCharacterScanActionAt = timestampMillis
        if (scrollDirection != null) effectiveCharacterScanScrollActions++
        actionScope.launch {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, action)
            }
            when (result) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (scrollDirection != null && scrollOriginPosition != null) {
                        effectiveRosterSearch.recordExecutedScroll(scrollDirection, scrollOriginPosition)
                        // Give each newly exposed viewport its own short recognition grace period.
                        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = label,
                        message = "已执行：$label；等待视觉确认",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is AutomationActionResult.Rejected -> {
                    if (scrollDirection != null) {
                        effectiveCharacterScanScrollActions = (effectiveCharacterScanScrollActions - 1).coerceAtLeast(0)
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("有效效果扫描动作被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    /** Builds the recommendation consumed by the guarded battle-team execution stage. */
    private fun refreshBattleTeamRecommendation(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (activeSessionId != sessionId) return
        if (pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            if (
                lastBattleTeamRecommendationKey != null ||
                _state.value.battleTeamRecommendation != null ||
                _state.value.battleTeamRecommendationUnavailableReason != null
            ) {
                lastBattleTeamRecommendationKey = null
                _state.value = _state.value.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = null,
                )
            }
            return
        }

        if (combatContext?.kind == LabyrinthCombatKind.BOSS &&
            bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "Boss编组预处理：清空队伍1/2/3并返回队伍1",
            )
            return
        }

        if (combatContext?.kind in setOf(LabyrinthCombatKind.EX, LabyrinthCombatKind.BOSS) &&
            effectiveCharacterScanStage != LabyrinthEffectiveCharacterScanStage.COMPLETE
        ) {
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                battleTeamRecommendation = null,
                battleTeamRecommendationUnavailableReason = null,
                battleTeamSelectionPlan = null,
                message = "EX/Boss编组前正在扫描“有效效果”角色；完成后再生成阵容",
            )
            return
        }

        if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty() &&
            bossEditorTeamIndex in 2..3
        ) {
            _state.value = _state.value.copy(battleTeamRecommendation = null, battleTeamSelectionPlan = null,
                battleTeamRecommendationUnavailableReason = "第一队已确认；按单队首战策略核对空置队伍并前往第三队开始战斗")
            return
        }

        val current = _state.value
        val currentCombatContext = combatContext
        val encounterStrategy = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> currentExEncounterStrategy
            LabyrinthCombatKind.BOSS -> labyrinthBossEncounterStrategy(validatedRoute)
            else -> null
        }
        val preferCohesiveDamageSystem = when (currentCombatContext?.kind) {
            LabyrinthCombatKind.EX -> true
            LabyrinthCombatKind.BOSS -> preferPureBossDamageSystem
            else -> false
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            current.observedRelics,
            relicMarkStackCalibration,
        )
            .getValue(LabyrinthRelicMark.DEFENSE)
        val targetCount = encounterStrategy?.targetCount ?: currentCombatContext?.targetCount ?: 1
        val acquiredCharacterIds = current.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .map(::canonicalLabyrinthRoleId)
            .distinct()
        val cacheKey = buildString {
            append(acquiredCharacterIds.sorted().joinToString(","))
            append("|defense=")
            append(defenseStacks)
            append("|targets=")
            append(targetCount)
            append("|team=")
            append(currentCombatContext?.teamIndex ?: 1)
            append("|retry=")
            append(battleRetryCount)
            append("|encounter=")
            append(encounterStrategy?.id ?: "none")
            append("|effective=")
            append(synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.sorted().joinToString(",") })
            append("|cohesiveDamage=")
            append(preferCohesiveDamageSystem)
            append("|failedTeams=")
            append(synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.sorted().joinToString(";") })
            append("|used=")
            append(
                synchronized(committedBattleCharacterIds) {
                    committedBattleCharacterIds.sorted().joinToString(",")
                },
            )
        }
        if (
            cacheKey == lastBattleTeamRecommendationKey &&
            (current.battleTeamRecommendation != null ||
                current.battleTeamRecommendationUnavailableReason != null)
        ) {
            val message = labyrinthBattleTeamSelectionMessage(
                combatContext = currentCombatContext,
                recommendation = current.battleTeamRecommendation,
                unavailableReason = current.battleTeamRecommendationUnavailableReason,
            )
            if (current.message != message) _state.value = current.copy(message = message)
            return
        }
        lastBattleTeamRecommendationKey = cacheKey

        val usedCharacterIds = synchronized(committedBattleCharacterIds) {
            committedBattleCharacterIds.toSet()
        }
        val eligibleCharacterIds = acquiredCharacterIds.filterNot(usedCharacterIds::contains)
        val planner = battleTeamRecommendationPlanner
        val recommendationResult = if (planner == null) {
            LabyrinthBattleTeamRecommendationResult.Unavailable(
                battleTeamRecommendationUnavailableReason ?: "角色决策资料未加载",
            )
        } else {
            runCatching {
                val decisionContext = LabyrinthRoleDecisionContext(
                    defenseMarkStacks = defenseStacks,
                    targetCount = targetCount,
                    preferStrongestVanguard = currentCombatContext?.kind == LabyrinthCombatKind.BOSS &&
                        currentCombatContext.teamIndex == 1,
                    preferSingleDamageSystem = preferCohesiveDamageSystem,
                    encounterStrategy = encounterStrategy,
                    effectiveCharacterIds = synchronized(effectiveExCharacterIds) {
                        effectiveExCharacterIds.toSet()
                    },
                )
                if (battleRetryCount > 0) {
                    val failedSignatures = synchronized(failedBattleTeamSignatures) {
                        failedBattleTeamSignatures.toSet()
                    }
                    planner.retryRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                        failedTeamSignatures = failedSignatures,
                        retryNumber = battleRetryCount,
                        lastFailedTeamSignature = synchronized(failedBattleTeamSignatures) {
                            failedBattleTeamSignatures.lastOrNull()
                        },
                    )
                } else {
                    planner.initialRecommendation(
                        acquiredCharacterIds = eligibleCharacterIds,
                        context = decisionContext,
                    )
                }
            }.getOrElse { failure ->
                LabyrinthBattleTeamRecommendationResult.Unavailable(
                    if (failure is OutOfMemoryError) {
                        // Do not retry every frame or misreport this as missing character data.
                        "编组计算内存不足，已停止本次自动编组；请停止并重启辅助 App 后再试"
                    } else {
                        "生成第${currentCombatContext?.teamIndex ?: 1}队建议失败：${failure.message ?: "未知错误"}"
                    },
                )
            }
        }
        when (recommendationResult) {
            is LabyrinthBattleTeamRecommendationResult.Ready -> {
                val recommendation = recommendationResult.recommendation
                _state.value = current.copy(
                    battleTeamRecommendation = recommendation,
                    battleTeamRecommendationUnavailableReason = null,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = recommendation,
                        unavailableReason = null,
                    ),
                )
                runCatching { Log.i(TEAM_RECOMMENDATION_LOG_TAG, recommendation.logText()) }
            }

            is LabyrinthBattleTeamRecommendationResult.Unavailable -> {
                _state.value = current.copy(
                    battleTeamRecommendation = null,
                    battleTeamRecommendationUnavailableReason = recommendationResult.reason,
                    message = labyrinthBattleTeamSelectionMessage(
                        combatContext = currentCombatContext,
                        recommendation = null,
                        unavailableReason = recommendationResult.reason,
                    ),
                )
                runCatching {
                    Log.w(TEAM_RECOMMENDATION_LOG_TAG, "第一队建议不可用：${recommendationResult.reason}")
                }
            }
        }
    }

    private fun refreshBattleTeamSelectionPlan(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
        observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation?,
    ) {
        if (activeSessionId != sessionId) return
        val recommendation = _state.value.battleTeamRecommendation
        val planner = battleTeamSelectionPlanner
        if (
            pageState != LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ||
            recommendation == null ||
            observation == null ||
            planner == null
        ) {
            if (_state.value.battleTeamSelectionPlan != null || lastBattleTeamSelectionPlanLog != null) {
                lastBattleTeamSelectionPlanLog = null
                _state.value = _state.value.copy(battleTeamSelectionPlan = null)
            }
            return
        }
        val plan = planner.plan(
            sessionId = sessionId,
            recommendation = recommendation,
            observation = observation,
        )
        val logText = plan.logText()
        val current = _state.value
        _state.value = current.copy(
            battleTeamSelectionPlan = plan,
            message = labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = recommendation,
                unavailableReason = null,
                selectionPlan = plan,
            ),
        )
        if (lastBattleTeamSelectionPlanLog != logText) {
            lastBattleTeamSelectionPlanLog = logText
            runCatching { Log.i(TEAM_SELECTION_PLAN_LOG_TAG, logText) }
        }
    }

    private fun handleActionDecision(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (actionInFlight.get() || activeSessionId != sessionId) return
        val planner = actionPlanner ?: return
        val decision = synchronized(actionPlannerLock) {
            planner.decide(
                state = result.observation.state,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                nowMillis = timestampMillis,
                anchorScores = result.observation.anchorScores,
                anchorMatches = result.anchorMatches,
                openingCharacterMatches = result.openingCharacterMatches,
                openingCharacterSelection = result.openingCharacterSelection,
            )
        }
        when (decision) {
            is LabyrinthEntryActionDecision.Wait -> {
                if (activeSessionId == sessionId && _state.value.message != decision.reason) {
                    _state.value = _state.value.copy(message = decision.reason)
                }
            }
            is LabyrinthEntryActionDecision.Execute -> dispatchAction(sessionId, decision)
            is LabyrinthEntryActionDecision.Complete ->
                if (nodeExecutionConfigured() && shouldHandOffToRouteExecution(decision.state)) {
                    // A live session can be started on the map or on a route-owned page. Hand it
                    // to the route executor instead of treating the entry planner as finished.
                    entryPhaseComplete = true
                    existingRunResumeHandoffArmed = false
                    _state.value = _state.value.copy(
                        message = if (decision.state == LabyrinthEntryPageState.NODE_SELECTION) {
                            "入口流程完成，开始按已保存路线执行"
                        } else {
                            "检测到中途运行页面 ${decision.state.name}，接管当前路线执行"
                        },
                    )
                } else {
                    finishFromPlanner(sessionId, decision.reason)
                }
            is LabyrinthEntryActionDecision.Stop -> finishFromPlanner(sessionId, decision.reason)
        }
    }

    private fun dispatchAction(
        sessionId: AutomationSessionId,
        decision: LabyrinthEntryActionDecision.Execute,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        val importedOpeningLive = validatedExecutionContext?.importedOpening != null && !_state.value.dryRun
        if (importedOpeningLive) {
            val target = pendingImportedOpeningTapTarget
            val tap = decision.action as? AutomationAction.Tap
            if (
                importedOpeningLiveTapDispatched ||
                decision.kind != LabyrinthEntryActionKind.SELECT_INITIAL_CHARACTER ||
                target == null || tap == null || tap.point != target.tapPoint
            ) {
                actionInFlight.set(false)
                _state.value = _state.value.copy(message = "外部路线单次初始选人安全门拒绝非目标动作")
                return
            }
        }
        actionScope.launch {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(decision.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, decision.action)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (importedOpeningLive) {
                        importedOpeningLiveTapDispatched = true
                    }
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        // The next page may be the node map, a choice page, or an unfinished
                        // CHARACTER_JOINED reward from before the app/session was restarted.
                        existingRunResumeHandoffArmed = true
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = decision.label,
                            openingGestureDispatched = current.openingGestureDispatched || importedOpeningLive,
                            message = "已执行：${decision.label}",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }
                is AutomationActionResult.Rejected -> {
                    if (importedOpeningLive) {
                        stop("初始角色单次手势被拒绝：${result.reason}")
                        return@launch
                    }
                    if (decision.kind == LabyrinthEntryActionKind.RESUME_DAWN_REALM) {
                        existingRunResumeHandoffArmed = false
                    }
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        synchronized(actionPlannerLock) {
                            actionPlanner = createActionPlanner()
                        }
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> if (importedOpeningLive) {
                    stop("初始角色单次手势未执行：会话已暂停")
                }
                is AutomationActionResult.DryRun -> if (importedOpeningLive) {
                    stop("初始角色单次手势未执行：后端返回Dry Run")
                }
            }
            actionInFlight.set(false)
        }
    }

    private fun finishFromPlanner(sessionId: AutomationSessionId, reason: String) {
        if (!actionInFlight.compareAndSet(false, true)) return
        actionScope.launch {
            if (activeSessionId == sessionId) stop(reason)
            actionInFlight.set(false)
        }
    }

    private fun finishFromPlannerAndRequestReroll(
        sessionId: AutomationSessionId,
        reason: String,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        val accountId = activeRunAccountId
        actionScope.launch {
            if (activeSessionId == sessionId) {
                val stopped = stop("$reason；正在切换到刷开局")
                if (stopped && accountId != null) {
                    val started = runCatching { rerollRequester?.invoke(accountId) == true }.getOrDefault(false)
                    if (!started) {
                        _state.value = _state.value.copy(
                            message = "$reason；自动重刷启动失败，请手动进入刷开局页面继续",
                        )
                    }
                } else if (stopped) {
                    _state.value = _state.value.copy(
                        message = "$reason；缺少当前账号，未自动启动重刷",
                    )
                }
            }
            actionInFlight.set(false)
        }
    }

    private fun nodeExecutionConfigured(): Boolean =
        nodeTemplateLoader != null

    private fun shouldHandOffToRouteExecution(state: LabyrinthEntryPageState): Boolean = state in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.RELIC_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.SHOP,
        LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
        LabyrinthEntryPageState.BATTLE_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
        LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
        LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    )

    private fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision? {
        val shop = result.shopObservation ?: return null
        if (shop.dialogState != LabyrinthShopDialogState.NONE) return null
        val refreshAvailable = result.anchorMatches[EntryAnchorId.SHOP_REFRESH_BUTTON]
            ?.score
            ?.let { it >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?.let { anchorReady -> anchorReady && shop.refreshButtonEnabled }
            ?: false
        return shopPolicy.decide(
            // Prefer the area of the node we actually tapped. routeProgress.currentArea can lag
            // behind while the post-entry shop page is already visible, which previously caused
            // an area-4 shop to fall through into relic recognition and stop on missing metadata.
            currentArea = activeNodeArea ?: _state.value.routeProgress?.currentArea,
            items = shop.items,
            acquired = _state.value.observedRelics,
            calibratedStacks = relicMarkStackCalibration,
            refreshAvailable = refreshAvailable,
            lockedFocus = relicFocusMark,
            relicPurchasesInCycle = shopRelicPurchasesThisCycle,
        )
    }

    /** Battle waiting gates all route taps before UNKNOWN can reach any animation fallback. */
    private fun handleBattleWaitFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        if (result.relicDetailObservation != null || result.nodeMoveConfirmation != null) {
            battleWait.reset()
            return false
        }
        val restartSafeBossContext = combatContext ?: labyrinthPersistedBossNode(validatedRoute)?.let {
            LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        }
        val bossSettlementNextRect = labyrinthBossSettlementNextButtonRect(
            pageState = result.observation.state,
            combatContext = restartSafeBossContext,
            nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
            bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
        )
        if (bossSettlementNextRect != null) {
            if (combatContext?.kind != LabyrinthCombatKind.BOSS) {
                activeNodeType = LabyrinthNodeTypes.BOSS
                combatContext = restartSafeBossContext
                _state.value = _state.value.copy(combatContext = combatContext)
            }
            // The current client renders area-3/area-5 Boss victory summaries as UNKNOWN pages.
            // Do not let the generic battle waiter swallow a real post-battle "下一步" screen
            // for up to five minutes. The post-entry handler below will use this exact rect.
            battleWait.reset()
            postEntryUnknownSince = Long.MIN_VALUE
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "已确认进入Boss战后结算，结束战斗等待并准备点击下一步",
                )
            }
            return false
        }
        when (battleWait.observe(result.observation.state, timestampMillis)) {
            LabyrinthBattleWaitDecision.NONE -> return false
            LabyrinthBattleWaitDecision.TIMED_OUT -> {
                finishFromPlanner(sessionId, "等待战斗结算超过5分钟，已停止并保留诊断状态")
                return true
            }
            LabyrinthBattleWaitDecision.WAIT -> {
                clearCharacterAcquisitionContext()
                portraitRecovery.reset()
                waitingForManualRoleSelection = false
                postEntryUnknownSince = Long.MIN_VALUE
                // Do not inherit reward animation evidence when battle ends on an unknown frame.
                lastPostEntryState = LabyrinthEntryPageState.BATTLE_IN_PROGRESS
                postEntryStableFrames = 0
                postEntryAttempts = 0
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "等待战斗结算（最长5分钟）；暂停动画补点",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                return true
            }
        }
    }

    /**
     * Resolve the actual EX encounter before the challenge button is allowed to fire.
     *
     * Route block ids are deliberately ignored here: EX placement is randomized and event nodes
     * can also enter an EX.  A single EX is identified from challenge-page text.  A multi-monster
     * EX requires the stable five-info-button layout, then exactly slot 3 is opened and its fixed
     * detail-name row is OCR'd.  Unknown identities stop safely rather than borrowing another
     * encounter's guide.
     */
    private fun handleExEncounterRecognitionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val observation = result.exEncounter
        val page = result.observation.state

        // Restarting/reinstalling the app clears the in-memory activeNodeType/combatContext while
        // the game may remain on an already-entered Boss challenge page.  The route cursor is
        // persisted as soon as node entry is confirmed, so use that semantic evidence as the
        // restart-safe fallback instead of forcing the Boss title through the normal/extreme OCR
        // gate.  Live traversal still prefers activeNodeType from the actual dispatched node tap.
        val persistedBossNode = labyrinthPersistedBossNode(validatedRoute)
        val bossRouteEvidence = activeNodeType == LabyrinthNodeTypes.BOSS ||
            (activeNodeType == null && combatContext == null && persistedBossNode != null)

        // Boss uses the same BATTLE_CHALLENGE shell, but its title is "首领战格子" rather than
        // "战斗格子（普通/极难）".  The route node type is already semantic evidence that this
        // is a Boss; recover that context here before the ordinary/EX difficulty gate runs.
        // This also heals a transient/null combatContext or a restarted session without asking
        // the Boss title to match either of the normal/extreme suffix templates.
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            bossRouteEvidence &&
            combatContext?.kind != LabyrinthCombatKind.BOSS
        ) {
            activeNodeType = LabyrinthNodeTypes.BOSS
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.BOSS,
                teamIndex = 1,
                targetCount = combatContext?.targetCount ?: 1,
            )
            currentExEncounterStrategy = null
            exSlot3ProbePending = false
            exEncounterProbeStartedAt = Long.MIN_VALUE
            lastBattleTeamRecommendationKey = null
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已确认首领节点挑战页；Boss不参与普通/极难二分类",
                )
            }
        }

        // The game can enter EX from an event even though the route node itself is EVENT.  “极难”
        // is game-visible evidence and therefore has priority over the route-derived combat kind.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            observation?.extremeChallenge == true &&
            combatContext?.kind != LabyrinthCombatKind.EX
        ) {
            combatContext = LabyrinthCombatContext(
                kind = LabyrinthCombatKind.EX,
                targetCount = when {
                    observation.multiMonsterLikely -> 5
                    observation.specialDualLikely -> 2
                    else -> 1
                },
            )
            currentExEncounterStrategy = null
            exSlot3ProbePending = false
            exSlot3ProbeAttempts = 0
            exEncounterProbeStartedAt = timestampMillis
            synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
            lastBattleTeamRecommendationKey = null
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "挑战页识别到极难，已按EX遭遇接管；正在识别具体攻略",
            )
        }

        // EVENT arena choices can open an ordinary challenge page while the route-derived
        // combatContext is still null (the clicked map node itself is EVENT).  Once the challenge
        // title has positively resolved as non-EX, restore an explicit NORMAL context so retry,
        // team recommendation and failure policy never run with an untyped battle.
        val persistedCurrentNode = labyrinthPersistedCurrentNode(validatedRoute)
        val eventRouteEvidence = activeNodeType == LabyrinthNodeTypes.EVENT ||
            (activeNodeType == null && persistedCurrentNode?.blockType == LabyrinthNodeTypes.EVENT)
        if (
            page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            eventRouteEvidence &&
            observation?.challengeDifficultyResolved == true &&
            observation.extremeChallenge != true &&
            combatContext == null
        ) {
            if (activeNodeType == null) {
                activeNodeType = LabyrinthNodeTypes.EVENT
                activeNodeArea = persistedCurrentNode?.area
            }
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
            lastBattleTeamRecommendationKey = null
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "事件进入普通战挑战页，已恢复普通战上下文",
                )
            }
        }

        // Challenge-title OCR is asynchronous. In particular, an EVENT may enter an EX while the
        // route-derived combat context is still null/non-EX. The first recognized challenge frame
        // must therefore wait for a positive "战斗格子" title read before a normal challenge is
        // allowed. Otherwise the generic challenge branch can win the race one frame before
        // "极难" arrives.
        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
            combatContext?.kind != LabyrinthCombatKind.BOSS &&
            combatContext?.kind != LabyrinthCombatKind.EX &&
            observation?.challengeDifficultyResolved != true
        ) {
            if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
                finishFromPlanner(
                    sessionId,
                    "挑战页标题在限定时间内未可靠确认普通/极难；不执行盲目挑战",
                )
            } else if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "挑战页：正在确认普通/极难，确认前禁止点击挑战",
                )
            }
            return true
        }

        // A monster-detail modal is action-owned only when this session opened slot 3.  Manually
        // opened details remain read-only, which prevents a generic UNKNOWN fallback from closing
        // a panel the user is inspecting.
        if (observation?.monsterDetailOpen == true) {
            if (!exSlot3ProbePending) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "检测到魔物详情；非程序发起的EX识别，保持人工控制",
                    )
                }
                return true
            }
            if (!observation.trusted || observation.encounterId == null) {
                if (exEncounterProbeStartedAt != Long.MIN_VALUE &&
                    timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS
                ) {
                    finishFromPlanner(
                        sessionId,
                        "$exIdentityProbeDescription 详情在限定时间内未匹配已知EX：" +
                            (observation.monsterDetailText?.takeIf(String::isNotBlank) ?: "OCR无可靠文本") +
                            "；保留页面等待补录攻略",
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "正在识别$exIdentityProbeDescription：${observation.monsterDetailText ?: "等待两次独立OCR"}",
                    )
                }
                return true
            }
            val strategy = LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }
            if (strategy == null || strategy.identityName.startsWith("？？？")) {
                finishFromPlanner(
                    sessionId,
                    "$exIdentityProbeDescription 已读为${observation.encounterName ?: observation.encounterId}，但攻略尚未建档；保持详情页",
                )
                return true
            }
            currentExEncounterStrategy = strategy
            combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                kind = LabyrinthCombatKind.EX,
                targetCount = strategy.targetCount,
            )
            lastBattleTeamRecommendationKey = null
            val close = observation.closeButtonRect
            if (close == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}，但详情关闭按钮结构未确认",
                    )
                }
                return true
            }
            if (lastPostEntryActionAt != Long.MIN_VALUE &&
                timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS
            ) return true
            dispatchPostEntryTap(
                sessionId = sessionId,
                label = "EX识别完成：关闭魔物详情",
                rect = close,
                timestampMillis = timestampMillis,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "已识别EX：${strategy.identityName}；关闭详情后按攻略编组",
                )
            }
            return true
        }

        if (page != LabyrinthEntryPageState.BATTLE_CHALLENGE) return false
        val isEx = combatContext?.kind == LabyrinthCombatKind.EX || observation?.extremeChallenge == true
        if (!isEx) return false

        // Single-target EX can be identified directly from the stable challenge-page text.
        if (currentExEncounterStrategy == null && observation?.trusted == true && observation.encounterId != null) {
            LabyrinthExEncounterCatalog.all.firstOrNull { it.id == observation.encounterId }?.let { strategy ->
                currentExEncounterStrategy = strategy
                combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.EX)).copy(
                    kind = LabyrinthCombatKind.EX,
                    targetCount = strategy.targetCount,
                )
                lastBattleTeamRecommendationKey = null
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        message = "已识别EX：${strategy.identityName}；准备按攻略进入编组",
                    )
                }
            }
        }
        if (currentExEncounterStrategy != null) return false

        if (exEncounterProbeStartedAt == Long.MIN_VALUE) exEncounterProbeStartedAt = timestampMillis
        if (exSlot3ProbePending) {
            if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS) {
                if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                    finishFromPlanner(sessionId, "已尝试打开$exIdentityProbeDescription 详情${exSlot3ProbeAttempts}次仍未出现详情页，停止EX自动挑战")
                } else {
                    exSlot3ProbePending = false
                    exEncounterProbeStartedAt = timestampMillis
                    if (activeSessionId == sessionId) {
                        _state.value = _state.value.copy(message = "$exIdentityProbeDescription 详情未出现，准备有限重试")
                    }
                }
            }
            return true
        }

        val structuredProbe = when {
            observation?.multiMonsterLikely == true ->
                "第3只魔物" to observation.slot3InfoButtonRect
            observation?.specialDualLikely == true ->
                "特殊EX右侧本体" to observation.specialDualIdentityInfoButtonRect
            else -> null
        }
        if (structuredProbe != null) {
            val (probeDescription, rect) = structuredProbe
            exIdentityProbeDescription = probeDescription
            if (rect == null) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "已确认EX布局，但$probeDescription 信息按钮未达到安全识别线")
                }
                return true
            }
            if (exSlot3ProbeAttempts >= MAX_EX_SLOT3_PROBE_ATTEMPTS) {
                finishFromPlanner(sessionId, "$probeDescription 详情探测达到上限，未识别具体EX；不发起挑战")
                return true
            }
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return true
            exSlot3ProbeAttempts++
            // Set pending before dispatch so a very fast next capture cannot be treated as a
            // manually opened modal while the executor callback is still finishing.
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                label = "识别EX：查看$probeDescription 详情",
                rect = rect,
                timestampMillis = timestampMillis,
            )
            return true
        }

        // Give direct challenge-page OCR a short chance, then fall back to the fixed Details
        // button for a single-target EX.  Opening Details is non-destructive and lets the existing
        // detail-modal resolver read the name from a much cleaner row.  This also covers future
        // single EX names whose challenge-page text shifts slightly between client revisions.
        val singleDetailRect = labyrinthSingleExDetailProbeRect(
            pageState = page,
            isEx = isEx,
            encounterResolved = currentExEncounterStrategy != null,
            multiMonsterLikely = observation?.multiMonsterLikely == true,
            specialDualLikely = observation?.specialDualLikely == true,
            elapsedMillis = timestampMillis - exEncounterProbeStartedAt,
            stableFrames = postEntryStableFrames,
            attempts = exSlot3ProbeAttempts,
            frameWidth = result.frameWidth,
            frameHeight = result.frameHeight,
            maxAttempts = MAX_EX_SLOT3_PROBE_ATTEMPTS,
        )
        if (singleDetailRect != null) {
            exIdentityProbeDescription = "单体魔物"
            exSlot3ProbeAttempts++
            exSlot3ProbePending = true
            exEncounterProbeStartedAt = timestampMillis
            dispatchPostEntryTap(
                sessionId = sessionId,
                label = "识别EX：查看单体魔物详情",
                rect = singleDetailRect,
                timestampMillis = timestampMillis,
            )
            return true
        }

        // If neither direct OCR nor the bounded detail probe can establish identity, retain the
        // old safe stop rather than challenging an unknown EX.
        if (timestampMillis - exEncounterProbeStartedAt >= EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS) {
            finishFromPlanner(
                sessionId,
                "已确认EX挑战页，但单体名称OCR/详情识别均未建立可信身份；不发起盲目挑战",
            )
        } else if (activeSessionId == sessionId) {
            _state.value = _state.value.copy(
                message = "EX挑战页：等待单体名称OCR；失败后将安全打开详情识别",
            )
        }
        return true
    }

    /**
     * EVENT-only full-roster selector.  This deliberately does not reuse the opening roster's
     * fixed guild policy or 3/3 counter.  The current viewport is ranked with the same role model
     * used by three-choice rewards, then the page's 1-person invite button is confirmed.
     */
    private fun handleEventFreeRoleSelection(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ) {
        if (activeSessionId != sessionId || actionInFlight.get()) return
        val viewport = result.openingCharacterSelection ?: run {
            _state.value = _state.value.copy(message = "事件自由选角：等待角色视口生成")
            return
        }
        if (viewport.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) {
            _state.value = _state.value.copy(message = "事件自由选角：等待当前视口角色稳定识别")
            return
        }

        val visibleSelected = viewport.visibleCharacters
            .filter { it.selected && it.trusted && !it.characterId.isNullOrBlank() }
        if (eventFreeRoleSelectedCharacterId == null && visibleSelected.size == 1) {
            // Respect an already-selected single card (for example a user tap immediately before
            // automation saw the frame) instead of adding a second character.
            eventFreeRoleSelectedCharacterId = visibleSelected.single().characterId
        }

        val selectedId = eventFreeRoleSelectedCharacterId
        if (selectedId != null) {
            val inviteRect = anchorRect(result, EntryAnchorId.INVITE_ENABLED)
                ?: anchorRect(result, EntryAnchorId.INVITE_ENABLED_STANDARD)
            if (inviteRect == null) {
                _state.value = _state.value.copy(
                    message = "事件自由选角：已选择角色，等待 1/1 与“去邀请”按钮就绪",
                )
                return
            }
            if (timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS) return
            dispatchPostEntryTap(
                sessionId = sessionId,
                label = "确认事件自由选角",
                rect = inviteRect,
                timestampMillis = timestampMillis,
                eventFreeRoleCandidateId = selectedId,
                eventFreeRoleConfirm = true,
            )
            return
        }

        val planner = roleRewardChoicePlanner ?: run {
            _state.value = _state.value.copy(
                message = roleRewardChoiceUnavailableReason ?: "事件自由选角：角色评分资料未加载",
            )
            return
        }
        val defenseStacks = relicChoicePolicy.markStacks(
            _state.value.observedRelics,
            relicMarkStackCalibration,
        ).getValue(LabyrinthRelicMark.DEFENSE)
        val pendingSelectedIds = synchronized(pendingAcquiredCharacterIds) {
            pendingAcquiredCharacterIds.toSet()
        }
        val acquiredIds = _state.value.joinedCharacters
            .map(LabyrinthJoinedCharacter::characterId)
            .plus(pendingSelectedIds)
            .toSet()
        val decision = planner.decideFreeVisibleRole(
            candidates = viewport.visibleCharacters,
            acquiredCharacterIds = acquiredIds,
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = defenseStacks),
        )
        when (decision) {
            is LabyrinthRoleRewardChoiceDecision.Wait -> {
                _state.value = _state.value.copy(message = decision.reason)
            }

            is LabyrinthRoleRewardChoiceDecision.Select -> {
                if (!decision.actionSafe) {
                    _state.value = _state.value.copy(
                        message = decision.safetyNote ?: "事件自由选角：推荐尚未达到自动点击安全条件",
                    )
                    return
                }
                if (timestampMillis - lastPostEntryActionAt < POST_ENTRY_ACTION_INTERVAL_MILLIS) return
                _state.value = _state.value.copy(
                    message = "事件自由选角建议：${decision.displayName}；${decision.explanation.take(2).joinToString("；")}",
                )
                dispatchPostEntryTap(
                    sessionId = sessionId,
                    label = "事件自由选角：${decision.displayName}",
                    rect = decision.buttonRect,
                    timestampMillis = timestampMillis,
                    eventFreeRoleCandidateId = decision.characterId,
                )
            }
        }
    }

    /** Dispatch recognized route pages and bounded reward/settlement animation recovery. */
    private fun handlePostEntryPage(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        val pageState = result.observation.state
        val previousPostEntryState = lastPostEntryState
        val roleRewardPage = postEntryIsRoleRewardPage(result)
        val roleRewardSelectionSignature = if (roleRewardPage) {
            labyrinthRoleRewardSelectionSignature(result)
        } else {
            null
        }
        portraitRecovery.observe(
            page = pageState,
            rewardEvidence = pageState == LabyrinthEntryPageState.CHARACTER_JOINED ||
                shouldArmCharacterAcquisitionFallback(pageState, LabyrinthEntryPageState.UNKNOWN, activeNodeType),
            now = timestampMillis,
        )
        if (lastPostEntryState != pageState) {
            lastPostEntryState = pageState
            postEntryStableFrames = 0
            postEntryAttempts = 0
            eventChoiceCommitted = false
            if (pageState == LabyrinthEntryPageState.RELIC_CHOICE) {
                pendingRelicSelection = null
                relicChoiceCommitted = false
            }
            if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
                resetBattleTeamExecutionTracking()
            }
        }
        if (roleRewardPage &&
            roleRewardSelectionSignature != null &&
            roleRewardSelectionSignature != lastObservedRoleRewardSelectionSignature
        ) {
            // A new choice round can reuse the exact same page enum and geometry. Treat the
            // candidate-set change as a page transition for stability/attempt accounting.
            lastObservedRoleRewardSelectionSignature = roleRewardSelectionSignature
            postEntryStableFrames = 0
            postEntryAttempts = 0
        }
        postEntryStableFrames++

        if (handleExEncounterRecognitionFrame(sessionId, result, timestampMillis)) return

        if (roleRewardPage) {
            if (!roleRewardBatchActive) roleRewardJoinedSequenceStarted = false
            roleRewardBatchActive = true
            // Choice rounds come first. Any generic reward fallback inherited from the Boss/
            // event result must stop here so an inter-choice UNKNOWN transition is never tapped.
            if (!roleRewardJoinedSequenceStarted && characterAcquisitionActive) {
                clearCharacterAcquisitionContext()
            }
            // The page enum can remain INITIAL_CHARACTER_SELECTION across several reward rounds.
            // Only the candidate-set signature tells us whether this exact round was already
            // clicked. A new candidate set immediately re-arms automatic selection.
            roleRewardChoiceCommitted = roleRewardSelectionSignature != null &&
                roleRewardSelectionSignature == committedRoleRewardSelectionSignature
        }

        // The role-card selection is human input, but the following portrait/召唤 animation is
        // not. Some event/link/reward paths skip a separately recognizable role-selection page
        // and expose only UNKNOWN frames, so arm the bounded safe-area fallback from the last
        // known reward page instead of waiting forever for a role-selection transition.
        if (!characterAcquisitionActive &&
            !(roleRewardBatchActive && !roleRewardJoinedSequenceStarted) &&
            shouldArmCharacterAcquisitionFallback(
                previousPage = previousPostEntryState,
                currentPage = pageState,
                activeNodeType = activeNodeType,
            )
        ) {
            characterAcquisitionActive = true
            waitingForManualRoleSelection = false
            characterAcquisitionClicks = 0
            characterAcquisitionStartedAt = timestampMillis
            lastCharacterAcquisitionActionAt = Long.MIN_VALUE
            postEntryAttempts = 0
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已进入角色/奖励动画兜底，连续点击安全区域推进")
            }
        }

        // A multi-role settlement is one batch: several choice pages can be followed by several
        // CHARACTER_JOINED popups. Do not clear the acquisition driver between those pages.
        val keepsRoleRewardBatch = labyrinthKeepsRoleRewardBatchOnPage(pageState, roleRewardPage)
        val keepsAcquisitionPage = pageState in setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.ITEM_REWARD,
        ) || (roleRewardBatchActive && roleRewardPage)
        if (characterAcquisitionActive && !keepsAcquisitionPage) {
            clearCharacterAcquisitionContext()
        }
        if (roleRewardBatchActive && !keepsRoleRewardBatch) {
            clearRoleRewardBatchTracking()
        }

        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION &&
            previousPostEntryState == LabyrinthEntryPageState.BATTLE_RESULT &&
            result.battleTeamSelection?.bossTeamIndex == null &&
            combatContext?.kind == LabyrinthCombatKind.BOSS
        ) {
            combatContext = combatContext?.copy(
                teamIndex = (combatContext?.teamIndex ?: 1) + 1,
            )
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    combatContext = combatContext,
                    message = "Boss第${combatContext?.teamIndex}队编组：正在生成安全编组计划",
                )
            }
        }
        if (pageState == LabyrinthEntryPageState.UNKNOWN) {
            if (postEntryUnknownSince == Long.MIN_VALUE) postEntryUnknownSince = timestampMillis
            if (!characterAcquisitionActive && postBossStage == LabyrinthPostBossStage.NONE &&
                timestampMillis - postEntryUnknownSince > UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS
            ) {
                finishFromPlanner(sessionId, "路线阶段未知页面超过限定时间，已停止并保留诊断状态")
                return
            }
        } else {
            postEntryUnknownSince = Long.MIN_VALUE
        }
        if (postBossStage == LabyrinthPostBossStage.NONE &&
            pageState == LabyrinthEntryPageState.RUN_CLEAR_RESULT
        ) {
            postBossStage = LabyrinthPostBossStage.SCORE_RESULT
            postBossStartedAt = timestampMillis
            postBossUnknownAttempts = 0
        }
        if (postBossStage == LabyrinthPostBossStage.CHEST_SEQUENCE &&
            pageState == LabyrinthEntryPageState.ITEM_REWARD
        ) {
            postBossStage = LabyrinthPostBossStage.FINAL_ITEM_REWARD
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE
        ) {
            if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return
            markRunCleared(sessionId)
            return
        }
        if (postBossStage == LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME &&
            postBossStartedAt != Long.MIN_VALUE &&
            timestampMillis - postBossStartedAt > FINAL_HOME_RETURN_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(sessionId, "最终道具已关闭，但未在限定时间内返回黎明界主页")
            return
        }

        // Later role rewards are human card selections. The common "选择" template is visible
        // before the user chooses anything, so it must never be used as an automatic tap target.
        // We only arm the automatic animation driver after the page itself changes, which proves
        // that the user has completed the selection.
        if (roleRewardPage) {
            waitingForManualRoleSelection = true
            if (postEntryStableFrames == POST_ENTRY_STABLE_FRAMES && activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "角色奖励批次：继续完成当前三选一；全部选完后统一处理角色加入弹窗")
            }
        } else if (waitingForManualRoleSelection &&
            pageState != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION
        ) {
            waitingForManualRoleSelection = false
            if (roleRewardBatchActive && activeSessionId == sessionId) {
                _state.value = _state.value.copy(
                    message = "角色奖励批次选择过渡中；不盲点，等待下一次三选一或首个角色加入弹窗",
                )
            }
        }

        if (pageState == LabyrinthEntryPageState.CHARACTER_JOINED && roleRewardBatchActive) {
            roleRewardJoinedSequenceStarted = true
            waitingForManualRoleSelection = false
            if (!characterAcquisitionActive) {
                characterAcquisitionActive = true
                characterAcquisitionClicks = 0
                characterAcquisitionStartedAt = timestampMillis
                lastCharacterAcquisitionActionAt = Long.MIN_VALUE
                postEntryAttempts = 0
            }
        }

        if (postEntryStableFrames < POST_ENTRY_STABLE_FRAMES) return
        if (pageState == LabyrinthEntryPageState.BATTLE_FAILED) {
            val context = combatContext
            if (context == null) {
                finishFromPlanner(sessionId, "已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战")
                return
            }
            if (
                !_state.value.dryRun &&
                labyrinthShouldRerollAfterBattleFailure(
                    rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
                    battleRetryCount = battleRetryCount,
                )
            ) {
                finishFromPlannerAndRequestReroll(
                    sessionId,
                    "${labyrinthBattleKindLabel(context.kind)}连续3次挑战失败，不结算当前战斗",
                )
                return
            }
            val retryLimit = labyrinthEffectiveBattleRetryLimit(
                kind = context.kind,
                rerollAfterThreeFailures = rerollAfterThreeBattleFailures,
            )
            if (battleRetryCount >= retryLimit) {
                finishFromPlanner(
                    sessionId,
                    "${labyrinthBattleKindLabel(context.kind)}已达到自动重试上限：" +
                        "已重试${battleRetryCount}次；保留在失败页，不自动点击结束",
                )
                return
            }
            if (result.battleFailure == null) {
                finishFromPlanner(sessionId, "战斗失败页按钮结构未达到安全线；不执行固定坐标重试")
                return
            }
        }
        if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            handleBattleTeamSelectionExecution(
                sessionId = sessionId,
                result = result,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                timestampMillis = timestampMillis,
            )
            return
        }
        val postEntryActionInterval = if (characterAcquisitionActive) {
            CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS
        } else {
            POST_ENTRY_ACTION_INTERVAL_MILLIS
        }
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < postEntryActionInterval
        ) {
            return
        }

        val finalAnimationPage = pageState == LabyrinthEntryPageState.UNKNOWN ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION ||
            pageState == LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT
        val finalAnimationActive = postBossStage == LabyrinthPostBossStage.BEFORE_SCORE ||
            postBossStage == LabyrinthPostBossStage.CHEST_SEQUENCE
        val maxAttempts = when {
            characterAcquisitionActive -> MAX_CHARACTER_ACQUISITION_CLICKS
            finalAnimationActive && finalAnimationPage -> MAX_POST_BOSS_UNKNOWN_ATTEMPTS
            activeNodeType == LabyrinthNodeTypes.EVENT -> MAX_EVENT_ACTIONS
            else -> MAX_POST_ENTRY_ATTEMPTS
        }
        if (characterAcquisitionActive &&
            characterAcquisitionStartedAt != Long.MIN_VALUE &&
            timestampMillis - characterAcquisitionStartedAt > CHARACTER_ACQUISITION_TIMEOUT_MILLIS
        ) {
            finishFromPlanner(sessionId, "角色获得流程超过限定时间，已停止并保留诊断状态")
            return
        }
        if (postEntryAttempts >= maxAttempts) {
            val reason = if (characterAcquisitionActive) {
                "角色获得动画推进达到上限，已停止并保留诊断状态"
            } else if (finalAnimationActive && finalAnimationPage) {
                "最终结算动画推进达到上限，未找到下一结算页面"
            } else {
                "${pageState.name} 页面重复点击达到上限，已暂停等待人工检查"
            }
            finishFromPlanner(sessionId, reason)
            return
        }

        var roleRewardWaitReason: String? = null
        val roleRewardPlan = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            !roleRewardChoiceCommitted
        ) {
            if (postEntryStableFrames < ROLE_REWARD_SELECTION_STABLE_FRAMES) {
                roleRewardWaitReason =
                    "角色三选一身份稳定中：$postEntryStableFrames/$ROLE_REWARD_SELECTION_STABLE_FRAMES"
                null
            } else when (val decision = _state.value.roleRewardChoiceDecision) {
                is LabyrinthRoleRewardChoiceDecision.Select -> {
                    if (decision.actionSafe) {
                        "选择角色：${decision.displayName}；${decision.explanation.joinToString("；")}" to
                            decision.buttonRect
                    } else {
                        roleRewardWaitReason = decision.safetyNote ?: "当前角色推荐仅供参考，不满足自动点击安全条件"
                        null
                    }
                }
                is LabyrinthRoleRewardChoiceDecision.Wait -> {
                    roleRewardWaitReason = decision.reason
                    null
                }
                null -> {
                    roleRewardWaitReason = "角色三选一决策尚未生成"
                    null
                }
            }
        } else {
            null
        }
        val manualInputMessage = if (
            pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
            postEntryIsRoleRewardPage(result) &&
            roleRewardChoicePlanner == null
        ) {
            listOfNotNull(
                "角色三选一暂由人工完成",
                roleRewardChoiceUnavailableReason,
            ).joinToString("；")
        } else if (pageState == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
            labyrinthBattleTeamSelectionMessage(
                combatContext = combatContext,
                recommendation = _state.value.battleTeamRecommendation,
                unavailableReason = _state.value.battleTeamRecommendationUnavailableReason,
                selectionPlan = _state.value.battleTeamSelectionPlan,
            )
        } else {
            labyrinthManualInputMessage(pageState)
        }
        var shopWaitReason: String? = null
        // Page ownership is the hard action boundary. Workflow flags may refine the action
        // *inside* the current page, but they can never make one page execute another page's
        // proposal. This is intentionally page-first rather than flow-first.
        val plan: Pair<String, EntryPixelRect>? = when (pageState) {
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> when {
                roleRewardPlan != null -> roleRewardPlan
                roleRewardWaitReason != null || manualInputMessage != null -> null
                else -> null
            }

            LabyrinthEntryPageState.CHARACTER_JOINED ->
                "关闭角色加入结果" to (
                    anchorRect(result, EntryAnchorId.JOINED_CLOSE)
                        ?: anchorRect(result, EntryAnchorId.JOINED_CLOSE_STANDARD)
                        ?: referenceRect(960, 870, frameWidth, frameHeight)
                    )

            LabyrinthEntryPageState.ITEM_REWARD -> when {
                postBossStage == LabyrinthPostBossStage.FINAL_ITEM_REWARD ->
                    "关闭最终获得道具" to (
                        anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: referenceRect(960, 870, frameWidth, frameHeight)
                    )
                else ->
                    "关闭获得道具界面" to (
                        anchorRect(result, EntryAnchorId.ITEM_REWARD_CLOSE)
                            ?: referenceRect(960, 870, frameWidth, frameHeight)
                    )
            }

            LabyrinthEntryPageState.UNKNOWN -> when {
                labyrinthHasBattleControls(result) -> null
                labyrinthBossSettlementNextButtonRect(
                    pageState = pageState,
                    combatContext = combatContext,
                    nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                    bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                ) != null ->
                    "Boss结算：下一步" to requireNotNull(
                        labyrinthBossSettlementNextButtonRect(
                            pageState = pageState,
                            combatContext = combatContext,
                            nextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON],
                            bossSummaryNextButtonMatch = result.anchorMatches[EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON],
                        ),
                    )
                characterAcquisitionActive -> {
                    val rect = if (activeNodeType == LabyrinthNodeTypes.EVENT) {
                        val (x, y) = labyrinthEventUnknownFallbackPoint(characterAcquisitionClicks)
                        referenceRect(x, y, frameWidth, frameHeight)
                    } else {
                        referenceRect(960, 780, frameWidth, frameHeight)
                    }
                    "推进角色获得动画" to rect
                }
                finalAnimationActive && finalAnimationPage ->
                    "推进最终结算动画" to finalSettlementFallbackRect(
                        result = result,
                        pageState = pageState,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                    )
                portraitRecovery.canAttempt(timestampMillis, labyrinthPortraitRecoveryBlocked(result)) ->
                    "尝试收起角色立绘" to referenceRect(960, 780, frameWidth, frameHeight)
                else -> null
            }

            LabyrinthEntryPageState.EVENT_ANIMATION -> when {
                labyrinthHasBattleControls(result) -> null
                characterAcquisitionActive ->
                    "推进角色获得动画" to referenceRect(960, 780, frameWidth, frameHeight)
                else -> (
                    anchorRect(result, EntryAnchorId.EVENT_ANIMATION_SKIP)
                        ?: referenceRect(960, 780, frameWidth, frameHeight)
                    ).let { "推进事件动画" to it }
            }

            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
            -> if (finalAnimationActive && finalAnimationPage) {
                "推进最终结算动画" to finalSettlementFallbackRect(
                    result = result,
                    pageState = pageState,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                )
            } else {
                null
            }

            LabyrinthEntryPageState.RUN_CLEAR_RESULT ->
                if (postBossStage == LabyrinthPostBossStage.SCORE_RESULT) {
                    "关闭最终分数结果" to (
                        anchorRect(result, EntryAnchorId.RUN_RESULT_CLOSE_BUTTON)
                            ?: referenceRect(960, 1000, frameWidth, frameHeight)
                    )
                } else {
                    null
                }

            LabyrinthEntryPageState.LINK_CHOICE ->
                (result.linkChoiceSelection?.preferredChoice
                    ?: result.linkChoiceSelection?.choices?.firstOrNull())?.let { choice ->
                    "选择连结印记：${choice.element.label}" to choice.selectionButtonRect
                }

            LabyrinthEntryPageState.RELIC_CHOICE -> {
                if (relicChoiceCommitted) {
                    null
                } else {
                    relicChoicePolicy.choose(
                        acquired = _state.value.observedRelics,
                        candidates = result.relicChoiceSelection?.choices.orEmpty(),
                        lockedFocus = relicFocusMark,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = _state.value.routeProgress?.currentArea,
                    )?.let { decision ->
                        if (decision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                            relicFocusMark = decision.focusMark
                        }
                        pendingRelicSelection = decision.choice
                        "选择遗物：${decision.choice.displayName}；${decision.reason}" to
                            decision.choice.selectionButtonRect
                    }
                }
            }

            LabyrinthEntryPageState.EVENT_CHOICE -> if (eventChoiceCommitted) {
                null
            } else {
                when (val decision = _state.value.eventChoiceDecision) {
                    is LabyrinthEventChoiceDecision.Select -> if (decision.actionSafe) {
                        "选择事件：${decision.choiceId} ${decision.label}；${decision.explanation}" to
                            decision.buttonRect
                    } else {
                        null
                    }
                    is LabyrinthEventChoiceDecision.Wait -> null
                    null -> labyrinthSingleChoiceEventButtonRect(result)?.let { rect ->
                        "选择事件：唯一选项（单选事件结构确认）" to rect
                    }
                }
            }

            LabyrinthEntryPageState.SHOP -> when (val decision = currentShopDecision(result)) {
                is LabyrinthShopDecision.BuyRelic -> {
                    val relic = decision.relicDecision.choice
                    if (decision.relicDecision.phase != LabyrinthRelicChoicePhase.BUILD_BASELINE) {
                        relicFocusMark = decision.relicDecision.focusMark
                    }
                    plannedShopRoleImprintLabel = null
                    plannedShopPurchaseRelicId = relic.relicId
                    "购买遗物：${relic.displayName ?: relic.relicId}；${decision.relicDecision.reason}" to
                        decision.item.buyButtonRect
                }

                is LabyrinthShopDecision.BuyRoleImprint -> {
                    plannedShopPurchaseRelicId = null
                    plannedShopRoleImprintLabel = decision.label
                    "购买印记：${decision.label}；${decision.reason}" to decision.item.buyButtonRect
                }

                is LabyrinthShopDecision.Refresh -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_REFRESH_BUTTON)
                    if (rect != null) {
                        "最终区域刷新商店" to rect
                    } else {
                        shopWaitReason = "策略允许刷新，但刷新按钮没有达到安全识别线"
                        null
                    }
                }

                is LabyrinthShopDecision.Close -> {
                    clearPlannedShopPurchase()
                    val rect = anchorRect(result, EntryAnchorId.SHOP_CLOSE)
                    if (rect != null) {
                        "关闭商店：${decision.reason}" to rect
                    } else {
                        shopWaitReason = "商店已无计划购买项，但关闭按钮未稳定识别"
                        null
                    }
                }

                is LabyrinthShopDecision.Wait -> {
                    shopWaitReason = decision.reason
                    null
                }

                null -> {
                    shopWaitReason = "商店商品识别尚未生成"
                    null
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION -> {
                val plannedId = plannedShopPurchaseRelicId
                val plannedImprint = plannedShopRoleImprintLabel
                val candidate = result.shopObservation?.purchaseCandidate
                when {
                    plannedId == null && plannedImprint == null -> {
                        shopWaitReason = "未检测到程序发起的购买，本次确认弹窗保持人工控制"
                        null
                    }
                    plannedShopPurchaseStartedAt == Long.MIN_VALUE ||
                        timestampMillis - plannedShopPurchaseStartedAt > SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS -> {
                        clearPlannedShopPurchase()
                        shopWaitReason = "程序发起的购买确认已超时，本次弹窗保持人工控制"
                        null
                    }
                    plannedImprint != null -> {
                        // The originating shop-card title was already OCR-classified as a role
                        // imprint. The confirmation modal is transaction-bound and short-lived;
                        // require the normal confirmation button, but do not run relic icon
                        // identity against an item that is explicitly not a relic.
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            "确认购买印记：$plannedImprint" to confirmRect
                        } else {
                            shopWaitReason = "印记购买确认已建立，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                    candidate?.relicId == null -> {
                        shopWaitReason = "购买确认弹窗中的遗物尚未可靠识别，拒绝自动确认"
                        null
                    }
                    candidate.relicId != plannedId -> {
                        shopWaitReason = "购买确认遗物${candidate.relicId}与计划${plannedId}不一致，拒绝自动确认"
                        null
                    }
                    else -> {
                        val confirmRect = anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        if (confirmRect != null) {
                            "确认购买遗物：${candidate.displayName ?: candidate.relicId}" to confirmRect
                        } else {
                            // Never fall back to the old hard-coded point: on the current modal
                            // it lands on the currency-change row above the confirm button.
                            shopWaitReason = "购买确认遗物已核对，但确认按钮未达到安全识别线"
                            null
                        }
                    }
                }
            }

            LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE ->
                if (plannedShopPurchaseRelicId != null || plannedShopRoleImprintLabel != null) {
                    // Purchase-complete is a single-button standard modal; dismissing it returns
                    // to SHOP, where all visible slots are recognized again. This is what makes
                    // the game-side "后面的商品补位" naturally enter the next decision cycle.
                    "关闭购买完成" to referenceRect(960, 746, frameWidth, frameHeight)
                } else {
                    shopWaitReason = "人工购买完成弹窗保持人工控制"
                    null
                }

            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION ->
                "确认退出商店" to (
                    anchorRect(result, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON)
                        ?: referenceRect(1180, 745, frameWidth, frameHeight)
                )

            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> null

            LabyrinthEntryPageState.BATTLE_FAILED ->
                result.battleFailure?.let { failure ->
                    "战斗失败：重新挑战" to failure.retryButtonRect
                }

            LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                if (
                    labyrinthBattleChallengeCanAutoStart(
                        combatContext = combatContext,
                        challengeDifficultyResolved = result.exEncounter?.challengeDifficultyResolved == true,
                        extremeChallenge = result.exEncounter?.extremeChallenge == true,
                        exEncounterResolved = currentExEncounterStrategy != null,
                    )
                ) {
                    "发起挑战" to (
                        anchorRect(result, EntryAnchorId.BATTLE_CHALLENGE_BUTTON)
                            ?: referenceRect(1640, 920, frameWidth, frameHeight)
                    )
                } else {
                    null
                }

            LabyrinthEntryPageState.BATTLE_RESULT ->
                "战斗结算：下一步" to (
                    anchorRect(result, EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON)
                        ?: referenceRect(1640, 990, frameWidth, frameHeight)
                )

            LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
            LabyrinthEntryPageState.NODE_SELECTION,
            -> null

            else -> null
        }
        if (plan == null) {
            if (activeSessionId == sessionId && postEntryStableFrames >= POST_ENTRY_STABLE_FRAMES) {
                val message = when (pageState) {
                    LabyrinthEntryPageState.BATTLE_IN_PROGRESS ->
                        "战斗中，等待结算"
                    LabyrinthEntryPageState.BATTLE_FAILED ->
                        "战斗失败页已识别，等待安全重试条件"
                    LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                        when {
                            combatContext?.kind == LabyrinthCombatKind.EX || result.exEncounter?.extremeChallenge == true ->
                                "EX挑战页：等待具体遭遇攻略识别完成，禁止直接进入编组"
                            result.exEncounter?.challengeDifficultyResolved != true ->
                                "挑战页：等待确认普通/极难，禁止盲目挑战"
                            else -> "挑战页已确认，等待安全挑战条件"
                        }
                    LabyrinthEntryPageState.SHOP ->
                        shopWaitReason ?: "商店策略等待商品识别稳定"
                    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
                    -> shopWaitReason ?: "商店购买弹窗等待安全条件"
                    LabyrinthEntryPageState.EVENT_CHOICE ->
                        when (val decision = _state.value.eventChoiceDecision) {
                            is LabyrinthEventChoiceDecision.Select ->
                                decision.safetyNote ?: "事件推荐已生成，等待按钮稳定"
                            is LabyrinthEventChoiceDecision.Wait -> decision.reason
                            null -> "事件选项已识别，等待OCR语义决策"
                        }
                    LabyrinthEntryPageState.UNKNOWN ->
                        when {
                            roleRewardBatchActive && !roleRewardJoinedSequenceStarted ->
                                "角色奖励批次选择过渡中；等待下一次三选一，不执行盲点"
                            finalAnimationActive -> "最终结算动画中，程序会在有限次数内推进"
                            else -> "未知页面，自动点击已暂停；等待页面识别恢复"
                        }
                    else -> roleRewardWaitReason
                        ?: manualInputMessage
                        ?: "${pageState.name}：暂不自动处理，等待画面推进"
                }
                _state.value = _state.value.copy(message = message)
            }
            return
        }
        val (label, rect) = plan
        if (rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight) return
        val dispatchedRoleRewardCharacterId = if (label.startsWith("选择角色：")) {
            (_state.value.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)?.characterId
        } else {
            null
        }
        dispatchPostEntryTap(
            sessionId = sessionId,
            label = label,
            rect = rect,
            timestampMillis = timestampMillis,
            roleRewardSelectionSignature = if (label.startsWith("选择角色：")) {
                roleRewardSelectionSignature
            } else {
                null
            },
            roleRewardSelectedCharacterId = dispatchedRoleRewardCharacterId,
            shopPurchaseRelicId = if (
                label.startsWith("购买遗物：") || label.startsWith("确认购买遗物：")
            ) plannedShopPurchaseRelicId else null,
            shopPurchaseRoleImprintLabel = if (
                label.startsWith("购买印记：") || label.startsWith("确认购买印记：")
            ) plannedShopRoleImprintLabel else null,
        )
    }

    private fun handleBattleTeamSelectionExecution(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        if (actionInFlight.get() || activeSessionId != sessionId) return
        val observation = result.battleTeamSelection ?: return

        var preparationStep: LabyrinthBattleTeamExecutionStep? = null
        if (observation.bossTeamIndex != null && combatContext?.kind == LabyrinthCombatKind.BOSS) {
            if (bossEditorPreparationStage == LabyrinthBossEditorPreparationStage.INACTIVE) {
                // Also recovers a session that entered an already-open Boss editor directly.
                bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.TEAM_1
                bossEditorBlocked = false
                pendingBossTeamAdvance = null
            }
            if (bossEditorPreparationStage != LabyrinthBossEditorPreparationStage.COMPLETE) {
                val preparation = labyrinthBossEditorPreparationDecision(
                    observation = observation,
                    stage = bossEditorPreparationStage,
                )
                bossEditorPreparationStage = preparation.stage
                if (preparation.stage == LabyrinthBossEditorPreparationStage.COMPLETE) {
                    bossEditorTeamIndex = 1
                    bossEditorBlocked = false
                    pendingBossTeamAdvance = null
                    preparedBossFirstTeamIds = emptyList()
                    synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
                    synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
                    combatContext = (combatContext ?: LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
                        .copy(kind = LabyrinthCombatKind.BOSS, teamIndex = 1)
                    lastBattleTeamRecommendationKey = null
                    lastBattleTeamSelectionPlanLog = null
                    resetBattleTeamExecutionTracking()
                    _state.value = _state.value.copy(
                        combatContext = combatContext,
                        battleTeamRecommendation = null,
                        battleTeamSelectionPlan = null,
                        battleTeamRecommendationUnavailableReason = null,
                        message = "Boss编组预处理完成：三队已清空并返回队伍1；开始扫描并生成新阵容",
                    )
                    return
                }
                preparationStep = preparation.step
                if (preparationStep == null) {
                    _state.value = _state.value.copy(message = "Boss编组预处理：等待队伍页与当前成员稳定识别")
                    return
                }
            }
        }

        if (preparationStep == null && observation.bossTeamIndex != null && bossEditorBlocked) {
            _state.value = _state.value.copy(message = "Boss切队记录不完整，请从队伍1重新启动自动编组；禁止复用其他队建议")
            return
        }
        val step = preparationStep ?: if (
            bossTeamMode == LabyrinthBossTeamMode.SINGLE_TEAM &&
            preparedBossFirstTeamIds.isNotEmpty()
        ) {
            labyrinthBossSingleTeamExecutionStep(
                observation,
                preparedBossFirstTeamIds,
                anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
            ).also { next ->
                if (next == null) {
                    _state.value = _state.value.copy(message = when {
                        observation.recognitionState != com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState.STABLE ->
                            "Boss切队后等待画面稳定"
                        observation.selectedCharacters.isNotEmpty() ->
                            "Boss单队首战阻塞：第${observation.bossTeamIndex}队并非空队，请核对后从队伍1重新启动"
                        else -> "Boss第一队已确认；等待可靠识别当前队伍页及战斗开始按钮"
                    })
                }
            }
        } else {
            val recommendation = _state.value.battleTeamRecommendation ?: return
            val plan = _state.value.battleTeamSelectionPlan ?: return
            if (!plan.isStillValid(sessionId, recommendation, observation)) return
            val search = battleRosterSearch.observe(sessionId, observation, plan.recommendedIds)
            if (plan.scrollRequired) {
                when (search) {
                    LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE -> {
                        _state.value = _state.value.copy(message = "角色列表滑动后等待越界回弹并重新稳定")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.EXHAUSTED -> {
                        val missing = plan.notCurrentlyVisibleIds.joinToString("、") {
                            plan.recommendedNames[it] ?: plan.currentlySelectedNames[it] ?: it
                        }
                        finishFromPlanner(sessionId, "自动编组已从顶部查找到底部，${observation.currentFilter.label}筛选仍未找到可靠目标：$missing；停止滚动")
                        return
                    }
                    LabyrinthBattleRosterSearchDecision.UNAVAILABLE -> {
                        finishFromPlanner(sessionId, "自动编组无法确认角色列表已稳定或可靠识别滚动边界，已停止；不执行盲目滑动")
                        return
                    }
                    else -> Unit
                }
            }
            val plannedStep = labyrinthBattleTeamExecutionStep(
                plan = plan,
                sessionId = sessionId,
                recommendation = recommendation,
                observation = observation,
                startButtonRect = anchorRect(result, EntryAnchorId.BATTLE_TEAM_START_BUTTON),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                scrollDirection = if (search == LabyrinthBattleRosterSearchDecision.TO_TOP)
                    LabyrinthBattleRosterScrollDirection.TO_TOP else LabyrinthBattleRosterScrollDirection.NEXT_PAGE,
            )
            plannedStep
        } ?: return

        if (step.key != lastBattleTeamExecutionKey) {
            lastBattleTeamExecutionKey = step.key
            battleTeamExecutionAttempts = 0
        }
        val isCharacterToggle = step.kind == LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER ||
            step.kind == LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER
        val maximumAttempts = if (isCharacterToggle) 1 else MAX_BATTLE_TEAM_STEP_ATTEMPTS
        if (battleTeamExecutionAttempts >= maximumAttempts) {
            // A blind retry can undo a successful select/deselect when the recognition frame is
            // late. Wait for visual confirmation and stop safely instead of toggling the card.
            if (
                isCharacterToggle &&
                timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS
            ) {
                return
            }
            finishFromPlanner(sessionId, "自动编组动作无视觉反馈：${step.label}")
            return
        }
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS &&
            battleTeamScrollActions >= MAX_BATTLE_TEAM_SCROLL_ACTIONS
        ) {
            finishFromPlanner(sessionId, "自动编组滚动达到上限，仍未找到推荐角色")
            return
        }
        if (lastBattleTeamActionAt != Long.MIN_VALUE &&
            timestampMillis - lastBattleTeamActionAt < BATTLE_TEAM_ACTION_INTERVAL_MILLIS
        ) {
            return
        }
        dispatchBattleTeamAction(sessionId, step, timestampMillis)
    }

    private fun dispatchBattleTeamAction(
        sessionId: AutomationSessionId,
        step: LabyrinthBattleTeamExecutionStep,
        timestampMillis: Long,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        battleTeamExecutionAttempts++
        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
            battleTeamScrollActions++
        }
        lastBattleTeamActionAt = timestampMillis
        actionScope.launch {
            val executor = actionExecutor
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(step.action, "动作执行器不可用")
            } else {
                executor.execute(sessionId, step.action)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> if (activeSessionId == sessionId) {
                    if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                        val direction = step.scrollDirection
                        val originPosition = step.scrollOriginPosition
                        if (direction != null && originPosition != null) {
                            battleRosterSearch.recordExecutedScroll(direction, originPosition)
                        }
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM) {
                        pendingBossTeamAdvance = ((bossEditorTeamIndex ?: 1) + 1) to step.confirmedTeamIds
                    }
                    if (step.kind == LabyrinthBattleTeamExecutionKind.START_BATTLE) {
                        battleWait.onStartExecuted(clock())
                        if (step.confirmedTeamIds.isNotEmpty()) {
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures += labyrinthBattleTeamSignature(step.confirmedTeamIds)
                            }
                        }
                        synchronized(committedBattleCharacterIds) {
                            committedBattleCharacterIds += step.confirmedTeamIds
                        }
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        actionCount = current.actionCount + 1,
                        lastActionLabel = step.label,
                        message = "已执行：${step.label}；等待画面确认",
                    )
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
                is AutomationActionResult.Rejected -> {
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        battleTeamExecutionAttempts = (battleTeamExecutionAttempts - 1).coerceAtLeast(0)
                        if (step.kind == LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS) {
                            battleTeamScrollActions = (battleTeamScrollActions - 1).coerceAtLeast(0)
                        }
                        lastBattleTeamActionAt = Long.MIN_VALUE
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("自动编组动作被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    private fun resetBattleTeamExecutionTracking() {
        battleRosterSearch.reset()
        lastBattleTeamExecutionKey = null
        battleTeamExecutionAttempts = 0
        battleTeamScrollActions = 0
        lastBattleTeamActionAt = Long.MIN_VALUE
    }

    private fun resetExEncounterTracking() {
        currentExEncounterStrategy = null
        exSlot3ProbePending = false
        exSlot3ProbeAttempts = 0
        exEncounterProbeStartedAt = Long.MIN_VALUE
        exIdentityProbeDescription = "EX识别目标"
        synchronized(effectiveExCharacterIds) { effectiveExCharacterIds.clear() }
        effectiveRosterSearch.reset()
        effectiveCharacterScanStage = LabyrinthEffectiveCharacterScanStage.IDLE
        effectiveCharacterScanScrollActions = 0
        effectiveCharacterScanNoScrollStableFrames = 0
        effectiveCharacterUnsafeStartedAt = Long.MIN_VALUE
        effectiveCharacterScanSkippedUnsafe = false
        lastEffectiveCharacterScanActionAt = Long.MIN_VALUE
        lastBattleTeamRecommendationKey = null
    }

    private fun resetBattleRetryTracking() {
        battleRetryCount = 0
        synchronized(currentBattleTeamSignatures) { currentBattleTeamSignatures.clear() }
        synchronized(failedBattleTeamSignatures) { failedBattleTeamSignatures.clear() }
    }

    private fun postEntryIsRoleRewardPage(result: LabyrinthEntryFrameResult): Boolean {
        return labyrinthIsRoleRewardPage(result, POST_ENTRY_SELECTION_MIN_SCORE)
    }

    private fun finalSettlementFallbackRect(
        result: LabyrinthEntryFrameResult,
        pageState: LabyrinthEntryPageState,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect {
        val center = { referenceRect(960, 780, frameWidth, frameHeight) }
        val bottomCenter = { referenceRect(960, 1000, frameWidth, frameHeight) }
        val bottomRight = { referenceRect(1640, 990, frameWidth, frameHeight) }
        return when (pageState) {
            LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY ->
                anchorRect(result, EntryAnchorId.RUN_CLEAR_NEXT_BUTTON)
                    ?: bottomRight()

            LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION -> bottomCenter()
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT -> bottomRight()
            LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
            LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
            -> center()

            LabyrinthEntryPageState.UNKNOWN -> when (postBossStage) {
                LabyrinthPostBossStage.CHEST_SEQUENCE ->
                    if (postBossUnknownAttempts >= 3) bottomRight() else center()
                LabyrinthPostBossStage.BEFORE_SCORE ->
                    when (postBossUnknownAttempts % 4) {
                        1, 3 -> bottomRight()
                        else -> center()
                    }
                else -> center()
            }

            else -> center()
        }
    }

    private fun dispatchPostEntryTap(
        sessionId: AutomationSessionId,
        label: String,
        rect: EntryPixelRect,
        timestampMillis: Long,
        roleRewardSelectionSignature: String? = null,
        roleRewardSelectedCharacterId: String? = null,
        shopPurchaseRelicId: String? = null,
        shopPurchaseRoleImprintLabel: String? = null,
        eventFreeRoleCandidateId: String? = null,
        eventFreeRoleConfirm: Boolean = false,
    ) {
        if (!actionInFlight.compareAndSet(false, true)) return
        if (shopPurchaseRelicId != null || shopPurchaseRoleImprintLabel != null) {
            // This is semantic transaction context, not a page-local coordinate. It must survive
            // the brief UNKNOWN transition that can appear between the buy tap and confirmation.
            plannedShopPurchaseRelicId = shopPurchaseRelicId
            plannedShopRoleImprintLabel = shopPurchaseRoleImprintLabel
            plannedShopPurchaseStartedAt = timestampMillis
        }
        postEntryAttempts++
        lastPostEntryActionAt = timestampMillis
        if (label == "尝试收起角色立绘") portraitRecovery.recordAttempt(timestampMillis)
        if (label.startsWith("选择事件：") || label == "推进事件动画" || label == "推进事件流程") {
            eventActionAttempts++
        }
        if (label == "推进角色获得动画") {
            characterAcquisitionClicks++
            lastCharacterAcquisitionActionAt = timestampMillis
        }
        if (label == "推进最终结算动画") postBossUnknownAttempts++
        actionScope.launch {
            val executor = actionExecutor
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            val actionResult = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (actionResult) {
                is AutomationActionResult.Executed -> {
                    if (label.startsWith("事件自由选角：") && eventFreeRoleCandidateId != null) {
                        eventFreeRoleSelectedCharacterId = eventFreeRoleCandidateId
                    }
                    if (eventFreeRoleConfirm) {
                        eventFreeRoleCandidateId?.let { selectedId ->
                            synchronized(pendingAcquiredCharacterIds) {
                                pendingAcquiredCharacterIds += selectedId
                                pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                            }
                        }
                    }
                    if (label.startsWith("选择事件：")) {
                        eventChoiceCommitted = true
                    }
                    if (label.startsWith("选择遗物：")) {
                        relicChoiceCommitted = true
                    }
                    if (label.startsWith("选择角色：")) {
                        roleRewardChoiceCommitted = true
                        committedRoleRewardSelectionSignature = roleRewardSelectionSignature
                        roleRewardBatchActive = true
                        roleRewardSelectedCharacterId?.let { selectedId ->
                                synchronized(pendingAcquiredCharacterIds) {
                                    pendingAcquiredCharacterIds += selectedId
                                    pendingAcquiredCharacterId = pendingAcquiredCharacterIds.firstOrNull()
                                }
                        }
                    }
                    when (label) {
                        "EX识别完成：关闭魔物详情" -> {
                            exSlot3ProbePending = false
                            exEncounterProbeStartedAt = Long.MIN_VALUE
                        }
                        "战斗失败：重新挑战" -> {
                            synchronized(failedBattleTeamSignatures) {
                                failedBattleTeamSignatures += currentBattleTeamSignatures
                            }
                            synchronized(currentBattleTeamSignatures) {
                                currentBattleTeamSignatures.clear()
                            }
                            synchronized(committedBattleCharacterIds) {
                                committedBattleCharacterIds.clear()
                            }
                            battleRetryCount++
                            battleWait.reset()
                            bossEditorTeamIndex = null
                            pendingBossTeamAdvance = null
                            bossEditorBlocked = false
                            bossEditorPreparationStage = if (combatContext?.kind == LabyrinthCombatKind.BOSS) {
                                LabyrinthBossEditorPreparationStage.TEAM_1
                            } else {
                                LabyrinthBossEditorPreparationStage.INACTIVE
                            }
                            preparedBossFirstTeamIds = emptyList()
                            combatContext = combatContext?.copy(teamIndex = 1)
                            lastBattleTeamRecommendationKey = null
                            lastBattleTeamSelectionPlanLog = null
                            resetBattleTeamExecutionTracking()
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                        }
                        "关闭购买完成" -> clearPlannedShopPurchase()
                        "最终区域刷新商店" -> {
                            // Refresh does not leave SHOP, so explicitly require fresh stable
                            // frames before another purchase/refresh decision.
                            postEntryStableFrames = 0
                            postEntryAttempts = 0
                            shopRelicPurchasesThisCycle = 0
                            clearPlannedShopPurchase()
                        }
                        "关闭角色加入结果" -> if (!roleRewardBatchActive) {
                            clearCharacterAcquisitionContext()
                        } else {
                            // More joined popups may follow immediately with the same page state.
                            // Keep the batch/fallback alive and refresh only its progress timeout.
                            characterAcquisitionStartedAt = timestampMillis
                        }
                        "关闭获得道具界面" -> if (characterAcquisitionActive && !roleRewardBatchActive) {
                            clearCharacterAcquisitionContext()
                        }
                        "关闭最终分数结果" -> {
                            postBossStage = LabyrinthPostBossStage.CHEST_SEQUENCE
                            postBossUnknownAttempts = 0
                        }
                        "关闭最终获得道具" -> {
                            postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
                            postBossStartedAt = timestampMillis
                        }
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = label,
                            pendingAcquiredCharacterId = pendingAcquiredCharacterId,
                            message = "已执行：$label",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (shopPurchaseRelicId != null) clearPlannedShopPurchase()
                    if (label.startsWith("识别EX：查看")) {
                        exSlot3ProbePending = false
                        exSlot3ProbeAttempts = (exSlot3ProbeAttempts - 1).coerceAtLeast(0)
                        exEncounterProbeStartedAt = Long.MIN_VALUE
                    }
                    if (actionResult.reason == GAME_NOT_FOREGROUND_REASON) {
                        postEntryAttempts = (postEntryAttempts - 1).coerceAtLeast(0)
                        if (label == "推进角色获得动画") {
                            characterAcquisitionClicks = (characterAcquisitionClicks - 1).coerceAtLeast(0)
                        }
                        if (label == "推进最终结算动画") {
                            postBossUnknownAttempts = (postBossUnknownAttempts - 1).coerceAtLeast(0)
                        }
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("点击被拒绝：${actionResult.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    /** Only the final item popup being closed and the idle maze home being visible completes a run. */
    private fun markRunCleared(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        postBossStage = LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME
        val current = _state.value
        val progress = current.routeProgress ?: LabyrinthRouteProgress(
            currentArea = 0,
            visitedCount = 0,
            routeNodeCount = routeNodeCount,
        )
        _state.value = current.copy(routeProgress = progress.copy(complete = true))
        finishFromPlanner(sessionId, "已完成最终结算并返回黎明界主页")
    }

    private fun anchorRect(result: LabyrinthEntryFrameResult, anchorId: String): EntryPixelRect? =
        result.anchorMatches[anchorId]
            ?.takeIf { it.score >= POST_ENTRY_ANCHOR_MIN_SCORE }
            ?.rect

    /** 把 1920x1080 参考坐标按当前帧尺寸换算成一个小点击框。 */
    private fun referenceRect(x: Int, y: Int, frameWidth: Int, frameHeight: Int): EntryPixelRect {
        val px = (x / 1920f * frameWidth).toInt().coerceIn(1, frameWidth - 2)
        val py = (y / 1080f * frameHeight).toInt().coerceIn(1, frameHeight - 2)
        return EntryPixelRect(left = px - 1, top = py - 1, width = 2, height = 2)
    }

    private fun resetNodeExecutionState() {
        battleWait.reset()
        portraitRecovery.reset()
        eventFreeRoleSelectedCharacterId = null
        nodeSession = null
        nodeInitStarted.set(false)
        nodeInitFailedReason = null
        finalBossLocalizationStartedAt = Long.MIN_VALUE
        entryPhaseComplete = false
        resetPendingNodeClickStability()
        pendingNodeTransition = null
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        nodeViewportScanner.reset()
        lastNodeActionAt = Long.MIN_VALUE
        nodeErrorStreak = 0
        routeNodeCount = 0
        lastPostEntryState = null
        postEntryStableFrames = 0
        postEntryAttempts = 0
        lastPostEntryActionAt = Long.MIN_VALUE
        postEntryUnknownSince = Long.MIN_VALUE
        pendingRelicSelection = null
        relicChoiceCommitted = false
        relicFocusMark = null
        relicStackLedger.clear()
        clearCharacterAcquisitionContext()
        clearRoleRewardBatchTracking()
        existingRunResumeHandoffArmed = false
        postBossStage = LabyrinthPostBossStage.NONE
        postBossUnknownAttempts = 0
        postBossStartedAt = Long.MIN_VALUE
        combatContext = null
        lastBattleTeamRecommendationKey = null
        lastBattleTeamSelectionPlanLog = null
        resetBattleTeamExecutionTracking()
        synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
        resetBattleRetryTracking()
        bossEditorTeamIndex = null
        pendingBossTeamAdvance = null
        bossEditorBlocked = false
        bossEditorPreparationStage = LabyrinthBossEditorPreparationStage.INACTIVE
        preparedBossFirstTeamIds = emptyList()
        activeNodeType = null
        eventActionAttempts = 0
        _state.value = _state.value.copy(
            combatContext = null,
            battleTeamRecommendation = null,
            battleTeamRecommendationUnavailableReason = null,
            battleTeamSelectionPlan = null,
        )
        resetSessionRecoveryTracking()
    }

    private fun clearCharacterAcquisitionContext() {
        characterAcquisitionActive = false
        characterAcquisitionClicks = 0
        characterAcquisitionStartedAt = Long.MIN_VALUE
        lastCharacterAcquisitionActionAt = Long.MIN_VALUE
    }

    private fun clearRoleRewardBatchTracking() {
        roleRewardBatchActive = false
        roleRewardChoiceCommitted = false
        committedRoleRewardSelectionSignature = null
        roleRewardJoinedSequenceStarted = false
        lastObservedRoleRewardSelectionSignature = null
        waitingForManualRoleSelection = false
        synchronized(pendingAcquiredCharacterIds) {
            pendingAcquiredCharacterIds.clear()
            pendingAcquiredCharacterId = null
        }
    }

    private fun handleNodeSelectionFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        portraitRecovery.reset()
        val session = nodeSession
        if (session == null) {
            maybeStartNodeInit(sessionId)
            return
        }
        // A reward animation may end on a map frame without a separately recognized close
        // page. Once the node page is back, the bounded portrait fallback must not leak into
        // the next node or keep tapping the map.
        if (characterAcquisitionActive) {
            clearCharacterAcquisitionContext()
        }
        // 关闭角色加入/奖励等弹窗后，地图会经历短暂的过渡动画。此时节点模板可能与
        // 弹窗残影同时命中，立即点击或滚图会把脏帧当成稳定地图。
        if (lastPostEntryActionAt != Long.MIN_VALUE &&
            timestampMillis - lastPostEntryActionAt < NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS
        ) {
            val message = "弹窗关闭后等待节点地图稳定"
            if (_state.value.message != message) {
                _state.value = _state.value.copy(message = message)
                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
            }
            return
        }
        val nodeState = session.getState()
        val viewportSignature = labyrinthNodeViewportSignature(result.nodeClassifications)
        nodeLog(
            "node-frame size=${frameWidth}x${frameHeight} " +
                "area=${nodeState.currentArea} current=${nodeState.currentNodeId} " +
                "visited=${nodeState.visitedNodes.size} " +
                "classes=${describeNodeClassifications(result.nodeClassifications)}",
        )
        pendingNodeTransition?.let { pending ->
            val observedPending = pending.copy(
                nodeSelectionFramesSinceAction = pending.nodeSelectionFramesSinceAction + 1,
            )
            pendingNodeTransition = observedPending
            val waitStartedAt = pending.confirmationDispatchedAtMillis ?: pending.dispatchedAtMillis
            val timeout = if (pending.confirmationDispatchedAtMillis == null) {
                NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS
            } else {
                NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS
            }
            val elapsedMillis = timestampMillis - waitStartedAt
            if (
                labyrinthKeepsPendingNodeTransition(
                    elapsedMillis = elapsedMillis,
                    timeoutMillis = timeout,
                    nodeSelectionFramesSinceAction = observedPending.nodeSelectionFramesSinceAction,
                )
            ) {
                val message = if (pending.confirmationDispatchedAtMillis == null) {
                    "已点击${pending.label}，等待移动确认弹窗"
                } else {
                    "已确认移动到${pending.label}，等待游戏进入节点"
                }
                if (_state.value.message != message) {
                    _state.value = _state.value.copy(message = message)
                }
                return
            }
            pendingNodeTransition = null
            resetNodeMoveConfirmationTracking()
            resetPendingNodeClickStability()
            if (nodeTapAttempts >= MAX_NODE_TAP_ATTEMPTS) {
                finishFromPlanner(sessionId, "节点点击未被游戏确认：${pending.label}")
                return
            }
            nodeLog(
                "node-transition-timeout target=${pending.label} " +
                    "elapsed=${elapsedMillis}ms " +
                    "nodeFrames=${observedPending.nodeSelectionFramesSinceAction} " +
                    "confirmationSent=${pending.confirmationDispatchedAtMillis != null} " +
                    "confirmationAttempts=${pending.confirmationAttempts} " +
                    "tapAttempts=$nodeTapAttempts/$MAX_NODE_TAP_ATTEMPTS " +
                    "page=${result.observation.state.name}",
                warning = true,
            )
            _state.value = _state.value.copy(message = "游戏未进入${pending.label}，准备重试同一节点")
            return
        }
        // Bind any current-frame ordinary-node evidence. The final-Boss-only fast path supplies
        // none: observing that empty mapping also invalidates old camera coordinates after a pan.
        val visuallyPlannedAction = session.processClassifications(result.nodeClassifications)
        session.getLastMatchResult()?.let { match ->
            nodeViewportScanner.observe(
                area = nodeState.currentArea,
                viewportSignature = viewportSignature,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                matchResult = match,
            )
        }

        val directFinalBoss = session.directFinalBossTarget()
        val action: NodeAction
        if (directFinalBoss != null) {
            val predictedX = nodeViewportScanner.expectedScreenCenterX(directFinalBoss.column)
            val rect = finalBossPlatformClickRect(
                matches = result.finalBossPlatforms,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                predictedX = predictedX,
            )
            nodeLog(
                "node-direct-final-boss target=Boss#${directFinalBoss.blockId} " +
                    "area=${directFinalBoss.area} column=${directFinalBoss.column} " +
                    "predictedX=${predictedX?.let { "%.1f".format(it) } ?: "n/a"} " +
                    "platforms=${result.finalBossPlatforms} candidates=${result.finalBossPlatformCandidates} rect=$rect",
            )
            if (rect == null) {
                resetPendingNodeClickStability()
                publishRouteProgress(sessionId, session, nextLabel = "Boss#${directFinalBoss.blockId}", nextRect = null)
                if (finalBossLocalizationStartedAt == Long.MIN_VALUE) finalBossLocalizationStartedAt = timestampMillis
                if (!_state.value.dryRun && timestampMillis - finalBossLocalizationStartedAt >= 30_000L) {
                    finishFromPlanner(sessionId, "最终Boss底座定位超时，已保留诊断；未执行固定坐标点击")
                    return
                }
                _state.value = _state.value.copy(message = "最终Boss底座未可靠定位或与路线位置矛盾，等待新画面；禁止固定坐标点击")
                return
            }
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = NodeAction.ClickNode(
                blockId = directFinalBoss.blockId,
                blockType = directFinalBoss.blockType,
                screenRect = rect,
            )
        } else {
            finalBossLocalizationStartedAt = Long.MIN_VALUE
            action = visuallyPlannedAction
        }
        if (action !is NodeAction.ClickNode) {
            // "Stable frames" must be consecutive. A conflict/wait frame, or a target crop that
            // disappears, invalidates any click evidence accumulated before it.
            resetPendingNodeClickStability()
        }
        when (action) {
            is NodeAction.ClickNode -> {
                nodeLog(
                    "node-decision target=${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId} " +
                        "rect=${action.screenRect}",
                )
                nodeErrorStreak = 0
                onNodeClickPlanned(
                    sessionId = sessionId,
                    session = session,
                    action = action,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    timestampMillis = timestampMillis,
                    viewportSignature = viewportSignature,
                )
            }

            is NodeAction.TypeConflict -> {
                val expected = LabyrinthNodeTypes.labelOf(action.expectedBlockType)
                val detected = LabyrinthNodeTypes.labelOf(action.detectedBlockType)
                val label = "$expected#${action.blockId}"
                nodeLog(
                    "node-decision TYPE_CONFLICT target=$label detected=$detected " +
                        "confidence=${"%.3f".format(action.confidence)} " +
                        "topology=${action.topologyBindingKind ?: "none"}/" +
                        "${action.topologyConfidence?.let { "%.3f".format(it) } ?: "n/a"} " +
                        "rect=${action.screenRect}",
                    warning = true,
                )
                // Do not turn a semantic contradiction into a scroll request: the crop is already
                // on screen. A few frames are tolerated for map animation/template jitter, while a
                // persistent conflict stops live execution before any unsafe click can occur.
                nodeErrorStreak++
                publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
                val message = "TYPE_CONFLICT：视觉$detected ≠ 路线$label，已拒绝点击"
                if (!_state.value.dryRun && nodeErrorStreak >= MAX_NODE_ERROR_STREAK) {
                    finishFromPlanner(sessionId, "$message（连续${nodeErrorStreak}帧）")
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = message)
                    overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                }
            }

            NodeAction.WaitForLoad -> {
                nodeLog("node-decision wait-for-load")
                publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null)
            }

            NodeAction.Complete -> {
                publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null, complete = false)
                if (_state.value.dryRun) {
                    _state.value = _state.value.copy(message = "路线节点回放完成")
                } else {
                    // The node graph can finish as soon as the final Boss node is accepted. The
                    // run is not complete yet: Congratulations, score, chest and final item
                    // pages still have to be consumed and the game must return to its idle home.
                    if (postBossStage == LabyrinthPostBossStage.NONE) {
                        postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
                        postBossStartedAt = timestampMillis
                        postBossUnknownAttempts = 0
                    }
                    _state.value = _state.value.copy(message = "路线节点已完成，等待最终结算链")
                }
            }

            is NodeAction.Error -> {
                nodeLog("node-decision error=${action.message}", warning = true)
                nodeErrorStreak++
                if (!_state.value.dryRun && nodeErrorStreak >= MAX_NODE_ERROR_STREAK) {
                    finishFromPlanner(sessionId, "路线执行连续失败：${action.message}")
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "路线匹配失败：${action.message}")
                }
            }
        }
    }

    private fun maybeStartNodeInit(sessionId: AutomationSessionId) {
        if (!nodeInitStarted.compareAndSet(false, true)) return
        val loadTemplates = nodeTemplateLoader
        val executionContext = validatedExecutionContext
        if (loadTemplates == null || executionContext == null) {
            nodeInitFailedReason = "节点执行未配置"
            finishFromPlanner(sessionId, nodeInitFailedReason!!)
            return
        }
        actionScope.launch {
            val failure: String? = run {
                val route = validatedRoute ?: executionContext.route ?: return@run "当前路线不提供完整地图，不可执行节点"
                validatedRoute = route
                if (route.allNodes.isEmpty()) {
                    return@run "已保存路线缺少完整地图数据，请重新完成路线来源验证"
                }
                val templates = runCatching(loadTemplates).getOrElse { error ->
                    return@run "加载节点模板失败：${error.message ?: "未知错误"}"
                }
                val session = nodeSessionFactory()
                val routeNodes = route.toLabyrinthRoute().nodes
                val initializationFailure = runCatching {
                    session.initialize(
                        allNodes = route.toFullMap(),
                        route = routeNodes,
                        nodeTemplates = templates,
                        currentNodeId = route.currentBlockId,
                    )
                }.exceptionOrNull()
                if (initializationFailure != null) {
                    return@run "初始化节点会话失败：${initializationFailure.message ?: "未知错误"}"
                }
                routeNodeCount = routeNodes.size
                nodeSession = session
                null
            }
            if (activeSessionId != sessionId) return@launch
            if (failure != null) {
                nodeInitFailedReason = failure
                finishFromPlanner(sessionId, failure)
                return@launch
            } else {
                nodeSession?.let { initialized ->
                    val state = initialized.getState()
                    nodeLog(
                        "route-init area=${state.currentArea} current=${state.currentNodeId} " +
                            "visited=${state.visitedNodes.size} routeNodes=$routeNodeCount",
                    )
                }
                _state.value = _state.value.copy(
                    message = "路线已加载，开始节点${if (_state.value.dryRun) "回放" else "执行"}",
                )
                nodeSession?.let { publishRouteProgress(sessionId, it, nextLabel = null, nextRect = null) }
            }
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    private fun onNodeClickPlanned(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        action: NodeAction.ClickNode,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ) {
        val label = "${LabyrinthNodeTypes.labelOf(action.blockType)}#${action.blockId}"
        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = action.screenRect)
        if (_state.value.dryRun) return

        val rect = action.screenRect
        // 同一目标和同一物理位置需连续多帧稳定才允许点击。模板窗口明显跳动时重新计数，
        // 避免把前一帧的正确底座与后一帧落在底栏附近的噪声框累计在一起。
        if (
            pendingNodeClickBlockId == action.blockId &&
            nodeClickRectsAreStable(pendingNodeClickRect, rect)
        ) {
            pendingNodeClickStableFrames++
        } else {
            pendingNodeClickBlockId = action.blockId
            pendingNodeClickRect = rect
            pendingNodeClickStableFrames = 1
        }
        if (pendingNodeClickStableFrames < NODE_CLICK_STABLE_FRAMES) return
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            return
        }
        val invalidRectReason = when {
            rect == null -> "null"
            rect.left < 0 || rect.top < 0 -> "negative"
            rect.left + rect.width > frameWidth || rect.top + rect.height > frameHeight ->
                "out-of-bounds"
            else -> null
        }
        if (invalidRectReason != null) {
            nodeLog(
                "node-target-invalid target=$label reason=$invalidRectReason rect=$rect " +
                    "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature",
                warning = true,
            )

            if (
                pendingNodeScrollBlockId == action.blockId &&
                pendingNodeScrollViewportSignature == viewportSignature
            ) {
                pendingNodeScrollStableFrames++
            } else {
                pendingNodeScrollBlockId = action.blockId
                pendingNodeScrollViewportSignature = viewportSignature
                pendingNodeScrollStableFrames = 1
                pendingNodeScrollStartedAt = timestampMillis
            }

            val routeTarget = session.getLastMatchResult()?.nextNode
            if (routeTarget != null && nodeViewportScanner.targetLikelyVisible(routeTarget.column)) {
                val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                    else timestampMillis - pendingNodeScrollStartedAt
                if (
                    pendingNodeScrollStableFrames >= NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES &&
                    elapsed >= NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS
                ) {
                    finishFromPlanner(
                        sessionId,
                        "路线目标${label}按当前地图几何应已在画面内，但连续多帧未可靠识别；保持地图不滚动并停止，避免把目标滚出视野",
                    )
                } else if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "${label}按地图几何应在当前画面，保持镜头不动并重新识别（" +
                            "$pendingNodeScrollStableFrames/$NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES）",
                    )
                }
                return
            }

            val elapsed = if (pendingNodeScrollStartedAt == Long.MIN_VALUE) 0L
                else timestampMillis - pendingNodeScrollStartedAt
            if (
                pendingNodeScrollStableFrames < NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES ||
                elapsed < NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS
            ) {
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(
                        message = "暂未找到可点击的$label，优先保持当前地图并继续识别（" +
                            "$pendingNodeScrollStableFrames/$NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES）",
                    )
                }
                return
            }
            requestNodeMapScroll(
                sessionId,
                label,
                frameWidth,
                frameHeight,
                timestampMillis,
                viewportSignature,
            )
            return
        }
        resetNodeScrollSearchGate()
        val clickRect = rect ?: return
        if (!actionInFlight.compareAndSet(false, true)) return
        lastNodeActionAt = timestampMillis
        actionScope.launch {
            val executor = actionExecutor
            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(clickRect)
            nodeLog(
                "node-tap target=$label rect=$clickRect tap=$clickX,$clickY " +
                    "frame=${frameWidth}x${frameHeight} " +
                    "display=${CaptureStateRegistry.gestureDisplayId()} " +
                    "attempt=${nodeTapAttempts + 1}/$MAX_NODE_TAP_ATTEMPTS " +
                    "viewport=$viewportSignature",
            )
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = clickX.toFloat(),
                    y = clickY.toFloat(),
                ),
            )
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    nodeTapAttempts++
                    resetNodeMoveConfirmationTracking()
                    eventFreeRoleSelectedCharacterId = null
                    activeNodeType = action.blockType
                    activeNodeArea = session.getLastMatchResult()
                        ?.nextNode
                        ?.takeIf { it.blockId == action.blockId }
                        ?.area
                        ?: _state.value.routeProgress?.currentArea
                    eventActionAttempts = 0
                    synchronized(committedBattleCharacterIds) { committedBattleCharacterIds.clear() }
                    resetBattleRetryTracking()
                    resetExEncounterTracking()
                    bossEditorTeamIndex = null
                    pendingBossTeamAdvance = null
                    bossEditorBlocked = false
                    bossEditorPreparationStage = if (action.blockType == LabyrinthNodeTypes.BOSS) {
                        LabyrinthBossEditorPreparationStage.TEAM_1
                    } else {
                        LabyrinthBossEditorPreparationStage.INACTIVE
                    }
                    preparedBossFirstTeamIds = emptyList()
                    combatContext = combatContextFor(action.blockType)
                    pendingNodeTransition = PendingNodeTransition(
                        blockId = action.blockId,
                        blockType = action.blockType,
                        label = label,
                        dispatchedAtMillis = timestampMillis,
                    )
                    resetPendingNodeClickStability()
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "点击节点 $label",
                            combatContext = combatContext,
                            message = "已点击$label，等待移动确认弹窗",
                        )
                        publishRouteProgress(sessionId, session, nextLabel = label, nextRect = rect)
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("节点点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    private fun handleNodeMoveConfirmationFrame(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        timestampMillis: Long,
    ): Boolean {
        val confirmation = result.nodeMoveConfirmation ?: run {
            resetNodeMoveConfirmationTracking()
            return false
        }
        val pending = pendingNodeTransition ?: run {
            resetNodeMoveConfirmationTracking()
            publishNodeMoveConfirmationMessage(
                sessionId,
                "检测到移动确认弹窗，但没有待确认的自动节点，请手动处理",
            )
            return true
        }
        val rect = confirmation.confirmButtonRect
        if (lastNodeMoveConfirmationRect == rect) {
            nodeMoveConfirmationStableFrames++
        } else {
            lastNodeMoveConfirmationRect = rect
            nodeMoveConfirmationStableFrames = 1
        }
        if (nodeMoveConfirmationStableFrames < NODE_MOVE_CONFIRMATION_STABLE_FRAMES) {
            publishNodeMoveConfirmationMessage(sessionId, "已识别移动确认弹窗，正在确认稳定性")
            return true
        }
        val lastConfirmationAt = pending.confirmationDispatchedAtMillis
        if (
            lastConfirmationAt != null &&
            timestampMillis - lastConfirmationAt < NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS
        ) {
            publishNodeMoveConfirmationMessage(sessionId, "已点击移动确认，等待游戏响应")
            return true
        }
        if (pending.confirmationAttempts >= MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS) {
            finishFromPlanner(sessionId, "移动确认按钮多次无响应：${pending.label}")
            return true
        }
        if (!actionInFlight.compareAndSet(false, true)) return true
        dispatchNodeMoveConfirmation(sessionId, pending, rect, timestampMillis)
        return true
    }

    private fun dispatchNodeMoveConfirmation(
        sessionId: AutomationSessionId,
        pending: PendingNodeTransition,
        rect: EntryPixelRect,
        timestampMillis: Long,
    ) {
        actionScope.launch {
            val tap = AutomationAction.Tap(
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                ),
            )
            nodeLog(
                "node-move-confirm-tap target=${pending.label} rect=$rect " +
                    "tap=${tap.point.x},${tap.point.y} " +
                    "attempt=${pending.confirmationAttempts + 1}/$MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS " +
                    "display=${CaptureStateRegistry.gestureDisplayId()}",
            )
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(tap, "动作执行器不可用")
            } else {
                executor.execute(sessionId, tap)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    val currentPending = pendingNodeTransition
                    if (currentPending?.blockId == pending.blockId) {
                        pendingNodeTransition = currentPending.copy(
                            confirmationDispatchedAtMillis = timestampMillis,
                            confirmationAttempts = currentPending.confirmationAttempts + 1,
                            nodeSelectionFramesSinceAction = 0,
                        )
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "确认移动 ${pending.label}",
                            message = "已确认移动到${pending.label}，等待游戏进入节点",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("移动确认点击被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    private fun publishNodeMoveConfirmationMessage(sessionId: AutomationSessionId, message: String) {
        if (activeSessionId != sessionId || _state.value.message == message) return
        _state.value = _state.value.copy(message = message)
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun resetNodeMoveConfirmationTracking() {
        nodeMoveConfirmationStableFrames = 0
        lastNodeMoveConfirmationRect = null
    }

    private fun publishWaitingForGameForeground(sessionId: AutomationSessionId) {
        if (activeSessionId != sessionId) return
        _state.value = _state.value.copy(message = "游戏不在前台，保持会话并等待返回")
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun confirmPendingNodeTransition(
        sessionId: AutomationSessionId,
        pageState: LabyrinthEntryPageState,
    ) {
        if (!labyrinthConfirmsNodeEntry(pageState)) return
        val pending = pendingNodeTransition ?: return
        val session = nodeSession ?: return
        if (!labyrinthNodeEntryMatchesExpectedType(pending.blockType, pageState)) {
            nodeLog(
                "node-entry-mismatch target=${pending.label} expected=" +
                    "${LabyrinthNodeTypes.labelOf(pending.blockType)} actual=${pageState.name}",
                warning = true,
            )
            finishFromPlanner(
                sessionId,
                "节点${pending.label}进入页面不匹配：识别为${pageState.name}，已停止并保留诊断状态",
            )
            return
        }
        nodeLog(
            "node-entry-confirmed target=${pending.label} page=${pageState.name} " +
                "confirmationAttempts=${pending.confirmationAttempts} tapAttempts=$nodeTapAttempts",
        )
        session.afterClick(pending.blockId)
        persistNodeRouteProgress(sessionId, session.getState())
        if (!_state.value.dryRun && session.getState().isComplete &&
            postBossStage == LabyrinthPostBossStage.NONE
        ) {
            // The final node is normally a Boss/EX page, so completion is observed here when
            // the destination page is first recognized—not later when the node matcher is
            // polled again. Keep the live run open for the complete settlement chain.
            postBossStage = LabyrinthPostBossStage.BEFORE_SCORE
            postBossStartedAt = clock()
            postBossUnknownAttempts = 0
        }
        pendingNodeTransition = null
        resetPendingNodeClickStability()
        nodeTapAttempts = 0
        resetNodeMoveConfirmationTracking()
        nodeScrollAttempts = 0
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = null
        if (activeSessionId == sessionId) {
            val combatMessage = combatContext?.let { context ->
                when (context.kind) {
                    LabyrinthCombatKind.NORMAL -> "普通战"
                    LabyrinthCombatKind.EX -> "EX战"
                    LabyrinthCombatKind.BOSS -> "Boss第${context.teamIndex}队"
                }
            }
            _state.value = _state.value.copy(
                combatContext = combatContext,
                message = "已确认进入节点：${pending.label}" +
                    (combatMessage?.let { "（$it）" } ?: ""),
            )
            publishRouteProgress(sessionId, session, nextLabel = null, nextRect = null)
            overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        }
    }

    /**
     * Keep the saved route cursor aligned with the last destination page that the game actually
     * accepted. The Room store repeats the enterId and monotonic-order checks in its transaction,
     * so delayed writes cannot move a newer session backwards.
     */
    private fun persistNodeRouteProgress(
        sessionId: AutomationSessionId,
        nodeState: NodeSessionState,
    ) {
        if (_state.value.dryRun) return
        val currentBlockId = nodeState.currentNodeId ?: return
        val context = validatedExecutionContext ?: return
        val source = routeSource ?: return
        val route = validatedRoute ?: context.route ?: return
        val advancedRoute = route.withAdvancedCurrentBlock(currentBlockId)
        if (advancedRoute == null) {
            nodeLog(
                "route-progress-rejected-memory enterId=${route.enterId} " +
                    "saved=${route.currentBlockId} requested=$currentBlockId area=${nodeState.currentArea}",
                warning = true,
            )
            return
        }
        val advancedContext = context.copy(route = advancedRoute)
        validatedExecutionContext = advancedContext
        validatedRoute = advancedRoute
        actionScope.launch {
            val result = runCatching {
                persistenceMutex.withLock {
                    source.updateCurrentBlock(advancedContext, currentBlockId)
                }
            }
            if (activeSessionId != sessionId) return@launch
            result.fold(
                onSuccess = { persisted ->
                    nodeLog(
                        if (persisted) {
                            "route-progress-persisted source=${advancedContext.source} " +
                                "account=${advancedContext.accountId} enterId=${advancedRoute.enterId} " +
                                "current=$currentBlockId area=${nodeState.currentArea}"
                        } else {
                            "route-progress-rejected source=${advancedContext.source} " +
                                "account=${advancedContext.accountId} " +
                                "enterId=${advancedRoute.enterId} current=$currentBlockId"
                        },
                        warning = !persisted,
                    )
                },
                onFailure = { error ->
                    nodeLog(
                        "route-progress-persist-failed source=${advancedContext.source} " +
                            "account=${advancedContext.accountId} " +
                            "enterId=${advancedRoute.enterId} current=$currentBlockId " +
                            "error=${error.message ?: error::class.java.simpleName}",
                        warning = true,
                    )
                },
            )
        }
    }

    private fun combatContextFor(blockType: Int): LabyrinthCombatContext? = when (blockType) {
        LabyrinthNodeTypes.NORMAL_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
        LabyrinthNodeTypes.EX_BATTLE -> LabyrinthCombatContext(LabyrinthCombatKind.EX)
        LabyrinthNodeTypes.BOSS -> LabyrinthCombatContext(LabyrinthCombatKind.BOSS)
        else -> null
    }

    private fun resetPendingNodeClickStability() {
        pendingNodeClickBlockId = Long.MIN_VALUE
        pendingNodeClickRect = null
        pendingNodeClickStableFrames = 0
    }

    private fun resetNodeScrollSearchGate() {
        pendingNodeScrollBlockId = Long.MIN_VALUE
        pendingNodeScrollViewportSignature = null
        pendingNodeScrollStableFrames = 0
        pendingNodeScrollStartedAt = Long.MIN_VALUE
    }

    private fun nodeClickRectsAreStable(
        previous: EntryPixelRect?,
        current: EntryPixelRect?,
    ): Boolean {
        if (previous == null || current == null) return previous == current
        val previousCenterX = previous.left + previous.width / 2
        val previousCenterY = previous.top + previous.height / 2
        val currentCenterX = current.left + current.width / 2
        val currentCenterY = current.top + current.height / 2
        val xTolerance = maxOf(16, minOf(previous.width, current.width) * 18 / 100)
        val yTolerance = maxOf(16, minOf(previous.height, current.height) * 18 / 100)
        return kotlin.math.abs(previousCenterX - currentCenterX) <= xTolerance &&
            kotlin.math.abs(previousCenterY - currentCenterY) <= yTolerance
    }

    private fun requestNodeMapScroll(
        sessionId: AutomationSessionId,
        label: String,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
        viewportSignature: String,
    ) {
        nodeLog(
            "node-scroll-request target=$label attempt=${nodeScrollAttempts + 1}/$MAX_NODE_SCROLL_ATTEMPTS " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                nodeViewportScanner.diagnostics(nodeSession?.getLastMatchResult()?.nextNode?.blockId),
            warning = true,
        )
        if (nodeScrollAttempts >= MAX_NODE_SCROLL_ATTEMPTS) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MAX_ATTEMPTS",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            finishFromPlanner(sessionId, "自动滚动后仍找不到下一节点：$label")
            return
        }
        val routeTarget = nodeSession?.getLastMatchResult()?.nextNode
        if (routeTarget == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "MISSING_ROUTE_TARGET",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            finishFromPlanner(sessionId, "路线缺少待定位节点，无法继续扫描：$label")
            return
        }
        val hasVisualViewport = viewportSignature.isNotBlank()
        if (hasVisualViewport && nodeViewportScanner.awaitingSwipeOutcome()) {
            if (activeSessionId == sessionId) {
                _state.value = _state.value.copy(message = "已滚动地图，等待视口稳定并判断是否到达边界；暂不连续滑动")
            }
            return
        }
        val scanPlan = if (hasVisualViewport) {
            nodeViewportScanner.plan(
                targetBlockId = routeTarget.blockId,
                targetLogicalColumn = routeTarget.column,
            )
        } else {
            labyrinthBlindMapScanPlan(
                currentNodeId = nodeSession?.getState()?.currentNodeId ?: 0L,
                targetLogicalColumn = routeTarget.column,
                completedAttempts = nodeScrollAttempts,
                reverseAfterAttempts = MAX_NODE_SCROLL_ATTEMPTS / 2,
            )
        }
        if (scanPlan == null) {
            logNodeScrollTerminalDiagnostics(
                label = label,
                reason = "FULL_AREA_BOUNDARIES",
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                viewportSignature = viewportSignature,
            )
            finishFromPlanner(sessionId, "已分段扫描地图两侧边界，仍未识别到下一节点：$label")
            return
        }
        if (lastNodeActionAt != Long.MIN_VALUE &&
            timestampMillis - lastNodeActionAt < NODE_CLICK_INTERVAL_MILLIS
        ) {
            return
        }
        val swipe = labyrinthMapSwipe(frameWidth, frameHeight, scanPlan.direction) ?: run {
            finishFromPlanner(sessionId, "地图尺寸无效，无法滚动到下一节点：$label")
            return
        }
        if (!actionInFlight.compareAndSet(false, true)) return
        nodeScrollAttempts++
        resetNodeScrollSearchGate()
        lastNodeScrollSourceSignature = viewportSignature
        lastNodeActionAt = timestampMillis
        nodeLog(
            "node-scroll-dispatch target=$label direction=${scanPlan.direction.name} " +
                "reason=${scanPlan.reason} viewport=$viewportSignature",
        )
        actionScope.launch {
            val executor = actionExecutor
            val result = if (executor == null) {
                AutomationActionResult.Rejected(swipe, "动作执行器不可用")
            } else {
                executor.execute(sessionId, swipe)
            }
            when (result) {
                is AutomationActionResult.Executed -> {
                    if (hasVisualViewport) {
                        nodeViewportScanner.recordSwipe(scanPlan.direction, viewportSignature)
                    }
                    if (activeSessionId == sessionId) {
                        val current = _state.value
                        val directionLabel = when (scanPlan.direction) {
                            LabyrinthMapScanDirection.FORWARD -> "向右"
                            LabyrinthMapScanDirection.BACKWARD -> "向左"
                        }
                        _state.value = current.copy(
                            actionCount = current.actionCount + 1,
                            lastActionLabel = "滚动地图寻找 $label",
                            message = "已${directionLabel}搜索地图，等待画面稳定后重新识别下一节点（" +
                                "$nodeScrollAttempts/$MAX_NODE_SCROLL_ATTEMPTS）",
                        )
                        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                    }
                }

                is AutomationActionResult.Rejected -> {
                    if (result.reason == GAME_NOT_FOREGROUND_REASON) {
                        nodeScrollAttempts = (nodeScrollAttempts - 1).coerceAtLeast(0)
                        publishWaitingForGameForeground(sessionId)
                    } else {
                        stop("地图滚动被拒绝：${result.reason}")
                    }
                }
                AutomationActionResult.StaleSession -> Unit
                AutomationActionResult.Paused -> Unit
                is AutomationActionResult.DryRun -> Unit
            }
            actionInFlight.set(false)
        }
    }

    private fun logNodeScrollTerminalDiagnostics(
        label: String,
        reason: String,
        frameWidth: Int,
        frameHeight: Int,
        viewportSignature: String,
    ) {
        val session = nodeSession
        val rawCandidates = session?.getLastClassifications().orEmpty().joinToString(";") { candidate ->
            "${candidate.column}/${candidate.row}:${LabyrinthNodeTypes.labelOf(candidate.blockType)}" +
                "@${"%.2f".format(candidate.confidence)}:" +
                "click=${candidate.isClickable}:" +
                "cyan=${"%.2f".format(candidate.cyanGlowScore)}:" +
                "cyanRows=${"%.2f".format(candidate.cyanGlowRowCoverage)}:" +
                "purple=${"%.2f".format(candidate.purpleGlowScore)}:" +
                "purpleRows=${"%.2f".format(candidate.purpleGlowRowCoverage)}:" +
                "purpleSide=${"%.2f".format(candidate.purpleGlowSideScore)}:" +
                "purpleLower=${"%.2f".format(candidate.purpleGlowLowerScore)}:" +
                "color=${"%.2f".format(candidate.colorRoiScore)}:" +
                "rect=${candidate.screenRect}:template=${candidate.templateId}"
        }.ifBlank { "none" }
        val match = session?.getLastMatchResult()
        val mappings = match?.visibleNodes.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.blockType)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val conflicts = match?.conflicts.orEmpty().joinToString(";") { conflict ->
            "${conflict.reason}:${conflict.blockId}:" +
                "expected=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}:" +
                "detected=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)}:" +
                "rect=${conflict.screenRect}"
        }.ifBlank { "none" }
        val topology = match?.topologyMappings.orEmpty().joinToString(";") { mapping ->
            "${mapping.blockId}:${LabyrinthNodeTypes.labelOf(mapping.expectedBlockType)}:" +
                "visual=${mapping.visualColumn}/${mapping.visualRow}:" +
                "detected=${LabyrinthNodeTypes.labelOf(mapping.detectedBlockType)}:" +
                "topo=${"%.2f".format(mapping.topologyConfidence)}:" +
                "rect=${mapping.screenRect}"
        }.ifBlank { "none" }
        val topologyColumns = match?.topologyObservedColumns.orEmpty().joinToString(";") { column ->
            "v${column.visualColumn}@${column.centerX}[${column.nodeCount}]->" +
                "c${column.mappedLogicalColumn ?: "?"}"
        }.ifBlank { "none" }
        val routeTarget = match?.nextNode?.let { node ->
            "${node.blockId}:${LabyrinthNodeTypes.labelOf(node.blockType)}"
        } ?: label
        nodeLog(
            "node-scroll-terminal reason=$reason target=$routeTarget " +
                "frame=${frameWidth}x${frameHeight} viewport=$viewportSignature " +
                "columnOffset=${match?.logicalColumnOffset ?: "unknown"} " +
                "topologyScore=${match?.topologyAlignmentScore ?: 0.0} " +
                "scan=${nodeViewportScanner.diagnostics(match?.nextNode?.blockId)} " +
                "topologyColumns=[$topologyColumns] topology=[$topology] " +
                "raw=[$rawCandidates] mapped=[$mappings] conflicts=[$conflicts]",
            warning = true,
        )
    }

    private fun publishRouteProgress(
        sessionId: AutomationSessionId,
        session: LabyrinthNodeSession,
        nextLabel: String?,
        nextRect: EntryPixelRect?,
        complete: Boolean = false,
    ) {
        if (activeSessionId != sessionId) return
        val nodeState = session.getState()
        _state.value = _state.value.copy(
            routeProgress = LabyrinthRouteProgress(
                currentArea = nodeState.currentArea,
                visitedCount = nodeState.visitedNodes.size,
                routeNodeCount = routeNodeCount,
                nextNodeLabel = nextLabel,
                nextNodeRect = nextRect,
                complete = complete || nodeState.isComplete,
            ),
        )
    }

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        val result = current.lastResult
        val pageOwner = result?.observation?.state
            ?.let(::labyrinthPageUiOwner)
            ?: LabyrinthPageUiOwner.NONE
        val detail = buildList {
            if (pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION) {
                if (current.paused) {
                    current.battleTeamSelectionPlan?.overlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.overlayLines()?.let(::addAll)
                } else {
                    current.battleTeamSelectionPlan?.compactOverlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.compactOverlayLine()?.let(::add)
                }
                current.battleTeamRecommendationUnavailableReason?.let {
                    add("第一队建议不可用：$it")
                }
            }
            add("原始帧：${current.receivedFrameCount}")
            add("识别帧：${current.frameCount}")
            if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                nodeRelicStackSummary(result)?.let { add("地图遗物：$it") }
            }
            if (!current.dryRun) {
                add("动作：${current.actionCount}")
                add("输入显示器：${CaptureStateRegistry.gestureDisplayId()}")
            }
            if (result == null) {
                add("页面：等待首个游戏画面")
                add("捕获：${if (captureActive()) "运行中" else "已停止"}")
                add("帧消费者：${CaptureFrameBus.currentOwner() ?: "无"}")
                if (!captureActive()) {
                    CaptureStateRegistry.lastStopReason()?.let { add("捕获停止原因：$it") }
                }
                current.message?.takeIf(String::isNotBlank)?.let { add("状态：$it") }
            } else {
                add("页面：${result.observation.state.name}")
                add("置信度：${"%.3f".format(result.observation.confidence)}")
                add("耗时：${result.elapsedMillis} ms")
                if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                    val scores = result.observation.anchorScores
                    val nodeHeader = maxOf(
                        scores[EntryAnchorId.NODE_HEADER],
                        scores[EntryAnchorId.NODE_HEADER_STANDARD],
                    )
                    val nodeLeft = maxOf(
                        scores[EntryAnchorId.NODE_CHARACTERS],
                        scores[EntryAnchorId.NODE_CHARACTERS_STANDARD],
                        scores[EntryAnchorId.NODE_RELICS],
                        scores[EntryAnchorId.NODE_RELICS_STANDARD],
                    )
                    val nodeRight = maxOf(
                        scores[EntryAnchorId.NODE_RETREAT],
                        scores[EntryAnchorId.NODE_RETREAT_STANDARD],
                        scores[EntryAnchorId.NODE_RETURN],
                        scores[EntryAnchorId.NODE_RETURN_STANDARD],
                    )
                    val nodeSelectionScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_SELECTION] ?: 0.0
                    val nodeMapViewScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_MAP_VIEW] ?: 0.0
                    add(
                        "节点诊断：选=${"%.2f".format(nodeSelectionScore)} · " +
                            "看=${"%.2f".format(nodeMapViewScore)} · " +
                            "头=${"%.2f".format(nodeHeader)} · 左=${"%.2f".format(nodeLeft)} · " +
                            "右=${"%.2f".format(nodeRight)}",
                    )
                }
                result.matchedFeatures.take(3).takeIf(List<String>::isNotEmpty)?.let {
                    add("特征：${it.joinToString()}")
                }
                result.nodeClassifications
                    .groupingBy { it.blockType }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .takeIf { it.isNotEmpty() }
                    ?.let { nodes ->
                        add(
                            "节点：" + nodes.joinToString("、") { (type, count) ->
                                "${LabyrinthNodeTypes.labelOf(type)}×$count"
                            },
                        )
                    }
                result.characterMatches
                    .takeIf(List<*>::isNotEmpty)
                    ?.let { characters ->
                        add(
                            "角色：" + characters.joinToString("、") { character ->
                                character.displayName ?: "未收录"
                            },
                        )
                }
                if (pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION) {
                    when (val decision = current.roleRewardChoiceDecision) {
                        is LabyrinthRoleRewardChoiceDecision.Select -> {
                            add(
                                "角色三选一建议：${decision.characterId} ${decision.displayName}" +
                                    if (decision.actionSafe) "" else "（仅推荐）",
                            )
                            decision.safetyNote?.let { add("推荐安全状态：$it") }
                        }
                        is LabyrinthRoleRewardChoiceDecision.Wait ->
                            add("角色三选一等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.battleTeamSelection?.let { team ->
                    if (team.recognitionState == LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY) {
                        add("编组：${team.currentFilter.label} · 角色列表变化，等待稳定")
                    } else {
                        val recognized = team.recognizedCharacterCount
                        val total = team.visibleCharacters.size + team.selectedCharacters.size
                        add(
                            "编组：${team.currentFilter.label} · 角色 $recognized/$total · " +
                                if (team.scrollbar.canScroll) "可滚动" else "无需滚动",
                        )
                        team.selectedCharacters
                            .mapNotNull { it.displayName }
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { add("当前队伍：${it.joinToString("、")}") }
                    }
                }
                result.linkChoiceSelection?.let { link ->
                    add(
                        "连结印记：" + link.choices.joinToString("、") { choice ->
                            val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "#$it" } ?: "#?"
                            "$rank${choice.element.label}(${"%.2f".format(choice.confidence)})"
                        },
                    )
                    add("属性优先级：${link.priority.label}")
                    link.preferredChoice?.let { preferred ->
                        add(
                            "当前建议：${preferred.slotId.removePrefix("link_choice_")}号卡 · " +
                                "${preferred.element.label}（优先${preferred.priorityRank}）",
                        )
                    }
                }
                result.eventChoiceSelection?.let { event ->
                    add(
                        "事件OCR：${event.event.id} · ${"%.2f".format(event.confidence)}" +
                            " / 差${"%.2f".format(event.rivalMargin)} · 稳定${event.stableFrames}" +
                            if (event.trusted) " · 可信" else " · 待确认",
                    )
                    when (val decision = current.eventChoiceDecision) {
                        is LabyrinthEventChoiceDecision.Select -> add(
                            "事件建议：${decision.choiceId} ${decision.label} · ${"%.2f".format(decision.utility)}" +
                                if (decision.actionSafe) " · 可执行" else " · 仅推荐",
                        )
                        is LabyrinthEventChoiceDecision.Wait -> add("事件等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.relicChoiceSelection?.let { relics ->
                    add(
                        "迷宫遗物：" + relics.choices.joinToString("、") { relic ->
                            val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                            val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                            val bonus = relic.attributeBonus
                                ?.takeIf(String::isNotBlank)
                                ?.let { "+$it" }
                                .orEmpty()
                            val prefix = if (relic.recognized) "" else "疑似"
                            val currentStacks = relic.currentMarkStacks?.let { "/当前$it" }.orEmpty()
                            "$prefix$identity[$attribute$bonus$currentStacks](${"%.2f".format(relic.confidence)}/差${"%.2f".format(relic.rivalMargin)})"
                        },
                    )
                    relicChoicePolicy.choose(
                        acquired = current.observedRelics,
                        candidates = relics.choices,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = current.routeProgress?.currentArea,
                        lockedFocus = relicFocusMark,
                    )?.let { decision ->
                        add("当前建议：${decision.choice.displayName ?: "未收录"} · ${decision.reason}")
                    }
                }
                result.nodeRelicStackObservation
                    ?.entries
                    ?.takeIf(List<*>::isNotEmpty)
                    ?.let { entries ->
                        add(
                            "地图遗物层数：" + entries.joinToString("、") { entry ->
                                val mark = entry.attribute ?: "未知"
                                val stacks = entry.stacks?.toString() ?: "?"
                                "$mark$stacks"
                            },
                        )
                    }
                result.shopObservation?.let { shop ->
                    val dialog = shop.dialogState.takeIf { it != LabyrinthShopDialogState.NONE }
                        ?.let { " · 弹窗=${it.name}" }
                        .orEmpty()
                    add(
                            "商店：购买位${shop.items.size} · 可购买${shop.availableItemCount} · " +
                                "按钮识别${shop.recognizedButtonCount}${dialog}",
                    )
                    if (shop.dialogState == LabyrinthShopDialogState.NONE) {
                        add(
                            "商店商品：" + shop.items.joinToString("、") { item ->
                                if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) {
                                    "${item.roleImprintLabel ?: "职能印记"}(OCR确认)"
                                } else {
                                    val relic = item.relicMatch
                                    val identity = relic?.displayName ?: relic?.suspectedDisplayName ?: "非遗物/未确认"
                                    val prefix = if (relic?.recognized == true) "" else "疑似"
                                    "$prefix$identity(${"%.2f".format(relic?.confidence ?: 0.0)})"
                                }
                            },
                        )
                        currentShopDecision(result)?.let { decision ->
                            add(
                                "商店策略：" + when (decision) {
                                    is LabyrinthShopDecision.BuyRelic ->
                                        "购买${decision.relicDecision.choice.displayName ?: decision.relicDecision.choice.relicId} · " +
                                            decision.relicDecision.reason
                                    is LabyrinthShopDecision.BuyRoleImprint ->
                                        "购买${decision.label} · ${decision.reason}"
                                    is LabyrinthShopDecision.Refresh -> "刷新 · ${decision.reason}"
                                    is LabyrinthShopDecision.Close -> "关闭 · ${decision.reason}"
                                    is LabyrinthShopDecision.Wait -> "等待 · ${decision.reason}"
                                },
                            )
                        }
                    }
                    shop.purchaseCandidate?.let { candidate ->
                        add(
                            "待购买遗物：${candidate.displayName ?: "未收录"} · " +
                                "${candidate.attribute ?: "未知属性"} · " +
                                "${"%.2f".format(candidate.confidence)}",
                        )
                    }
                }
                current.joinedCharacters
                    .takeIf(List<LabyrinthJoinedCharacter>::isNotEmpty)
                    ?.let { characters ->
                        add("已加入：" + characters.joinToString("、") { it.displayName })
                    }
                if (relicMarkStackCalibration.isNotEmpty()) {
                    add("系列记录：" + relicMarkStackCalibration.entries.joinToString("、") { "${it.key.label}${it.value}" })
                }
                if (relicStackLedger.needsAudit) {
                    add("地图数字与记录冲突，保留已确认值；可打开系列效果一览核对，不阻塞路线")
                }
                current.observedRelics
                    .takeIf(List<LabyrinthObservedRelic>::isNotEmpty)
                    ?.let { relics ->
                        add(
                            "已观察遗物：" + relics.joinToString("、") {
                                "${it.displayName}[${it.attribute}]×${it.observationCount}"
                            },
                        )
                    }
                current.pendingAcquiredCharacterId?.let { add("待确认获得角色：$it") }
                current.routeProgress?.let { progress ->
                    add(
                        "路线：区域${progress.currentArea} · 已走${progress.visitedCount}/${progress.routeNodeCount}" +
                            if (progress.complete) " · 已完成" else "",
                    )
                    if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                        progress.nextNodeLabel?.let { add("下一节点：$it") }
                    }
                }
            }
        }.joinToString("\n")
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = if (current.dryRun) "黎明界入口识别" else "黎明界入口流程",
            status = if (current.paused) {
                "已暂停"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan?.teamReady == true
            ) {
                "第一队已一致 · 准备开始战斗"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan != null
            ) {
                val plan = current.battleTeamSelectionPlan
                "自动编组 · 保持${plan.alreadyCorrectIds.size}/取消${plan.needDeselectIds.size}/选择${plan.needSelectIds.size}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamRecommendation != null
            ) {
                val recommendation = current.battleTeamRecommendation
                "推荐第一队 · ${recommendation.damageTypeLabel} · ${"%.2f".format(recommendation.score)}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.EVENT_CHOICE &&
                current.eventChoiceDecision is LabyrinthEventChoiceDecision.Select
            ) {
                val decision = current.eventChoiceDecision
                if (decision.actionSafe) {
                    "事件${decision.eventId}建议 · ${decision.label}"
                } else {
                    "事件${decision.eventId}推荐 · ${decision.label} · 仅建议"
                }
            } else if (
                pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION &&
                current.roleRewardChoiceDecision is LabyrinthRoleRewardChoiceDecision.Select
            ) {
                val decision = current.roleRewardChoiceDecision
                if (decision.actionSafe) {
                    "角色三选一建议 · ${decision.displayName}"
                } else {
                    "角色三选一推荐 · ${decision.displayName} · 仅建议"
                }
            } else if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                val stacks = nodeRelicStackSummary(result)
                when {
                    stacks != null && current.dryRun -> "只读运行中 · 遗物 $stacks"
                    stacks != null -> "点击运行中 · 遗物 $stacks"
                    current.dryRun -> "只读运行中 · 遗物识别中"
                    else -> "点击运行中 · 遗物识别中"
                }
            } else if (current.dryRun) {
                "只读运行中"
            } else {
                "点击运行中"
            },
            detail = detail,
            dryRun = current.dryRun,
            paused = current.paused,
            boxes = buildPageOwnedOverlayBoxes(pageOwner, current, result),
            renderBoxesOnDevice = false,
        )
    }

    private fun nodeRelicStackSummary(result: LabyrinthEntryFrameResult?): String? =
        result?.nodeRelicStackObservation
            ?.entries
            ?.takeIf(List<*>::isNotEmpty)
            ?.joinToString(" ") { entry ->
                val mark = entry.attribute ?: "?"
                val stacks = entry.stacks?.toString() ?: "?"
                "$mark$stacks"
            }

    private fun buildPageOwnedOverlayBoxes(
        owner: LabyrinthPageUiOwner,
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> = when (owner) {
        LabyrinthPageUiOwner.NODE_SELECTION ->
            buildNextNodeOverlayBoxes(current, result) + buildPausedNodeDiagnosticsBoxes(current, result)
        LabyrinthPageUiOwner.CHARACTER_SELECTION,
        LabyrinthPageUiOwner.CHARACTER_JOINED,
        -> buildCharacterPageOverlayBoxes(current, result)
        LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION -> buildBattleTeamOverlayBoxes(current, result)
        LabyrinthPageUiOwner.LINK_CHOICE -> buildLinkChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.RELIC_CHOICE -> buildRelicChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.SHOP -> buildShopOverlayBoxes(result)
        LabyrinthPageUiOwner.EVENT_CHOICE -> buildEventChoiceOverlayBoxes(current, result)
        else -> emptyList()
    }

    private fun buildEventChoiceOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val event = result?.eventChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: return emptyList()
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: return emptyList()
        val selectedChoiceId = (current.eventChoiceDecision as? LabyrinthEventChoiceDecision.Select)?.choiceId
        return event.choices.mapNotNull { visual ->
            val rect = visual.buttonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val selected = visual.choice.id == selectedChoiceId
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = buildString {
                    append(if (selected) "推荐 " else "事件 ")
                    append(visual.choice.id)
                    append(' ')
                    append(visual.choice.name)
                    append(" / 蓝${"%.2f".format(visual.buttonConfidence)}")
                },
                recognized = event.trusted && visual.buttonConfidence >= 0.18,
                selected = selected,
                labelAbove = true,
            )
        }
    }

    private fun buildCharacterPageOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        result ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val chosenId = (current.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)
            ?.characterId
            ?.let(::canonicalLabyrinthRoleId)
        val roleMatches = result.characterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                // The reward page has a stricter irreversible-action gate than the shared icon
                // matcher.  Reflect that in the overlay too: a weak shared "trusted" match must
                // still be shown as tentative until the icon is strong or name OCR confirms it.
                trusted = labyrinthRoleRewardIdentityIsActionSafe(match),
                selected = match.characterId?.let(::canonicalLabyrinthRoleId) == chosenId,
                rect = match.screenRect,
                nameEvidenceText = match.nameEvidenceText,
                nameEvidenceScore = match.nameEvidenceScore,
                nameEvidenceMargin = match.nameEvidenceMargin,
                nameAssisted = match.nameAssisted,
            )
        }
        val openingMatches = result.openingCharacterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                trusted = match.trusted,
                selected = match.selected,
                rect = match.screenRect,
            )
        }
        return (roleMatches + openingMatches).mapNotNull { source ->
            val rect = source.rect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val identity = source.displayName ?: source.suspectedId ?: "未识别"
            val nameEvidence = source.nameEvidenceText
                ?.replace("\n", "")
                ?.replace("\r", "")
                ?.take(8)
                ?.takeIf(String::isNotBlank)
                ?.let { text ->
                    " / 字${"%.2f".format(source.nameEvidenceScore)}" +
                        " 差${"%.2f".format(source.nameEvidenceMargin)}[$text]"
                }
                .orEmpty()
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (source.trusted) {
                    buildString {
                        append(identity)
                        if (source.nameAssisted) append(" ✓字")
                        append(" ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}")
                        append(nameEvidence)
                    }
                } else {
                    "疑似$identity，不点击 ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}$nameEvidence"
                },
                recognized = source.trusted,
                selected = source.selected,
                labelAbove = true,
            )
        }
    }

    private data class CharacterOverlaySource(
        val characterId: String?,
        val suspectedId: String?,
        val displayName: String?,
        val confidence: Double,
        val rivalMargin: Double,
        val trusted: Boolean,
        val selected: Boolean,
        val rect: EntryPixelRect,
        val nameEvidenceText: String? = null,
        val nameEvidenceScore: Double = 0.0,
        val nameAssisted: Boolean = false,
        val nameEvidenceMargin: Double = 0.0,
    )

    /**
     * Show enlarged node diagnostics only after the user pauses the session. The annotation layer
     * is visible to MediaProjection, so drawing these boxes while recognition is running would
     * make the boxes themselves possible node candidates.
     */
    private fun buildPausedNodeDiagnosticsBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        if (!labyrinthShowsPausedNodeDiagnostics(current.paused)) return emptyList()
        if (result?.observation?.state != LabyrinthEntryPageState.NODE_SELECTION) return emptyList()

        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val matchResult = nodeSession?.getLastMatchResult()
        val boxes = mutableListOf<AutomationOverlayBox>()
        result.nodeClassifications.forEach { classification ->
            val sourceRect = classification.screenRect ?: return@forEach
            val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
            val mapping = matchResult?.visibleNodes?.firstOrNull { it.screenRect == sourceRect }
            val conflict = matchResult?.conflicts?.firstOrNull { it.screenRect == sourceRect }
            val topology = matchResult?.topologyMappings?.firstOrNull { it.screenRect == sourceRect }
            val mappingLabel = when {
                conflict != null -> " → TYPE_CONFLICT ${conflict.blockId} " +
                    "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}"
                topology != null -> " → 拓扑 ${topology.blockId} " +
                    "${LabyrinthNodeTypes.labelOf(topology.expectedBlockType)} " +
                    "T=${"%.2f".format(topology.topologyConfidence)}"
                mapping != null -> " → ${mapping.blockId} ${LabyrinthNodeTypes.labelOf(mapping.blockType)}"
                else -> " → 未映射"
            }
            toNodeDiagnosticsOverlayBox(
                rect = rect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                label = "候选 ${classification.column}/${classification.row} 视觉=" +
                    "${LabyrinthNodeTypes.labelOf(classification.blockType)} " +
                    "类别${"%.2f".format(classification.typeConfidence)} " +
                    "结构${"%.2f".format(classification.confidence)}" +
                    (if (classification.isClickable) " 可点" else " 不可点") +
                    mappingLabel,
                recognized = classification.isClickable,
                selected = conflict != null,
            )?.let(boxes::add)
        }

        matchResult?.conflicts
            ?.filter { conflict ->
                conflict.screenRect != null &&
                    result.nodeClassifications.none { it.screenRect == conflict.screenRect }
            }
            ?.forEach { conflict ->
                val sourceRect = conflict.screenRect ?: return@forEach
                val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "TYPE_CONFLICT ${conflict.blockId} " +
                        "视觉=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)} " +
                        "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

        val targetRect = current.routeProgress?.nextNodeRect
        val targetLabel = current.routeProgress?.nextNodeLabel
        if (targetRect != null && targetLabel != null) {
            expandNodeDiagnosticsRect(targetRect, frameWidth, frameHeight)?.let { rect ->
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "目标 $targetLabel",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(targetRect)
            val markerSize = minOf(DEBUG_CLICK_MARKER_SIZE_PX, frameWidth, frameHeight)
            if (markerSize > 0) {
                val marker = EntryPixelRect(
                    left = (clickX - markerSize / 2).coerceIn(0, frameWidth - markerSize),
                    top = (clickY - markerSize / 2).coerceIn(0, frameHeight - markerSize),
                    width = markerSize,
                    height = markerSize,
                )
                toNodeDiagnosticsOverlayBox(
                    rect = marker,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "点击点 $clickX,$clickY",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }
        }
        return boxes
    }

    private fun expandNodeDiagnosticsRect(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val left = (rect.left - DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtLeast(0)
        val top = (rect.top - DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtLeast(0)
        val right = (rect.left + rect.width + DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtMost(frameWidth)
        val bottom = (rect.top + rect.height + DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtMost(frameHeight)
        if (right <= left || bottom <= top) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    private fun toNodeDiagnosticsOverlayBox(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
        label: String,
        recognized: Boolean,
        selected: Boolean,
    ): AutomationOverlayBox? {
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return null
        return AutomationOverlayBox(
            left = left,
            top = top,
            width = width,
            height = height,
            label = label,
            recognized = recognized,
            selected = selected,
        )
    }

    private fun buildNextNodeOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        // MediaProjection includes this overlay in later frames. During execution a cyan target
        // rectangle would therefore become new node-recognition input and reinforce a bad match.
        if (!labyrinthShowsNextNodeOverlayBox(current.dryRun)) return emptyList()
        val progress = current.routeProgress ?: return emptyList()
        val rect = progress.nextNodeRect ?: return emptyList()
        val label = progress.nextNodeLabel ?: return emptyList()
        val frameWidth = result?.frameWidth?.takeIf { it > 0 } ?: 1920
        val frameHeight = result?.frameHeight?.takeIf { it > 0 } ?: 1080
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return emptyList()
        return listOf(
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (current.dryRun) "将点击：$label" else "下一节点：$label",
                recognized = true,
                selected = true,
            ),
        )
    }

    private fun buildBattleTeamOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val team = result?.battleTeamSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val plan = current.battleTeamSelectionPlan
        return (team.visibleCharacters + team.selectedCharacters).mapNotNull { character ->
            val rect = character.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val selectTarget = plan?.visibleSelectTargets?.firstOrNull { it.slotId == character.slotId }
                val deselectTarget = plan?.visibleDeselectTargets?.firstOrNull { it.slotId == character.slotId }
                val unsafeTarget = plan?.unsafeVisibleMatches?.firstOrNull { it.slotId == character.slotId }
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = when {
                        selectTarget != null -> "将选择：${selectTarget.displayName}"
                        deselectTarget != null -> "将取消：${deselectTarget.displayName}"
                        unsafeTarget != null -> "低置信度，不点击：${unsafeTarget.displayName}"
                        else -> character.displayName ?: buildString {
                            append("未确认")
                            character.suspectedDisplayName?.let { append("（疑似$it）") }
                            append(" ${"%.2f".format(character.confidence)} / 差${"%.2f".format(character.rivalMargin)}")
                        }
                    },
                    recognized = character.characterId != null,
                    selected = character.selected,
                    labelAbove = true,
                )
            }
        }
    }

    private fun buildLinkChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val link = result?.linkChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return link.choices.mapNotNull { choice ->
            // Show the same lower selection button that the action executor will tap. The card
            // and badge remain available in the recognition result for diagnostics.
            val rect = choice.selectionButtonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "优先$it" } ?: "未识别"
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${choice.element.label} · $rank · ${"%.2f".format(choice.confidence)}",
                    recognized = choice.recognized,
                    selected = false,
                )
            }
        }
    }

    private fun buildRelicChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val relics = result?.relicChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return relics.choices.mapNotNull { relic ->
            val rect = relic.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                val currentStacks = relic.currentMarkStacks?.let { " · 当前$it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = if (relic.recognized) {
                        "$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    } else {
                        "疑似$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    },
                    recognized = relic.recognized,
                    selected = false,
                )
            }
        }
    }

    private fun buildShopOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val shop = result?.shopObservation ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return shop.items.mapNotNull { item ->
            val rect = item.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val status = when (item.status) {
                    LabyrinthShopItemStatus.AVAILABLE -> "可购买"
                    LabyrinthShopItemStatus.DISABLED -> "不可购买/余额不足"
                    LabyrinthShopItemStatus.UNKNOWN -> "按钮未确认"
                }
                val relic = item.relicMatch?.takeIf(LabyrinthRelicMatch::recognized)
                val relicLabel = relic?.displayName?.let { " · $it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${item.slotId.removePrefix("shop_item_")}号商品$relicLabel · $status · " +
                        "模板${"%.2f".format(item.buyButtonScore)} / 可用态${"%.2f".format(item.buyButtonEnabledEvidence)}",
                    recognized = item.purchasable,
                    selected = false,
                )
            }
        }
    }

    private fun recycle(frame: CapturedFrame) {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    private fun describeNodeClassifications(classifications: List<NodeClassification>): String =
        classifications.joinToString(separator = ";") { classification ->
            val rect = classification.screenRect
            "${classification.column}/${classification.row}:" +
                "${LabyrinthNodeTypes.labelOf(classification.blockType)}" +
                "@${"%.2f".format(classification.confidence)}" +
                ":click=${classification.isClickable}" +
                ":glow=cyan${"%.2f".format(classification.cyanGlowScore)}," +
                "rows${"%.2f".format(classification.cyanGlowRowCoverage)}," +
                "purple${"%.2f".format(classification.purpleGlowScore)}," +
                "purpleRows${"%.2f".format(classification.purpleGlowRowCoverage)}," +
                "purpleSide${"%.2f".format(classification.purpleGlowSideScore)}," +
                "purpleLower${"%.2f".format(classification.purpleGlowLowerScore)}" +
                ":color=${"%.2f".format(classification.colorRoiScore)}" +
                ":rect=${rect?.left},${rect?.top},${rect?.width},${rect?.height}" +
                ":template=${classification.templateId}"
        }.ifBlank { "none" }

    private fun nodeLog(message: String, warning: Boolean = false) {
        runCatching {
            if (warning) Log.w(NODE_LOG_TAG, message) else Log.d(NODE_LOG_TAG, message)
        }
    }

    private companion object {
        const val NODE_LOG_TAG = "LabyrinthNode"
        const val TEAM_RECOMMENDATION_LOG_TAG = "LabyrinthTeamPlan"
        const val TEAM_SELECTION_PLAN_LOG_TAG = "LabyrinthTeamSelection"
        const val OWNER = "labyrinth-entry-recognition"
        const val FRAME_INTERVAL_MILLIS = 500L
        const val FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        const val NODE_CLICK_STABLE_FRAMES = 3
        const val NODE_CLICK_INTERVAL_MILLIS = 2_500L
        const val NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS = 2_000L
        const val NODE_MOVE_CONFIRMATION_STABLE_FRAMES = 2
        const val MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS = 3
        const val MAX_NODE_TAP_ATTEMPTS = 3
        const val MAX_NODE_SCROLL_ATTEMPTS = 6
        const val NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES = 5
        const val NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS = 1_500L
        const val NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES = 12
        const val NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS = 6_000L
        const val MAX_NODE_ERROR_STREAK = 20
        const val POST_ENTRY_STABLE_FRAMES = 2
        const val ROLE_REWARD_SELECTION_STABLE_FRAMES = 3
        const val POST_ENTRY_ACTION_INTERVAL_MILLIS = 1_500L
        const val SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS = 8_000L
        const val CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS = 650L
        const val NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS = 2_000L
        const val MAX_POST_ENTRY_ATTEMPTS = 6
        const val BATTLE_TEAM_ACTION_INTERVAL_MILLIS = 1_200L
        const val BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS = 4_000L
        const val MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3
        const val MAX_BATTLE_TEAM_SCROLL_ACTIONS = 48
        const val MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS = 48
        const val EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS = 360L
        const val EFFECTIVE_SCAN_END_STABLE_FRAMES = 2
        const val EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS = 1_500L
        val EFFECTIVE_SCAN_CONTEXT_IDS = listOf("__effective-effect-scan__")
        const val EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS = 5_000L
        const val EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS = 10_000L
        const val EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS = 10_000L
        const val MAX_EX_SLOT3_PROBE_ATTEMPTS = 2
        const val MAX_EVENT_ACTIONS = 16
        const val MAX_CHARACTER_ACQUISITION_CLICKS = 40
        const val CHARACTER_ACQUISITION_TIMEOUT_MILLIS = 30_000L
        const val MAX_POST_BOSS_UNKNOWN_ATTEMPTS = 40
        const val FINAL_HOME_RETURN_TIMEOUT_MILLIS = 20_000L
        const val UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS = 30_000L
        const val POST_ENTRY_SELECTION_MIN_SCORE = 0.45
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45
        const val SESSION_BLOCK_STABLE_FRAMES = 2
        const val SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS = 1_500L
        const val MAX_SESSION_RETURN_TITLE_ATTEMPTS = 6
        const val DEBUG_NODE_BOX_MARGIN_X_PX = 36
        const val DEBUG_NODE_BOX_MARGIN_Y_PX = 48
        const val DEBUG_CLICK_MARKER_SIZE_PX = 56
    }
}

private const val SESSION_RETURN_TITLE_MIN_SCORE = 0.45
private const val SESSION_DATE_CHANGE_TITLE_MIN_SCORE = 0.72
private const val SESSION_DATE_CHANGE_CONFIRM_MIN_SCORE = 0.55
private const val NODE_VIEWPORT_SIGNATURE_BUCKET_PX = 32
