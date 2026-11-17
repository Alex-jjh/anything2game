# Game design

**Scope**: what the player does, which parts are fixed vs variable, and how play adapts. Does not cover visuals (→ `docs/design/`) or how packages are generated (→ ARCHITECTURE §4).

## 1. The answer to "fixed mode or templates?" — three layers, each with a different owner

| Layer | Fixed or variable | Who decides | Example |
|---|---|---|---|
| **Frame** — the meta-game | **Pluggable** (revised 11-17, see game-concepts §7): default Case, Classic shipped; user may switch | product | a material = a *case file*; it has 3–6 levels; each level ends with a recap; misses come back as a *boss* review |
| **Mechanic** — the mini-game inside a level | **Variable, chosen by the content**, not by the user | the compiler (LLM) picks per knowledge unit, from a closed list | definition → *Sort*; process → *Order*; claim → *Swipe*; reasoning → *Deduce* |
| **Skin** — look and feel | **Swappable** without touching content or logic | visual team | detective case file (default), or a plain "study" skin |

Why this split:

- **Fixed frame** = one thing to learn, one demo story, one progression system. Users on a phone should never have to configure a game.
- **Content-chosen mechanic** is the innovation claim: the game's *rules* depend on the *kind* of knowledge (intrinsic integration — Habgood & Ainsworth 2011). A user-chosen template would just be a costume over the same quiz.
- **Skins** keep the aesthetic decisions (owned by the visual team) out of the data and the engine, so they can change until the last week.

The user chooses only: **session length** (3 / 5 / 10 min) and optionally the skin. Nothing else.

## 2. The frame

```
Material (case file)
 ├─ Level 1 "What problem does the paper solve?"   ← goal written as a question
 │    ├─ unit: Swipe   (3 claims)
 │    ├─ unit: Sort    (5 cards → 2 bins)
 │    ├─ unit: Deduce  (scenario → which conclusion is supported)
 │    └─ recap: "You now know… / Still shaky: …"
 ├─ Level 2 …
 └─ Boss (appears next day): mixed variants of everything you missed
```

- A level is **3–5 minutes**, 3–5 units. It is the unit of interruption: progress is saved after every answer.
- Levels follow the material's logic (for a paper: problem → method → results → limits), produced by the compiler's plan step.
- Every answer, right or wrong, shows **why** and a **Show evidence** button (page + passage). Wrong answers explain the specific misconception of the chosen option, not just "incorrect".
- **Ask** ("raise hand") is available at any point; the level pauses and resumes at the same unit.

### Detective skin (recommended default) — why it is more than decoration

The skin's vocabulary matches the trust feature: units are *clues*, Show evidence is *pull the file*, the recap is *close the case*, a wrong answer is *the evidence doesn't support that*. It teaches the habit we want (claims need evidence) and gives the ad a story. Final call belongs to the visual team.

## 3. Mechanic library (closed list)

