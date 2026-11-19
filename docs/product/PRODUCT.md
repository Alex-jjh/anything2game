# Anything2Game — where the product stands (2026-11-20)

**Scope**: the one page to read first. What the product is today, how the parts fit, which earlier proposals still hold, the plan for the video, and the decisions the team still owes. Details live in the linked documents. This summary covers the teammates' Chinese design docs too. Under the CSI301 rules, the app design, the reports and the demo are the team's own work: this page records facts and options, and the team decides.

## 1. One line, and how the tutor fits the positioning

> Anything you have to read, turned into something you play — and every answer points back to the page.

The original stance was "AI does not read for you; it turns your reading into levels you must think to clear." The team's latest change adds a conversational tutor. It fits the stance only in this order:

1. **Find the gaps first.** A short diagnostic with an honest "I don't know".
2. **Explain only the gaps.** Every reply cites the page.
3. **Play to make it stick.** Retrieval inside the game frames.
4. **Bring back what was missed.** Spaced review.

This is the line that separates us from a summariser or a chat-with-PDF app (NotebookLM / Gemini Notebook): we never hand over a summary to re-read.

## 2. The product today

```
Welcome: who is playing?  ── Lin, the sample student (lived-in demo) | Guest (fresh) ──┐
                                                                                        ▼
            ┌──────────── Practice (巩固) ─────────── Learn (讲解, default) ─────────── Me (我的) ────────────┐
            │ one card per course (content pack)   │ pick PDF → confirm → server     │ nickname, character,   │
            │ levels: new / in progress / needs     │ extracts text → 3 basic          │ stats (courses,        │
            │ practice / done                       │ questions incl. "I don't know"   │ practised, due),       │
            │ new practice = Classic; old sessions  │ → starting point per concept     │ switch profile,        │
            │ resume in their frame                 │ → grounded chat (rephrase,       │ local-data note        │
            │                                       │ example, continue, ask) with the │                        │
            │                                       │ page; offline fixed example      │                        │
            └───────────────┬───────────────────────┴──────────────────────────────────┴────────────────────────┘
                            ▼
      Play: Classic / Case / Escape / Rookie / Companion frames; swipe, sort, order, deduce, cross-exam;
      verdict stamps, Show evidence, player character reacts, recap → spaced review (Leitner)
```

| Area | Status | Where |
|---|---|---|
| Content packs (presets, server installs, imports) | built; 7 presets in the APK | [content-packs](../architecture/content-packs.md), ADR-017 |
| Play screen, 5 frames, stamps, evidence, recap | built; Case is the most polished | [engagement-flow](../design/engagement-flow.md) |
| Player characters (3 original placeholders) | built; picked in *Me*; team art to replace them | [characters](../design/characters.md) |
| Three-tab home + local profile | built (teammate) | [home-navigation](../design/home-navigation.md) (Chinese) |
| Diagnostic + conversational tutor | built end to end against a **local server with fake models**; real-model quality untested | [document-tutor](../design/document-tutor.md) (Chinese), evidence in [document-tutor-acceptance](../design/document-tutor-acceptance.md) |
| Sample student / guest profiles | built; Lin's history is made by playing the presets through the real engine | `app/data/profile/`, this page §5 |
| Server compile pipeline + eval harness | built; real-model evals on Bedrock (local only) | [eval-2026-11-19-ab](../testing/eval-2026-11-19-ab.md) |
| Cloud deployment | Terraform written, **not applied**; the video does not need it | [infra](../../infra/README.md) |
| Phone context: walking pause | simulator only (no real Activity Recognition yet) | [mobile-native](mobile-native.md) |
| Widget, review notification, camera OCR | not built | [mobile-native](mobile-native.md) |

## 3. Why this order (learning science, team feedback)

| Step | Why | Source |
|---|---|---|
| Diagnose first, with "I don't know" | the team found quiz-first openings too stressful on hard papers; a diagnostic with an honest escape lowers the stakes while still being retrieval | team feedback (Q31); pretesting effect |
| Explain only gaps, with the page | an explanation of everything is a summary; grounding keeps it checkable | positioning (§1); support gate (ADR-015) |
| Practise in a game frame | retrieval beats re-reading; the frame turns it into a case to solve | testing effect; [game-concepts](game-concepts.md) |
| Review tomorrow | spacing; misses return without streak guilt | Leitner; D8 in [design-review](../design/design-review-2026-11-19.md) |

