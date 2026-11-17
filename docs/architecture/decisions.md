# Decision log

**Scope**: decisions that shape more than one component. Each has context, the decision, why, and what it costs. Status: `accepted` (act on it), `proposed` (team to confirm), `open`.

| # | Decision | Status |
|---|---|---|
| 001 | Monorepo, one root Gradle build | proposed |
| 002 | Kotlin on both client and server; shared `contract` module | proposed |
| 003 | Game = fixed frame × content-chosen mechanic × swappable skin | proposed |
| 004 | Compile once (strong model) → adapt on device → flash only when stuck | proposed |
| 005 | The device decides flow; `:engine` is pure Kotlin | proposed |
| 006 | Text is the universal intermediate; OCR on device | proposed |
| 007 | Anonymous installs, progress on device only | proposed |
| 008 | Compose for new UI | proposed |
| 009 | Answers ship inside the package | proposed |
| 010 | Portrait-first | proposed |
| 011 | LLM adapters (DeepSeek/Qwen prod, Claude for dev evals); HK-region hosting | proposed |

---

### 001 Monorepo, one root Gradle build
**Context**: two GitHub repos exist — `anything2game-android` (one commit, working prototype) and `anything2game-api` (empty). The course takes ONE source-code submission. 4–6 people, 3 weeks, and the contract will change daily in week 1.
**Options**:

| | Polyrepo (keep both) | Monorepo |
|---|---|---|
| Contract | lives in one repo, copied or submoduled into the other → drift, or a published artefact (JitPack / GitHub Packages) to maintain | `:contract` module compiled by both sides in one build; a breaking change fails both immediately |
| A cross-cutting change (new mechanic = DTO + renderer + prompt) | 2 PRs, merged in the right order | 1 PR |
| Docs | split or duplicated | one `docs/` |
| Submission | merge two repos into one zip at the end | zip the repo |
| Independence | separate CI, permissions, Android Studio opens only Android | one CI; Android Studio opens the whole build (server modules are small) |

**Decision**: monorepo. Reuse the empty `anything2game-api` repo by renaming it to `anything2game` on GitHub (no new repo), push the imported history, archive `anything2game-android`.
**Why**: the polyrepo benefits (independent release cycles, different permissions) don't exist in this project; its costs (contract drift, ordered cross-repo PRs) hit exactly the riskiest week. The existing repos are not a sunk cost: one is empty, the other is imported with its history, so each member's commits still count as contribution evidence.
**Revisit if**: the backend must be handed to someone who should not see the app code, or the server needs its own deploy pipeline triggered only by server commits (path filters in CI solve this inside a monorepo too).
### 002 Kotlin everywhere
**Context**: the brief says core functionality must be implemented in Java or Kotlin. Turning material into a game is arguably core.
**Decision**: server in Kotlin + Ktor; PDF extraction with Apache PDFBox; LLM calls over plain HTTP behind `LlmClient`.
**Why**: removes any doubt about the language rule; DTOs are shared, not re-typed; every member can read every part (all members must code).
**Cost**: Python's LLM/PDF ecosystem is richer. PDFBox covers text + page index; we don't need more for text PDFs.

### 003 Three-layer game
**Decision**: fixed frame (case file → levels → recap → boss), mechanic chosen per knowledge unit by the compiler from a closed list (Swipe, Sort, Deduce; stretch: Order, Match), skin swappable by the visual team. Users choose only session length (and skin).
**Why**: one structure to learn and demo; the innovation is that rules follow knowledge type (Habgood & Ainsworth 2011), which a user-picked template would not give; visuals stay decoupled from data. Details: game-design.md §1.

### 004 Compile → adapt locally → flash
**Decision**: T1 strong model compiles each document version once, including 2 variants per unit, per-option feedback and hints. T2 the phone adapts with pre-generated content, offline. T3 flash model only for Ask, re-explain after repeated misses, and background variant refill; ≤ 5 s timeout with fallback.
**Why**: cost (pay once per document, dedupe by hash), latency and flaky metro networks (play never waits on a model), quality (validation gates only work on batch output). Flash covers what cannot be pre-computed: the user's own question.
**Cost**: compile takes longer and costs more per document → mitigated by progressive publication (level 1 first).

### 005 Device decides flow
**Decision**: session state machine, mastery, scheduling and context policy live in `:engine` (no Android imports). The LLM never decides what comes next.
**Why**: deterministic, unit-testable on this VM, works offline, and explainable in the report.

### 006 Text as the universal intermediate
**Decision**: every source becomes page-indexed text. PDF → server (PDFBox). Shared text / web page text → device sends text. Photos → ML Kit OCR on device → text. Video is out of the MVP (needs legal transcripts).
**Why**: "anything" with one pipeline; images never leave the phone (privacy story); the server stays simple.

### 007 Anonymous installs, local progress
**Decision**: `POST /v1/installs` issues a token; no accounts; play records stay in Room.
**Why**: no personal data to protect beyond uploaded documents, which can be deleted; fewer screens.
**Cost**: no cross-device sync — named as future work in individual reports.

### 008 Compose for new UI
**Decision**: new screens in Jetpack Compose; prototype Views screens are replaced, not extended.
**Why**: drag/swipe mechanics and animation are far simpler; screenshot tests (Roborazzi) let us review UI without an emulator.

### 009 Answers ship inside the package
**Decision**: the package includes correct answers and feedback so play works offline.
**Why**: offline play is a pillar; this is a learning app, not exam grading. Stated as a known limitation.

### 010 Portrait-first
**Context**: PRD v0.1 said landscape; the 2026-11-10 meeting leaned portrait.
**Decision**: portrait layouts only; rotation is allowed and must not lose state, but no dedicated landscape layout.
**Why**: the hero moment is one-handed on a metro; every mechanic is designed for a thumb.

### 011 LLM provider and hosting
**Context**: the team expects to host on Alibaba Cloud or Kingsoft Cloud and use DeepSeek or Qwen in production; Claude Sonnet for testing.
**Decision**:
- `LlmClient` interface with two adapters: `OpenAiCompatibleLlmClient` (DeepSeek and Qwen/DashScope both expose OpenAI-compatible chat completions — one adapter, configured by base URL + model + key) and `AnthropicLlmClient` (official Anthropic Java SDK, model `claude-sonnet-5-5`). Provider and model per tier (`compile`, `flash`) come from server config, never from code.
- **Do not rely on any provider's strict-schema mode.** Ask for JSON (`response_format: json_object` on DeepSeek/Qwen; structured outputs on Claude), then our `PackageValidator` decides; one repair retry with the validator's errors, then drop the unit. Same gates for every provider.
- Claude is a **dev/eval tool only**: run from developer machines and `tools/` evals (also as a quality reference and LLM judge). It is not called from the deployed server — Anthropic's API is not offered in mainland China or Hong Kong `[verify]`, and production should use the providers the server can reach. Use a personal API key, not a work account.
- Hosting: prefer a **Hong Kong (or Singapore) region** for the demo server. Mainland regions need ICP filing (备案) before serving a domain over HTTP(S), which can take longer than our remaining time `[verify current timeline]`. HK needs no filing, reaches DeepSeek/Qwen, and is reachable from mainland phones. If the team already has a filed domain on a mainland account, use that instead.
**Still open**: exact model per tier (e.g. Qwen large vs flash-tier, DeepSeek chat) — pick after running the eval in `tools/` on 3 sample PDFs: gate pass rate, latency to level 1, cost per compile.
