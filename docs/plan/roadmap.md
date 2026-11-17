# Roadmap: 2026-11-17 → 2026-12-07

**Scope**: what gets built when, and who owns which module. Not a contribution-% record (that comes from real work and git history).

## Milestones

| Week | Dates | Goal | Exit check |
|---|---|---|---|
| W1 | Mon 11-17 → Sun 11-23 | **Contract frozen + one full loop** | contract v1 frozen Wed 11-19; phone plays the sample package through Swipe/Sort/Deduce + evidence + recap; server compiles one real PDF into a valid package (via curl) |
| W2 | 11-24 → 11-30 | **Real end-to-end** | share a PDF on a real phone → notification "Level 1 ready" → play → Ask works; OCR import; boss + widget. **Feature freeze Sun 11-30** |
| W3 | 12-01 → 12-07 | **Polish, test, film, write** | 5-person usability test 12-01/02 → fixes; context features verified on device; video filmed 12-03/04, edited 12-05; reports final 12-05; **submit Sat 12-06** (1-day buffer) |

Group and individual reports are written by members themselves through W2–W3 (course rule: no AI in reports).

## Work packages and owners

Each member owns at least one code module and presents it in the video.

| WP | Module | Contents | Owner (4–6 people) |
|---|---|---|---|
| WP1 | `contract` + `server/compile` | DTOs, validators, examples; planner/generator/validator; prompts; evals | architecture lead |
| WP2 | `:engine` + play screens | PlaySession state machine, mastery, Swipe/Sort/Deduce renderers | Android lead (prototype author) |
| WP3 | skin + design system | palette, type, components, mechanic visuals, Roborazzi screenshots | visual design ×2 |
| WP4 | import + sync | share-sheet intent, SAF, CameraX + ML Kit OCR, WorkManager upload/poll, notifications | member D |
| WP5 | evidence + review | evidence viewer (page + passage), boss, widget | member E (or WP2 if 4 people) |
| WP6 | `server` api/ingest/adapt + context | API, PDFBox ingest, Ask/variants; context policy + device checklist | member F (or WP1/WP4 if 4 people) |

## Critical path and risks

1. **Contract v1 (11-19)** blocks everyone → architecture lead ships DTOs + example JSON first; Android works from the example file until the server is live.
2. **LLM provider (decision 011)** blocks real compiles → test 2 providers by 11-20 on one sample PDF.
3. **No emulator on the dev VM** → engine/contract/server tested on JVM; UI by screenshot tests; platform features on a real phone at least twice a week.
4. **Demo-day network** → reviewed packages in `samples/` as a labelled offline fallback; never presented as live.

## Cut order if late

Match → Order → widget → context auto-pause (keep a manual "commute mode") → OCR. Never cut: real compile of a new PDF, evidence, offline resume.
