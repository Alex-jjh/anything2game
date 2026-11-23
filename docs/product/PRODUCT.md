# Anything2Game — where the product stands (2026-11-23)

**Scope**: the one page to read first. What the product is today, how the parts fit, which earlier proposals still hold, the plan for the video, and the decisions the team still owes. Details live in the linked documents. This summary covers the teammates' Chinese design docs too. Under the CSI301 rules, the app design, the reports and the demo are the team's own work: this page records facts and options, and the team decides.

## 1. One line, and how the tutor fits the positioning

> Explore a concept by playing; turn a set of readings into a route you can return to.

The original stance was "AI does not read for you; it turns your reading into levels you must think to clear." The team's latest change adds a conversational tutor. It fits the stance only in this order:

1. **Find the gaps first.** A short diagnostic with an honest "I don't know".
2. **Explain only the gaps.** Every reply cites the page.
3. **Play to make it stick.** Retrieval inside the game frames.
4. **Bring back what was missed.** Spaced review.

This is the line that separates us from a summariser or a chat-with-PDF app (NotebookLM / Gemini Notebook): we never hand over a summary to re-read.

## 2. The current update

The user approved [concept games and multi-PDF Spaces](../superpowers/specs/2026-11-23-concept-games-spaces-design.md). The [acceptance record](../design/concept-games-spaces-acceptance.md) states what has actually passed; device acceptance is still in progress.

| Entrance | User's next action | Shared foundation |
|---|---|---|
| **Playground / 游乐场** | Choose a reviewed game or generate a supported concept, manipulate the system, then try a changed-condition challenge | Typed GameSpec, one native runtime, versioned checkpoints and evidence |
| **Spaces / 学习空间** — default | Create a Space, add multiple PDFs, follow its route or open its materials directly | Existing tutor, practice frames, content library and one progress projection |
| **Me / 我的** | View local learning dates/history, edit nickname and character, switch profile | Same profile-scoped records as the other entrances |

Old Learn and Practice tab preferences migrate to Spaces. A saved Case remains a Case at its known content version. Unversioned historical mastery is retained as history rather than credited to the current edition. Removing a material from a Space does not delete its source or learning records.

The first native mechanisms are information partition and finite resource allocation. They have real actions, state changes, failure/retry and transfer conditions; completion comes from the shared engine. Reviewed presets run offline. Supported generation topics include binary search and opportunity cost in English and Chinese. Other topics explicitly report that they are unsupported. Arbitrary generated scripts, universal topic coverage and web retrieval are outside this release.

The service remains one Ktor deployment. PDF and concept generation share the existing worker, model routing, quotas and storage. Phone and server share typed contracts, pack validation and game rules. The [architecture reference](../architecture/concept-games-spaces.md) documents the concrete API and recovery behavior.

| Area | Status / evidence |
|---|---|
| Native games, multi-PDF Spaces and shared progress | This update; final verified scope and screenshots in the [acceptance record](../design/concept-games-spaces-acceptance.md) |
| Existing course packs, reading and framed practice | Retained inside Spaces; seven old course packs plus bilingual reviewed game presets |
| Nickname, characters and sample/guest profiles | Local, profile-scoped; no account/cloud-sync claim |
| Concept/path generation | Same typed pipeline for configured providers and fake fixtures; new live-model teaching quality unmeasured |
| Older compile generation evaluations | [Bedrock evaluation record](../testing/eval-2026-11-19-ab.md); these do not evaluate the new concept-game prompts |
| Cloud deployment | Terraform written, not applied as part of this update |
| Walking context | Simulator; no new physical Activity Recognition integration |
| Widget, notification, camera OCR, new game mechanisms | Follow-up work |

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
| [engagement-flow](../design/engagement-flow.md): Case hero flow | retained in Space practice via **Play as** ([frames](frames.md)) |
| [characters](../design/characters.md) | built and integrated in *Me* |
| [design-review](../design/design-review-2026-11-19.md) length budgets | not yet applied to generation; preset text is still long in places |

