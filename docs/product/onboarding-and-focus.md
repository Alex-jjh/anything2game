# How a course starts: explain first or play first, the reading map and focus choice (2026-11-19)

**Scope**: what the player sees before the first question, and how the opening adapts to what a reader cares about. Answers Q30–Q32 in the [question log](question-log.md). This is input for the team's design decision. Under the CSI301 rules the app design, the reports and the demo are the team's own work. The technical parts (contract, engine, generation) are code and may use AI.

## 1. Today's flow (Case frame)

```
Briefing (≈10 s)   partner sets the stake and names the suspect; gives a reason to doubt, teaches nothing
   ↓
Clues ×3–5         20–40 s each, hands-on at once (swipe / sort / order / deduce)
   ↓ after each answer
Explanation        verdict stamp + why + "Show evidence" (jumps to the page)
   ↓
Cross-examination  use the evidence collected in this level to catch the Summary's overclaim
   ↓
Case closed        score → what comes back tomorrow → key points
   ↓
Next day           misses return through the review reminder
```

Today the **explanation follows the answer**. The Companion frame is the exception: the partner introduces each unit in two or three cited lines before the question. During a question the source cannot be opened yet. "Show evidence" is only offered after answering, or on cited scene lines.

**Why question first**:
- **Learning science.** Retrieval beats re-reading (the testing effect). Even an unsuccessful attempt before the answer is shown improves learning (the pretesting effect).
- **Positioning.** An explanation first is a summary to re-read, which is the thing we argue against.
- **Fragmented time.** The player is acting within 10 seconds and gets a complete small win inside one metro stop.

## 2. How common games and apps order "teach" and "test"

| Product | Flow | When it teaches | What we take from it |
|---|---|---|---|
| Duolingo | a new item is shown once, then tested at once; mistakes come back | almost while testing | short units, mistakes return |
| Detective games (investigate → trial genre) | investigate and collect evidence, then cross-examine and present evidence | investigating *is* learning; the trial tests it | clues become ammunition for the confrontation (a genre structure, not a specific game) |
| Escape / puzzle games | no instructions; the first puzzle teaches the mechanic, later ones combine | the level teaches the mechanic, not text | the first card is a tutorial card |
| Brilliant | interactive problem first, explanation after | after doing | the same as our flow |
| Paper2Galgame | a character explains the paper, quizzes in between | explain first, test second | we deliberately invert it |
| Kahoot / Quizlet | test only, no story | never | — |

Good games separate **teaching the mechanic** from **teaching the content**:
- **Mechanic**: taught by doing, in a forgiving first level. No manual.
- **Content**: discovered by the player. The explanation confirms or rewards the discovery.

## 3. Team feedback: questions at the very start feel like pressure

The concern is valid, especially for hard papers. The pressure has two sources, and they need different fixes:

| Source | Symptom | Fix |
|---|---|---|
| Not knowing the content (cognitive) | hard paper, not read yet, answering feels like guessing | a **reading map** before any question: which questions this document answers, on which pages |
| Fear of being judged (emotional) | losing hearts on the first screen, fear of failing | the first level is a **warm-up**: no hearts lost, open book, difficulty rises afterwards |

**Guard against over-correcting.** If the map becomes "read a summary first", the product turns back into "the AI reads for you". So the map shows **questions, not answers**. For example:

- "What problem is this paper trying to solve?" (pp. 1–2)
- "How is the method different from earlier work?" (pp. 3–5)
- "Do the experiments really show what it claims?" (pp. 6–9)

This is an advance organiser, or a set of pre-questions. It tells the reader what to look for, which lowers disorientation while keeping the benefit of finding answers themselves.

## 4. Proposal: different readers care about different parts

A seminar presenter cares about the method, an exam reader about the conclusions, someone replicating the work about the setup and its limits. The proposal: start with a fast preprocessing step (a flash-tier model), then generate according to the reader's focus.

