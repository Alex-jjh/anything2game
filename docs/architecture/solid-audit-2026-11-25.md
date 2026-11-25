# SOLID and modularity audit (2026-11-25)

**Scope:** where the Android app breaks SOLID or repeats one concept in several places, what that costs the user, and what we fix before 12-07. The trigger was the team's report that one source is shown differently on different screens. That case is fixed separately in [source-viewer.md](source-viewer.md). Paths are under `android/app/src/main/kotlin/com/anything2game/app/` unless they start with `engine/`, `contract/` or `server/`.

## Findings (ranked)

| # | Severity | Principle | What | User / maintenance cost | Fix (size) | Done |
|---|---|---|---|---|---|---|
| 1 | high | DRY, SRP | "Is this level done?" is computed three ways: `engine/progress/ProgressProjection` for the route, `ui/library/LibraryViewModel` `LevelStatus` for practice, and `HomePreferences` stars for games. The same status also gets two different words (Materials "Learned", Library "Done"). | The route says done while the practice list says "needs practice". | One source of truth: the activity ledger plus `ProgressProjection`. One label/badge mapper. Inject the mastery policy. Stars go into the ledger. (M) | **Done** (486fadd). `ActivityProgress.standing()` is the one rule (new, needs practice, done, due, learned); route statuses derive from it, and the mastery policy is passed in. The practice list reads the same projection (`practiceLevels`). One word and one badge per state in `ui/common/Status.kt`, used by the practice list, the route and Me. Stars live in the ledger; old preference stars move in once. Left: Me still counts clues from the unit mastery store (practised, due), not activities. |
| 2 | high | OCP | Game mechanics have no registry. Adding a third mechanic means editing about 20 `is PartitionConfig` switches across contract, engine and `ui/game/*`. | Slow, risky to extend. | `GameMechanic` (engine) plus a `GameMechanicView` registry (UI). Deferred unless a third mechanic is planned. (L) | Deferred. |
| 3 | high | DIP, ISP, LSP | `AppDependencies` exposes seven nullable concrete members, used through 17 `requireNotNull` calls. `PlayViewModel` keeps a non-atomic persistence fallback for fakes. ViewModels take the whole container. There is an `as? SimulatedContextSource` cast. | An untested persistence path; screens coupled to everything. | Non-null ports. Each VM gets only the ports it uses. Remove the fallback. Add a simulator capability. (M) | **Done** (486fadd, eb66aba). The seven members are non-null. `PlayViewModel` keeps only the journalled `PracticeStore` path and takes the ports it uses; so do the Library, Space, Game and Playground view models. `AppDependencies.contextSimulator` replaces the cast. Left: the ports are still the concrete classes (`MaterialCatalog`, `SpaceRepository`, ...), not interfaces. |
| 4 | high | SRP | Business logic in composables: game stars, resume detection and sort (`ui/game/Playground.kt`, `GameScreen.kt`), `openImported` in `AppRoot`, and the "resumable" decision in `SpacesScreen`. The Spaces card and `RouteMap` each have their own current/finished rule. | The card and the map can disagree. Logic can't be unit-tested. | Add `PlaygroundViewModel`/`GameViewModel`. Make the card read `RouteMap`. Move `openImported` to a use case. (M) | **Done** (486fadd, eb66aba, 316a371). `GameViewModel` and `PlaygroundViewModel` hold stars, resume detection, shelf order and the Quick look flag. `OpenImported` replaces `AppRoot.openImported`. The Spaces card reads the `RouteMap`; the step sheet asks `SpaceViewModel.statusOf`. |
| 5 | medium | DIP, layering | `data/learning/MaterialCatalog` imports `ui.play.offeredFrames`. `ui/space` imports `ui.library` / `ui.home` widgets. | Layers are tangled. | Move the frame policy below the UI. Shared widgets go to `ui/common`. (S) | Not started. |
| 6 | medium | OCP, DRY | Six `when (frameId)` tables in `ui/play/FrameViews.kt`, plus a parallel `StepKind` enum in `ui/space/RouteMap.kt`. One frame has two names. | Inconsistent names. | One `FramePresentation` table keyed by `FrameId`. (S–M) | **Done** (8983c7d). `FramePresentation` (`ui/play`) has one row per `FrameId`; `StepKind.Practice(frame)` takes its name and icon from it. Companion gets its own route label. |
| 7 | medium | SRP | `MaterialCatalog` does eight jobs: inventory, classification, PDF resolution, route checks, activity resolution, status, sources and preset routes. | Hard to change one thing safely. | Split it, one verb per class. (M) | Not started. |
| 8 | medium | OCP, LSP | The tutor `mode` is compared as magic strings in about 13 places. The repository branches on `local-mock` in every method. | Easy to mislabel a mode. | A `TutorMode` enum carrying badge and note. Split scripted and remote backends. (M) | **Partly done** (8983c7d). `TutorMode` enum in the contract (wire strings unchanged, read once at decode, unknown strings refused), with badge and note texts in `ui/reading/TutorText.kt`. Left: the repository still branches on `LOCAL_MOCK` in each method; splitting scripted and remote backends is not done. |
| 9 | medium | DRY | Page label written out 9 times; icon defaults repeated; `Understanding` label repeated; game lookup by `startsWith`. | The same thing renders differently. | `displayPage()`, `GameSpec.icons()`, `Understanding.labelRes()`, `ReviewedGames.entryFor()`. (S) | **Done** (8983c7d). `displayPage()` on `SourceChunk` and `EvidenceRef.Pdf`, `PartitionConfig.icon()` / `AllocationConfig.icons()` / `StageConfig.mainIcon()`, `Understanding.labelRes()`, `ReviewedGames.entryFor(spec)`. |
| 10 | medium | dead code | `LibraryContent` is never shown to users but is still screenshot-tested. Unused prefs. A duplicate empty-state block. | Tests check a screen nobody sees. | Delete and merge. (S) | **Done** (486fadd, eb66aba). Deleted `LibraryContent`, `PackageCard`, `LevelRow`, `FramePicker`, their screenshot tests, the unused `HomePreferences` familiarity / prepared flags and the strings only they used. `EmptyState` merges the two empty blocks. |
| 11 | medium | layering | User wording sits in contract and engine (tutor lead-ins, rookie name, editorial game copy). There are five different "what language is this?" rules. | Inconsistent language handling. | One `ContentLanguage` helper (S). Moving game copy into packs (L) can come later. | Deferred. |
| 12 | low–med | SRP | `SpaceScreen` is about 150 lines with about 20 state holders. Its VM exposes repositories directly. | Hard to change. | VM intent methods; split the screen. (M) | Deferred. |
| 13 | low–med | OCP | `Navigator` hand-maintains 12 saved-state keys. `AppRoot` is a 105-line `when`. | Every new route touches four places. | A `@Serializable` `Route` and one host per route. (S–M) | Deferred. |
| 14 | low | LSP, ISP | Port defaults silently drop the content version (`Ports.kt`). | Progress can land in the wrong version. | Make the versioned methods required. (S–M) | Deferred. |
| 15 | low | OCP, hygiene | The demo mapping is hardcoded in `PresetRoute`. The sample seeder and developer tools ship in release. Some constants are duplicated. | Release carries demo-only code. | Move the mapping to pack metadata; debug-only tools. (S) | Deferred. |