## 4. Earlier proposals: what still holds

| Proposal | Now |
|---|---|
| [onboarding-and-focus](onboarding-and-focus.md): reading map, focus choice, warm-up level | The **opening** is replaced by the tutor's diagnostic, which plays the reading map's role. The **focus / facet** idea (tag units by problem / method / results, select at play time) is still open (decision D4). |
| [home-navigation](../design/home-navigation.md): chapter self-rating + summary | superseded by the tutor (stated in the teammate's doc) |
| [engagement-flow](../design/engagement-flow.md): Case hero flow | built, but **new practice opens Classic only**, so Case is not reachable from the home screen (decision D1) |
| [characters](../design/characters.md) | built and integrated in *Me* |
| [design-review](../design/design-review-2026-11-19.md) length budgets | not yet applied to generation; preset text is still long in places |

## 5. The video: preset courses first, generation only briefly

The video (≤ 15 min, faces shown, no AI voice or avatar) sells the concept on the **pre-generated courses**, which run offline and are reproducible. Generation gets one short segment. Suggested order, mapped to the marking scheme (Component 1):

| # | Segment | Shows | Marking point |
|---|---|---|---|
| 1 | Hook: Lin's problem (an 18-page paper, 25-minute commutes) | — | Purpose, vision |
| 2 | Who it is for; three neighbours (Paper2Galgame, NotebookLM, ahafrog) in one line each | [why-and-market §5](why-and-market.md) | Target users, related work |
| 3 | Open as **Lin**: a lived-in home (courses played, one review due, a case half-way) | Practice tab, sample profile | Demo: key functions |
| 4 | Resume the ResNet / Attention **case**: swipe with the character leaning along, verdict stamp, evidence page, cross-examine the Summary, hold to object, case closed | Play screen | Demo; creativity |
| 5 | **Learn** tab on the fixed example: diagnostic with "I don't know", the starting point per concept, grounded chat with the source | Tutor (offline) | Demo; usability |
| 6 | Generation in 30 s: pick a PDF → the server compiles (local server) → a new course pack appears | Import → pack install | Demo: "anything" |
| 7 | Phone-native: one-hand thumb zone, haptics, tilt, walking pause, offline | Play + simulator | Mobile computing |
| 8 | Usability: what changed after testing (pressure → diagnostic; docked actions; large text) | before/after screens | Usability improvements |
| 9 | Ethics, legal, privacy: consent before upload, no AI calls from the phone, local profiles, copyright (short quotes only, never the original), AI can be wrong → every answer has its page | — | Professional/legal/social/ethical |
| 10 | Each member: the part they built, and their contribution % read out with face shown | — | Part 3 (mandatory) |

## 6. Decisions the team owes (ranked by effect on the video)

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Frames in *Practice* | keep Classic-only; or offer "Play as" (Case default, Classic quick) | **Offer Case again**: it is the "game" in Anything2Game and the strongest demo moment |
| D2 | Learn tab on preset courses | only the fixed retrieval example; or give each preset pack an optional tutor script (pack entry, additive to ADR-017) | **Add tutor scripts to the 2–3 presets used in the video** (generated with the real model, reviewed by a person) |
| D3 | Dev labels in the recorded build ("AI mock", local-server notes) | keep; or hide behind a demo flag | **Keep honest labels**, and record flows that never show mock content (fixed example, presets) |
| D4 | Focus / facets | after the video; or now | **After the video** |
| D5 | Work packages | every member must code and present their part | assign this week ([roadmap](../plan/roadmap.md)) |
| D6 | Character art | team draws them (SpriteAvatar) or keeps the placeholders | **team draws them** (design must be the team's own) |

## 7. Risks

- **Course rules.** Design, reports and the demo must be the team's own work, and every member must code. Use AI for code only.
- **The tutor has only run on fake models.** Its real-model quality, prompt-injection resistance and cost are unmeasured. Do not claim them in the video.
- **No cloud deployment.** The generation segment is recorded against a local server.
- **Preset papers are third-party works.** Show only short quotes. Never ship or show the original PDFs.
