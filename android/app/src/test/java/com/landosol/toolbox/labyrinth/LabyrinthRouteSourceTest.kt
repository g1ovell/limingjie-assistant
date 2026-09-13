package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRouteSourceTest {
    @Test
    fun `bilibili source still requires an account`() = runTest {
        val source = BilibiliLabyrinthRouteSource(
            routeStore = MemoryRouteStore(validRoute()),
            checkpointStore = MemoryCheckpointStore(validCheckpoint()),
        )

        val result = source.loadExecutionContext(accountId = null)

        assertTrue(result is LabyrinthExecutionContextResult.Blocked)
        assertTrue((result as LabyrinthExecutionContextResult.Blocked).message.contains("账号"))
    }

    @Test
    fun `bilibili source reuses the existing route and checkpoint workflow`() = runTest {
        val source = BilibiliLabyrinthRouteSource(
            routeStore = MemoryRouteStore(validRoute()),
            checkpointStore = MemoryCheckpointStore(validCheckpoint()),
        )

        val result = source.loadExecutionContext(accountId = 42L)

        assertTrue(result is LabyrinthExecutionContextResult.Ready)
        val context = (result as LabyrinthExecutionContextResult.Ready).context
        assertEquals(LabyrinthRouteSourceKind.BILIBILI_NATIVE, context.source)
        assertEquals(42L, context.accountId)
        assertEquals(validRoute().enterId, context.route.enterId)
        assertTrue(context.message.contains("Enter ID"))
    }

    @Test
    fun `accountless source with valid context passes the generic execution gate`() = runTest {
        val source = FakeAccountlessRouteSource(validRoute(), validCheckpoint())

        val result = resolveLabyrinthExecutionContext(source, accountId = null)

        assertTrue(result is LabyrinthExecutionContextResult.Ready)
        assertEquals(
            LabyrinthRouteSourceKind.EXTERNAL_IMPORTED,
            (result as LabyrinthExecutionContextResult.Ready).context.source,
        )
    }

    @Test
    fun `accountless source without a route is rejected`() = runTest {
        val result = resolveLabyrinthExecutionContext(
            FakeAccountlessRouteSource(route = null, checkpoint = validCheckpoint()),
            accountId = null,
        )

        assertTrue(result is LabyrinthExecutionContextResult.Blocked)
        assertTrue((result as LabyrinthExecutionContextResult.Blocked).message.contains("路线"))
    }

    @Test
    fun `missing source is rejected instead of executing silently`() = runTest {
        val result = resolveLabyrinthExecutionContext(source = null, accountId = null)

        assertTrue(result is LabyrinthExecutionContextResult.Blocked)
        assertTrue((result as LabyrinthExecutionContextResult.Blocked).message.contains("路线来源"))
    }

    private class MemoryRouteStore(private val route: LabyrinthRouteJson?) : LabyrinthRouteStore {
        override suspend fun save(
            config: LabyrinthRerollConfig,
            route: com.landosol.toolbox.protocol.labyrinth.LabyrinthRoute,
            attempt: Int,
            allNodes: List<com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode>,
            currentBlockId: Long?,
        ) = Unit

        override suspend fun loadLatest(accountId: Long): LabyrinthRouteJson? = route
    }

    private class MemoryCheckpointStore(
        private val checkpoint: LabyrinthRerollCheckpoint?,
    ) : LabyrinthRerollCheckpointStore {
        override suspend fun save(checkpoint: LabyrinthRerollCheckpoint) = Unit
        override suspend fun load(accountId: Long): LabyrinthRerollCheckpoint? = checkpoint
        override suspend fun clear(accountId: Long) = Unit
    }

    private class FakeAccountlessRouteSource(
        private val route: LabyrinthRouteJson?,
        private val checkpoint: LabyrinthRerollCheckpoint?,
    ) : LabyrinthRouteSource {
        override val source = LabyrinthRouteSourceKind.EXTERNAL_IMPORTED
        override val accountRequirement = LabyrinthAccountRequirement.OPTIONAL

        override suspend fun loadExecutionContext(accountId: Long?): LabyrinthExecutionContextResult {
            val currentRoute = route ?: return LabyrinthExecutionContextResult.Blocked("未找到路线")
            val currentCheckpoint = checkpoint
                ?: return LabyrinthExecutionContextResult.Blocked("未找到路线检查点")
            return LabyrinthExecutionContextResult.Ready(
                LabyrinthExecutionContext(
                    source = source,
                    accountId = accountId,
                    route = currentRoute,
                    checkpoint = currentCheckpoint,
                    message = "测试来源已提供可执行路线",
                ),
            )
        }

        override suspend fun updateCurrentBlock(
            context: LabyrinthExecutionContext,
            currentBlockId: Long,
        ): Boolean = true
    }

    private fun validRoute() = LabyrinthRouteJson(
        enterId = 101L,
        guildId = 1,
        difficulty = 1,
        attempt = 1,
        rerollUntilFound = false,
        perfectStart = false,
        thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER.name,
        area3BossIds = emptyList(),
        area5BossIds = emptyList(),
        nodes = listOf(node(10001L)),
        allNodes = listOf(node(10001L)),
    )

    private fun validCheckpoint() = LabyrinthRerollCheckpoint(
        accountId = 42L,
        attempt = 1,
        enterId = 101L,
        guildId = 1,
        difficulty = 1,
        policy = LabyrinthRoutePolicy(),
        verdict = LabyrinthRouteVerdict.TARGET,
        message = null,
        updatedAt = 1L,
    )

    private fun node(blockId: Long) = LabyrinthNodeJson(
        area = 1,
        column = 1,
        row = 1,
        blockId = blockId,
        blockType = 1,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = true,
    )
}
