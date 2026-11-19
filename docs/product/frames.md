# What really differs between the game frames (2026-11-20)

**Scope**: an honest comparison of Classic, Case, Escape, Rookie and Companion: what is identical, what differs, and what each is *for*. It is the basis of the "Play as" choice in Practice. This is input for the team's decision (PRODUCT.md D1). How frames work technically is covered in `contract/.../Frame.kt` and `FrameSpec`.

## 1. What is the same in every frame

By design (ADR in `Frame.kt`), a frame **never changes an item, its answer or its citation**. The 3–5 clues in the middle of a level are the same questions, graded the same way, in every frame. Mastery and spaced review are shared too: a unit you got right in Case counts in Classic.

That keeps the content honest and costs nothing extra to generate. It also means that **if a frame only adds a story, it is a skin, not a different game.**

## 2. What actually differs (from `FrameSpec` and the overlays)

| | Classic | Case | Rookie | Escape | Companion |
|---|---|---|---|---|---|
| Story around the clues | none | briefing + outro | intro + outro | intro + outro | intro + outro |
| Before each clue | — | — | the rookie **asks a question** | — | a partner **explains in 2–3 cited lines** |
| Grouping | in order | in order, clues pinned to an evidence board | in order | **rooms** = parts of the reading | in order |
| Stakes | none | credibility 3 (fail = case goes cold) | none | credibility 3 | none |
| Final challenge | — | **cross-examine the AI Summary**: pick the overclaim, present evidence | **correct the rookie's notes**: find the misconception | **lock**: order the steps / deduce how the parts connect | — |
| What you do with the knowledge | **recall** | **evaluate** claims against the source | **explain / correct** someone else | **structure**: how parts connect | **meet it for the first time** |
| Best when | quick review, two minutes | you have read it and want to check | you know it (teaching is the deepest test) | the reading is a method or process | you have not read it yet |
| Learning-science hook | retrieval practice | evidence-based reasoning; error detection | protégé effect (learning by teaching) | chunking; causal/sequence knowledge | pre-training / worked examples |

So the essential difference is the **cognitive act** of the final challenge and of what comes before each clue, matched to **the reader's state**. The story only motivates that act.

## 2b. Why a player would find each one fun (user view, hypotheses to test)

Assigned reading is a chore with a deadline. A frame earns its place only if it turns that chore into a small want. Each "want" below is a hypothesis for the 5-person test (§6), not a fact.

| Frame | The moment players remember | Why it pulls | What would kill it |
|---|---|---|---|
| Classic | "Done in two minutes, 3 of 4." | **Competence with no ceremony**: a clear small win in a dead moment (queue, metro) | feeling like homework when it shows up too often |
| Case | Holding to object, then **OVERCLAIM** stamped on the AI's sentence, with the page that proves it | **A villain who is a summary**: catching a confident machine out is satisfying and feels clever. The earlier clues pay off as ammunition (Ace-Attorney-style genre payoff). The player keeps **agency**: they choose which sentence to doubt | an overclaim that is too obvious, or one that is unfair (the evidence was never collected) |
| Rookie | "Sam got it wrong and *I* knew why." | **Being the expert** (protégé effect): explaining feels like status, not a test. It is relatedness with a character who needs you | a rookie who is patronising or whose mistakes are silly |
| Escape | The door opening after the lock | **Progress you can see** (room by room) and a goal at the end; good for readings that are a process | rooms that are just walls between unrelated questions (hence: Escape only for process-heavy courses) |
| Learn (tutor; replaces Companion) | "It started from what I didn't know." | **Relief**: no pressure to know it already, and it is personal, explaining only the gaps | long explanations that become a summary to re-read |

What cuts across all of them:
- **Your own character comes along.** It reacts to *your* moves (leans with the card, swells while you hold, sleeps when you walk), so the progress feels like yours.
- **Choice is the first act.** Picking how to play (autonomy) lowers the pressure the team noticed (Q31) and makes the session feel chosen.
- **Every verdict shows its page.** Trust makes winning feel earned rather than lucky.
- **Coming back is a feature, not guilt.** Tomorrow's review brings back exactly what you missed, with no streak shaming.

What we deliberately avoid: points for their own sake, timers, streak guilt, long dialogue, guessing games with no way to check.

**How we check (5-person test, PRODUCT.md §5):**
- Given the choice, does the player pick a non-Classic frame a second time?
- Do they finish the level, and do they replay it?
- Can they name the moment they liked?
- Would they tell a classmate?
- For Rookie: do players say the teach-back felt different from the clue before it?

## 3. Where the frames are still thin

1. **The middle clues do not change.** In Case, Escape and Rookie, most of the play time is the same clue sequence. Only the beginning and the end differ.
2. **Escape's rooms only regroup.** Unless the document is a process, the rooms feel like Classic with walls.
3. **Companion overlaps the new Learn tab.** The tutor (diagnose → explain gaps with the page) does Companion's job better.

## 4. Recommendation (for the team)

| Frame | Keep? | Why |
|---|---|---|
| Classic | **yes, default for quick review** | fastest; the daily review loop |
| Case | **yes, the hero mode for the video** | the strongest "game" moment; matches the positioning (catch the AI summary) |
| Rookie | **yes** | a genuinely different act (teach and correct); good for exam preparation |
| Escape | **only for process-heavy documents** | offer it when a document has order/process units; otherwise it adds little |
| Companion | **fold into Learn** | the tutor covers "first pass before reading"; keep the overlay format for later |

Next steps that would make the differences go beyond the ends of a level, all additive to the contract:
- **Case**: each clue's verdict pins its evidence on the board, and the boss can only use pinned evidence (partly built).
- **Rookie**: after a correct clue, the rookie states a misconception from that unit's feedback, and you say why it is wrong (teach-back). This reuses existing misconception feedback.
- **Escape**: each room ends with a mini order/deduce built from that room's units.

## 5. What is in the app now

Practice shows **Play as** on every course card. It offers the generated frames ordered by reader state: Classic → Case → Rookie → Escape. Companion is folded into Learn. Escape appears only when a course has at least 2 process units, or process makes up at least a quarter of it (today: Attention and the zh spaced-repetition course). Under the chips it shows *what you do* and *when it fits*. Classic stays the default, an open session resumes in its own frame, and the choice is remembered per course and per profile.

## 6. In progress (2026-11-20)

- **Rookie teach-back** and **Escape room locks**: derived, read-only challenges built from the validated items, so each frame has its own act *during* the level, not only at the ends. Being built on branch `feat/derived-challenges`.
- An independent test pass will play full levels in every frame once that lands.
