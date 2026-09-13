# Manual Imported Labyrinth Route Source

## Scope and baseline

Baseline: `772aac6 refactor: generalize labyrinth route sources`, branch `compat/xiaomi-ldplayer`.
This stage imports a logical route and previews opening selection only. No gestures, game progression, login, reroll API, or Room migration. Existing opening roster configuration is unchanged.

## Route consumption audit

- A: `LabyrinthEntryRecognitionSession` uses `guildId` for the existing opening roster. Its map session consumes `nodes`, `allNodes`, and `currentBlockId`; persistence uses `enterId` and account ownership. `LabyrinthRouteJson.toFullMap()` exposes protocol graph nodes, not just the chosen path.
- B: `LabyrinthNodeSession` and `node/LabyrinthNodeActionPlanner` bind recognized platforms to logical nodes using area/column/row, node type, full-column cardinality, adjacency (`nextBlockIds`), and `blockId`. The planned click uses the current visual rectangle after binding. `questId` is protocol/Boss metadata, not a screen coordinate.
- C: Existing **map execution** depends on node identity (`blockId`) and complete topology. A chosen route text cannot reconstruct omitted sibling nodes or reliable protocol identities. Initial character recognition/planning does not need those IDs.
- D: `enterId` participates in Bilibili checkpoint validation and saved-route progress updates. Initial character recognition/planning does not need it. The imported preview does not execute these persistence paths.
- E: Account ownership, `enterId`, network checkpoint verdict/attempt/policy, full protocol map and resume verification belong to the native route workflow. Imported text supplies neither account/session nor server IDs. No `labyrinth/top`, `resume`, `enter`, or `retire` calls are introduced.
- F: Reusing `LabyrinthRouteJson` would require invented data and is unsafe. Instead, `LabyrinthExecutionContext` carries either the existing native route/checkpoint or an `ImportedOpeningState` with a logical plan. Constructor checks prevent mixing these forms. The imported frame path returns before dispatchers, recovery, persistence, and map execution. Starting it in live mode is blocked.

## Parser and exact regression fixture

Original: `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\manual-route-sample.txt`.
Byte-identical fixture: `android/app/src/test/resources/manual-route-sample.txt`.
Both SHA-256: `395437729ed2c554eeed6514a91617649d26b16412ac5cf7b416ed34df6b85c1`.

Grammar: one `区域N：node-node-...` line per area, areas 1–5 exactly once. Each node is `column + branch + 【type】`. Branch is 上/中/下/合流. Type is 起点/普通怪物/EX怪物/事件/角色/遗物/商店 or `Boss(nonempty name)`. Boss names are not an allowlist; hyphens inside names are preserved. Nested/unbalanced ASCII parentheses and missing names are rejected. Surrounding text/line whitespace and CRLF are accepted; internal tokens must match completely.

Every area starts with `1合流【起点】`; subsequent columns strictly increase but may jump. Missing/duplicate/out-of-range/empty areas, invalid branches/types/start, malformed Boss, extra text, and trailing separators reject the entire input. Errors identify area and node where possible. Nothing is converted into pixel coordinates or fabricated IDs.

| Area | Nodes | Boss |
| --- | ---: | --- |
| 1 | 6 | — |
| 2 | 7 | — |
| 3 | 7 | 冰霜魔狼 |
| 4 | 8 | — |
| 5 | 8 | 愤怒巨龙 |

Total: 36 nodes. All four branches and all eight node types occur in this fixture.

## Source, state and storage

`ManualImportedLabyrinthRouteSource`: identity `EXTERNAL_IMPORTED`; account and game-session requirements `OPTIONAL` (neither required). It depends only on its text store and parser, not on a Bilibili session/API or GameClientProfile.

`ImportedOpeningState` describes a **user-declared**, not server-verified, existing run at opening selection, with node execution not started. Native route/checkpoint are null; there is no fake account, blockId, questId, or enterId.

Storage: app-private SharedPreferences file `manual_labyrinth_route.xml`, keys `text` and `openingGuildId`. Saving commits synchronously and checks success. Loading reparses; clearing makes source loading fail. Invalid input does not overwrite a previously valid saved route. Room entities/schema/version are untouched.

The text contains no opening guild. UI explicitly exposes the existing guild presets, defaulting to guild 5 (拉比林斯) for the current scenario. This is user configuration, not inferred network metadata. Its existing roster remains `1091 → 1171 → 1011`.

## UI and dry-run boundary

Existing 黎明界 → 自动执行 tab → 外部路线 card. Paste all five lines, confirm the guild, choose 解析并保存. Summary shows area count, node count, Boss names, saved guild, and validity. Parser/storage errors are shown directly. 清除外部路线 invalidates the source. No new navigation destination.

外部路线只读预演 uses existing capture authorization and starts the existing recognition session with `dryRun=true`, `accountId=null`, and the imported source. Accessibility/capture are checked. Each frame waits for the resolved game client's foreground package. No automatic game launch occurs. The imported path invokes the existing OpeningRosterPolicy and action planner, records the next plan, and returns before executing any action. Unknown pages or untrusted/duplicate/selected candidates do not produce a selection plan. Other planned action kinds are not executed.

