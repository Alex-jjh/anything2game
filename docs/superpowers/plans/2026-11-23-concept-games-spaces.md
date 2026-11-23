# Concept games and PDF Spaces implementation plan

> Execute with superpowers:subagent-driven-development. User approved implementation, testing, screenshots, and GitHub push on 2026-11-23; no further design approval is needed.

**Goal:** Ship the API Android edition with playable mobile concept games, multi-document PDF Spaces, shared progress, and a working generation service; preserve existing learning content and sessions.

**Architecture:** Modular Ktor monolith and native Compose client. `contract` owns wire/content types, pure JVM `android:engine` owns mechanics and learning policies, server and app both consume them. One transport, archive codec, leased job worker, grading policy, and progress projection.

**Tech stack:** Kotlin, kotlinx.serialization/coroutines, Ktor, PostgreSQL/FileStore, Jetpack Compose, Gradle, JUnit/Robolectric, adb.

**Spec:** [Approved design](../specs/2026-11-23-concept-games-spaces-design.md). User approval supersedes its earlier proposal-status wording. The first release supports information partition and finite resource allocation, with grounded reviewed knowledge cards. Arbitrary-script games and unbounded web knowledge search are outside scope; unsupported topics return an honest structured result.

## Global constraints

- Work in `/Users/linziwei/Desktop/CSI301GW/api-concept-games`, branch `codex/concept-games-spaces`; base `3462393`. Original checkout remains intact. Do not dispatch subagents from implementer tasks.
- Existing v1 packs, PDF tutor/practice, evidence links, character preferences, profile isolation, and version-matching sessions must continue to work. Do not silently inherit unversioned mastery or upload local content.
- Add no second HTTP implementation, task scheduler, ZIP integrity implementation, model provider, game rule implementation, or Leitner schedule. Introduce interfaces only where they separate existing responsibilities.
- Shared DTOs and registry are authoritative. Native engine is the only mechanic evaluator; model output never executes as code. Programmatic validation must fail closed before publication/installation.
- Keep UI mobile-first: portrait, 48 dp controls, tap alternatives, bounded text, stable checkpoints, back/background recovery, offline installed games. Expose accurate loading/error/partial/unsupported states and actual evidence.
- Use existing English/Chinese resources and CaseFile Skin. New activity names are user-facing; keep implementation terminology out of normal flows.
- TDD for nontrivial runtime, persistence, protocol, lifecycle behavior; tests assert real behavior with hand-derived expectations. Run focused tests while iterating, affected suites before committing. Read the TDD writing-good-tests reference.
- No keys or `.env` in commits, no downloaded third-party PDF blobs. User explicitly authorized the final push. Save screenshot evidence and reproducible QA report in repository.

## Review focus

| Risk | Owner and required evidence |
|---|---|
| Wrong/unsolvable or unsupported game accepted | Task 1 rule traces, transfer result, invalid config/version/capability and malformed archive tests; Task 2 publication gate |
| Retry/cancel/lease loses ownership or publishes late | Task 2 real route/store tests, idempotent conflict and cancel race regression |
| Local packs leak into server generation, PDF chunk IDs collide | Task 2 owned/versioned source selection and exclusions; Task 3 local references; Task 4 evidence navigation |
| Migration duplicates spaces, overwrites progress, or double-counts shared activity | Task 3 repeated migration, profile isolation, version mismatch, atomic/idempotent completion tests |
| New navigation loses old tutor/frames or game checkpoints | Task 4 journeys and Task 5 adb lifecycle/back tests and screenshots |

## Task 1: Shared content contracts, two deterministic mechanics, and package compatibility

**Files:** create focused types under `contract/src/main/kotlin/com/anything2game/contract/{game,space,progress}/`; extend `contract/pack/ContentPack.kt` without duplicating archive I/O. Add `android/engine/.../{game,progress}/`; update `settings.gradle.kts` to include pure JVM engine in server-only builds. Tests in corresponding contract/engine test trees.

