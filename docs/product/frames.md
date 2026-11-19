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

Practice shows **Play as** on every course card. It offers only the frames generated for that course, ordered by reader state: Classic → Case → Rookie → Escape → Companion. Under the chips it shows *what you do* and *when it fits*. Classic stays the default, an open session resumes in its own frame, and the choice is remembered per course and per profile.
