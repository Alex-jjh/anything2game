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
├── data/                   AssetPackageRepository (bundled samples = contract/examples), file-backed Session/Mastery stores
├── platform/               ContextSource (SimulatedContextSource now; sensor adapter next)
├── importing/              IntentImportParser → ImportRequest
└── ui/
    ├── AppRoot.kt          3 routes: Library · Play · Import
    ├── theme/Skin.kt       colours by ROLE (evidence, correct, wrong, hint…) — swap the skin, not the screens
    ├── library/  importing/  common/
    └── play/
        ├── PlayViewModel   load package + frame overlay → restore → reduce → persist; no game rules
        ├── PlayScreen      stateless; every input becomes an engine Action
        ├── FrameViews      scenes (dialogue / rooms), credibility pips, evidence board, failed screen
        └── mechanics/      MechanicRenderer + RenderContext; Swipe, Sort, Deduce, Order, Cross-exam; registry
```

Adding a mechanic: item type in `contract`, grader in `engine/grading`, renderer in `ui/play/mechanics`, register both. Nothing else changes.

## Frames (game templates)

Five frames over the same content and mechanics (`docs/product/game-concepts.md` §7). The player picks one per package on the library card; the choice is saved.

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
| Five frames (Classic, Case, Escape, Rookie, Companion) with bundled overlays for both packages; cross-exam boss, final lock, credibility, evidence board · Two bundled packages (EN, ZH), Swipe / Sort / Deduce / Order, hints, per-option feedback, retry of missed units, evidence with highlighted quote, recap with "still shaky", progress saved after every step | Server connection: "Make a game" and Ask say so honestly |
| Share text/PDF from another app or pick a PDF → confirmation screen | Real sensors: walking/metro/offline are driven by the in-app **context simulator** |
| Walking pause, one-hand mode, offline Ask message (via simulator) | Boss review screen, widget, camera OCR |

## Real-device checklist (the dev VM has no emulator)

1. Share a web page's text from a browser → app opens on the import screen.
2. Share a PDF from Files → name and size shown.
3. Play a level, rotate mid-card, kill the app from recents, reopen → same card, same decisions.
4. Simulator: Walking → pause screen; On the metro → bigger buttons; Offline → Ask explains.
5. Switch phone language to Chinese → UI in Chinese.
