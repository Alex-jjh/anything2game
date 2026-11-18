# Product review, 2026-11-18 — what is wrong with the product today, and what to do about it

**Scope**: PM view of the whole product after the first real-material eval ([eval-2026-11-18](../testing/eval-2026-11-18.md)), plus the technical consequences. 19 days to submission (2026-12-07). The generation-layer question is worked out in [layered-generation.md](../architecture/layered-generation.md).

## 1. The honest picture

| Promise (ad / docs) | Reality today | Gap |
|---|---|---|
| "Share anything, play in a minute" | The app **cannot yet send a file to the server**; the server needs ~110 s (median) to the first level | the headline promise is not end-to-end |
| "Every answer points back to the page" | evidence works; but the judge flagged up to 1 in 3 units with a possible factual error (2 of 3 spot checks confirmed) | trust is the product; one wrong answer costs it |
| "Knowledge picks the mechanic" | works; but *order* is invented when the source has no sequence, *sort* feedback is thin | the innovation claim is only as good as the weakest mechanic |
| "Five ways to play" | five frames exist; quality ranges from excellent (Rookie) to leaking answers (Companion) | five uneven modes read as unfinished, and are harder to explain |
| "Fits your commute" | context features run only on a simulator; spaced review (boss/widget) not built | pillar ③ is still a slide |
| "It works on your material" | papers fine; **slides — the most common student material — produce logistics trivia**; >80-page notes rejected | the hero user mostly has slides |

**Biggest risk is not technical.** We have not watched a single student use it. Whether students *want* to play their reading (desirability) is still a hypothesis (game-concepts §6).

## 2. Problems, ranked (impact on the hero user × how close to the deadline)

| # | Problem | Why it matters to Lin | Fix | Cost |
|---|---|---|---|---|
| P1 | No end-to-end path from her PDF to a game on the phone | the product does not exist for her yet; the demo can only show samples | app networking: upload → poll → download package/frames → play; Ask through the server | M (Android) |
| P2 | Wrong answers | "this app taught me something the paper doesn't say" ends trust permanently | **precision over recall**: fewer, verified items (support gate, claim layer); "report this item"; never ship flagged items | M (server) |
| P3 | Two minutes before anything happens | the moment of need passes; she goes back to WeChat | give *something* in seconds (case-file preview from the first claims), level 1 in ~60 s (per-unit parallel generation), notification when the rest is ready | M |
| P4 | Unknown whether it is fun / useful | we could be polishing the wrong thing | 5-person test **this week** on the real app with their own slides; watch, don't ask | S |
| P5 | Slides go wrong | slides are what students actually have | one chunk per slide; skip logistics; treat slide bullets as claims with the slide title as scope | S–M |
| P6 | Five uneven frames | choice paralysis; the ad needs one hero | lead with **Case**; ship Classic as the calm mode; keep Rookie (best quality) and mark Escape/Companion "beta" until their checks pass | S |
| P7 | No reason to come back tomorrow | retention is where learning happens (spacing) | boss review of shaky clues + daily-clue widget, driven by the mastery records we already store | M |
| P8 | Generic, not *her* goal | an exam needs different clues than a seminar | one optional question at import: *Exam · Seminar · Just the gist* → weights which claims become clues | S (once claims exist) |
| P9 | Cost ≈ USD 0.95 per document | fine for a demo, not for freemium | frames read units + cited chunks, not the whole document; prompt caching of the document prefix; flash tier for checks | S–M |

## 3. Product principles this review adds

1. **Precision over recall.** Twelve right clues beat sixteen with two wrong ones. When in doubt, drop the item and say "we skipped parts we couldn't verify".
2. **Faithful to the document, not "true about the world".** We promise "the material says this, here is the page" — not that the paper is right. This is also how we talk about errors (and source inconsistencies, see the 41.8 vs 41.0 BLEU case).
3. **Value in seconds, depth in minutes.** Something useful appears while the rest compiles.
4. **One hero mode.** Case is the story; others are options, not the pitch.
5. **Measure before polishing.** Every change to generation is run through `tools/eval` and compared with the previous run.

## 4. Plan for the remaining 19 days

| Week | Product | Engineering |
|---|---|---|
| W2 (11-18 → 11-23) | 5-person test with the sample packages + paper prototype of cross-exam; decide hero frame | app ↔ server networking (P1); claim-layer prototype + support gate behind a flag; eval A/B on the same corpus |
| W3 (11-24 → 11-30) | 5-person test #2 on the real app with participants' own slides | adopt or reject the layered pipeline by the A/B numbers; per-unit parallel generation; slide handling; boss review + widget. **Feature freeze 11-30** |
| W4 (12-01 → 12-06) | film the ad + demo; report | fixes only; deploy the demo stack (owner's go-ahead) |
