package com.landosol.toolbox

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.landosol.toolbox.account.AccountRepository
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.GameClientProfile
import com.landosol.toolbox.automation.GameClientProfileResolver
import com.landosol.toolbox.automation.GameClientResolution
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.AndroidAccessibilityActionBackend
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.automation.accessibility.isExpectedGamePackage
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.MediaProjectionCaptureService
import com.landosol.toolbox.automation.session.AccessibilityForegroundPresenceObserver
import com.landosol.toolbox.automation.session.CompositeSessionResetBackend
import com.landosol.toolbox.automation.session.GameClientRelaunchResult
import com.landosol.toolbox.automation.session.GameSessionResetStartResult
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.automation.session.GameSessionResetWorkflow
import com.landosol.toolbox.automation.session.Relauncher
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.SessionExpiryFrameTracker
import com.landosol.toolbox.automation.session.SessionExpiryTerminator
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.clanbattle.ClanBattleRecognitionSession
import com.landosol.toolbox.automation.overlay.AndroidAutomationNotificationHost
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import com.landosol.toolbox.labyrinth.AndroidLabyrinthRoleDecisionDataLoader
import com.landosol.toolbox.labyrinth.AndroidLabyrinthCnDatabaseRepository
import com.landosol.toolbox.labyrinth.LabyrinthCnDatabaseUpdateResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.RoomLabyrinthRouteStore
import com.landosol.toolbox.labyrinth.BilibiliLabyrinthRouteSource
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthEntryFrameProcessor
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.protocol.bilibili.BilibiliLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliGameGatewayFactory
import com.landosol.toolbox.protocol.bilibili.BilibiliNativeLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliSdkGatewayFactory
import com.landosol.toolbox.protocol.bilibili.InMemoryGameSessionRegistry
import com.landosol.toolbox.security.AndroidKeystoreCredentialStore
import com.landosol.toolbox.security.AndroidKeystoreSdkSessionStore
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStartResult
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunConfig
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunWorkflow
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunRoundOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LandosolToolboxApplication : Application() {
    private val databaseUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gameClientResolution by lazy { GameClientProfileResolver.resolve(packageManager) }
    private val resolvedGameClient: GameClientProfile?
        get() = (gameClientResolution as? GameClientResolution.Available)?.profile

    override fun onCreate() {
        super.onCreate()
        // The dashboard is loopback-only, so it is safe to start for test/release APKs too.
        // Starting it here also makes http://127.0.0.1:8765/ immediately reachable from a
        // browser running inside the emulator, before the first recognition session starts.
        labyrinthDebugDashboard
        databaseUpdateScope.launch {
            when (val result = labyrinthCnDatabaseRepository.updateIfNeeded()) {
                is LabyrinthCnDatabaseUpdateResult.UpToDate ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database is current: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Updated ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database updated: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Failed ->
                    Log.w(DATABASE_UPDATE_LOG_TAG, result.reason)
            }
        }
    }

    val automationSessionManager by lazy { AutomationSessionManager() }
    val automationOverlayCoordinator by lazy {
        AutomationOverlayCoordinator(AndroidAutomationNotificationHost(this))
    }
    private val accessibilityActionBackend by lazy {
        AndroidAccessibilityActionBackend { resolvedGameClient?.packageName }
    }
    val automationActionExecutor by lazy {
        SessionBoundActionExecutor(automationSessionManager, accessibilityActionBackend)
    }
    val clanBattleRecognitionSession by lazy {
        ClanBattleRecognitionSession(automationSessionManager, overlayCoordinator = automationOverlayCoordinator)
    }
    private val labyrinthRoleDecisionData by lazy {
        AndroidLabyrinthRoleDecisionDataLoader(this).load()
    }
    val labyrinthStrategySettings by lazy {
        com.landosol.toolbox.labyrinth.AndroidLabyrinthStrategySettingsStore(this)
    }
    suspend fun loadLabyrinthRoleRatings(): List<com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val data = labyrinthRoleDecisionData
            val document = (data as? LabyrinthRoleDecisionDataResult.Ready)?.runtime?.document
                ?: error((data as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason ?: "角色资料不可用")
            val icons = try {
                assets.open("resource-packs/cn-bilibili/icons.json").bufferedReader().use {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.landosol.toolbox.gamedata.GameIconPackDocument>(it.readText())
                }
            } catch (_: Exception) { null }
            com.landosol.toolbox.labyrinth.labyrinthRoleRatingCatalog(document, icons)
        }
    val labyrinthCnDatabaseRepository by lazy {
        AndroidLabyrinthCnDatabaseRepository(this)
    }
    private val labyrinthDebugDashboard by lazy {
        LabyrinthDebugDashboardServer().also { server ->
            server.start()
        }
    }
    val labyrinthEntryRecognitionSession by lazy {
        // Start the debug endpoint with its initial waiting snapshot instead of waiting for the
        // first captured frame. That keeps first-frame capture failures observable in the browser.
        val debugDashboard = labyrinthDebugDashboard
        val roleDecisionRuntime = (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Ready)?.runtime
        val roleDecisionUnavailableReason =
            (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason
        val characterAttributes = roleDecisionRuntime?.profiles
            ?.mapValues { (_, profile) -> profile.attribute }
            .orEmpty()
        LabyrinthEntryRecognitionSession(
            strategyProvider = {
                val settings = labyrinthStrategySettings.state.value
                com.landosol.toolbox.labyrinth.LabyrinthStrategySnapshot(
                    settings,
                    roleDecisionRuntime?.document?.let { document ->
                        com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser.createRuntime(
                            settings.applyTo(document), settings,
                        )
                    },
                )
            },
            rerollRequester = { accountId ->
                labyrinthController.startAfterBattleFailureReroll(accountId)
            },
            sessionManager = automationSessionManager,
            processorFactory = { finalBossOnly ->
                AndroidLabyrinthEntryFrameProcessor.create(
                    context = this,
                    characterAttributes = characterAttributes,
                    finalBossOnly = finalBossOnly,
                )::process
            },
            captureStop = { MediaProjectionCaptureService.stop(this) },
            overlayCoordinator = automationOverlayCoordinator,
            actionExecutor = automationActionExecutor,
            actionsAvailable = LandosolAccessibilityService::isConnected,
            actionTargetReady = {
                isExpectedGamePackage(resolvedGameClient?.packageName, LandosolAccessibilityService.foregroundPackage())
            },
            actionPlannerFactory = {
                LabyrinthEntryActionPlanner(
                    LabyrinthEntryActionPlannerConfig(
                        manualCharacterSelection = false,
                        requireConfiguredOpeningRoster = true,
                    ),
                )
            },
            roleRewardChoicePlanner = roleDecisionRuntime?.rewardChoicePlanner,
            roleRewardChoiceUnavailableReason = roleDecisionUnavailableReason,
            eventChoicePlanner = roleDecisionRuntime?.eventChoicePlanner,
            battleTeamRecommendationPlanner = roleDecisionRuntime?.battleTeamRecommendationPlanner,
            battleTeamRecommendationUnavailableReason = roleDecisionUnavailableReason,
            battleTeamSelectionPlanner = roleDecisionRuntime?.battleTeamSelectionPlanner,
            runStateStore = labyrinthRunStateStore,
            routeSource = labyrinthRouteSource,
            nodeTemplateLoader = { AndroidLabyrinthNodeTemplateLoader(this).load() },
            debugFramePublisher = debugDashboard?.let { dashboard ->
                { frame, state, result, presentation ->
                    dashboard.publish(
                        bitmap = frame.bitmap,
                        state = state,
                        result = result,
                        presentation = presentation,
                        nowMillis = frame.timestampMillis,
                    )
                }
            },
            gameLauncher = gameLauncher@{
                val intent = resolvedGameClient?.packageName?.let(packageManager::getLaunchIntentForPackage)
                    ?: return@gameLauncher false
                runCatching {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            },
        )
    }
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val labyrinthRunStateStore by lazy { RoomLabyrinthRunStateStore(database) }
    val labyrinthRouteStore by lazy { RoomLabyrinthRouteStore(database) }
    private val importedRouteStore by lazy { com.landosol.toolbox.labyrinth.AndroidImportedRouteTextStore(this) }
    val importedRouteController by lazy { com.landosol.toolbox.labyrinth.ImportedRouteController(importedRouteStore) }
    val importedRouteSource by lazy { com.landosol.toolbox.labyrinth.ManualImportedLabyrinthRouteSource(importedRouteStore) }
    val labyrinthRouteSource by lazy {
        BilibiliLabyrinthRouteSource(
            routeStore = labyrinthRouteStore,
            checkpointStore = com.landosol.toolbox.labyrinth.RoomLabyrinthRerollCheckpointStore(database),
        )
    }
    private val credentialStore by lazy { AndroidKeystoreCredentialStore(this) }
    private val sessionStore by lazy { AndroidKeystoreSdkSessionStore(this) }
    val gameSessionRegistry by lazy { InMemoryGameSessionRegistry() }
    private val bilibiliSdkGateway by lazy { BilibiliSdkGatewayFactory.create(this) }
    private val bilibiliSdkLoginCoordinator by lazy {
        BilibiliLoginCoordinator(bilibiliSdkGateway, sessionStore)
    }
    val bilibiliNativeLoginCoordinator: BilibiliNativeLoginCoordinator by lazy {
        BilibiliNativeLoginCoordinator(
            sdkCoordinator = bilibiliSdkLoginCoordinator,
            sdkGateway = bilibiliSdkGateway,
            sessionStore = sessionStore,
            gameGateway = BilibiliGameGatewayFactory.create(this),
            gameSessionRegistry = gameSessionRegistry,
        )
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(database, credentialStore, sessionStore, gameSessionRegistry)
    }
    val labyrinthController by lazy {
        com.landosol.toolbox.labyrinth.LabyrinthController(
            accountRepository, gameSessionRegistry, database,
            loginCoordinatorProvider = {
                // 首页不解析渠道依赖；未安装客户端是功能前置条件，不是启动异常。
                try {
                    bilibiliNativeLoginCoordinator
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            },
            settingsStore = com.landosol.toolbox.labyrinth.AndroidLabyrinthRerollSettingsStore(this),
            launchForeground = { com.landosol.toolbox.labyrinth.LabyrinthRerollService.start(this) },
        )
    }

    /**
     * 无 Root 会话失效触发式重置：点进黎明界触发「会话失效」弹窗 → 识别到
     * 「错误提示」→ 点「返回标题」→ 回到标题页，随后等待登录/主页/冒险/黎明界。
     */
    val gameSessionResetWorkflow by lazy {
        val frameTracker = SessionExpiryFrameTracker()
        val presence = AccessibilityForegroundPresenceObserver(
            gamePackageName = resolvedGameClient?.packageName.orEmpty(),
            foregroundPackage = LandosolAccessibilityService::foregroundPackage,
        )
        val terminator = SessionExpiryTerminator(
            onTap = { point ->
                when (accessibilityActionBackend.execute(AutomationAction.Tap(point))) {
                    com.landosol.toolbox.automation.AutomationBackendResult.Completed -> true
                    else -> false
                }
            },
            triggerEntryPoint = SESSION_EXPIRY_TRIGGER_POINT,
            returnTitlePoint = SESSION_RETURN_TITLE_POINT,
            popupVisible = { frameTracker.popupVisible },
            titleReached = { frameTracker.titleReached },
            frameSize = { frameTracker.frameSize },
            available = LandosolAccessibilityService::isConnected,
            triggerAnchorPoint = { frameTracker.anchorCenter(EntryAnchorId.LABYRINTH_ENTRY) },
            returnTitleAnchorPoint = { frameTracker.anchorCenter(EntryAnchorId.SESSION_RETURN_TITLE) },
        )
        GameSessionResetWorkflow(
            sessionManager = automationSessionManager,
            backend = CompositeSessionResetBackend(
                terminator = terminator,
                relauncher = Relauncher {
                    val intent = resolvedGameClient?.packageName?.let(packageManager::getLaunchIntentForPackage)
                        ?: return@Relauncher GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                    runCatching {
                        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess.let { launched ->
                        if (launched) GameClientRelaunchResult.LAUNCH_REQUESTED
                        else GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                    }
                },
            ),
            captureActive = CaptureStateRegistry::isActive,
            processorFactory = {
                val processor = AndroidLabyrinthEntryFrameProcessor.create(this)
                val processFrame: (CapturedFrame) -> LabyrinthEntryFrameResult = { frame ->
                    frameTracker.trackFrame(frame.bitmap.width, frame.bitmap.height)
                    processor.process(frame).also(frameTracker::record)
                }
                processFrame
            },
            blockClassifier = { result ->
                SessionBlockClassifier().classify(
                    scores = result.observation.anchorScores.toSessionBlockScores(),
                    pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
                )
            },
            overlayCoordinator = automationOverlayCoordinator,
            presence = presence,
            actionExecutor = automationActionExecutor,
            entryActionPlannerFactory = { LabyrinthEntryActionPlanner() },
        )
    }

    /**
     * 刷取次数外层循环：入口+路线执行 → 会话重置 → 重新进入，直到达到目标轮数。
     * 单轮成功以路线进度 complete 为准；会话提前停止（急停/拒绝/超时）会中止循环。
     */
    val labyrinthAutoRunWorkflow by lazy {
        LabyrinthAutoRunWorkflow(
            startRun = { accountId ->
                labyrinthEntryRecognitionSession.startAutomation(accountId) is
                    LabyrinthEntryRecognitionStartResult.Started
            },
            awaitRunFinished = {
                labyrinthEntryRecognitionSession.state.first { it.running }
                val final = labyrinthEntryRecognitionSession.state.first { !it.running }
                if (final.routeProgress?.complete == true) {
                    LabyrinthAutoRunRoundOutcome.Completed
                } else {
                    LabyrinthAutoRunRoundOutcome.Aborted(final.message ?: "路线执行提前结束")
                }
            },
            resetSession = {
                when (gameSessionResetWorkflow.start()) {
                    is GameSessionResetStartResult.Started -> {
                        gameSessionResetWorkflow.state.first { it.running }
                        val final = gameSessionResetWorkflow.state.first { !it.running }
                        final.status == GameSessionResetStatus.STOPPED &&
                            final.lastBlock == SessionBlockKind.NONE
                    }

                    else -> false
                }
            },
        )
    }

    /** 自动执行循环运行在应用级作用域，离开页面不中断；停止需显式调用。 */
    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var autoRunJob: Job? = null

    /** Multi-round reset is intentionally disabled until the single-round loop is proven. */
    @Suppress("UNUSED_PARAMETER")
    fun startLabyrinthAutoRun(accountId: Long?, targetRuns: Int): Boolean = false

    fun stopLabyrinthAutoRun() {
        autoRunJob?.cancel()
        autoRunJob = null
        autoRunScope.launch {
            labyrinthEntryRecognitionSession.stop("用户停止自动执行")
            gameSessionResetWorkflow.stop("用户停止自动执行")
        }
    }

    private companion object {
        const val DATABASE_UPDATE_LOG_TAG = "LabyrinthCnDatabase"
        /** 触发入口「冒险→黎明界」，与 [LabyrinthEntryActionPlanner] 的锚点一致 */
        val SESSION_EXPIRY_TRIGGER_POINT = ScreenPoint(1735f, 805f)
        /** 「返回标题」按钮（模板中心，1080p 参考系） */
        val SESSION_RETURN_TITLE_POINT = ScreenPoint(961f, 739f)
    }
}
