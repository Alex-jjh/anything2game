# Player characters — pick your own, and it reacts to what you do (2026-11-19)

**Scope**: how the player's own character is drawn, how it reacts during play, and how the team's art replaces the placeholders. Code: `android/app/src/main/kotlin/com/anything2game/app/ui/character/`. Review images: `AvatarScreenshotTest` (expression sheet + frame sequence → GIF). Draft for the team; the character look is the team's decision.

## 1. Decisions

| Question | Decision | Why |
|---|---|---|
| Fixed cast or player-chosen? | **Player-chosen character** that follows you across every frame and every course; frame NPCs (Mira, Sam, The Archive…) stay | Continuity creates attachment (a companion you keep), while frames keep their own story |
| Static expressions, procedural, Rive, Live2D? | **Procedural parts + shared motion now**; images or Rive can replace the art later through one interface | Round, simple characters suit squash-and-stretch; reacts frame by frame to gestures; no dependency; Live2D is overkill and licensed |
| Use a well-known meme character (e.g. 奶龙) as the placeholder? | **No** — original placeholders (Mochi / Pip / Kumo) built to the same recipe: round body, big simple face, exaggerated expressions | Third-party IP in a public demo video and app is an infringement risk; the motion system is what is being tested, and it transfers to any art |
| Can the character hint at answers? | **Never.** While a question is open, mood comes only from the player's own input (lean, hold, picked statement, hint) | Otherwise the mascot becomes an answer oracle |

## 2. Architecture (swap point)

```
AvatarCharacter (interface: name, trait, lines per mood, Draw(mood, lookAt, useTilt))
 ├─ BlobAvatar    — procedural, parameterised by BlobLook (colours, shape, topper)   ← placeholders
 ├─ SpriteAvatar  — one image per mood + the same motion on top                       ← team's PNG/vector sheet
 └─ (RiveAvatar)  — a Rive state machine reading the same inputs                       ← later, if wanted

rememberAvatarMotion(mood)  — shared motion: breath, blink, squash/stretch, hop, lean (+ lagging copy), swell, poke
AvatarSignals (CompositionLocal) — renderers write live input: lean (swipe), charge (hold), focus (doubt/object)
avatarMood(state, signals)  — game events → mood (ui/play/AvatarPlacement.kt)
Avatars registry + AvatarPreferences — the picker on the library screen
```

Adding the team's character = one `SpriteAvatar(id, name, trait, images = mapOf(Mood.IDLE to R.drawable…, …))` line in `Avatars.all`. Missing moods fall back to IDLE, so a first sheet can have 3–4 images.

## 3. How the character interacts

| Moment | Reaction | Phone capability |
|---|---|---|
| Idle | breathes, blinks; eyes follow the phone's tilt | gravity sensor |
| Swipe card | leans with the card, body tilts from the feet; antenna/leaf lag behind (secondary motion) | drag gesture |
| Pick a doubtful statement | doubt: one brow up, squint, "?" | — |
| Hold to object | swells and trembles as the button fills | long-press + haptics |
| Objection / overclaim caught | squash, then pointing pose, "!" and impact lines | haptic confirm |
| Correct / wrong | hop with happy eyes / deflates (never sad or guilt-tripping) | — |
| Hint used | thinking: looks up-left, hand to chin | — |
| Walking detected | falls asleep on the pause screen ("Zz") | Activity Recognition |
| Case closed | celebrates with sparkles; says one line in its own voice | — |
| Poke it | surprised squash + haptic tick (easter egg) | touch |

Personality comes from short client-side lines (EN/ZH string arrays per character): Mochi is earnest, Pip is snarky, Kumo is dreamy. The server is not involved, and no AI call is made from the phone (ADR-013). A later, additive step: an optional `mood` tag on generated dialogue lines, so NPC portraits match what they say.

## 4. Accessibility and performance

- Reduced motion (system "remove animations"): the character stays still, with mood changes only.
- Drawn on one `Canvas` from about 20 primitives, so there are no bitmaps and the APK does not grow.
- It is not a button: the poke reaction is decorative, and all game actions keep their own controls.

## 5. Open for the team

- The real look: shapes, colours and names. Draw parts for `BlobLook`, a mood sheet for `SpriteAvatar`, or a Rive file.
- Whether the character also replaces the Case partner (Mira) or stands next to her (current build: next to her on stage).
- Voice and tone of each personality's lines.