Debug dashboard adds `executionSource`, `importedRouteAreas`, `openingPlanIds`, `plannedNextAction`, and `plannedCharacterId` alongside existing `actionCount` and recognition evidence. Planned fields describe a plan, not a sent gesture.

## Validation status

- Exact fixture hash verified.
- Final targeted tests: **51/51 passed**, zero failures/errors/skips. Existing 32 retained: route source 5, execution gate 4, reroll workflow 17, recognition session 6. New parser/source/UI/session suite 19. Includes actual session frame processing with nine synthetic recognized candidates: existing plan 1091→1171→1011, next 1091, actionCount=0, backend gesture calls=0; a subsequent map page produces no selection plan. Final test build completed in 1m22s.
- lintDebug: **0 errors, 12 warnings**. Warnings concern existing SDK identifiers, manifest, overlay accessibility/drawing, capture SDK check, resources, and wakelock code; none in new imported-route code.
- assembleDebug: **successful**, combined lint/build took 2m31s. APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 94,569,749 bytes, built 2026-09-14 09:12:34 local time.
- Install to emulator-5554: **Success**, `C:\leidian\LDPlayer9\adb.exe -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk`. No application/game data was cleared. MainActivity launch returned `Status: ok`; PID 3864 remained present and MainActivity was resumed, crash buffer empty. Accessibility enabled-setting includes LandosolAccessibilityService; the subsequent device run confirmed the service window and imported UI flow.
- Real imported-route dry-run: **PASS**, after the user manually imported/saved and confirmed the game at 0/3. Fresh dashboard evidence (timestamp 1789341426753, recognized frame 135) reports `EXTERNAL_IMPORTED`, 5 areas, `dryRun=true`, `INITIAL_CHARACTER_SELECTION`, confidence 0.9945115076222313, 1920×1080, all 9 characters trusted and unselected, openingPlanIds `[1091,1171,1011]`, plannedCharacterId `1091`, and actionCount **0**. Earlier live reads at frames 39 and 69 independently showed the same source/plan/zero actions; these were not stale previous-stage results.

Build environment: existing JDK 17 / Gradle 8.7 / Android SDK, repository-local writable Gradle home, existing read-only dependency cache, offline; `--no-daemon --max-workers=1 --no-parallel`, JVM `-Xms64m -Xmx1536m -XX:ActiveProcessorCount=2`. Existing Kotlin daemon directory permission failure falls back to compilation without that daemon. No downloaded toolchain or global configuration change.

Logs/APK/caches stay in untracked work/build locations. Existing untracked initial-selection report remains untouched. No commit or push.

Git check at the manual-test pause: `git diff --check` passed. Seven tracked files changed (159 insertions, 16 deletions). New files: parser/source/controller, Android store, regression test, exact sample fixture, and this report. These are still untracked, so ordinary `git diff --stat` does not include them. Previous untracked `docs/xiaomi-initial-selection-smoke-test.md` and `work/` remain untouched/untracked.

## Completed device validation

The user completed the new UI import/start flow. App-private saved text matches the original sample after trimming surrounding whitespace; saved guild is 5. Actual foreground activity belongs to `com.bilibili.priconne.mi`. Accessibility manager reports the active game window at (0,0)–(1920,1080) and the assistant service. The imported session's connected-service gate and foreground gate passed in production. The MediaProjection JPEG is pure Android game content and visibly shows 0/3, no selected cards, and a disabled invitation button. Crash buffer is empty.

| characterId | Name | Confidence | Trusted | Selected |
| --- | --- | ---: | --- | --- |
| 1049 | 静流 | 0.761822 | true | false |
| 1091 | 静流（情人节） | 0.716871 | true | false |
| 1171 | 静流（夏日） | 0.687567 | true | false |
| 1200 | 静流（黑暗） | 0.709802 | true | false |
| 1212 | 菈比莉斯塔（超负荷） | 0.713413 | true | false |
| 1337 | 菈比莉斯塔（阿尔法） | 0.661296 | true | false |
| 1011 | 璃乃 | 0.643655 | true | false |
| 1129 | 璃乃（仙境） | 0.651726 | true | false |
| 1193 | 璃乃（圣诞节） | 0.635104 | true | false |

Evidence is outside the repository: `C:\Users\ALEINWARE\Desktop\limingjie_clone\work\manual-imported-route\2026-09-14\` (`recognition.json`, `recognition-final.json`, `frame.jpg`, `foreground.txt`, `accessibility.txt`, `imported-route.xml`, `crash-buffer.txt`). JSON/JPEG endpoints were read separately; they are neighboring samples, not claimed to be atomically captured together. No credentials or network-session data were collected. The imported production path passes accountId=null and has no Bilibili session/API dependency; this conclusion combines inspected code with the successful real source/session, not network interception.

No code changes or new build were needed during this device-validation turn. The agent sent no gestures and ends testing here. User should stop recognition via the existing assistant control; the agent did not force-stop the assistant or bypass its non-exported stop receiver. This completes the dry-run prerequisite for a separately authorized single-character click test. Imported live execution is still intentionally blocked; this APK must not be described as already permitting live imported route execution or full map execution.