- [ ] Inspect existing DTO/PackCodec/Leitner patterns and add failing behavioral tests first.
- [ ] Define serializable `GameSpec`, stages, typed configs/actions/checkpoints, evidence union (scoped PDF / reviewed knowledge / external reference), `MechanicRegistry`, `Space` and local/server member union, `LearningPath` and typed activity refs. Add generation request/status/content DTOs to contract using existing CompileJobState and ApiError. All names/signatures actually implemented must be enumerated in the report for later tasks.
- [ ] Keep bounds/capability/rules versions in one registry. GameSpec declares content identity/version, evidence, stage goals, required capabilities; no scripts/expressions. Define one sealed typed payload per mechanic rather than a general DSL.
- [ ] Implement one deterministic reducer for information partition (choose split, observe eliminated candidates, constrained queries) and one for finite resource allocation (allocate finite resource, observe output/trade-off, undo/reset). Use seed, legal-action checks, bounded finite states. Each exemplar includes changed-condition transfer; a button cannot award success independently of rules. Checkpoints verify game version/rules/actions on restore.
- [ ] Provide two reviewed bilingual exemplar definitions with real primary source URLs and honest teaching-setting labels, and one engine API to start/apply/replay/validate. Server replay uses exactly this API. No thirdparty code copied. Knowledge-source records and ready specimen factories are shared where needed, with no fabricated retrieval provenance.
- [ ] Extend the existing PackCodec with typed v2 game/path payload, manifest checksums/dependencies/capabilities and atomic-install-compatible validated bytes. Preserve all v1 public behavior. Do not add another ZIP codec; reuse common archive integrity checks. Unknown format/capability, unsafe entry, oversize, bad checksum, missing dependencies, and unsolvable game must not install silently (runtime solvability validation may be engine boundary).
- [ ] Define shared versioned activity key/completion/mastery/day projection helpers that use existing LeitnerPolicy. Distinguish played/completed/mastered and helped outcomes. No Android filesystem code here. Duplicate completion IDs and repeated activity appearances have explicit semantics.
- [ ] Verify `./gradlew :contract:test :android:engine:test -Pa2g.samplePdfs=false --console=plain` and commit. Report red/green evidence, actual interfaces, config examples and integration notes.

## Task 2: Space service and grounded game/path generation through shared worker

**Files:** `server/.../store/{Store,FileStore,PostgresStore}.kt`, new database migration; `AppContext.kt`, `api/ApiRoutes.kt` or focused route file; `compile/CompileJobRunner.kt` and focused generation services; `llm/Prompts.kt`, existing router/fake provider, `resources/prompts`; `server/build.gradle.kts`. Contract changes only for demonstrated missing wire requirements. Tests under `server/src/test`.

- [ ] Read Task 1 report and consume its actual DTO/engine APIs. Write failing route/store/generation tests before behavior.
- [ ] Implement owner-scoped Space create/list/get/rename/member add/remove with revision conflict and clientRequestId/body dedupe. Preserve local opaque member refs without trusting their content; server-document refs must match owner and version. Implement both FileStore and Postgres schema/repository with explicit migration and restart persistence.
- [ ] Implement `/v1/generation-jobs` submit/read/cancel for concept games and Space paths plus immutable `/v1/content/{id}/versions/{version}` retrieval. Reuse existing auth/errors/quotas. Same owner/op/request ID+same body returns same job; different body conflicts. Existing compile routes remain compatible.
- [ ] Generalize existing leased worker/store lifecycle so old compile and new work share polling, concurrency, heartbeat, cancel, retry, and guarded publication. Do not fake a document row for concept jobs; use explicit typed/nullable source fields and migration. Prefer extending existing JobRecord instead of building a parallel scheduler.
- [ ] Add KnowledgeSourceProvider with reviewed cards as initial implementation. Supported concepts route from actual bounded knowledge; unsupported input is explanatory. Build Knowledge → Experience → typed GameSpec or LearningPath → deterministic validation pipeline through existing LlmRouter. Prompts inject registry/mobile/source rules once. Bounded repair/retry; invalid/unresolved output never becomes ready. Fake provider returns typed deterministic fixtures for tests, clearly identified; real configured providers use the same prompt/validation path.
- [ ] Space paths use only owner-verified uploaded sources for any new cross-document synthesis; explicitly report excluded local/unavailable refs. Retain existing local practice nodes. Evidence must identify document/version/chunk so duplicate chunk IDs never collide. Versioned path does not mutate active runs.
- [ ] Ensure cancellation/document deletion/lease loss prevents later publication. Test restart, duplicate request body conflicts, other-owner access, incompatible refs, unsupported/invalid generation, partial source availability, successful shared-engine replay, and compile regression. Record which Postgres tests actually ran and which require unavailable infrastructure.
- [ ] Verify `./gradlew :server:test :contract:test :android:engine:test -Pa2g.samplePdfs=false --console=plain` (capture logs to scratch) and server-only compile, commit; report actual endpoints/interface integration examples.

## Task 3: Android shared repositories, transport, migration and atomic progress

**Files:** `android/app/.../data/tutor/TutorHttpClient.kt`, new `data/api/ApiTransport.kt`, `data/content/ContentLibrary.kt` and installer as necessary, focused Space/game/progress repositories and stores, `AppDependencies.kt`, `data/FileStores.kt`, engine completion integration where necessary. Tests in Android data/engine tests.