| Mechanic | Knowledge type it fits | Gesture | Item shape | MVP? |
|---|---|---|---|---|
| **Swipe** — true or false? | factual claims, common misconceptions | swipe right = supported, left = not | statement, verdict, why | ✅ |
| **Sort** — file the cards | definitions, categories, compare/contrast | drag card into one of 2–3 bins | bins, cards→bin | ✅ |
| **Deduce** — what follows from this? | causal reasoning, application, evidence limits | tap one option | scenario, options with per-option feedback | ✅ (existing prototype's question) |
| **Order** — rebuild the sequence | processes, method steps, timelines | drag to reorder | steps in correct order | stretch |
| **Match** — pair them up | term ↔ meaning, cause ↔ effect | tap-tap pairing | pairs | stretch |

Rules for the compiler:
- It must choose from this list and justify the choice in a field (`mechanicReason`) — reviewed in evals, not shown to users.
- Every item must cite ≥1 source chunk. No citation → item rejected.
- A level should mix ≥2 mechanics so it doesn't feel like a quiz.

Adding a mechanic later = one renderer in `:app`, one item type in `contract`, one prompt section in `server`. Nothing else changes.

## 4. How play adapts — "compile big once, adapt locally, call Flash only when stuck"

| Tier | Where | When | What |
|---|---|---|---|
| **T1 Compile** | server, strong model, async | once per document version | plan levels → generate units with **2 extra variants each**, per-option feedback, 2-step hint ladder, citations → validate → publish level by level |
| **T2 Adapt locally** | phone, `:engine`, deterministic, offline | every answer | mastery per unit; wrong → hint, then a pre-generated variant later in the session; misses scheduled into the boss; context policy (session length, pause) |
| **T3 Adapt live** | server, flash model, ≤ 5 s timeout | only when the pre-generated pool can't help | **Ask**: free question, answered from this document's chunks only, must cite or say "not in the material"; **Re-explain**: same misconception missed twice; **Refill**: variant pool for a unit exhausted (prefetched in background, never blocks play) |

Principles:
1. **The LLM writes content; the device decides flow.** Flow logic is plain Kotlin — testable, offline, cheap.
2. **Flash never blocks the main path.** Every T3 call has a fallback to T1 content; a late answer after the user moved on is discarded.
3. **Progressive readiness**: level 1 is published as soon as it validates (target: playable < 60 s after import), the rest compiles in the background with a notification when done.

### Mastery model (T2, parameters are experiment knobs, not claims)

Per unit, Leitner-style box 0–3:
- correct first try → box +1; correct after hint → stay; wrong → box 0, enqueue a variant 2 units later.
- Boss tomorrow = all units in box ≤1. Due times (e.g. +10 min, +1 day, +3 days) are tunable (Cepeda et al. 2006 gives no universal schedule).
- Recap shows "solid" (box ≥2) vs "still shaky" — never equates completion with mastery.

## 5. Context policy (pillar ③, in `:engine`)

| Signal | Detected by | Effect |
|---|---|---|
| Walking | Activity Recognition (fallback: step detector) | pause the level, show "Continue when you've stopped"; resume card when still again |
| In vehicle | Activity Recognition | one-hand layout: prefer Swipe/Deduce units, bigger targets |
| Offline / metered | ConnectivityManager | play downloaded packages; Ask disabled with a clear reason; prefetch only on unmetered |
| Low battery | BatteryManager | no background compile polling; reduce animation |
| Evening / idle | WorkManager + widget | boss card on the home screen |

On the emulator-less VM these are driven by a **debug context panel** (fake signals); real signals are tested on a phone.

## 6. v2 — more game, still learning (proposed 2026-11-17)

**Why**: the v1 baseline plays like Duolingo — clean, but a quiz with a progress bar. The closest competitor (Paper2Galgame) wins on *character and story* but the player mostly clicks through a lecture. We want both: a game with stakes and a story, where the **player** does the reasoning.

### 6.1 The fantasy: you are the detective, the AI summary is the suspect

- **The case file** = the material. **Your partner** (an original character, designed by our visual team) briefs you and reacts to your moves.
- **The suspect** = *the Summary*: a smooth-talking character who "has already read it for you" and overclaims. Your job is to catch where it goes beyond the evidence.
- This turns our product thesis into the plot: *AI summaries make you feel you understood — here you catch them out.* It also teaches the habit we want (claims need evidence) and gives the ad its story.

### 6.2 Level loop (3–5 minutes)

```
Briefing (30 s)  →  Clues (3–4 mechanics)  →  Cross-examination (level boss)  →  Case closed
partner sets the    each solved clue is         the Summary makes 4–5 claims;     board snapshot +
question            PINNED to the board         press the overclaim WITH the      what's still shaky
                                                 page that disproves it
```

| Element | What the player sees | Learning job |
|---|---|---|
| **Briefing** | 3–5 short lines from the partner, the level goal as a question | sets the goal (advance organiser), no long reading |
| **Clues** | Swipe / Sort / Deduce / Order as now, but each solved clue flies onto the board | retrieval practice per knowledge type |
| **Cross-examination** (new mechanic) | statements appear one by one; tap one → *Present evidence* → pick the page/passage from your case file | apply the evidence, spot over-generalisation — the hardest, most "game" moment |
| **Credibility** (stakes) | 3 pips per level; a wrong press or a wrong verdict costs one; 0 → "the case went cold", replay with variants | errors matter, but no paywalled lives and no timer (mobile interruption) |
| **Case closed** | the board for this level, filled in; "still shaky" clues glow | recap + honest uncertainty |

### 6.3 The evidence board = a mind map you *earn*

NotebookLM generates a mind map for you; we make you build it.
- The compiler emits a small concept graph per package: one node per unit ("clue"), edges with short labels ("measured by", "contradicts", "only at"). Nodes start as silhouettes.
- Solving a clue pins its node; finishing a level reveals its edges. Tapping a node opens its evidence and lets you replay it.
- After the case, the board stays as **Case notes**: a 10-second review on the metro — the fragmented-time format NotebookLM gets right, but earned instead of handed over.

### 6.4 Game feel

- Combo: 3 first-try clues in a row → "Sharp eye" (cosmetic stamp on the board, no score inflation).
- Motion and haptics on every verdict (swipe fling, pin snap, stamp); sound optional and off by default (people play on the metro).
- Partner reaction lines by outcome (correct / partly / wrong / combo) live in the **skin** as localised strings, not in packages — cheap and swappable.
- No audio narration in the MVP: the course forbids AI voice-over in the video, and reading is faster than listening for 30-second briefings.

### 6.5 What we deliberately do not copy

No anime-companion visual novel, no click-through dialogue as the main activity, no character gacha/customisation. The partner and the Summary are original characters; their look belongs to the visual team.

### 6.6 What it changes in the system

| Part | Change | Compatible with v1? |
|---|---|---|
| contract | `Level.briefing: [ {speaker, text} ]` (optional); `LearningPackage.board: {nodes[{id, label, unitId}], edges[{from, to, label}]}` (optional); new item `crossexam {speaker, statements[{id, text, flaw?: {chunkId, why}}]}` | yes — optional fields + new mechanic type |
| engine | `CrossExamGrader`; `StakesPolicy` (credibility) in the session; board progress derived from results | additive |
| app | briefing scene, cross-exam renderer, board screen, credibility pips, reaction lines in the skin | additive (new renderers + screens) |
| server compiler | plan step also emits the board graph and 1 cross-exam per level (statements built from the level's own claims, flaws must cite a chunk) | prompt + validator rules |

### 6.7 Priority inside the remaining time

| P | Item | Why first |
|---|---|---|
| P0 | Cross-examination + credibility | the signature "this is a game" moment and the product thesis in one mechanic |
| P0 | Evidence board (earned mind map) | progression you can see; strongest demo shot; answers "NotebookLM already does mind maps" |
| P1 | Briefing + partner reactions | story with almost no engineering; needs the partner art |
| P1 | Motion / haptics / combo stamp | game feel |
| P2 | Order mechanic, daily-clue widget | nice, not decisive |
