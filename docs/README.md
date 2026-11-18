# Docs index

One kind of document lives in one place. Every document opens with a **Scope** line: what it answers and what it does not.

| Folder | Lens | Document | Status |
|---|---|---|---|
| `product/` | PM: why, for whom, against whom | [why-and-market.md](product/why-and-market.md) | draft; market facts to verify via web search |
| `product/` | PM: why play at all, friction, 8 concepts as frame templates, persuasion, effects on users | [game-concepts.md](product/game-concepts.md) | proposal |
| `product/` | Every question the owner raised → answer → where it is decided | [question-log.md](product/question-log.md) | living |
| `product/` | PM + game design: what you play, how it adapts | [game-design.md](product/game-design.md) | draft |
| `design/` | Visual / interaction: palette, type, components, screens per mechanic | (visual team creates) | empty |
| `architecture/` | Solution architecture: components, contract, state machines, generation pipeline | [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | draft |
| `architecture/` | Decoupling + multi-cloud audit, fix plan, IaC choice | [portability-audit.md](architecture/portability-audit.md) | 2026-11-18 |
| `architecture/` | Decision log (ADRs) | [decisions.md](architecture/decisions.md) | draft |
| `plan/` | Schedule and ownership | [roadmap.md](plan/roadmap.md) | draft |
| `research/` | Evidence base (learning science + HCI) | still inside the v1.1 appendix in `archive/` | to migrate |
| `testing/` | Test plan, device checklist, usability study | (to create) | empty |
| `archive/` | Superseded versions, read-only | [2026-11-10-dev-plan-v1.1.txt](archive/2026-11-10-dev-plan-v1.1.txt) (Chinese) | frozen |

The data contract is not in `docs/` — it is code in [`/contract`](../contract/README.md), because both sides' tests import it.
