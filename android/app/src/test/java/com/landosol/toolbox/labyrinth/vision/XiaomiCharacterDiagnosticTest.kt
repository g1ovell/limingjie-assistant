package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.CharacterResource
import com.landosol.toolbox.gamedata.GameIconPackDocument
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthTeamScorer
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Opt-in local screenshot diagnostic. User images and output stay outside the repository. */
class XiaomiCharacterDiagnosticTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun sameDisplayNameDoesNotMergeDifferentUnitIdentities() {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "android/app/src/main/assets").isDirectory) root = requireNotNull(root.parentFile)
        val assets = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val templates = listOf("1091", "1200").map { id ->
            LabyrinthBattleCharacterTemplate(id, "静流", "31", readImage(File(assets, "icons/characters/icon_unit_${id}31.png")))
        }
        val matcher = LabyrinthCharacterIconMatcher(templates)
        templates.forEach { template ->
            val found = matcher.match(template.image, EntryPixelRect(0, 0, template.image.width, template.image.height))
            assertTrue(found.trusted)
            assertEquals(template.characterId, found.characterId)
            assertEquals(setOf("1091", "1200"), found.candidates.map { it.characterId }.toSet())
        }
        val runtime = (LabyrinthRoleDecisionDataParser.parse(File(assets, "labyrinth-role-decision.json").readText())
            as LabyrinthRoleDecisionDataResult.Ready).runtime
        val members = listOf("1091", "1200").map { runtime.profiles.getValue(it) }
        assertNotEquals(members[0].displayName, members[1].displayName)
        // The scorer rejects duplicate character IDs, so evaluating both proves distinct identity input.
        LabyrinthTeamScorer(runtime.document.scoring).evaluate(members, LabyrinthRoleDecisionContext(0))
    }

    @Test
    fun diagnoseLocalScreenshot() {
        val input = System.getenv("XIAOMI_CHARACTER_SCREENSHOT")
        assumeTrue("Set XIAOMI_CHARACTER_SCREENSHOT for local diagnostics", !input.isNullOrBlank())
        val output = File(requireNotNull(System.getenv("XIAOMI_CHARACTER_OUTPUT"))).absoluteFile
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "android/app/src/main/assets").isDirectory) root = requireNotNull(root.parentFile)
        require(!output.toPath().startsWith(root.toPath())) { "Output must be outside Git repository" }
        output.mkdirs()
        val assetRoot = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val roster = json.decodeFromJsonElement(ListSerializer(CharacterResource.serializer()),
            json.parseToJsonElement(File(assetRoot, "characters.landosolroster.json").readText()).jsonObject.getValue("characters"))
        val byId = roster.associateBy { it.id }
        val icons = json.decodeFromString<GameIconPackDocument>(File(assetRoot, "icons.json").readText())
        val runtime = (LabyrinthRoleDecisionDataParser.parse(File(assetRoot, "labyrinth-role-decision.json").readText())
            as LabyrinthRoleDecisionDataResult.Ready).runtime
        val templates = icons.characterIcons.mapNotNull { icon ->
            val character = byId[icon.ownerId] ?: return@mapNotNull null
            LabyrinthBattleCharacterTemplate(icon.ownerId, character.displayName, icon.variant,
                readImage(if (icon.format == "webp") File(requireNotNull(System.getenv("XIAOMI_CHARACTER_WEBP_CACHE")), File(icon.file).name + ".png") else File(assetRoot, icon.file)),
                attribute = runtime.profiles[icon.ownerId]?.attribute)
        }
        val original = readImage(File(requireNotNull(input)))
        val viewport = System.getenv("XIAOMI_CHARACTER_VIEWPORT")?.split(',')?.map(String::toInt)
        val frame = if (viewport == null) original else {
            require(viewport.size == 4)
            val (x, y, w, h) = viewport
            PixelImage(1920, 1080, IntArray(1920 * 1080) { i ->
                original[x + (i % 1920) * w / 1920, y + (i / 1920) * h / 1080]
            })
        }
        writeImage(frame, File(output, "normalized.png"))
        val matcher = LabyrinthCharacterIconMatcher(templates)
        val recognizer = LabyrinthBattleTeamRecognizer(templates = templates, iconMatcher = matcher)
        val observed = recognizer.recognizeOpening(frame)
        @Suppress("UNCHECKED_CAST")
        val paths = listOf("TEMPLATE_PATHS", "OPTIONAL_TEMPLATE_PATHS").flatMap { field ->
            (AndroidLabyrinthEntryTemplateLoader::class.java.getDeclaredField(field).apply { isAccessible = true }
                .get(null) as Map<String, String>).entries
        }.mapNotNull { entry ->
            val file = File(root, "android/app/src/main/assets/${entry.value}")
            if (file.isFile) entry.key to readImage(file) else null
        }.toMap()
        val page = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(paths),
            characterRecognizer = LabyrinthCharacterRecognizer(matcher), battleTeamRecognizer = recognizer).process(frame)
        System.getenv("XIAOMI_CHARACTER_EXPECTED_IDS")?.takeIf(String::isNotBlank)?.let { ids ->
            assertEquals(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, page.observation.state)
            assertEquals(ids.split(','), observed.visibleCharacters.map { it.characterId })
            assertTrue(observed.visibleCharacters.all { it.trusted && runtime.profiles.containsKey(it.characterId) })
        }
        val slots = observed.visibleCharacters.map { match ->
            val r = match.screenRect
            val iconRect = EntryPixelRect(r.left + 6, r.top + 6, r.width - 14, r.width - 14)
            val probe = matcher.match(frame, iconRect, LabyrinthCharacterIconMask(
                ignoreRightEdge = true, ignoreTopLeft = true,
                ignoreTopRight = match.selected, ignoreBottomLeft = true, ignoreBottomBand = match.selected),
                requiredAttribute = match.detectedAttribute)
            writeImage(PixelImage(r.width, r.height, IntArray(r.width * r.height) { i ->
                frame[r.left + i % r.width, r.top + i / r.width]
            }), File(output, "${match.slotId}.png"))
            buildJsonObject {
                put("slot", match.slotId); put("boundingBox", r.toString())
                put("characterId", match.characterId); put("displayName", match.displayName)
                put("score", match.confidence); put("rivalMargin", match.rivalMargin)
                put("attribute", match.detectedAttribute?.name)
                put("scoringProfile", runtime.profiles[match.characterId]?.displayName)
                put("accepted", match.trusted); put("suspectedCharacterId", match.suspectedCharacterId)
                put("suspectedDisplayName", match.suspectedDisplayName)
                put("threshold", LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE)
                put("marginThreshold", LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN)
                put("reason", when { match.trusted -> "ACCEPT"; match.confidence < LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE -> "LOW_SCORE"; else -> "RIVAL_MARGIN" })
                put("bboxProbeNote", "Independent probe from detected bbox; production may also try normalized geometry")
                put("bboxProbeCandidates", buildJsonArray { probe.candidates.forEach { c -> add(buildJsonObject {
                    put("characterId", c.characterId); put("displayName", c.displayName); put("score", c.confidence); put("iconVariant", c.iconVariant)
                }) } })
            }
        }
        File(output, "diagnostic.json").writeText(buildJsonObject {
            put("input", input); put("width", frame.width); put("height", frame.height)
            put("templates", templates.size); put("identities", templates.map { it.characterId }.distinct().size)
            put("recognitionState", observed.recognitionState.name)
            put("page", page.observation.state.name); put("pageConfidence", page.observation.confidence)
            put("slots", JsonArray(slots))
        }.toString())
        println("Xiaomi diagnostic: ${slots.size} slots, ${observed.recognizedCharacterCount} recognized, ${templates.size} templates; $output")
    }

    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file))
        val w = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val h = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(w * h)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(image, 0, 0, w, h, pixels, 0, w)
        return PixelImage(w, h, pixels)
    }

    private fun writeImage(frame: PixelImage, file: File) {
        val cls = Class.forName("java.awt.image.BufferedImage")
        val img = cls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).newInstance(frame.width, frame.height, 2)
        val pixels = IntArray(frame.width * frame.height) { i -> frame[i % frame.width, i / frame.width] }
        cls.getMethod("setRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(img, 0, 0, frame.width, frame.height, pixels, 0, frame.width)
        Class.forName("javax.imageio.ImageIO").getMethod("write", Class.forName("java.awt.image.RenderedImage"),
            String::class.java, File::class.java).invoke(null, img, "png", file)
    }
}
