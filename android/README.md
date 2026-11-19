# android — the app

**Scope**: how the Android side is organised, built and tested. Architecture reasons: [ARCHITECTURE §1, §6](../docs/architecture/ARCHITECTURE.md).

## Modules

| Module | Knows Android? | Contents |
|---|---|---|
| `:android:engine` | no (pure Kotlin) | `grading/` one grader per mechanic + registry · `session/` level state machine (`LevelSession`), serialisable `SessionState` · `mastery/` Leitner policy + boss scheduler · `context/` context policy · `ports/` interfaces the app implements |
| `:android:app` | yes | Compose UI, platform adapters, composition root |

`:app` packages:

```
com.anything2game.app
├── AppDependencies.kt      interfaces screens depend on + AndroidAppContainer (the only place choosing implementations)
├── MainActivity.kt         share/view intents, file picker
├── data/                   ContentLibrary (validated asset/installed packs), file-backed Session/Mastery/Tutor stores
├── platform/               ContextSource (SimulatedContextSource now; sensor adapter next)
├── importing/              IntentImportParser → ImportRequest
└── ui/
    ├── AppRoot.kt          Home (Practice / Learn / Me) · Read/Tutor · Play · Import
    ├── theme/Skin.kt       colours by ROLE (evidence, correct, wrong, hint…) — swap the skin, not the screens
    ├── home/  reading/  library/  importing/  common/
    └── play/
        ├── PlayViewModel   load package + frame overlay → restore → reduce → persist; no game rules
        ├── PlayScreen      stateless; every input becomes an engine Action
        ├── FrameViews      scenes (dialogue / rooms), credibility pips, evidence board, failed screen
        └── mechanics/      MechanicRenderer + RenderContext; Swipe, Sort, Deduce, Order, Cross-exam; registry
```

Adding a mechanic: item type in `contract`, grader in `engine/grading`, renderer in `ui/play/mechanics`, register both. Nothing else changes.

## Frames (game templates)

Five frames over the same content and mechanics (`docs/product/game-concepts.md` §7). New practice starts in Classic. Historical unfinished sessions retain their previous frame; the engines remain available for compatibility.

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
| Seven bundled packs, Classic as the new practice entry, historical frame compatibility, grading/feedback/sources/review and saved progress; imported documents can independently compile and download validated practice packs | The older in-game Ask panel is still separate from the new document tutor and is not connected |
| Share text/PDF or choose a PDF → confirm destination → upload/text extraction → diagnostic → assessment → conversational teaching; offline fixed example also available | Real sensors: walking/metro/offline are driven by the in-app **context simulator** |
| Walking pause, one-hand mode, offline Ask message (via simulator) | Boss review screen, widget, camera OCR |

## Device checklist (executed cases are recorded in the acceptance report)

1. Share a web page's text from a browser → app opens on the import screen.
2. Share a PDF from Files → name and size shown.
3. Play a level, rotate mid-card, kill the app from recents, reopen → same card, same decisions.
4. Simulator: Walking → pause screen; On the metro → bigger buttons; Offline → Ask explains.
5. Switch phone language to Chinese → UI in Chinese.

## Three-tab home (2026-11-19)

The current entry point is Practice / Learn / Me, with Learn in the center and selected on first launch. Me contains a device-only nickname, existing character picker and local learning statistics; accounts and cloud sync remain unconnected. New practice uses Classic, while unfinished historical sessions keep their original frame. Learn now uses foundational multiple-choice questions plus “I do not know”, concept-level evidence and dialogue teaching. Uploaded documents use the server; the separate fixed example remains offline. The local QA server performs real PDF text extraction with explicitly labelled AI mock. Real-model quality has not been validated.

See [design and data boundaries](../docs/design/home-navigation.md) and [acceptance evidence](../docs/design/home-navigation-acceptance.md). The older LibraryContent remains a historical screenshot fixture; AppRoot renders HomeContent.

## Document tutor and backend integration (2026-11-19)

See [document-tutor.md](../docs/design/document-tutor.md) for A/B changes, draft API/schema, localhost startup, device port forwarding, data boundaries and remaining decisions. See [acceptance and screenshots](../docs/design/document-tutor-acceptance.md) for actual verification. Debug defaults to a developer-owned server at `127.0.0.1:18080`; release needs an explicit HTTPS `a2gApiBaseUrl`. No model keys belong in the Android app.
