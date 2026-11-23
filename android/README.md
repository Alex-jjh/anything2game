# android — the app

**Scope**: how the Android side is organised, built and tested. Architecture reasons: [ARCHITECTURE §1, §6](../docs/architecture/ARCHITECTURE.md).

## Modules

| Module | Knows Android? | Contents |
|---|---|---|
| `:android:engine` | no (pure Kotlin) | `game/` deterministic concept-game runtime · `progress/` shared projection · `grading/` graders · `session/` level state machine · `mastery/` existing Leitner policy · `context/` context policy · `ports/` interfaces the app implements |
| `:android:app` | yes | Compose UI, platform adapters, composition root |

`:app` packages:

```
com.anything2game.app
├── AppDependencies.kt      interfaces screens depend on + AndroidAppContainer (the only place choosing implementations)
├── MainActivity.kt         share/view intents, file picker
├── data/                   one API transport, versioned ContentLibrary, profile-scoped Space/generation/import/progress repositories
├── platform/               ContextSource (SimulatedContextSource now; sensor adapter next)
├── importing/              IntentImportParser → ImportRequest
└── ui/
    ├── AppRoot.kt          Home (Playground / Spaces / Me) · Read/Tutor · Play · Import
    ├── theme/Skin.kt       colours by ROLE (evidence, correct, wrong, hint…) — swap the skin, not the screens
    ├── game/              Playground, typed native boards, generation status
    ├── space/             Space cards, route, materials and scoped evidence
    ├── home/  reading/  library/  importing/  common/
    └── play/
        ├── PlayViewModel   load package + frame overlay → restore → reduce → persist; no game rules
        ├── PlayScreen      stateless; every input becomes an engine Action
        ├── FrameViews      scenes (dialogue / rooms), credibility pips, evidence board, failed screen
        └── mechanics/      MechanicRenderer + RenderContext; Swipe, Sort, Deduce, Order, Cross-exam; registry
```

Adding a mechanic: item type in `contract`, grader in `engine/grading`, renderer in `ui/play/mechanics`, register both. Nothing else changes.

## Frames (game templates)

Five frames over the same content and mechanics. What really differs is covered in [frames.md](../docs/product/frames.md). Space practice nodes retain **Play as** per course: Classic is the default, then Case and Rookie, and Escape only for process-heavy courses. Companion is folded into the reading flow. An open session resumes in its own frame. Rookie adds **teach-back** challenges and Escape adds **room locks**. Both are derived read-only from the level's items (`engine/frames/DerivedChallenges.kt`).

| Frame | Overlay content | Boss | Stakes |
|---|---|---|---|
| Classic | none | — | — |
| Case | partner + the Summary, intro/outro, evidence board | cross-examine the Summary | 3 credibility |
| Escape | narrator, rooms grouping units | final lock (order/deduce) | 3 credibility |
| Rookie | junior's lead-in before every unit | fix the rookie's notes (cross-exam) | — |
| Companion | partner's cited lead-in before every unit | — | — |

Engine side: `engine/frames/GameFrame` (strategy) + `ScriptedFrame` (the shared intro → rooms → lead-ins → units → boss → outro script) + `Frames` registry. Overlays are separate artefacts (`frame.v1.*.json`), validated by `FrameValidator` against `FrameSpec`; a frame without an overlay falls back to Classic. A frame never changes items, answers or citations, and never grades.

## Languages

UI strings in `res/values` (English) and `res/values-zh` (Chinese); follows the system language, and on Android 13+ users can pick the app language in system settings (`generateLocaleConfig`). Content language comes from the package (`language`); samples exist in both.

## Build and test (from repo root)

```sh
./gradlew :android:engine:test                      # engine rules (JVM)
./gradlew :android:app:assembleDebug                # APK → android/app/build/outputs/apk/debug/
./gradlew :android:app:testDebugUnitTest -Proborazzi.test.record=true   # screenshots → android/app/build/outputs/roborazzi/
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 21 and Android SDK platform 37 (`local.properties` → `sdk.dir`). Reviewed screenshots are copied to [`docs/design/screens/`](../docs/design/screens).

## What this build does / doesn't do

| Works | Not yet |
|---|---|
| Seven bundled content packs (four with explanation scripts); Play as per course; drag and drop in Sort and Order; grading, evidence, review and saved progress; local profiles (sample student Lin / guest); imported documents compile on the server and install as validated packs | The older in-game Ask panel remains separate from the document tutor |
| Space reading: focus → diagnostic → starting point → **explainer theatre** (visual-novel scene) for preset courses offline, and for uploaded documents via the server | Real sensors: walking, metro and offline are driven by the in-app **context simulator** |
| Walking switches to one-handed; pausing is opt-in in Me; offline Ask message | Cross-course "review now", widget, notifications, camera OCR |

## Device checklist (executed cases are recorded in the acceptance report)

1. Share a web page's text from a browser → app opens on the import screen.
2. Share a PDF from Files → choose or create a Space → confirm the file before upload.
3. Play a level, rotate mid-card, kill the app from recents, reopen → same card, same decisions.
4. Simulator: Walking → one-handed (pause only if enabled in Me); On the metro → bigger buttons; Offline → Ask explains.
5. Switch phone language to Chinese → UI in Chinese.

## Document explainer, profiles and drag and drop

- **Space reading:** `ui/reading/ExplainerTheatre.kt` (stage + paged dialogue + choice bar + log), `FocusStep.kt`, `DialoguePaging.kt`, and `ExplainerTheme.kt`. The theme slot takes CC0 standee art as drawables `standee_<expression>` plus `explainer_bg`.
- **Profiles:** `data/profile/`. Guest and sample student share the same store interfaces. Lin's history is generated by `engine/demo/ScriptedPlayer` playing the presets.
- **Drag and drop:** `ui/play/mechanics/DragSupport.kt` with Sort and Order. Tap and arrow paths and TalkBack actions are kept.
- **Journey tests:** `app/src/test/.../journeys/` drives the real `MainActivity` with real taps.

## Concept games and Spaces (2026-11-23)

The new entrance is **Playground / Spaces / Me**, with Spaces selected on a clean install. Existing PRACTICE and LEARN preferences migrate to Spaces. Reading and the old framed practice activities retain their engines inside a Space; native concept games use `GameRuntime`. All current learning dates derive from one progress projection. Historical records without known versions stay labeled history.

The immutable content library is shared; mutable runs, Space memberships, pending imports and API tickets belong to the active profile. Exact versions remain installed for saved sessions. `ApiTransport` handles authentication, HTTP and errors once. PDF source lookup retains original upload bytes and exact geometry. See [architecture and recovery guarantees](../docs/architecture/concept-games-spaces.md) and the [acceptance record](../docs/design/concept-games-spaces-acceptance.md) for actual verification and screenshots.

Concept generation supports reviewed binary-search and opportunity-cost topics in this release. Native presets run offline. Fake-generated artifacts retain provider attribution. Generation requires the server, while every game action is reduced and saved locally.

The [previous home design](../docs/design/home-navigation.md) and its [acceptance record](../docs/design/home-navigation-acceptance.md) document the earlier release, not the current navigation.

## Document tutor and backend integration (2026-11-19)

See [document-tutor.md](../docs/design/document-tutor.md) for A/B changes, draft API/schema, localhost startup, device port forwarding, data boundaries and remaining decisions. See [acceptance and screenshots](../docs/design/document-tutor-acceptance.md) for actual verification. Debug defaults to a developer-owned server at `127.0.0.1:18080`; release needs an explicit HTTPS `a2gApiBaseUrl`. No model keys belong in the Android app.