## 5. The video: preset courses first, generation only briefly

The video (≤ 15 min, faces shown, no AI voice or avatar) sells the concept on the **pre-generated courses**, which run offline and are reproducible. Generation gets one short segment. Suggested order, mapped to the marking scheme (Component 1):

| # | Segment | Shows | Marking point |
|---|---|---|---|
| 1 | Hook: Lin's problem (an 18-page paper, 25-minute commutes) | — | Purpose, vision |
| 2 | Who it is for; three neighbours (Paper2Galgame, NotebookLM, ahafrog) in one line each | [why-and-market §5](why-and-market.md) | Target users, related work |
| 3 | Open as **Lin**: a lived-in home (courses played, one review due, a case half-way) | Spaces, sample profile | Demo: key functions |
| 4 | Resume the ResNet / Attention **case**: swipe with the character leaning along, verdict stamp, evidence page, cross-examine the Summary, hold to object, case closed | Play screen | Demo; creativity |
| 5 | **Spaces** on a reviewed reading example: diagnostic with "I don't know", the starting point per concept, grounded chat with the source | Tutor (offline) | Demo; usability |
| 6 | Generation: add PDFs to a Space or request a supported concept (local server; fixture labels visible) | Import → pack install | Demo: "anything" |
| 7 | Phone-native: one-hand thumb zone, haptics, tilt, walking pause, offline | Play + simulator | Mobile computing |
| 8 | Usability: what changed after testing (pressure → diagnostic; docked actions; large text) | before/after screens | Usability improvements |
| 9 | Ethics, legal, privacy: consent before upload, no AI calls from the phone, local profiles, copyright (short quotes only, never the original), AI can be wrong → every answer has its page | — | Professional/legal/social/ethical |
| 10 | Each member: the part they built, and their contribution % read out with face shown | — | Part 3 (mandatory) |

## 6. Decisions the team owes (ranked by effect on the video)

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Frames in Space practice | Classic only; or "Play as" per course | **Done as "Play as"** (Classic default; Case, Rookie, Escape, Companion when generated), described by what you do and when it fits. Still open: integrate Companion into Space reading, and offer Escape only for process-heavy documents ([frames](frames.md) §4) |
| D2 | Explanation scripts on preset courses | only the fixed retrieval example; or give each preset pack an optional tutor script (pack entry, additive to ADR-017) | **Add tutor scripts to the 2–3 presets used in the video** (generated with the real model, reviewed by a person) |
| D3 | Dev labels in the recorded build ("AI mock", local-server notes) | keep; or hide behind a demo flag | **Keep honest labels**, and record flows that never show mock content (fixed example, presets) |
| D4 | Focus / facets | after the video; or now | **After the video** |
| D5 | Work packages | every member must code and present their part | assign this week ([roadmap](../plan/roadmap.md)) |
| D6 | Character art | team draws them (SpriteAvatar) or keeps the placeholders | **team draws them** (design must be the team's own) |

## 7. Risks

- **Review has no entry point of its own yet.** Misses do return in Space practice: the level shows "needs practice", and a replay offers other versions of the missed clues. But Me only shows a due *count*, with no "review now" session across courses. Characters and the recap talk about tomorrow, so this is the next product gap to close.
- **Journey tests.** `app/src/test/.../journeys/`: The original journey suite drives the real app with real taps (Lin, Case to case closed, Rookie, Escape, profiles, restart, rotation, zh). They found 8 bugs, all fixed.

- **Course rules.** Design, reports and the demo must be the team's own work, and every member must code. Use AI for code only.
- **The tutor has only run on fake models.** Its real-model quality, prompt-injection resistance and cost are unmeasured. Do not claim them in the video.
- **No cloud deployment.** The generation segment is recorded against a local server.
- **Preset papers are third-party works.** Show only short quotes. Never ship or show the original PDFs.
