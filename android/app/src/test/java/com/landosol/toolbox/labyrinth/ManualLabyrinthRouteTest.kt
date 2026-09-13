package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualLabyrinthRouteTest {
    private fun sample() = requireNotNull(javaClass.getResource("/manual-route-sample.txt")).readText()
    private fun reject(text: String, fragment: String) {
        val result = ManualLabyrinthRouteParser.parse(text)
        assertTrue(result.toString(), result is ImportedRouteParseResult.Invalid)
        assertTrue((result as ImportedRouteParseResult.Invalid).message, result.message.contains(fragment))
    }
    @Test fun `actual unmodified sample parses all 36 nodes and every branch and type`() {
        val result = ManualLabyrinthRouteParser.parse(sample()) as ImportedRouteParseResult.Valid
        assertEquals(36, result.plan.nodes.size)
        assertEquals(listOf(6, 7, 7, 8, 8), (1..5).map { area -> result.plan.nodes.count { it.area == area } })
        assertEquals(ImportedBranch.entries.toSet(), result.plan.nodes.map { it.branch }.toSet())
        assertEquals(ImportedNodeType.entries.toSet(), result.plan.nodes.map { it.nodeType }.toSet())
        assertEquals("冰霜魔狼", result.plan.boss(3))
        assertEquals("愤怒巨龙", result.plan.boss(5))
    }
    @Test fun `boss names are arbitrary rather than an allowlist`() {
        val parsed = ManualLabyrinthRouteParser.parse(sample().replace("冰霜魔狼", "新Boss-任意名")) as ImportedRouteParseResult.Valid
        assertEquals("新Boss-任意名", parsed.plan.boss(3))
    }
    @Test fun `missing area rejected`() = reject(sample().lines().drop(1).joinToString("\n"), "缺少区域")
    @Test fun `duplicate area rejected`() = reject(sample() + "\n" + sample().lines().first(), "重复区域")
    @Test fun `out of range area rejected`() = reject(sample().replace("区域5", "区域6"), "必须为1至5")
    @Test fun `empty area rejected`() = reject(sample().replace(Regex("区域3：[^\\r\\n]*"), "区域3："), "空区域")
    @Test fun `unknown direction rejected with location`() = reject(sample().replace("4下【角色】", "4左【角色】"), "区域3，第4节点：未知方向")
    @Test fun `unknown node rejected`() = reject(sample().replace("普通怪物", "宝箱"), "未知节点类型")
    @Test fun `malformed bosses rejected`() {
        listOf("Boss", "Boss()", "Boss(冰霜魔狼", "Boss((冰霜魔狼))", "Boss( )").forEach {
            reject(sample().replace("Boss(冰霜魔狼)", it), "Boss")
        }
    }
    @Test fun `trailing text and separators rejected`() {
        reject(sample().trim() + "垃圾", "未消费文本")
        reject(sample().trim() + "-", "未消费文本")
    }
    @Test fun `invalid start rejected`() = reject(sample().replaceFirst("1合流【起点】", "1上【起点】"), "1合流")
    @Test fun `blank rejected`() = reject(" \n ", "为空")
    @Test fun `reversed and duplicate columns rejected`() {
        reject(sample().replaceFirst("3中【事件】", "2中【事件】"), "严格递增")
        reject(sample().replaceFirst("3中【事件】", "1中【事件】"), "严格递增")
    }
    @Test fun `column gaps allowed`() {
        assertTrue(ManualLabyrinthRouteParser.parse(sample().replaceFirst("6合流【遗物】", "9合流【遗物】")) is ImportedRouteParseResult.Valid)
    }
    @Test fun `save UI state persists and can reload without an account`() = runTest {
        val store = MemoryStore()
        val controller = ImportedRouteController(store)
        controller.edit(sample()); controller.save()
        assertNull(controller.state.value.error)
        assertEquals(36, controller.state.value.saved!!.plan.nodes.size)
        assertEquals(controller.state.value.saved, ImportedRouteController(store).state.value.saved)
        val source = ManualImportedLabyrinthRouteSource(store)
        assertEquals(LabyrinthAccountRequirement.OPTIONAL, source.accountRequirement)
        assertEquals(LabyrinthGameSessionRequirement.OPTIONAL, source.gameSessionRequirement)
        val result = resolveLabyrinthExecutionContext(source, null) as LabyrinthExecutionContextResult.Ready
        assertEquals(LabyrinthRouteSourceKind.EXTERNAL_IMPORTED, result.context.source)
        assertNull(result.context.accountId)
        assertNull(result.context.route)
        assertNull(result.context.checkpoint)
        assertEquals(5, result.context.importedOpening!!.plan.areas.size)
    }
    @Test fun `invalid UI input never overwrites valid saved route`() {
        val store = MemoryStore(); val controller = ImportedRouteController(store)
        controller.edit(sample()); controller.save()
        controller.edit("bad"); controller.save()
        assertNotNull(controller.state.value.error)
        assertEquals(sample(), store.read()!!.first)
    }
    @Test fun `absent and cleared source both block`() = runTest {
        val store = MemoryStore(); val controller = ImportedRouteController(store)
        val source = ManualImportedLabyrinthRouteSource(store)
        assertTrue(resolveLabyrinthExecutionContext(source, null) is LabyrinthExecutionContextResult.Blocked)
        controller.edit(sample()); controller.save(); controller.clear()
        assertNull(controller.state.value.saved)
        assertTrue(resolveLabyrinthExecutionContext(source, null) is LabyrinthExecutionContextResult.Blocked)
    }
    @Test fun `source creates accountless read only session with zero backend calls and no launcher`() = runTest {
        val store = MemoryStore().apply { write(sample(), 5) }
        val manager = AutomationSessionManager()
        var gestures = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("no frames in session ownership test") } },
            actionExecutor = SessionBoundActionExecutor(manager) { gestures++; AutomationBackendResult.Completed },
            gameLauncher = { error("dry-run must not launch game") },
        )
        try {
            val result = session.start(executionSourceOverride = ManualImportedLabyrinthRouteSource(store))
            assertTrue(result.toString(), result is LabyrinthEntryRecognitionStartResult.Started)
            assertTrue(manager.current()!!.dryRun)
            assertEquals(LabyrinthRouteSourceKind.EXTERNAL_IMPORTED, session.state.value.executionSource)
            assertEquals(0, gestures)
            assertEquals(0, session.state.value.actionCount)
            val matches = listOf("1049", "1091", "1171", "1200", "1212", "1337", "1011", "1129", "1193")
                .mapIndexed { index, id -> LabyrinthBattleCharacterMatch(
                    slotId = "slot-$index", characterId = id, displayName = id, iconVariant = "default",
                    confidence = 0.99, screenRect = EntryPixelRect(100 + index * 150, 300, 100, 100), selected = false,
                ) }
            val frame = LabyrinthEntryFrameResult(
                observation = LabyrinthEntryPageObservation(
                    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 0.99, emptyMap(),
                    LabyrinthAnchorScores(mapOf(EntryAnchorId.SELECTION_COUNT_NONE to 0.95,
                        EntryAnchorId.INVITE_DISABLED to 0.95)),
                ), matchedFeatures = emptyList(), elapsedMillis = 10,
                openingCharacterMatches = matches, frameWidth = 1920, frameHeight = 1080,
            )
            val process = session.javaClass.declaredMethods.single { it.name.startsWith("processFrameResult") }
                .apply { isAccessible = true }
            val now = System.currentTimeMillis()
            repeat(3) { index -> process.invoke(session, manager.current()!!.id.value, frame, 1920, 1080, now + index * 3000L) }
            assertEquals(listOf("1091", "1171", "1011"), session.state.value.openingPlanIds)
            assertEquals("1091", session.state.value.plannedCharacterId)
            assertEquals(0, session.state.value.actionCount)
            assertEquals(0, gestures)
            process.invoke(session, manager.current()!!.id.value,
                frame.copy(observation = frame.observation.copy(state = LabyrinthEntryPageState.NODE_SELECTION)),
                1920, 1080, now + 12000L)
            assertNull(session.state.value.plannedCharacterId)
            assertEquals(0, gestures)
        } finally { session.stop() }
    }
    @Test fun `external source cannot start live automation`() = runTest {
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true }, processorFactory = { error("must not load") },
            actionExecutor = SessionBoundActionExecutor(manager) { error("must never dispatch") },
        )
        val result = session.start(dryRun = false, executionSourceOverride = ManualImportedLabyrinthRouteSource(MemoryStore().apply { write(sample(), 5) }))
        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertNull(manager.current())
    }
    private class MemoryStore : ImportedRouteTextStore {
        private var saved: Pair<String, Int>? = null
        override fun read() = saved
        override fun write(text: String, openingGuildId: Int) { saved = text to openingGuildId }
        override fun clear() { saved = null }
    }
}
