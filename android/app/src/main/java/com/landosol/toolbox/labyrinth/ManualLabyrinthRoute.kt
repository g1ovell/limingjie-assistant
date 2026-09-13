package com.landosol.toolbox.labyrinth

enum class ImportedBranch(val label: String) { UPPER("上"), MIDDLE("中"), LOWER("下"), MERGED("合流") }
enum class ImportedNodeType(val label: String) {
    START("起点"), NORMAL("普通怪物"), EX("EX怪物"), EVENT("事件"), CHARACTER("角色"),
    RELIC("遗物"), SHOP("商店"), BOSS("Boss"),
}
data class ImportedRouteNode(
    val area: Int, val column: Int, val branch: ImportedBranch,
    val nodeType: ImportedNodeType, val bossName: String? = null,
)
data class ImportedRoutePlan(val nodes: List<ImportedRouteNode>) {
    val areas: List<Int> get() = nodes.map { it.area }.distinct()
    fun boss(area: Int): String? = nodes.firstOrNull { it.area == area && it.nodeType == ImportedNodeType.BOSS }?.bossName
}
sealed interface ImportedRouteParseResult {
    data class Valid(val plan: ImportedRoutePlan) : ImportedRouteParseResult
    data class Invalid(val message: String) : ImportedRouteParseResult
}

/** Full-line/full-token grammar. No partial import and no network IDs or pixel coordinates. */
object ManualLabyrinthRouteParser {
    fun parse(text: String): ImportedRouteParseResult {
        if (text.isBlank()) return ImportedRouteParseResult.Invalid("路线文本为空")
        val nodes = mutableListOf<ImportedRouteNode>()
        val seen = mutableSetOf<Int>()
        for ((lineIndex, line) in text.trim().lines().withIndex()) {
            val header = Regex("区域([0-9]+)：(.*)").matchEntire(line.trim())
                ?: return ImportedRouteParseResult.Invalid("第${lineIndex + 1}行：区域格式错误或含多余文本")
            val area = header.groupValues[1].toIntOrNull()
                ?: return ImportedRouteParseResult.Invalid("第${lineIndex + 1}行：区域号非法")
            if (area !in 1..5) return ImportedRouteParseResult.Invalid("区域$area：必须为1至5")
            if (!seen.add(area)) return ImportedRouteParseResult.Invalid("区域$area：重复区域")
            val body = header.groupValues[2]
            if (body.isBlank()) return ImportedRouteParseResult.Invalid("区域$area：空区域")
            var previous = 0
            // Split only between nodes; a hyphen inside a Boss name is ordinary name text.
            val tokens = body.split(Regex("(?<=】)-"))
            for ((index, token) in tokens.withIndex()) {
                fun invalid(reason: String) = ImportedRouteParseResult.Invalid("区域$area，第${index + 1}节点：$reason")
                val match = Regex("([0-9]+)([^【]+)【([^【】]+)】").matchEntire(token)
                    ?: return invalid("节点格式错误或有未消费文本：$token")
                val column = match.groupValues[1].toIntOrNull() ?: return invalid("列号非法")
                if (column <= previous) return invalid("列号必须严格递增：$column")
                val branchText = match.groupValues[2]
                val branch = ImportedBranch.entries.firstOrNull { it.label == branchText }
                    ?: return invalid("未知方向“$branchText”")
                val typeText = match.groupValues[3]
                val boss = Regex("Boss\\(([^()【】]+)\\)").matchEntire(typeText)
                val type = if (boss != null && boss.groupValues[1].isNotBlank()) ImportedNodeType.BOSS
                    else ImportedNodeType.entries.firstOrNull { it != ImportedNodeType.BOSS && it.label == typeText }
                        ?: return invalid(if (typeText.startsWith("Boss")) "Boss格式应为Boss(名称)" else "未知节点类型“$typeText”")
                if (index == 0 && (column != 1 || branch != ImportedBranch.MERGED || type != ImportedNodeType.START)) {
                    return invalid("区域必须以1合流【起点】开始")
                }
                if (index > 0 && type == ImportedNodeType.START) return invalid("起点只能出现在区域首节点")
                nodes += ImportedRouteNode(area, column, branch, type, boss?.groupValues?.get(1))
                previous = column
            }
        }
        val missing = (1..5).filterNot(seen::contains)
        if (missing.isNotEmpty()) return ImportedRouteParseResult.Invalid("缺少区域：${missing.joinToString("、")}")
        return ImportedRouteParseResult.Valid(ImportedRoutePlan(nodes.sortedWith(compareBy({ it.area }, { it.column }))))
    }
}

