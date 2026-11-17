# Why would anyone play this? — user motivation, friction, and eight game concepts

**Scope**: PM reasoning *before* game design. Answers: what job the user hires us for, which friction to remove and which to keep, and which game concept fits best. Feeds the choice in [game-design.md](game-design.md) §6. Does not cover visuals or implementation.
**Status**: proposal for the team; claims about users are hypotheses until the preference test in §6.

## 1. Start from the user, not from "a game"

Nobody wakes up wanting to *learn a paper through a game*. Lin wants something else, and the game is only worth it if it gets her there faster or with less pain.

| Moment | What Lin feels | The job she would hire us for |
|---|---|---|
| The PDF lands in the group chat | dread, guilt ("18 pages…") | **get started without facing a wall of text** |
| Metro, queue, between classes (5–25 min) | bored, scrolling anyway | **make this dead time count, in pieces** |
| The night before the seminar / exam | anxiety ("do I actually get it?") | **tell me honestly whether I'm ready** |
| In the seminar | fear of being asked | **be able to say it in my own words, with the page** |
| A week later | forgetting | **don't let it fade** |

Two consequences:

1. **The trigger is external** (an assignment, an exam, a boss). We don't need to invent a reason to learn — we need to be the *least painful path* through a reason she already has.
2. **Our real competitors are not other games.** In the deadline moment it's *skimming + an AI summary*; in the commute moment it's *WeChat and short video*. So we must be (a) faster to real confidence than skim+summary, and (b) as easy to pick up for 30 seconds as a feed.

## 2. Friction: remove it or add it? Both — in different places

"Learning has friction" mixes four different things. Treating them the same is how products end up either boring (all friction kept) or useless (all friction removed — the AI summary).

| Friction | Example | Decision | Why |
|---|---|---|---|
| **Access** — getting started and staying in | finding the file, uploading, choosing what to study, reading instructions, losing your place after an interruption | **Remove, aggressively.** Share → first clue in < 60 s; no settings; resume on the exact card | pure cost; it is where people quit before they start |
| **Reading** — volume of text on a small screen | 18 pages, dense paragraphs | **Remove from the main path**, keep one tap away (Show evidence) | the phone is bad at long reading; the source is still there when you need it |
| **Thinking** — retrieving, discriminating, applying | "is this claim actually supported?", "which bin does this go in?" | **Keep, and increase on purpose** | this *is* the learning. Effortful retrieval beats re-reading (Roediger & Karpicke 2006); "desirable difficulties" (Bjork 1994). Removing it is what summaries do — and why they create an illusion of understanding |
| **Emotional** — fear of failing, feeling stupid | red crosses, public scores | **Cushion it**: mistakes become clues, retries are normal, scores stay private | fear makes people avoid the hard part we want them to do |

**One-line principle**: *remove friction around the thinking, add friction inside the thinking — and make that friction feel like a puzzle you chose, not a form you must fill.*

The game is the device that makes good friction feel voluntary. That gives the test for every concept below: **does the game's challenge *equal* the thinking we want, or does it sit on top of it?** (intrinsic integration — Habgood & Ainsworth 2011).

## 3. Why would she open it again? (motivation model)

| Driver | What it means here | Design consequence |
|---|---|---|
| Competence (self-determination theory) | "I beat it, I can explain it" | real challenge with clear wins; honest "still shaky" instead of fake 100 % |
| Autonomy | her material, her pace | any source in; she decides when to stop; no forced timer |
| Relatedness | others, characters | a partner who reacts; optional "challenge a classmate" on the same paper |
| Curiosity | "what's behind the next card?" | locked board nodes, a suspect to catch |
| External trigger → action → reward → investment | deadline → one tap → a case solved → *her* board of the paper | the board/notes belong to her and grow with each document |

## 4. Eight concepts

Each concept is the same content (units with knowledge types and citations) presented through a different game. Scores 1–5, higher is better. "Build" = feasible for 4–6 students by 2026-12-06 on top of the current engine.