## Already good

- `engine` is pure Kotlin and owns its ports.
- Practice mechanics use real registries.
- `PracticeStore` commits are journalled.
- Each profile gets a per-profile scope.
- Screens are stateless `*Content` composables.
- `TutorRules` is shared by server and app.
- Error mapping is centralised in `ui/common/Problems.kt`.

## Plan before 12-07

1. **One progress vocabulary:** #1, the card/map rule from #4, and the dead UI from #10.
2. **Presentation tables:** #6, the `TutorMode` enum from #8, and the helpers from #9.
3. **Non-null ports and logic out of composables:** #3, plus Playground/Game ViewModels from #4.

Deferred: #2 (until a third mechanic), the content move in #11, #12–#15.

## What the plan changed for the user

- One word per state, EN and ZH: New, In progress n/m, Needs practice, Done, Due for review, Learned. The route, the practice list and Me say the same thing. "Cleared" and the practice list's old "Done" (all units solid) are gone; "Done" now means finished once and "Learned" means solid.
- A level is judged from the activity ledger, so the practice list and the route agree. A level finished with help shows as Done, then Due for review, instead of Needs practice.
- Wiki-style headings in extracted text (`== Research and use ==`) read as headings.
- A route step of the Companion frame is named "Companion practice" (it used to be listed as Classic).
