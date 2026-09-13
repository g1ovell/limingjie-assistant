package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.bilibili.BilibiliGameSession
import com.landosol.toolbox.protocol.labyrinth.BilibiliLabyrinthApi
import com.landosol.toolbox.protocol.labyrinth.LabyrinthApi

/** Identifies the system that produced a route/checkpoint pair. */
enum class LabyrinthRouteSourceKind {
    BILIBILI_NATIVE,
    EXTERNAL_IMPORTED,
}

enum class LabyrinthAccountRequirement {
    REQUIRED,
    OPTIONAL,
}

enum class LabyrinthGameSessionRequirement {
    REQUIRED,
    OPTIONAL,
}

/** The common input consumed by visual route execution. */
data class LabyrinthExecutionContext(
    val source: LabyrinthRouteSourceKind,
    val accountId: Long?,
    val route: LabyrinthRouteJson?,
    val checkpoint: LabyrinthRerollCheckpoint?,
    val message: String,
    val importedOpening: ImportedOpeningState? = null,
) {
    init {
        require(if (source == LabyrinthRouteSourceKind.BILIBILI_NATIVE) {
            route != null && checkpoint != null && importedOpening == null
        } else {
            route == null && checkpoint == null && importedOpening != null && accountId == null
        }) { "Route source and execution state do not match" }
    }
}

sealed interface LabyrinthExecutionContextResult {
    data class Ready(val context: LabyrinthExecutionContext) : LabyrinthExecutionContextResult
    data class Blocked(val message: String) : LabyrinthExecutionContextResult
}

/** Route/checkpoint access for visual execution. It does not expose channel-specific login APIs. */
interface LabyrinthRouteSource {
    val source: LabyrinthRouteSourceKind
    val accountRequirement: LabyrinthAccountRequirement
    val gameSessionRequirement: LabyrinthGameSessionRequirement
        get() = LabyrinthGameSessionRequirement.OPTIONAL

    suspend fun loadExecutionContext(accountId: Long?): LabyrinthExecutionContextResult

    suspend fun updateCurrentBlock(
        context: LabyrinthExecutionContext,
        currentBlockId: Long,
    ): Boolean
}

/** Null or unknown sources are rejected before a non-read-only visual session is created. */
suspend fun resolveLabyrinthExecutionContext(
    source: LabyrinthRouteSource?,
    accountId: Long?,
): LabyrinthExecutionContextResult {
    val configuredSource = source
        ?: return LabyrinthExecutionContextResult.Blocked("未配置路线来源，已禁止执行保存路线")
    if (configuredSource.accountRequirement == LabyrinthAccountRequirement.REQUIRED && accountId == null) {
        return LabyrinthExecutionContextResult.Blocked("当前路线来源要求先选择账号，已禁止执行保存路线")
    }
    return configuredSource.loadExecutionContext(accountId)
}

/** Existing Bilibili route/checkpoint storage, kept behind the generic execution boundary. */
class BilibiliLabyrinthRouteSource(
    private val routeStore: LabyrinthRouteStore,
    private val checkpointStore: LabyrinthRerollCheckpointStore,
) : LabyrinthRouteSource {
    override val source: LabyrinthRouteSourceKind = LabyrinthRouteSourceKind.BILIBILI_NATIVE
    override val accountRequirement: LabyrinthAccountRequirement = LabyrinthAccountRequirement.REQUIRED

    override suspend fun loadExecutionContext(accountId: Long?): LabyrinthExecutionContextResult {
        val id = accountId
            ?: return LabyrinthExecutionContextResult.Blocked("未选择 Bilibili 账号，已禁止执行保存路线")
        val route = routeStore.loadLatest(id)
            ?: return LabyrinthExecutionContextResult.Blocked("未找到已保存路线，请先完成刷开局")
        val checkpoint = checkpointStore.load(id)
            ?: return LabyrinthExecutionContextResult.Blocked("未找到路线检查点，请先联网读取并确认当前开局")
        return when (val gate = validateLabyrinthExecution(route, checkpoint, top = null)) {
            is LabyrinthExecutionGateResult.Allowed ->
                LabyrinthExecutionContextResult.Ready(
                    LabyrinthExecutionContext(
                        source = source,
                        accountId = id,
                        route = route,
                        checkpoint = checkpoint,
                        message = gate.message,
                    ),
                )

            is LabyrinthExecutionGateResult.Blocked ->
                LabyrinthExecutionContextResult.Blocked(gate.message)
        }
    }

    override suspend fun updateCurrentBlock(
        context: LabyrinthExecutionContext,
        currentBlockId: Long,
    ): Boolean {
        val id = context.accountId ?: return false
        val route = context.route ?: return false
        return routeStore.updateCurrentBlock(id, route.enterId, currentBlockId)
    }
}

/** Reroll API construction is the only channel-specific part of the existing workflow. */
interface LabyrinthRerollProvider {
    val source: LabyrinthRouteSourceKind
    val accountRequirement: LabyrinthAccountRequirement
    val gameSessionRequirement: LabyrinthGameSessionRequirement

    fun createApi(session: BilibiliGameSession): LabyrinthApi
}

class BilibiliLabyrinthRerollProvider : LabyrinthRerollProvider {
    override val source: LabyrinthRouteSourceKind = LabyrinthRouteSourceKind.BILIBILI_NATIVE
    override val accountRequirement: LabyrinthAccountRequirement = LabyrinthAccountRequirement.REQUIRED
    override val gameSessionRequirement: LabyrinthGameSessionRequirement =
        LabyrinthGameSessionRequirement.REQUIRED

    override fun createApi(session: BilibiliGameSession): LabyrinthApi = BilibiliLabyrinthApi(session)
}