| # | Concept | One-line pitch | Learning = gameplay? | Mobile / interruptible | Works for *any* material | Build | Ad "wow" | Different from P2G / NotebookLM / Duolingo |
|---|---|---|---|---|---|---|---|---|
| A | **Path & streaks** (current baseline) | Duolingo for your own PDF | 3 — quiz under a game skin | 5 | 5 | 5 | 2 | 2 — looks like Duolingo |
| B | **Companion visual novel** | a character explains the paper to you | 2 — you mostly read/click | 2 — long text, landscape | 4 | 2 — art-heavy | 4 | 1 — it *is* Paper2Galgame |
| C | **Detective case** | catch the AI summary lying, using the page | 5 — judging claims against evidence is the skill | 4 | 4 — every material has claims and limits | 4 | 5 | 5 |
| D | **Roguelite card battler** | concepts are cards; fight misconception monsters | 3 — strong game, mapping per material is fragile | 3 | 2 — generated balance breaks easily | 1 | 4 | 4 |
| E | **Class duel** (async PvP) | same paper, you vs a classmate | 3 | 4 | 4 | 2 — accounts, matching, cheating | 4 | 4 |
| F | **Arcade speed run** | timed rapid-fire taps | 2 — rewards recognition and guessing | 2 — timers punish interruption | 5 | 4 | 3 | 2 — Kahoot / Quizlet |
| G | **Escape room** | solve clues to unlock the next section | 4 | 4 | 4 | 3 | 4 | 4 |
| H | **Teach the rookie** (protégé) | a confused junior character gets it wrong; you correct and explain | 5 — explaining/correcting is deep processing (learning-by-teaching) | 3 — more reading per step | 3 — needs good wrong-explanations | 3 | 3 | 4 |

Totals (unweighted, out of 30): **C 27**, G 23, A 22, E 21, H 21, F 18, D 17, B 15.

What each concept teaches us even if not chosen:
- **A** keeps the *habit* tools (daily clue, gentle streak) — use as the outer shell, not the core.
- **B** shows *character* is a real draw — keep a partner, but not as a lecturer.
- **D/G** show the pull of *progression you can see* — that is the evidence board / locked nodes.
- **E** is the growth engine ("compile once, the class plays") — later, as "challenge a classmate".
- **F** is a warning: speed and timers reward guessing and break on the metro.
- **H** is the strongest learning move — and it fits *inside* C: correcting the Summary is teaching it.

## 5. Recommendation

**Concept C (detective case) as the core, borrowing G's locked progression, H's "correct the character" move and A's habit shell. Not B, D, F.**

- The friction we keep (judging a claim against the evidence) *is* the game's challenge; the friction we remove (reading, setup, resuming) is invisible.
- It turns the product thesis into the plot (AI summaries overclaim; you catch them), which is the ad.
- Two modes by *moment*, not by game style:
  - **Quick clue** (30 s, from the widget/notification) — competes with the feed.
  - **Case** (3–5 min level ending in a cross-examination) — the commute session and the "am I ready?" check before the seminar.
- Tone: a challenge for adults (puzzle/detective), not cartoon rewards — university students drop products that feel patronising.

## 6. Assumptions to test before building more (cheap, this week)

| Assumption | Test | Pass if |
|---|---|---|
| Students would open a *game* for assigned reading at all | 5 classmates, 3 one-screen mock-ups (A path, B companion, C case): "which would you open on the metro for Thursday's paper?" + why | ≥ 3 of 5 pick C, or the reasons point to a clear better option |
| Cross-examination is fun, not homework | paper prototype: read 4 printed "Summary" statements + 1 page; find the overclaim | testers smile/compete; finish in < 90 s |
| The board feels like "mine" | after a level, show the filled board; ask what they'd use it for | they mention review/explaining it to someone |
| Quick clue is a real use case | ask when they'd tap a 30 s clue | concrete moments named (queue, lift, before class) |

If the test says otherwise, we change the frame — the engine, contract and mechanics stay (game-design.md §1: frame and skin are swappable layers).

## 7. The concepts are templates — at the *frame* layer

All eight concepts use the same content (units with knowledge type, items, citations) and the same mechanics. What differs is the **frame**: how units are ordered, what wraps them (story, map, opponent), what is at stake, and how progress is shown. So each concept is a frame template, and the architecture can treat it that way:

| Layer | Who picks | Swappable? | Examples |
|---|---|---|---|
| Content | compiler (from the material) | per document | units, citations, board graph |
| Mechanic | compiler, by knowledge type | closed list, extend by adding one | Swipe, Sort, Deduce, Order, Cross-exam |
| **Frame** | **default by product; user may switch in one setting** | **yes — a `GameFrame` strategy** | Case (C), Classic path (A), Escape room (G), Rookie (H), Companion (B) |
| Skin | visual team | yes | case-file paper, plain study, anime partner art |