### 4.1 Value

- **Relevance, and so motivation.**
- **Smaller scope, less overwhelm.** The reader plays the two or three parts they chose, more deeply.
- **The first act is a choice, not a test.** This directly lowers the pressure from §3.
- **Context awareness and personalisation.** This matches what the individual report asks for (extensions in the light of context awareness).

### 4.2 Risks if we *regenerate per focus*

| Risk | Why |
|---|---|
| Cost multiplies | every focus combination is another compile; "compile once, the class plays" and content-hash dedupe stop working |
| Presets break | preset packs are compiled ahead of time and cannot be regenerated per player |
| Trust drops | a quick flash-tier pass is not checked by our grounding (quote checks, support gate); the eval showed such content carries errors |
| Tunnel vision | pre-question research also suggests readers learn the targeted parts better and the untargeted parts worse |

### 4.3 Recommended design: generate once, tag, select at play time

```
import → plan step (exists, fast) → reading map: 3–5 question cards, each with pages and a focus tag
                                        ↓   the player picks a focus and "read it / not yet" here
            level generation (exists, slower) — every unit tagged: problem / method / results / limits
                                        ↓
            FocusPolicy orders and filters the queue: chosen facets in depth;
            at least one core unit from every other facet (no tunnel vision)
```

- **The flash model has one job**: for a newly imported document, turn the plan output into the map quickly. Preset courses need no model call at all.
- **The map screen is also the loading screen.** For a live import, the player makes their choices while the levels are still being generated, which hides most of the wait (level 1 took ~110 s in the eval).
- **Decoupled and additive**:
  - Contract: optional `facet` on units and an optional `map` on the package. v1 readers still work, and the pack format (ADR-017) is unchanged.
  - Engine: a pure-Kotlin `FocusPolicy` beside the existing `ContextPolicy`.
  - Server: the plan and level prompts emit facet tags, and the validator checks them.
- **Facets depend on the material**: papers use problem / method / results / limitations. Lecture slides use concepts / processes / examples. The plan step proposes the facets for each document.

## 5. The new opening (one screen, a few seconds)

1. **Reading map (case map)**: 3–5 question cards, each with pages and estimated minutes.
2. **"What do you want to investigate?"**: pick one or more focuses, or all.
3. **"Have you read it?"**:
   - Yes → Case frame, harder start.
   - Not yet → Companion frame (short cited lead-ins), open book allowed.
4. **Warm-up level**: no hearts lost; the first card teaches the gesture with an obvious statement and a hand animation. Then the real case starts.

Two supporting changes:
- **Open book during a question**: the player may open the relevant passage before answering. The unit then no longer counts as "right on the first try". The game becomes "solve the case with the file in hand" rather than a closed-book exam.
- **Keep explanations short and after the answer**: one line of *why* plus the page. Longer takeaways belong in the recap.

## 6. Work and plan

| Work | Size | Notes |
|---|---|---|
| Contract: optional `facet` and `map` fields + validators | S | backward compatible |
| Plan/level prompts emit facets; regenerate the 5 demo courses | M | combine with the length budgets (design-review §4): one regeneration for both |
| Engine `FocusPolicy` (order, filter, minimum coverage) + tests | S | pure Kotlin |
| App: map screen, focus / "read it?" choice, warm-up level, open-book | M | **good work package for a teammate**: it gives them a part of the code to own and explain in the video |
| Eval: facet-tag accuracy; coverage must not drop | S | existing harness |

About 2–3 days in total, in the week of 11-20 – 11-26.

## 7. For the team to decide

1. Adopt the reading map + focus choice? Recommended: yes.
2. Regenerate per focus, or generate once and tag? Recommended: generate once and tag (§4.3).
3. Ask "Have you read it?", and does the answer pick the frame?
4. Allow open book during a question, and what does it cost the player?
5. Who owns the map screen (UI design and code)?
