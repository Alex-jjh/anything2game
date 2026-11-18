# Product question log

**Scope**: every product/architecture question the owner has raised, the answer we reached, and where the decision now lives. New questions are appended; answers are updated in place with the date.

| # | Date | Question (as raised) | Answer in one line | Lives in |
|---|---|---|---|---|
| Q1 | 11-17 | How should the design show what is special about Android, and be innovative? | Three phone-only pillars: anything in (share sheet, camera OCR), knowledge picks the mechanic (native gestures), knows where you are (activity, network, widget) — plus evidence on every answer | [why-and-market §6](why-and-market.md) |
| Q2 | 11-17 | The teacher likes story-telling / start-up style — what is the story? | Hero Lin + "summaries make you feel you understood; here you catch them" + competitors + business model | [why-and-market](why-and-market.md), [game-concepts §8.3](game-concepts.md) |
| Q3 | 11-17 | Why build it at all? Simple market research | Jobs-to-be-done, why now, related work (incl. Paper2Galgame, NotebookLM), positioning map; facts marked `[verify]` until web search | [why-and-market](why-and-market.md) |
| Q4 | 11-17 | Is the game one fixed mode, or can you choose templates? | Four layers: content, mechanic (picked by knowledge type, never by user), **frame** (template; default Case, Classic shipped, user may switch), skin | [game-concepts §7](game-concepts.md), [decisions 003](../architecture/decisions.md) |
| Q5 | 11-17 | Generate one big thing first, then adapt quickly with a Flash model? | Yes: compile once with a strong model (incl. variants, hints, feedback) → adapt on the device → Flash only for Ask / re-explain / refill, never blocking play | [game-design §4](game-design.md), [decisions 004](../architecture/decisions.md) |
| Q6 | 11-17 | Where do docs live, where do components live, how do they talk? | Monorepo: `docs/`, `contract/`, `android/`, `server/`; one contract module compiled by both sides | [README](../../README.md), [ARCHITECTURE](../architecture/ARCHITECTURE.md) |
| Q7 | 11-17 | Why not keep the existing polyrepo? | Contract drift and ordered cross-repo PRs hit the riskiest week; one submission; history preserved | [decisions 001](../architecture/decisions.md) |
| Q8 | 11-17 | Server on Alibaba/Kingsoft Cloud, DeepSeek/Qwen, test with Claude Sonnet? | One OpenAI-compatible adapter (DeepSeek/Qwen) + Anthropic SDK adapter for dev evals; prefer Hong Kong region (no ICP filing) | [decisions 011](../architecture/decisions.md) |
| Q9 | 11-17 | Bilingual? | UI in English + Chinese, follows system / per-app language; content language per package; samples in both | [android/README](../../android/README.md) |
| Q10 | 11-17 | Modular, decoupled, SOLID? | Pure-Kotlin engine with ports; one grader + one renderer per mechanic via registries; skins by role; composition root | [android/README](../../android/README.md), [ARCHITECTURE §1](../architecture/ARCHITECTURE.md) |
| Q11 | 11-17 | It looks like Duolingo — can it be more of a game? Paper2Galgame was the reference, but it needn't be a galgame | Detective case: briefing → clues pinned to a board → cross-examine the AI Summary → case closed; credibility as stakes | [game-design §6](game-design.md) |
| Q12 | 11-17 | NotebookLM makes mind maps / blog-style outputs for fragmented time — relevant? | Yes: our evidence board is a mind map you *earn*; it stays as case notes for 10-second reviews | [game-design §6.3](game-design.md) |
| Q13 | 11-17 | Why would a user want to play? Learning has friction — reduce it or add it? | Remove friction around the thinking (access, reading, resuming, fear); keep/add it inside the thinking (retrieval, judging claims) and make it feel chosen | [game-concepts §1–3](game-concepts.md) |
| Q14 | 11-17 | Think of several game concepts and judge which fits | Eight concepts scored on six criteria; Case 27/30 recommended, Classic kept; others documented | [game-concepts §4–5](game-concepts.md) |
| Q15 | 11-17 | Can the concepts all be templates? | Yes, as frames over the same content and mechanics; ship Case + Classic; frames may not change items, grading or add timers | [game-concepts §7](game-concepts.md) |
| Q16 | 11-17 | Why would people be convinced to play, and how does it affect them? | Acquisition at the moment of need, sharp promise, villain, first win < 3 min, trust signals; effects table with risks (over-reliance, wrong items, overjustification, anxiety, walking, privacy) and mitigations | [game-concepts §8–9](game-concepts.md) |

| Q17 | 11-17 | So it's decoupling — and the AI must generate frame-specific content too? Build 5 frames and their backend nodes | Yes: core package compiled once; each frame's content is a separate overlay (`FrameOverlay`) produced by its own backend frame node and validated against `FrameSpec`; app ships Classic, Case, Escape, Rookie, Companion | [android/README](../../android/README.md#frames-game-templates), `contract/.../Frame.kt`, `server/` |
| Q18 | 11-18 | Google Cloud or AWS with Terraform/IaC — keep modules decoupled so multi-cloud works; CDK or TF? | Terraform/OpenTofu (CDK is AWS-only); server stays cloud-SDK-free; portable baseline = container + managed Postgres; 4 P0 fixes before any cloud deploy; demo region outside mainland, AWS/Alibaba HK preferred over GCP for campus reachability | [portability-audit](../architecture/portability-audit.md), [ADR-012](../architecture/decisions.md) |
| Q19 | 11-18 | Never call AI APIs directly from Android — always through our server | Accepted as ADR-013 and enforced by a build check | [ADR-013](../architecture/decisions.md), `android/app/build.gradle.kts` |
| Q20 | 11-18 | Infra on AWS (lecturer knows AWS; Cognito for auth), models swappable (Gemini etc.)? | Yes — ADR-014: AWS Terraform module (CloudFront VPC origin, internal ALB, Fargate, RDS, WAF, Cognito); per-tier model endpoints by env; Cognito used as optional standard-OIDC sign-in, anonymous play stays default | [ADR-014](../architecture/decisions.md), [infra/README](../../infra/README.md) |
| Q21 | 11-18 | Use the credential note to test the whole harness on real papers / slides | Local loopback proxy to Bedrock, server unchanged; 5 docs: 3 harness-found bugs fixed (PDF >4 KB upload, truncated levels, default-frame race); quality 3.5–4.4 / 5 with clear weak spots (order mechanic, companion giveaways, feedback depth); level 1 takes ~110 s | [eval-2026-11-18](../testing/eval-2026-11-18.md) |

## Still open (owner or team decides)

- Final frame choice after the 5-person preference test (game-concepts §6).
- Partner and Summary character design, skin look (visual team).
- Exact models per tier and hosting account (decisions 011).
- Team size and work-package owners (roadmap).
