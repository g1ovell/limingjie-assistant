# Labyrinth Route Source Generalization

## Current Coupling

Before this change, the visual route executor received three independent callbacks from the
application: load a saved route by account, persist the current block by account, and run the
execution gate by account. The callbacks were all wired to the Bilibili Room stores. This made a
non-Bilibili or accountless route source impossible to represent cleanly, and made the visual
session appear to require a Bilibili account even when the route itself could be supplied by
another source.

## Existing Bilibili Flow

`LabyrinthController` still owns the existing Bilibili reroll/login workflow. It selects the
account, obtains the Bilibili game session, creates the existing `LabyrinthRerollWorkflow`, and
saves the route and checkpoint in the existing Room tables. Its API construction now goes through
`BilibiliLabyrinthRerollProvider`, which is a small channel-specific adapter and preserves the
existing `BilibiliLabyrinthApi` behavior. The provider declares both its account requirement and
its required game-session capability; those declarations are metadata for future providers, not a
new login path.

## New Abstraction

`LabyrinthRouteSource` is the visual executor's single route boundary. A source declares its
`LabyrinthRouteSourceKind` and whether an account is `REQUIRED` or `OPTIONAL`, loads a validated
`LabyrinthExecutionContext` (route, checkpoint, source identity, and user-facing message), and
persists route progress through the same context. A missing source is rejected before a live node
session is created; an unknown source cannot silently fall through to Bilibili behavior.

The session validates the source once before starting live execution, keeps the returned context
for node initialization, and uses that context for progress persistence. This removes the old
account-specific route callbacks without changing node recognition, action planning, or route
progress ordering rules.

## Account Requirement Model

`BilibiliLabyrinthRouteSource` remains `REQUIRED` and rejects a missing account, route, checkpoint,
or invalid checkpoint through the existing `validateLabyrinthExecution` gate. The generic resolver
also enforces the declared requirement before calling a source. An accountless source can declare
`OPTIONAL` and provide a valid route/checkpoint context with a null account; no login or Xiaomi
network behavior is introduced here.

## Visual Automation Boundary

Read-only recognition is unchanged. Live route execution still requires Accessibility, screen
capture, node templates, and a validated route source. The source gate is now about the route
provenance and execution context rather than an unconditional Bilibili account callback. No
MediaProjection, tap, login, route import UI, or full automation behavior was added in this stage.

## Bilibili Compatibility

The application wires `BilibiliLabyrinthRouteSource` to the existing `RoomLabyrinthRouteStore` and
`RoomLabyrinthRerollCheckpointStore`. No Room entity, database version, migration, resource pack,
route JSON format, or Bilibili strategy weight changed. Existing Bilibili execution therefore
continues to use the same saved route, checkpoint, Enter ID, and monotonic progress checks.

## Test Fake Accountless Source

`LabyrinthRouteSourceTest` contains a small `EXTERNAL_IMPORTED` fake source. It proves that a valid
accountless context passes the generic resolver, while a missing route, missing source, or required
account still blocks execution. The fake is test-only; there is no production external import
implementation in this stage.

## Database / Migration Impact

There is no schema change and no migration. Existing Room route/checkpoint tables remain the
persistence used by the Bilibili source. Future sources can choose a different store without
changing the visual session contract, but that implementation is intentionally deferred.

## Remaining Work

Future channel adapters must provide their own route source and, if they use network rerolling, a
matching `LabyrinthRerollProvider`. They must also define how route/checkpoint identity is verified
for that channel. Xiaomi login, network APIs, manual route import, and channel-specific templates
are outside this change.

## Next Step

Run the targeted unit tests, `lintDebug`, and `assembleDebug`. If those checks pass, the next
development step can be a separately reviewed route-source implementation for another channel;
this commit does not start that work.
