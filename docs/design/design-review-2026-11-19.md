# Design review — game templates and look (2026-11-19)

**Scope**: what the current screens get wrong when filled with **real** pipeline output, measured against the research, and what to change before filming. Inputs: [mobile-game-feel](research/mobile-game-feel.md), [learning-and-narrative-games](research/learning-and-narrative-games.md), [visual-identity-and-a11y](research/visual-identity-and-a11y.md), the official *frontend-design* skill, and stress screenshots of the demo courses (`DemoStressScreenshotTest`, `android/app/build/outputs/roborazzi/stress/`). Course rule: the app's design is the team's own work — this document gives findings and options; the visual direction is chosen by the team.

## 1. Principles we will hold the design to (from the research)

| # | Principle | Source |
|---|---|---|
| D1 | **The learning act is the game act.** No points bolted on; feedback is informational ("which page proves it"), rewards are about competence ("3 of 3 overclaims caught"). | narrative §1 (1, 2) |
| D2 | **Instant, layered feedback**: visual within ~100 ms of touch-down; one haptic per meaningful event (`GestureThresholdActivate` at swipe commit, `Confirm` correct, `Reject` real failure, `SegmentTick` bin hover); sound off by default. | game-feel §1, rules 1–3 |
| D3 | **Spend motion once per level**: standard motion for routine actions, one expressive moment ("case closed"); reduced motion respected. | game-feel rules 4, 5, 10 |
| D4 | **Thumb first**: primary actions in the lower part of the screen, ≥ 48 dp targets, swipe away from system-gesture edges, 200 % font scale works. | game-feel rule 6 |
| D5 | **Every gesture has a button and an accessibility action**; never right/wrong by colour alone (icon + word + lightness). | game-feel rule 7; identity §3 |
| D6 | **Fewer, better clues; partial-correctness feedback; any valid evidence accepted** (no "only this exact item" trial-and-error). | narrative rules 3–5 |
| D7 | **Dialogue short, tap-to-advance, skippable, with history**; adult, dry tone, never cute or guilt-tripping. | narrative rules 7, 8 |
| D8 | **Recap = what you missed + the page that proves it + when it comes back** (spaced review), no streak guilt. | narrative rules 6, 9 |
| D9 | **A point of view, not the default stack**: one repeated device (e.g. evidence tag / stamp), restricted palette, type with a job; pass AA contrast. | identity §1, frontend-design skill |
| D10 | **Content fits the phone**: the generator must write to length budgets — the UI cannot fix a 40-word card. | stress screenshots (below) |

## 2. Audit of the current screens with real content

| Screen | Finding (real demo content) | Violates | Severity | Fix |
|---|---|---|---|---|
| All | Cream `#F6F1E7` + serif headings = the most common "AI-generated" look | D9 | high (first impression in the video) | choose a direction (§3) |
| All | Contrast fails: amber evidence text 3.23:1, green correct 4.48:1, outlines 1.36:1; correct vs wrong almost the same lightness | D5, D9 | high | new token set (all three directions pass) |
| Scenes (Companion, Rookie, Case intro) | **Cast colours reuse semantic colours**: Aya's bubble is green = reads as "correct"; the Summary is red = reads as "wrong" before you've done anything | D5 | high | cast identity = avatar shape + name; colour only a neutral tint, never the correct/wrong pair |
| Scenes | All lines at once mid-screen; half the screen empty; *Continue* in the middle; avatars are initials | D4, D7 | medium | visual-novel layout: portrait area top, dialogue panel anchored bottom, tap-to-advance per line, *Continue* in the thumb zone, history on swipe-up |
| Swipe | Card upper-middle, buttons mid-screen, ~45 % empty below; no next-card peek | D2, D4 | medium | card stack centred lower, buttons at the bottom edge, next card visible behind, commit haptic at the threshold |
| Cross-exam | 4–5 long italic statements, each repeating "The Summary"; *Present evidence* scrolls off screen; the evidence picker lists **every** passage of the paper (dozens) | D4, D6 | **high** (the signature moment) | testimony layout (one speaker header, numbered statements); sticky *Present* at the bottom; **present only the evidence you collected in this level** (the board's pins) — intrinsic link between clues and the boss, and far fewer choices |
| Order (Escape lock) | Steps of 30–40 words wrap to 6 lines; up/down arrows take a third of the width | D4, D10 | medium | drag handles + long-press; length budget ≤ 14 words per step |
| Sort | Long card texts make chips wrap; bins below the fold on long sets | D4, D10 | medium | drag + tap, bins docked at the bottom, budget ≤ 10 words per card |
| Recap | Text-only list of long points; board below the fold; no moment | D3, D8 | medium | one orchestrated "case closed" (stamp lands, pins snap), then misses with page links and the review time |
| Board | Circular layout overlaps beyond ~6 nodes; real packages have 8–12 | D9 | low–medium | per-level board (3–5 pins), whole-case board on its own screen |
| Header | Hint, hearts and "x of y" compete for one row | D4 | low | hearts with the level title; hint next to the question |

## 3. Visual direction — three options for the team (details and contrast numbers in the identity research)

| Direction | Device | Feel | Pros | Cons |
|---|---|---|---|---|
| **Lab Tag** (cool paper, cobalt action, UV-lime evidence highlighter, mono exhibit labels) | evidence tag "证物 A-07" | forensic lab, clinical-playful | best contrast, Latin fonts only, clearly not cream | can feel like a dev tool without warmth |
| **Night Desk** (dark-first, lamp-lit evidence) | lit passage under a lamp | late-night case reading, immersive | most atmospheric, good for evening commute | closest to the old warm palette; weakest colour-blind separation |
| **Cyanotype** (one ink blue, vermilion stamp, yellow evidence) | rubber stamp verdict "已核实 / 夸大 / 驳回" | blueprint + stamp, bold | strongest identity, best colour-blind separation, smallest palette | needs a bold Han font subset; brutalist risk if the stamp isn't used consistently |

Whichever is chosen: the same device appears in four places (verdict, *Show evidence*, cast name tag, loading), bilingual by design (a Han character as a graphic element), light + dark, AA contrast.

## 4. Content length budgets (generation side, D10)

The UI cannot rescue wordy items. Add to the level/frame prompts and to the validator as **warnings** (scored in the eval, not blocking): swipe statement ≤ 20 words / 40 Han characters; sort card ≤ 10 words / 20 Han characters; order step ≤ 14 words / 28 Han characters; deduce option ≤ 18 words; cross-exam statement ≤ 22 words; dialogue line ≤ 25 words; recap point ≤ 20 words.

## 5. Action list before filming (ranked by what the camera sees)

| P | Change | Type | Owner |
|---|---|---|---|
| P0 | Pick the visual direction (§3) and the character look (partner, Summary, rookie, companion) | **team decision** | Alex + design classmate |
| P0 | Cross-exam: testimony layout, sticky *Present*, evidence = this level's collected pins | interaction | Android |
| P0 | Scenes: visual-novel layout, tap-to-advance, cast colours separated from right/wrong | interaction | Android |
| P0 | Length budgets in prompts + validator warnings; regenerate demo courses | generation | server |
| P1 | Swipe stack lower + peek + threshold haptic; Sort drag; Order drag handles | interaction | Android |
| P1 | "Case closed" moment (one expressive animation) + recap with page links | interaction/motion | Android |
| P1 | Contrast/colour-blind pass, 200 % font scale pass, TalkBack actions | accessibility | Android |
| P2 | Per-level board, history/backlog for dialogue, sound toggle | interaction | Android |
