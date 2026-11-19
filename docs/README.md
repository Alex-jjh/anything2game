# Docs index

One kind of document lives in one place. Every document opens with a **Scope** line: what it answers and what it does not.

| Folder | Lens | Document | Status |
|---|---|---|---|
| `product/` | PM: why, for whom, against whom | [why-and-market.md](product/why-and-market.md) | draft; market facts to verify via web search |
| `product/` | PM: why play at all, friction, 8 concepts as frame templates, persuasion, effects on users | [game-concepts.md](product/game-concepts.md) | proposal |
| `product/` | Product review after the first real eval: problems ranked, principles, 19-day plan | [product-review-2026-11-18.md](product/product-review-2026-11-18.md) | 2026-11-18 |
| `product/` | Mobile-native features and game mechanics, ranked; architecture rules | [mobile-native.md](product/mobile-native.md) | 2026-11-18 |
| `product/` | Every question the owner raised → answer → where it is decided | [question-log.md](product/question-log.md) | living |
| `product/` | PM + game design: what you play, how it adapts | [game-design.md](product/game-design.md) | draft |
| `product/` | How a course starts: explain vs play first, game comparison, start-up pressure, reading map + focus choice | [onboarding-and-focus.md](product/onboarding-and-focus.md) | proposal for team |
| `design/` | Design review: principles from research, audit with real content, 3 visual directions, action list | [design-review-2026-11-19.md](design/design-review-2026-11-19.md) | for team decision |
| `design/` | Engagement flow: the 8 beats of a Case level, why each pulls, built vs next; storyboard of the real build | [engagement-flow.md](design/engagement-flow.md) · [hero-flow.png](design/screens/hero-flow.png) | draft for team |
| `design/` | Player characters: pick your own, procedural motion, how it reacts, how the team's art swaps in | [characters.md](design/characters.md) | draft for team |
| `design/research/` | Research: mobile game feel, learning & narrative games, visual identity & a11y | [research/](design/research/) | 2026-11-19 |
| `architecture/` | Solution architecture: components, contract, state machines, generation pipeline | [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | draft |
| `architecture/` | Decoupling + multi-cloud audit, fix plan, IaC choice | [portability-audit.md](architecture/portability-audit.md) | 2026-11-18 |
| `architecture/` | Layered generation (source → claims → game → frame), hallucination analysis, A/B plan | [layered-generation.md](architecture/layered-generation.md) | proposed |
| `architecture/` | Ingest: text-first, vision only for low-text pages (slides, scans) | [multimodal-ingest.md](architecture/multimodal-ingest.md) | proposed |
| `architecture/` | Content packs: one `.a2gpack` format for presets, server downloads and imports (ADR-017) | [content-packs.md](architecture/content-packs.md) | decided |
| `architecture/` | Decision log (ADRs) | [decisions.md](architecture/decisions.md) | draft |
| `plan/` | Schedule and ownership | [roadmap.md](plan/roadmap.md) | draft |
| `research/` | Evidence base (learning science + HCI) | still inside the v1.1 appendix in `archive/` | to migrate |
| `testing/` | A/B classic vs gate vs layered → gate adopted | [eval-2026-11-19-ab.md](testing/eval-2026-11-19-ab.md) | 2026-11-19 |
| `testing/` | Test plan, device checklist, usability study | (to create) | empty |
| `archive/` | Superseded versions, read-only | [2026-11-10-dev-plan-v1.1.txt](archive/2026-11-10-dev-plan-v1.1.txt) (Chinese) | frozen |

The data contract is not in `docs/` — it is code in [`/contract`](../contract/README.md), because both sides' tests import it.