- [ ] Read prior reports. Extract networking/token/timeout/error mapping into one ApiTransport; preserve TutorHttpClient as thin compatibility adapter. Game/Space clients use that same transport and DTOs, never provider credentials.
- [ ] Add profile-scoped Space persistence with stable migration: original source/version grouped once, each old independent source becomes same-title Space; LocalPackRef stays local. Repeated startup is idempotent; versioned existing SessionState resumes. Preserve unversioned mastery as legacy history and prevent counting it as current mastery. New mastery keys include content version.
- [ ] Add package installation/list/load for v2 games with one ContentLibrary/PackCodec path, capability checks and atomic replacement. Seed the two reviewed examples through the same path; mark pre-generated content accurately. Never replace healthy installed content with malformed download.
- [ ] Implement durable game run repository with seed/version/rules/checkpoint, versioned completion and shared progress. Commit checkpoint/completion/mastery/day together using one atomic snapshot or equivalent. Duplicate completion callback has no double points; same activity in two Spaces counts once globally. Reuse LeitnerPolicy. Integrate existing tutor/practice completion through this single progress recorder without duplicating grading.
- [ ] Add Space local route projection from installed tutor/practice activities, generated path version application, exclusions, member add/remove/create/rename, persisted pending generation ID/resume/cancel, per-PDF import queue model with independent failure. Do not upload local sources automatically. Keep source navigation resolvers centralized.
- [ ] Expose cohesive repository ports on AppDependencies with test-safe defaults or adapt existing fixtures. Avoid a giant generic repository with mode booleans. UI consumers receive actual models not duplicated DTOs.
- [ ] Verify data/profile/migration/completion tests plus affected engine and existing Android unit suites. Commit and report public APIs, view-model integration guidance, any required fixture migrations.

## Task 4: Mobile playground, multi-PDF Spaces and integrated navigation

**Files:** `ui/home/HomeScreen.kt`, `ui/library/LibraryViewModel.kt`, `ui/AppRoot.kt`, `ui/Navigator.kt`, `MainActivity.kt`, new focused `ui/game` and `ui/space` screens/ViewModels, English/Chinese resources, affected journey tests. Reuse Skin/character/evidence components.

- [ ] Read prior actual interfaces. Add failing meaningful journey/lifecycle tests for new flows, adapt existing tests to retained behavior instead of deleting regression coverage.
- [ ] Ship bottom tabs Playground/Spaces/Me (游乐场/学习空间/我的). Migrate PRACTICE and LEARN stored values to Spaces, PROFILE remains; clean install defaults Spaces as in the approved design. Existing tutor and old framed practice are reachable only through Space context and preserve back destination.
- [ ] Playground has concise concept input, supported examples, generation state/unsupported/error/cancel/retry, installed game cards and resume. Generated successful pack installs and opens actual runtime. Pre-generated cards are playable offline and labelled. Render both mechanisms with state-changing actions, causal feedback and transfer challenge; shared engine decides outcomes. Use large tap/stepper controls, undo/retry, help/evidence and persistent checkpoints. No desktop fixed canvas, hover, typing-heavy game, or fake completion.
- [ ] Spaces list uses one card per Space showing multiple PDF count, true progress, next activity, learning-day summary. Create/rename Space, add existing material, multi-PDF picker/import with per-file success/error and retry. Space detail switches route/materials, with tutor, light practice and old Case/Rookie/Escape access. Allow removing membership without deleting source. Local exclusions are visible before server synthesis request.
- [ ] Route cards clearly show available/locked/needs-update/complete/review statuses from shared progress; current active run not reordered by refreshed path. Source links navigate correct PDF/page or reviewed knowledge reference. Me reads same progress and retains profile/avatar/history, labels legacy history accurately.
- [ ] Handle loading/empty/offline/error, process/SavedStateHandle restoration, rotations, background resume, back stack, accessibility labels and large-font scrolling. Persist actions at stable boundaries before navigation. Avoid oversized home file; feature screens separate responsibilities.
- [ ] Verify all Android tests, relevant shared tests and assemble debug; fix genuine regressions. Commit, report journey tags/adb navigation steps for final QA.

## Task 5: Device acceptance, screenshots, documentation and GitHub delivery

**Owner:** controller, with final whole-branch review per skill; no simultaneous implementation agents.

- [ ] Run full Gradle verification (`test`, `:android:app:assembleDebug`, direct-AI guard), inspect summary counts, and document pre-existing warnings/environment-dependent skips honestly.
- [ ] Start dedicated Android AVD without wiping any existing user emulator. Install this APK; run fake/local API where needed with explicit fixture mode. Verify both games and transfer, save/relaunch, three-doc Space, old reading/practice, member removal, generation success/failure/cancel, correct evidence, Chinese and English layout, large text. Use UI-tree-derived taps.
- [ ] Save representative real-app PNG screenshots (playground, both games, Space list/route/materials, Me) and QA script/report under `docs/design/screens/2026-11-23-concept-games-spaces/` and `android/qa/`. Inspect screenshots visually; do not substitute design mock for evidence. Record real-model quality as untested if no credentials, not an implementation success claim.
- [ ] Update README/docs index/API docs and approved design status to distinguish shipped capability, supported scope, and follow-up ideas. Include prompt/source research links and compatibility caveats.
- [ ] Perform whole-branch reviewer pass, address material issues, rerun affected checks. Confirm no duplicate transport/worker/mechanics, no secrets/downloaded PDFs in diff, no unrelated files. Commit evidence/docs.
- [ ] Fetch origin, ensure safe branch state, push `codex/concept-games-spaces` to `https://github.com/Alex-jjh/anything2game-api.git`; no force push. Provide commit/branch link, test results and screenshot paths. User authorized push; no redundant approval pause.
