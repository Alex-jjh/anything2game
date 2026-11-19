# Team review, 2026-11-20: what we agreed and what we built

**Scope**: the decisions from the team's late-night review (WeChat voice + text; the product-relevant messages are archived verbatim in [archive/2026-11-20-team-review-chat.md](../archive/2026-11-20-team-review-chat.md)), turned into product decisions with their status. People: Alyssa (product/design lead for the explainer), Ziwei (Learn tab / tutor), Jiahao (architecture). The UI design choices remain the team's own (CSI301 rule); this records them.

## 1. What the team said, in short

| # | Point | Who |
|---|---|---|
| 1 | The pre-questions (diagnostic) and the explanation *content* are fine. The **presentation** of the explanation is wrong: it became a chat UI. "Explanation should look like this": character standee on top, dialogue box below, one character explaining the reading to you (the paper2gal style). The app's first Case scene layout is exactly the layout wanted. | Alyssa |
| 2 | Add the little character (小精灵) into the explanation; the user should see it and interact with it. | Ziwei |
| 3 | The current characters may not reach a galgame feel; allow **switching themes**, e.g. an anime theme from CC0 bodies/avatars/expressions; consider a background image with a scrim, or a plain background. | Ziwei, Jiahao |
| 4 | Phone **space use**: landscape makes text tiny, portrait wastes space; choose a layout that focuses attention without waste. | Jiahao |
| 5 | Readers have different **focus** (methodology, results, "just what's new"). Offer choices + "other" + free text (NotebookLM-style), default to the AI's judgement. The diagnostic today only measures level, not focus. | Jiahao, Ziwei, Alyssa |
| 6 | **Walking auto-pause is gimmicky**: many walks need no pause. Test on a real device. | Jiahao |
| 7 | Generation can be pre-done (prefix content). Polish **one mode** for the video, make interaction smooth and ergonomic, mention the rest. | Jiahao, Alyssa |
| 8 | Concern: the Case layout looks like Duolingo (language learning). Counterpoint: a single character explaining is exactly right for papers. | Ziwei, Alyssa |

## 2. Decisions and status

| # | Decision | Status |
|---|---|---|
| 1 | Learn tab explanation becomes an **explainer theatre**: stage (background + explainer standee with expressions) on top, dialogue box at the bottom, pages advance on tap, choice bar (Rephrase · Example · Continue · Ask) in the thumb zone; the chat history stays one tap away (Log). Diagnostic and assessment keep their logic, restyled as part of the same scene. | done: `ui/reading/ExplainerTheatre.kt`; screens in `docs/design/screens/explainer/` |
| 2 | The player's own character sits on the stage and reacts (listening, thinking while a question is answered, happy at the end of a concept); pokeable. | done |
| 3 | **Theme slot**: default "paper" theme ships; an "illustrated" theme appears only when standee art (drawables by mood) and a background are added. The team picks CC0 art; no third-party art is bundled by us. | done |
| 4 | **Portrait first**: stage ≈ 45 %, dialogue ≥ 18 sp, actions at the bottom; landscape puts the stage left and the dialogue right. | done |
| 5 | **Focus step** before the diagnostic: Quick overview / Method / Results / Limits (multi-select) + "Something else" (free text) + "Let the explainer decide" (default). Chosen facets are asked and explained first; nothing is dropped. Free text is stored and only steers the AI when online. | done: `ui/reading/FocusStep.kt`; facets on questions, chosen ones first |
| 5b | Every **preset course** gets its own pre-generated diagnostic + explanation script inside its content pack (ADR-017, additive `tutor.json`); Attention and ResNet scripts are generated (tagged by facet) and **need a person's review** before filming. | done: scripts for Attention, ResNet and the zh course travel in their packs (`tutor.json`); **review by a person before filming** |
| 6 | Walking **no longer pauses by default**; it switches to the one-handed layout. "Pause when I'm walking" is an opt-in in Me. | done (`f741268`) |
| 7 | Video: polish the **explainer + Case** path on the preset courses; generation shown briefly. | plan in [PRODUCT.md §5](PRODUCT.md) |
| 8 | Positioning answer to "looks like Duolingo": Duolingo teaches a fixed course with drills; we explain *your* reading with a character and then let you prove it in a case, every line with its page ([why-and-market §5](why-and-market.md)). | doc |

Notes from the build:
- **The explainer, Aya,** is an original procedural character, coloured sky blue so it stays clearly apart from well-known round yellow mascots and from the player's three characters.
- **Dialogue pages** fill what the box holds (≈ 170 Latin or 90 CJK characters at 19 sp), with fewer per page at larger system font sizes. Text is never clipped.

Native-feeling drag and drop is also built (it was a goal of the review owner):
- **Sort:** press and hold a card and drag it into a bin. The bin highlights, with a haptic tick on entering it and a confirm when the card is dropped.
- **Order:** drag a row by its handle, or press and hold it. The other rows slide out of the way, and the list auto-scrolls at its edges.
- **Kept paths:** tap and arrow buttons remain, and TalkBack actions cover every move.
- **Screens:** `docs/design/screens/drag/`.
- **Remaining issue:** long generated steps (30+ words) make rows tall. The real fix is the length budgets in generation (design-review §4).

## 3. Still to decide (team)

- The explainer's look: which CC0 standee art, whether to use a background image, the explainer's name and personality.
- Whether the Learn tab's explainer and the Practice frames share one character, or the explainer is a separate persona.
- Real-device check (tomorrow, in person): text size, thumb reach, haptics, and the walking setting.
