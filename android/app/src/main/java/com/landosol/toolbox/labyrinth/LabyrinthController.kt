package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.account.AccountRepository
import com.landosol.toolbox.account.AccountCaptchaState
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.bilibili.BilibiliNativeLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.CaptchaProof
import com.landosol.toolbox.protocol.bilibili.GameSessionRegistry
import com.landosol.toolbox.protocol.bilibili.NativeLoginResult
import com.landosol.toolbox.protocol.labyrinth.BilibiliLabyrinthApi
import com.landosol.toolbox.protocol.labyrinth.LabyrinthFailureKind
import com.landosol.toolbox.protocol.labyrinth.LabyrinthOperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LabyrinthUiState(
    val selectedAccount: AccountListItem? = null,
    val guildOptions: List<LabyrinthGuildOption> = LabyrinthRerollOptions.guilds,
    val selectedGuildId: Int = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
    val availableDifficulties: List<Int> = (1..LabyrinthRerollOptions.MAX_DIFFICULTY).toList(),
    val selectedDifficulty: Int = LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
    val perfectStart: Boolean = LabyrinthRerollOptions.DEFAULT_PERFECT_START,
    val routeEvaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE,
    val valueAllowance: Int = LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthRerollOptions.defaultThirdBlockChoice,
    val area3BossOptions: List<LabyrinthBossOption> = LabyrinthRerollOptions.area3Bosses,
    val selectedArea3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val area5BossOptions: List<LabyrinthBossOption> = LabyrinthRerollOptions.area5Bosses,
    val selectedArea5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val maxAttempts: String = "100",
    val rerollUntilFound: Boolean = true,
    val retireExisting: Boolean = true,
    val isWorking: Boolean = false,
    val progress: String? = null,
    val startedAtMillis: Long? = null,
    val settingsReady: Boolean = false,
    val routeBlockIds: List<Long> = emptyList(),
    val routeVerdict: LabyrinthRouteVerdict? = null,
    val verdictMessage: String? = null,
    val checkpointEnterId: Long? = null,
    val currentGuildId: Int? = null,
    val currentDifficulty: Int? = null,
    val captcha: AccountCaptchaState? = null,
    val message: String? = null,
)

private data class LabyrinthChromeState(
    val selectedGuildId: Int = LabyrinthRerollOptions.DEFAULT_GUILD_ID,
    val selectedDifficulty: Int = LabyrinthRerollOptions.DEFAULT_DIFFICULTY,
    val maxUnlockedDifficulty: Int? = null,
    val perfectStart: Boolean = LabyrinthRerollOptions.DEFAULT_PERFECT_START,
    val routeEvaluationMode: LabyrinthRouteEvaluationMode = LabyrinthRerollOptions.DEFAULT_ROUTE_EVALUATION_MODE,
    val valueAllowance: Int = LabyrinthRerollOptions.DEFAULT_VALUE_ALLOWANCE,
    val thirdBlockChoice: LabyrinthThirdBlockChoice = LabyrinthRerollOptions.defaultThirdBlockChoice,
    val selectedArea3BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea3BossIds,
    val selectedArea5BossIds: Set<Int> = LabyrinthRerollOptions.defaultArea5BossIds,
    val maxAttempts: String = "100",
    val rerollUntilFound: Boolean = true,
    val retireExisting: Boolean = true,
    val isWorking: Boolean = false,
    val progress: String? = null,
    val startedAtMillis: Long? = null,
    val settingsReady: Boolean = false,
    val routeBlockIds: List<Long> = emptyList(),
    val routeVerdict: LabyrinthRouteVerdict? = null,
    val verdictMessage: String? = null,
    val checkpointEnterId: Long? = null,
    val currentGuildId: Int? = null,
    val currentDifficulty: Int? = null,
    val captcha: AccountCaptchaState? = null,
    val message: String? = null,
)

private enum class PendingLoginAction {
    START,
    CHECK_STATUS,
}

