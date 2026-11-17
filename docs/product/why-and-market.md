# Why build it · for whom · against whom

**Scope**: PM view. Answers "is it worth building and why would it win". Source material for the ad (Purpose / Target users / Related work) and the report introduction. Does not cover implementation (see ARCHITECTURE).
**Marker**: `[verify]` = from prior knowledge, not yet checked with web search. Check before it goes into the video or report.

## 1. One line

> Anything you have to read, playable in five minutes — and every answer points back to the page.

## 2. The problem, through one person

**Lin**, third-year undergrad. Before Thursday's seminar she must read an 18-page paper her supervisor dropped into the group chat. Her real free time Monday–Wednesday: 25-minute metro rides ×2, queues, gaps between classes. She has her phone every time, but:

- Reading a long PDF on a phone is painful — zooming, drifting attention, forgotten by evening.
- Asking an AI for a summary feels like learning, but it is just re-reading something shorter. Asked a detail in the seminar, she still can't answer.
- Summaries make things up, and she can't tell which sentence to trust.

Generalised:

| Pain | General problem | Evidence direction |
|---|---|---|
| Lots of fragmented time, but fragments can't hold a long read | Mobile attention is fragmented; tasks must survive interruption | Oulasvirta et al. 2005 (4-second bursts) |
| A summary ≈ re-reading; poor retention | Retrieval practice beats re-reading | Roediger & Karpicke 2006; Dunlosky et al. 2013 |
| AI invents things | Generated content must be checkable | — |

**Insight**: most AI study tools help you *read faster*. Learning science says you need to be *tested*. We don't let AI read for you — we let AI turn the material into **levels you have to think to clear**.

## 3. Target users

| Ring | Who | Moment | Why this order |
|---|---|---|---|
| **Hero (MVP, video)** | University students with assigned reading (papers, slides, chapters) | commute, between classes, before exams | we are the users; we can recruit 5 testers |
| Second ring | Certification learners (AWS, CPA, teaching licence…) | after-work commute | high willingness to pay `[verify]` |
| Third ring (startup story only) | Company onboarding & compliance reading | docs employees *must* read | B2B revenue: "compile once, the whole team plays" |

**Not for**: young children; high-stakes exam grading.

## 4. Why now

1. LLM structured output (JSON schema) is reliable enough to *compile* a document into levels with answers, misconception feedback and citations — not just a summary.
2. Flash-tier models are cheap and fast enough to adapt *during* play, so the big model runs once per document.
3. On-device capabilities are free: ML Kit OCR, activity recognition, WorkManager, home-screen widgets. "Snap a page and play" and "pause when you start walking" cost nothing extra.

## 5. Related work `[verify the whole table]`

| Product | What it does | Strength | Our opening |
|---|---|---|---|
| **Paper2Galgame** (Steam, 2026-08-09, "Very Positive", 98 reviews when checked 2026-11-17) | PDF/TXT/DOCX → 5–10 min visual novel: an anime companion explains the paper line by line, with quizzes, voice narration, a formula blackboard, character customisation (Workshop), co-op, paper discovery | **Closest competitor**; proves people want "paper → game"; strong character appeal | PC, landscape, sit-down sessions; the game is a *lecture you click through* — knowledge is narrated, quizzes are bolted on; no evidence trail. We: phone-first fragments, the *player* does the reasoning, mechanics follow knowledge type, every answer is checkable |
| Google NotebookLM | Upload sources → summaries, audio overviews (podcast), mind maps, reports/blog-style write-ups, quizzes/flashcards, with citations | **Strong citations**; Android app; formats made for fragmented time (listen on a commute) | Formats are *consumed*, not played; the mind map is handed to you. We: you **earn** the map by solving the case |
| Quizlet | Flashcards; AI builds study sets from notes; Match-style mini-games | Huge user base; has games | Games are a generic shell over term–definition pairs; ignore knowledge type; weak sourcing |
| Kahoot! / Quizizz (Wayground) | AI quiz from a PDF; live classroom competition | Classroom energy, teacher ecosystem | Teacher-run sessions, not one person anytime |
| Gizmo / Knowt / StudyFetch etc. | "Turn anything into flashcards/quizzes" + spaced repetition | Closest to our "anything" | Still cards and question banks; little use of phone context |
| Duolingo | The benchmark for gamified mobile learning: streaks, levels, nudges | Habit, mobile-first | Fixed curriculum — not your own material |
| Anki | Spaced repetition | Most faithful to learning science | You build cards yourself; steep; not fun |

**Positioning map**:

```
              Play: mechanics driven by the content
                         ▲
        Duolingo         │        ★ Anything2Game
   (fixed course, game)  │   (your material × mechanic per knowledge type)
 Fixed course ───────────┼─────────────► Your own material
        textbook apps    │   Quizlet · Gizmo · NotebookLM · Kahoot AI
                         │   (your material → cards / multiple choice)
                         ▼
              Play: cards / quizzes
```

**Two neighbours to define ourselves against** (the ad should name the difference in one line each):
- *Paper2Galgame turns a paper into a story you watch. We turn it into a case you solve.*
- *NotebookLM gives you a mind map. We make you build it.*

**Honest line**: "AI makes a quiz from a PDF" and "answers with citations" are each not new. The new thing is the combination: **any source in × knowledge type picks the mechanic × every item traceable × tuned to the phone's context**.

## 6. Three pillars — each one phone-specific

| Pillar | What the user sees | Android capability |
|---|---|---|
| ① Anything in | Tap *Share* in WeChat / browser / Files and it lands here; snap a book page and it lands here | Share-sheet intent filter, Storage Access Framework, CameraX + ML Kit **on-device** OCR |
| ② Knowledge picks the mechanic | Definitions → sort into bins; processes → put steps in order; claims → swipe true/false; reasoning → infer from evidence | Native gestures (swipe, drag, long-press) + haptics |
| ③ Knows where you are | One-hand mode on the metro; auto-pause when walking; works offline; tonight the widget brings back yesterday's miss | Activity Recognition, ConnectivityManager, WorkManager, App Widget, notifications |

The trust layer across all three: **every item has "Show evidence"**, jumping to the page and passage it came from.

## 7. Business model (story only, not built)

- **Freemium**: cost sits almost entirely in *compiling* a document (one big-model pipeline); playing and reviewing are near-free → charge by compiles per month.
- **Compile once, the class plays**: the same document (identified by content hash) compiles once; packages can be shared with classmates → viral growth + amortised cost. Legal line: share generated items and short quotes, never the original file; teacher/company-owned material first.
- **B2B**: onboarding, compliance, certification providers — "proof that staff actually read it".

## 8. Success metrics

| Metric | Definition | How the MVP measures it |
|---|---|---|
| Activation | Share of imports whose first level is finished within 3 minutes | timed in the usability test |
| Return | Comes back next day via widget/notification | small trial, descriptive only |
| Learning | Next-day quiz: played vs read an AI summary | optional mini-study; small n = feasibility only |
| Trust | After "Show evidence", user agrees the passage supports the item | asked during the test |

## 9. Risks

| Risk | Response |
|---|---|
| Wrong generated items | Compile-time validation (schema + quote found in source + second-pass check); every item has evidence; "report this item" |
| Copyright / privacy of uploaded papers | Consent notice before upload ("sent to an external AI"); never publish originals; "delete my material"; image OCR on device |
| Looking at the phone while walking | Auto-pause on walking — the risk becomes a feature |
| Cost blow-up | Dedupe compiles by content hash; flash calls capped, with timeout fallback |