/** User-declared starting state, not a server-verified checkpoint. No fabricated server identity. */
data class ImportedOpeningState(
    val plan: ImportedRoutePlan,
    val openingGuildId: Int,
    val phase: String = "OPENING_CHARACTER_SELECTION",
    val declaredExistingRun: Boolean = true,
    val nodeExecutionStarted: Boolean = false,
)
interface ImportedRouteTextStore {
    fun read(): Pair<String, Int>?
    fun write(text: String, openingGuildId: Int)
    fun clear()
}
data class ImportedRouteUiState(
    val text: String = "",
    val openingGuildId: Int = 5,
    val saved: ImportedOpeningState? = null,
    val error: String? = null,
)
class ManualImportedLabyrinthRouteSource(private val store: ImportedRouteTextStore) : LabyrinthRouteSource {
    override val source = LabyrinthRouteSourceKind.EXTERNAL_IMPORTED
    override val accountRequirement = LabyrinthAccountRequirement.OPTIONAL
    override val gameSessionRequirement = LabyrinthGameSessionRequirement.OPTIONAL
    override suspend fun loadExecutionContext(accountId: Long?): LabyrinthExecutionContextResult {
        val saved = store.read() ?: return LabyrinthExecutionContextResult.Blocked("尚未保存外部路线")
        val parsed = ManualLabyrinthRouteParser.parse(saved.first)
        if (parsed is ImportedRouteParseResult.Invalid) return LabyrinthExecutionContextResult.Blocked(parsed.message)
        if (saved.second !in LabyrinthOpeningRosterCatalog.configs) return LabyrinthExecutionContextResult.Blocked("初始公会方案无效")
        return LabyrinthExecutionContextResult.Ready(LabyrinthExecutionContext(
            source = source, accountId = null, route = null, checkpoint = null,
            message = "外部路线已加载；人工声明停在初始选人0/3，仅允许只读预演",
            importedOpening = ImportedOpeningState((parsed as ImportedRouteParseResult.Valid).plan, saved.second),
        ))
    }
    override suspend fun updateCurrentBlock(context: LabyrinthExecutionContext, currentBlockId: Long): Boolean = false
}

/** UI state and persistence are testable without Compose or Android. Invalid input never overwrites a saved plan. */
class ImportedRouteController(private val store: ImportedRouteTextStore) {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(ImportedRouteUiState())
    val state: kotlinx.coroutines.flow.StateFlow<ImportedRouteUiState> = mutable
    init {
        store.read()?.let { (text, guild) ->
            val parsed = ManualLabyrinthRouteParser.parse(text)
            mutable.value = ImportedRouteUiState(text, guild,
                (parsed as? ImportedRouteParseResult.Valid)?.let { ImportedOpeningState(it.plan, guild) },
                (parsed as? ImportedRouteParseResult.Invalid)?.message)
        }
    }
    fun edit(text: String) { mutable.value = mutable.value.copy(text = text, error = null) }
    fun selectGuild(id: Int) { if (id in LabyrinthOpeningRosterCatalog.configs) mutable.value = mutable.value.copy(openingGuildId = id) }
    fun save() {
        val current = mutable.value
        when (val parsed = ManualLabyrinthRouteParser.parse(current.text)) {
            is ImportedRouteParseResult.Invalid -> mutable.value = current.copy(error = parsed.message)
            is ImportedRouteParseResult.Valid -> {
                try {
                    store.write(current.text, current.openingGuildId)
                    mutable.value = current.copy(saved = ImportedOpeningState(parsed.plan, current.openingGuildId), error = null)
                } catch (error: Exception) { mutable.value = current.copy(error = "保存失败：${error.message}") }
            }
        }
    }
    fun clear() {
        try { store.clear(); mutable.value = ImportedRouteUiState() }
        catch (error: Exception) { mutable.value = mutable.value.copy(error = "清除失败：${error.message}") }
    }
}