Why let the user switch frames but never mechanics:
- **Autonomy** (a core motivator, §3): some students want story, some want a clean path. A one-time choice costs them nothing per session.
- **Mechanics must follow knowledge**, or the game stops being the thinking (§2). A user-picked mechanic would let a "process" be played as true/false.
- **Each frame is expensive** in writing, art and testing. So we *ship two* and design for more:
  - **Case** (default, the full experience) and **Classic** (the current baseline, already built — almost free).
  - B, G, H stay documented as future frames; D, E, F are not frames we plan (§4).
- **It makes the study possible**: Classic vs Case on the same package is a clean comparison of *frame* with content, mechanics and time held equal (Clark et al. 2016 — compare designs, not "game vs no game"). This can be the team's 2×2 if a second factor is added (e.g. evidence button on/off).

What a frame may and may not do (contract for `GameFrame`):

| May | May not |
|---|---|
| order and group units into rooms/steps; add a boss unit (e.g. cross-exam) | change an item, its answer or its citation |
| add narrative lines from the package (`briefing`) or the skin (reactions) | grade answers (engine graders do) |
| define stakes (credibility pips, none) and progress visuals (board, path) | add timers that punish interruption |
| choose which recap view to show | hide "still shaky" or claim mastery |

## 8. Why would someone be convinced to try it — and to keep playing?

### 8.1 First contact (acquisition)

| Lever | How it convinces | Where it shows up |
|---|---|---|
| **The moment of need** | we appear exactly when the PDF arrives: *Share → Make it a game*. No app search, no account | share-sheet entry, first-run screen |
| **A sharp promise against a known pain** | "Not another summary. Find out if you actually get it." — names the illusion every student has felt | ad, store text, first screen |
| **A villain everyone recognises** | "the AI summary is confidently wrong" — curiosity + a little indignation | the Summary character, cross-exam |
| **Proof in the first minute** | the first level ends by catching an overclaim *with the page* — the user sees value before investing | level 1 design (target: first win < 3 min) |
| **Trust signals** | every answer has evidence; samples are labelled "pre-generated"; what is sent to AI is said up front | evidence panel, badges, consent text |
| **Social proof / invitation** | a classmate shares a solved case on the same paper ("compile once, the class plays") | later: share a package link |
| **Low commitment** | free, no account, 3 minutes, works offline | onboarding |

### 8.2 Coming back (retention)

| Loop step | Ours |
|---|---|
| Trigger — external | a deadline, the seminar tomorrow, a new PDF in the chat |
| Trigger — internal | "do I really get it?" anxiety; boredom on the metro |
| Action — as small as possible | one tap from the widget/notification into a 30-second Quick clue |
| Reward — variable, informational | a case solved, the Summary caught, a board node revealed (not points for their own sake) |
| Investment — it becomes *hers* | the board / case notes of every paper she has played; the history of what was shaky and is now solid |

### 8.3 The pitch in one breath (for the ad)

*You already have to read it. Lin used to skim and ask an AI — and still froze in the seminar. Now she shares the PDF, and on the metro she plays the case: the AI summary makes claims, she catches the ones the paper doesn't support, page in hand. By Thursday she doesn't feel ready — she knows she is.*

## 9. How it affects users — intended effects, side effects, and what we do about them

| Effect | Intended or risk | Mechanism | What we do / measure |
|---|---|---|---|
| Better retention of assigned reading | intended | retrieval practice, spaced boss review | delayed check vs reading a summary (small, descriptive) |
| Calibrated confidence ("I know what I don't know") | intended | honest recap, "still shaky" | ask testers to predict their score, compare |
| Habit of checking claims against sources (AI literacy) | intended | cross-exam, Show evidence | count evidence opens; interview |
| Dead time becomes useful | intended | Quick clue, offline, resume | session count/length in the trial |
| **Over-reliance on generated questions** — learning only what the AI chose to ask | risk | the compiler may miss what the lecturer cares about | always one tap to the source; recap says "this covered X of the paper"; report-an-item |
| **Wrong items teach wrong facts** | risk | generation errors | validation gates, evidence on every item, flagged items excluded from demos |
| **Rewards crowd out interest** (overjustification; Deci, Koestner & Ryan 1999) | risk | points/badges as the goal | rewards are informational or cosmetic; no leaderboards by default |
| **Anxiety from stakes** | risk | credibility loss, failure screens | no timers, private results, a failed case is just replayed with variants |
| **Phone use while walking** | risk | commute setting | auto-pause on walking (context policy) |
| **Privacy of uploaded material** | risk | text sent to an AI provider | consent, OCR on device, delete-my-data, no public sharing of originals |
| Accessibility / language | risk | small screens, non-native readers | bilingual UI and content, large targets, buttons for every gesture |