/**
 * Criteria used by the explicit "读取当前开局/重新验证" action.
 *
 * The selected controls are the user's current target. A checkpoint describes the result of an
 * older verification and must not silently replace newly selected guild/difficulty/route rules.
 * Only its attempt counter is safe to retain when it belongs to the same server run.
 */
internal data class LabyrinthCurrentOpeningCriteria(
    val guildId: Int,
    val difficulty: Int,
    val routePolicy: LabyrinthRoutePolicy,
    val attempt: Int,
)

internal fun labyrinthCurrentOpeningCriteria(
    selectedGuildId: Int,
    selectedDifficulty: Int,
    selectedRoutePolicy: LabyrinthRoutePolicy,
    savedCheckpoint: LabyrinthRerollCheckpoint?,
    currentEnterId: Long?,
): LabyrinthCurrentOpeningCriteria = LabyrinthCurrentOpeningCriteria(
    guildId = selectedGuildId,
    difficulty = selectedDifficulty,
    routePolicy = selectedRoutePolicy,
    attempt = savedCheckpoint
        ?.takeIf { checkpoint -> checkpoint.enterId == currentEnterId }
        ?.attempt
        ?: 0,
)

class LabyrinthController(
    private val accountRepository: AccountRepository,
    private val sessionRegistry: GameSessionRegistry,
    private val database: AppDatabase,
    private val loginCoordinatorProvider: () -> BilibiliNativeLoginCoordinator?,
    private val settingsStore: LabyrinthRerollSettingsStore,
    private val launchForeground: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsAccountId: Long? = null
    private var frozenStart: Pair<AccountListItem, LabyrinthRerollConfig>? = null
    private var startRequested = false
    private var cancellationMessage: String? = null
    val hasActiveReroll: Boolean get() = chrome.value.isWorking && frozenStart != null

    private val chrome = MutableStateFlow(LabyrinthChromeState())
    private var runningJob: Job? = null
    private var pendingLoginAction: PendingLoginAction? = null

    init {
        scope.launch {
            accountRepository.observeAccounts().map { accounts ->
                accounts.firstOrNull(AccountListItem::isSelected)?.id
            }.distinctUntilChanged().collect { selectedAccountId ->
                if (selectedAccountId != settingsAccountId) {
                    runningJob?.cancelAndJoin()
                    chrome.value.captcha?.let { loginCoordinatorProvider()?.cancel(it.accountId) }
                    frozenStart = null
                    startRequested = false
                    pendingLoginAction = null
                    settingsAccountId = selectedAccountId
                    chrome.value = LabyrinthChromeState()
                    if (selectedAccountId != null) applySettings(settingsStore.load(selectedAccountId))
                }
                if (selectedAccountId == null) {
                    chrome.update {
                        it.copy(routeVerdict = null, verdictMessage = null, checkpointEnterId = null)
                    }
                } else {
                    refreshCheckpoint(selectedAccountId)
                }
            }
        }
    }

    val uiState: StateFlow<LabyrinthUiState> = combine(
        accountRepository.observeAccounts(),
        chrome,
    ) { accounts, current ->
        LabyrinthUiState(
            selectedAccount = accounts.firstOrNull { it.isSelected && it.id == settingsAccountId },
            selectedGuildId = current.selectedGuildId,
            availableDifficulties = (1..(
                current.maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY
                )).toList(),
            selectedDifficulty = current.selectedDifficulty,
            perfectStart = current.perfectStart,
            routeEvaluationMode = current.routeEvaluationMode,
            valueAllowance = current.valueAllowance,
            thirdBlockChoice = current.thirdBlockChoice,
            selectedArea3BossIds = current.selectedArea3BossIds,
            selectedArea5BossIds = current.selectedArea5BossIds,
            maxAttempts = current.maxAttempts,
            rerollUntilFound = current.rerollUntilFound,
            retireExisting = current.retireExisting,
            isWorking = current.isWorking,
            progress = current.progress,
            startedAtMillis = current.startedAtMillis,
            settingsReady = current.settingsReady,
            routeBlockIds = current.routeBlockIds,
            routeVerdict = current.routeVerdict,
            verdictMessage = current.verdictMessage,
            checkpointEnterId = current.checkpointEnterId,
            currentGuildId = current.currentGuildId,
            currentDifficulty = current.currentDifficulty,
            captcha = current.captcha,
            message = current.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, LabyrinthUiState())

    fun selectGuild(guildId: Int) = updateConfig {
        if (LabyrinthRerollOptions.guilds.none { it.guildId == guildId }) this else copy(selectedGuildId = guildId)
    }

    fun selectDifficulty(difficulty: Int) = updateConfig {
        val maxUnlocked = maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY
        if (difficulty !in 1..maxUnlocked) this else copy(selectedDifficulty = difficulty)
    }

    fun setPerfectStart(value: Boolean) = updateConfig { copy(perfectStart = value) }

    fun selectRouteEvaluationMode(value: LabyrinthRouteEvaluationMode) = updateConfig {
        copy(routeEvaluationMode = value)
    }

    fun setValueAllowance(value: Int) = updateConfig {
        if (value !in 0..LabyrinthRerollOptions.MAX_VALUE_ALLOWANCE) this else copy(valueAllowance = value)
    }

    fun selectThirdBlockChoice(value: LabyrinthThirdBlockChoice) = updateConfig {
        copy(thirdBlockChoice = value)
    }

    fun toggleArea3Boss(unitId: Int) = updateConfig {
        if (LabyrinthRerollOptions.area3Bosses.none { it.unitId == unitId }) this
        else copy(selectedArea3BossIds = selectedArea3BossIds.toggle(unitId))
    }

    fun toggleArea5Boss(unitId: Int) = updateConfig {
        if (LabyrinthRerollOptions.area5Bosses.none { it.unitId == unitId }) this
        else copy(selectedArea5BossIds = selectedArea5BossIds.toggle(unitId))
    }

    fun setMaxAttempts(value: String) = updateNumeric { copy(maxAttempts = value) }

    fun setRerollUntilFound(value: Boolean) = updateConfig { copy(rerollUntilFound = value) }

    fun setRetireExisting(value: Boolean) {
        updateConfig { copy(retireExisting = value) }
    }

    fun start(retreatConfirmed: Boolean = false) {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) return
        val account = uiState.value.selectedAccount
        if (account == null) {
            chrome.update { it.copy(message = "请先在账号库选择一个账号") }
            return
        }
        if (account.id != settingsAccountId) return
        val config = parseConfig(account.id) ?: return
        if (config.retireExisting && !retreatConfirmed) {
            reportMessage("开始前请确认允许彻底撤退现有开局")
            return
        }
        frozenStart = account to config
        chrome.update { it.copy(startedAtMillis = System.currentTimeMillis()) }
        requestFrozenStart()
    }

    /**
     * Internal handoff from live automation after three failed battle attempts. The strategy
     * itself is the user's authorization to abandon this run, so force retireExisting for this
     * one frozen task while preserving all other saved reroll criteria (v2/allowance/Boss/etc.).
     */
    fun startAfterBattleFailureReroll(accountId: Long): Boolean {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) {
            reportMessage("战斗失败后无法自动重刷：刷开局任务尚未就绪或已有任务运行")
            return false
        }
        val account = uiState.value.selectedAccount
        if (account == null || account.id != accountId || account.id != settingsAccountId) {
            reportMessage("战斗失败后无法自动重刷：当前账号已变化")
            return false
        }
        val config = parseConfig(account.id) ?: return false
        frozenStart = account to config.copy(retireExisting = true)
        chrome.update {
            it.copy(
                startedAtMillis = System.currentTimeMillis(),
                message = "连续3次战斗失败，正在撤退当前开局并重新刷取",
            )
        }
        requestFrozenStart()
        return startRequested || chrome.value.isWorking
    }

    private fun requestFrozenStart() {
        if (frozenStart == null || chrome.value.isWorking) return
        startRequested = true
        chrome.update { it.copy(isWorking = true, progress = "正在启动后台刷取服务", message = null) }
        runCatching { launchForeground() }.onFailure {
            startRequested = false
            frozenStart = null
            chrome.update { state -> state.copy(isWorking = false, progress = null,
                message = "无法启动后台服务，请返回前台检查通知权限后重试：${it.message}") }
        }
    }

    /** Only the foreground service may consume this explicitly authorized snapshot. */
    fun startFromService(): Boolean {
        if (!startRequested || runningJob?.isActive == true) return false
        startRequested = false
        val (account, config) = frozenStart ?: return false
        if (account.id != settingsAccountId) {
            chrome.update { it.copy(isWorking = false, message = "账号已切换，本次启动已取消") }
            return false
        }
        runningJob = scope.launch {
            chrome.update { it.copy(isWorking = true, message = null, routeBlockIds = emptyList()) }
            try {
                var sessionResets = 0
                while (true) {
                    val session = ensureGameSession(account, PendingLoginAction.START) ?: return@launch
                    val workflow = LabyrinthRerollWorkflow(
                        api = BilibiliLabyrinthApi(session),
                        routeStore = RoomLabyrinthRouteStore(database),
                        checkpointStore = RoomLabyrinthRerollCheckpointStore(database),
                    )
                    val workflowResult = workflow.run(config) { progress ->
                        chrome.update {
                            val limit = if (progress.rerollUntilFound) "不限" else progress.maxAttempts.toString()
                            it.copy(progress = "${progress.attempt}/$limit · ${progress.stage}")
                        }
                    }
                    when (val result = workflowResult) {
                        is LabyrinthRerollResult.Success -> {
                            chrome.update {
                                it.copy(
                                routeBlockIds = result.route.blockIds,
                                currentGuildId = config.guildId,
                                currentDifficulty = config.difficulty,
                                message = if (result.resumedExisting) {
                                    "已读取并保留现有黎明界路线"
                                } else {
                                    "第 ${result.attempt} 次刷到${if (config.routePolicy.perfectStart) "完美" else "目标"}路线，已保留当前开局"
                                },
                            )
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.NeedsExistingRunDecision -> {
                            chrome.update {
                                it.copy(message = "检测到已有黎明界开局；确认后勾选“允许撤退现有开局”再开始")
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.Exhausted -> {
                            chrome.update {
                                it.copy(
                                    currentGuildId = null,
                                    currentDifficulty = null,
                                    message = "已完成 ${result.attempts} 次尝试，未找到目标路线",
                                )
                            }
                            return@launch
                        }
                        is LabyrinthRerollResult.Failure -> {
                            if (result.kind != LabyrinthFailureKind.REJECTED || sessionResets >= MAX_SESSION_RESETS) {
                                chrome.update { it.copy(message = result.message) }
                                return@launch
                            }
                            sessionResets += 1
                            chrome.update {
                                it.copy(progress = "游戏服会话失效，正在从账号库重新登录（$sessionResets/${MAX_SESSION_RESETS}）")
                            }
                            sessionRegistry.delete(account.id)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update { it.copy(message = cancellationMessage ?: "已停止；若服务端已生成开局，将保留当前状态") }
            } catch (failure: Throwable) {
                chrome.update { it.copy(message = failure.message.orEmpty().ifBlank { "黎明界任务失败" }.take(200)) }
            } finally {
                withContext(NonCancellable) { runCatching { refreshCheckpoint(account.id) } }
                chrome.update { it.copy(isWorking = false, progress = null) }
                if (chrome.value.captcha == null) frozenStart = null
                runningJob = null
                cancellationMessage = null
            }
        }
        return true
    }

    fun checkStatus() {
        if (chrome.value.isWorking || chrome.value.captcha != null || !chrome.value.settingsReady) return
        val account = uiState.value.selectedAccount
        if (account == null) {
            chrome.update { it.copy(message = "请先在账号库选择一个账号") }
            return
        }
        if (account.id != settingsAccountId) return
        chrome.update { it.copy(isWorking = true, message = null, progress = "读取黎明界状态") }
        runningJob = scope.launch {
            chrome.update { it.copy(isWorking = true, message = null, progress = "读取黎明界状态") }
            try {
                val session = ensureGameSession(account, PendingLoginAction.CHECK_STATUS) ?: return@launch
                val api = BilibiliLabyrinthApi(session)
                val checkpointStore = RoomLabyrinthRerollCheckpointStore(database)
                val savedCheckpoint = checkpointStore.load(account.id)
                when (val result = api.top()) {
                    is LabyrinthOperationResult.Success -> {
                        val top = result.value
                        val maxUnlocked = LabyrinthRerollOptions.maxUnlockedDifficulty(top.clearedDifficulties)
                        val criteria = labyrinthCurrentOpeningCriteria(
                            selectedGuildId = chrome.value.selectedGuildId,
                            selectedDifficulty = chrome.value.selectedDifficulty,
                            selectedRoutePolicy = currentRoutePolicy(),
                            savedCheckpoint = savedCheckpoint,
                            currentEnterId = top.enterId,
                        )
                        val routePolicy = criteria.routePolicy
                        val targetGuildId = criteria.guildId
                        val targetDifficulty = criteria.difficulty
                        val attempt = criteria.attempt
                        val existingRoute = if (top.enterId != null) {
                            ExistingLabyrinthRouteResolver(api).resolve(top, routePolicy)
                        } else {
                            null
                        }
                        val routeBlockIds: List<Long>
                        val message = when (existingRoute) {
                            is ExistingLabyrinthRouteResult.Found -> {
                                val value = existingRoute.value
                                val guildId = value.guildId ?: top.guildId ?: targetGuildId
                                val isTarget = guildId == targetGuildId && value.difficulty == targetDifficulty
                                val criteriaComparison =
                                    "当前公会ID $guildId / 难度 ${value.difficulty}；" +
                                        "所选目标公会ID $targetGuildId / 难度 $targetDifficulty"
                                if (isTarget) {
                                    RoomLabyrinthRouteStore(database).save(
                                        config = LabyrinthRerollConfig(
                                            accountId = account.id,
                                            guildId = guildId,
                                            difficulty = value.difficulty,
                                            maxAttempts = 1,
                                            retireExisting = false,
                                            routePolicy = routePolicy,
                                        ),
                                        route = value.route,
                                        attempt = attempt,
                                        allNodes = value.allNodes,
                                        currentBlockId = value.currentBlockId,
                                    )
                                }
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = value.route.enterId,
                                        guildId = targetGuildId,
                                        difficulty = targetDifficulty,
                                        policy = routePolicy,
                                        verdict = if (isTarget) {
                                            LabyrinthRouteVerdict.TARGET
                                        } else {
                                            LabyrinthRouteVerdict.NOT_TARGET
                                        },
                                        message = if (isTarget) {
                                            "现有开局符合当前刷取条件"
                                        } else {
                                            "现有开局的公会或难度与当前页面选项不一致：$criteriaComparison"
                                        },
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = if (isTarget) value.route.blockIds else emptyList()
                                if (isTarget) {
                                    "当前开局是目标路线，已保存可执行路线（Enter ID 尾号 ${value.route.enterId % 10_000}）"
                                } else {
                                    "当前开局可读取，但公会或难度不是当前目标（$criteriaComparison）"
                                }
                            }

                            ExistingLabyrinthRouteResult.NoMatchingRoute -> {
                                val enterId = requireNotNull(top.enterId)
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = enterId,
                                        guildId = targetGuildId,
                                        difficulty = targetDifficulty,
                                        policy = routePolicy,
                                        verdict = LabyrinthRouteVerdict.NOT_TARGET,
                                        message = "现有开局不符合当前页面选择的路线条件",
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = emptyList()
                                "当前开局不是目标路线"
                            }

                            is ExistingLabyrinthRouteResult.Failure -> {
                                val enterId = requireNotNull(top.enterId)
                                checkpointStore.save(
                                    LabyrinthRerollCheckpoint(
                                        accountId = account.id,
                                        attempt = attempt,
                                        enterId = enterId,
                                        guildId = targetGuildId,
                                        difficulty = targetDifficulty,
                                        policy = routePolicy,
                                        verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                        message = existingRoute.message,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                                routeBlockIds = emptyList()
                                "已确认存在开局，但暂时无法读取路线；保持待联网验证：${existingRoute.message}"
                            }

                            null -> {
                                checkpointStore.clear(account.id)
                                routeBlockIds = emptyList()
                                "黎明界状态正常，当前最高可挑战难度为 $maxUnlocked"
                            }
                        }
                        chrome.update {
                            it.copy(
                                maxUnlockedDifficulty = maxUnlocked,
                                selectedDifficulty = it.selectedDifficulty.coerceAtMost(maxUnlocked),
                                currentGuildId = top.guildId.takeIf { top.enterId != null },
                                currentDifficulty = top.difficulty.takeIf { top.enterId != null },
                                routeBlockIds = routeBlockIds,
                                message = message,
                            )
                        }
                    }
                    is LabyrinthOperationResult.Failure -> {
                        checkpointStore.load(account.id)?.let { checkpoint ->
                            checkpointStore.save(
                                checkpoint.copy(
                                    verdict = LabyrinthRouteVerdict.PENDING_VERIFICATION,
                                    message = result.message,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        chrome.update { it.copy(message = result.message) }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update { it.copy(message = "状态检查已取消") }
            } catch (failure: Throwable) {
                chrome.update { it.copy(message = failure.message.orEmpty().ifBlank { "读取黎明界状态失败" }.take(200)) }
            } finally {
                withContext(NonCancellable) { runCatching { refreshCheckpoint(account.id) } }
                chrome.update { it.copy(isWorking = false, progress = null) }
                runningJob = null
            }
        }
    }

    fun submitCaptcha(validate: String) {
        val captcha = chrome.value.captcha ?: return
        if (chrome.value.isWorking) return
        val loginCoordinator = resolveLoginCoordinator() ?: return
        chrome.update { it.copy(isWorking = true, message = null) }
        runningJob = scope.launch {
            chrome.update { it.copy(isWorking = true, message = null) }
            try {
                when (val result = loginCoordinator.completeCaptcha(captcha.accountId, CaptchaProof(validate))) {
                    is NativeLoginResult.Success -> {
                        accountRepository.updateGameUid(captcha.accountId, result.profile.viewerId.toString())
                        val action = pendingLoginAction
                        pendingLoginAction = null
                        chrome.update { it.copy(isWorking = false, captcha = null) }
                        when (action) {
                            PendingLoginAction.START -> requestFrozenStart()
                            PendingLoginAction.CHECK_STATUS -> scope.launch {
                                if (settingsAccountId == captcha.accountId) checkStatus()
                            }
                            null -> Unit
                        }
                    }

                    is NativeLoginResult.CaptchaRequired -> chrome.update {
                        it.copy(
                            isWorking = false,
                            captcha = captcha.copy(challenge = result.challenge, error = null),
                        )
                    }

                    is NativeLoginResult.Failure -> {
                        frozenStart = null
                        pendingLoginAction = null
                        chrome.update { it.copy(isWorking = false, captcha = null, message = result.message) }
                    }
                }
            } catch (cancelled: CancellationException) {
                chrome.update { it.copy(isWorking = false, captcha = null, message = "登录验证已取消") }
                throw cancelled
            } catch (failure: Throwable) {
                frozenStart = null
                pendingLoginAction = null
                chrome.update {
                    it.copy(
                        isWorking = false,
                        captcha = null,
                        message = failure.message.orEmpty().ifBlank { "登录验证失败" }.take(200),
                    )
                }
            } finally {
                runningJob = null
            }
        }
    }

    fun reportCaptchaError(message: String) {
        chrome.update { current ->
            current.copy(captcha = current.captcha?.copy(error = message.take(120)))
        }
    }

    fun cancelCaptcha() {
        val accountId = chrome.value.captcha?.accountId ?: return
        frozenStart = null
        startRequested = false
        pendingLoginAction = null
        scope.launch {
            loginCoordinatorProvider()?.cancel(accountId)
            if (settingsAccountId == accountId) {
                pendingLoginAction = null
                chrome.update { it.copy(captcha = null, isWorking = false, message = "已取消本次登录验证") }
            }
        }
    }

    fun stop() {
        startRequested = false
        frozenStart = null
        pendingLoginAction = null
        runningJob?.cancel()
        // Cancellation is asynchronous: keep controls locked until the job's finally has finished.
        if (runningJob == null) chrome.update { it.copy(isWorking = false, progress = null) }
        if (chrome.value.captcha != null) cancelCaptcha()
    }

    fun stopWithReason(message: String) {
        cancellationMessage = message
        stop()
        reportMessage(message)
    }

    fun reportMessage(message: String) { chrome.update { it.copy(message = message) } }

    fun saveSettings(settings: LabyrinthRerollSettings) {
        if (chrome.value.isWorking || chrome.value.captcha != null) return
        val accountId = settingsAccountId ?: return
        settings.validationError()?.let { reportMessage(it); return }
        if (settings.difficulty > (chrome.value.maxUnlockedDifficulty ?: LabyrinthRerollOptions.MAX_DIFFICULTY)) {
            reportMessage("所选难度尚未解锁"); return
        }
        settingsStore.save(accountId, settings)
        applySettings(settings)
        reportMessage("刷开局设置已保存")
    }

    private fun applySettings(settings: LabyrinthRerollSettings) {
        chrome.update { it.copy(selectedGuildId = settings.guildId, selectedDifficulty = settings.difficulty,
            perfectStart = settings.perfectStart,
            routeEvaluationMode = settings.routeEvaluationMode, valueAllowance = settings.valueAllowance,
            thirdBlockChoice = settings.thirdBlockChoice,
            selectedArea3BossIds = settings.area3BossIds, selectedArea5BossIds = settings.area5BossIds,
            maxAttempts = settings.maxAttempts, rerollUntilFound = settings.rerollUntilFound,
            retireExisting = settings.retireExisting, settingsReady = true) }
    }

    fun dismissMessage() {
        chrome.update { it.copy(message = null) }
    }

    private fun parseConfig(accountId: Long): LabyrinthRerollConfig? {
        val current = chrome.value
        val maxAttempts = current.maxAttempts.toIntOrNull()
        val error = when {
            LabyrinthRerollOptions.guilds.none { it.guildId == current.selectedGuildId } -> "请选择有效公会"
            current.selectedDifficulty !in 1..LabyrinthRerollOptions.MAX_DIFFICULTY -> "请选择有效难度"
            current.maxUnlockedDifficulty != null && current.selectedDifficulty > current.maxUnlockedDifficulty ->
                "所选难度尚未解锁"
            maxAttempts == null || maxAttempts !in 1..LabyrinthRerollOptions.MAX_ATTEMPTS ->
                "最大尝试次数必须在 1 到 ${LabyrinthRerollOptions.MAX_ATTEMPTS} 之间"
            else -> null
        }
        if (error != null) {
            chrome.update { it.copy(message = error) }
            return null
        }
        return uiSettings().toConfig(accountId)
    }

    private fun currentRoutePolicy(): LabyrinthRoutePolicy = chrome.value.let { current ->
        LabyrinthRoutePolicy(
            perfectStart = current.perfectStart,
            evaluationMode = current.routeEvaluationMode,
            valueAllowance = current.valueAllowance,
            thirdBlockChoice = current.thirdBlockChoice,
            area3BossIds = current.selectedArea3BossIds,
            area5BossIds = current.selectedArea5BossIds,
        )
    }

    private fun resolveLoginCoordinator(): BilibiliNativeLoginCoordinator? =
        loginCoordinatorProvider().also { coordinator ->
            if (coordinator == null) reportMessage("未检测到 Bilibili 渠道《公主连结》客户端")
        }

    private suspend fun ensureGameSession(
        account: AccountListItem,
        action: PendingLoginAction,
    ): BilibiliGameSession? {
        sessionRegistry.read(account.id)?.let { return it }
        val loginCoordinator = resolveLoginCoordinator() ?: return null
        chrome.update { it.copy(progress = "正在从账号库登录游戏服", message = null) }
        val material = runCatching { accountRepository.loadLoginMaterial(account.id) }
            .getOrElse { failure ->
                chrome.update {
                    it.copy(message = failure.message.orEmpty().ifBlank { "无法读取账号凭据" }.take(200))
                }
                return null
            }
        return when (val result = loginCoordinator.start(material)) {
            is NativeLoginResult.Success -> {
                accountRepository.updateGameUid(account.id, result.profile.viewerId.toString())
                pendingLoginAction = null
                chrome.update { it.copy(captcha = null) }
                sessionRegistry.read(account.id).also { session ->
                    if (session == null) chrome.update { it.copy(message = "登录成功，但游戏服会话未保存") }
                }
            }

            is NativeLoginResult.CaptchaRequired -> {
                pendingLoginAction = action
                chrome.update {
                    it.copy(
                        captcha = AccountCaptchaState(
                            accountId = account.id,
                            accountAlias = account.alias,
                            challenge = result.challenge,
                        ),
                        message = null,
                    )
                }
                null
            }

            is NativeLoginResult.Failure -> {
                chrome.update { it.copy(message = result.message) }
                null
            }
        }
    }

    private suspend fun refreshCheckpoint(accountId: Long) {
        val checkpoint = RoomLabyrinthRerollCheckpointStore(database).load(accountId)
        chrome.update {
            it.copy(
                routeVerdict = checkpoint?.verdict,
                verdictMessage = checkpoint?.message,
                checkpointEnterId = checkpoint?.enterId,
            )
        }
    }

    private fun updateConfig(transform: LabyrinthChromeState.() -> LabyrinthChromeState) {
        if (!chrome.value.isWorking && chrome.value.captcha == null && chrome.value.settingsReady) {
            chrome.update { it.transform().copy(message = null) }
            val settings = uiSettings()
            if (settings.validationError() == null) settingsAccountId?.let { settingsStore.save(it, settings) }
        }
    }

    private fun updateNumeric(transform: LabyrinthChromeState.() -> LabyrinthChromeState) {
        updateConfig(transform)
    }

    private fun uiSettings() = chrome.value.let {
        LabyrinthRerollSettings(
            guildId = it.selectedGuildId,
            difficulty = it.selectedDifficulty,
            perfectStart = it.perfectStart,
            routeEvaluationMode = it.routeEvaluationMode,
            valueAllowance = it.valueAllowance,
            thirdBlockChoice = it.thirdBlockChoice,
            area3BossIds = it.selectedArea3BossIds,
            area5BossIds = it.selectedArea5BossIds,
            maxAttempts = it.maxAttempts,
            rerollUntilFound = it.rerollUntilFound,
            retireExisting = it.retireExisting,
        )
    }

    private companion object {
        const val MAX_SESSION_RESETS = 3
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value
