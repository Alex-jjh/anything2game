# Game design

**Scope**: what the player does, which parts are fixed vs variable, and how play adapts. Does not cover visuals (→ `docs/design/`) or how packages are generated (→ ARCHITECTURE §4).

## 1. The answer to "fixed mode or templates?" — three layers, each with a different owner

| Layer | Fixed or variable | Who decides | Example |
|---|---|---|---|
| **Frame** — the meta-game | **Fixed.** One structure for every material | product | a material = a *case file*; it has 3–6 levels; each level ends with a recap; misses come back as a *boss* review |
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
