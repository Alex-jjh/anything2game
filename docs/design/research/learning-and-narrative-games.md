# Learning games and narrative game UI: research notes for Anything2Game

Date: 2026-11-19. Method: WebFetch on known URLs only (no web search). Every bullet carries the URL it came from.

Conventions used in this file:
- A bullet with a URL and no tag was stated on that page, as summarised by the fetch tool (the tool returns a model-made summary of the page, not raw text; see "Open questions").
- `[design inference]` = my own reasoning applying a read fact to Anything2Game. It is not a claim made by the source.
- `[unverified]` = not read on any page I could reach. Treat as a hypothesis to test.

---

## 1. Ten most actionable rules

1. Make the learning act the game act. Swiping a claim, sorting cards, and picking the overclaim ARE the content skill. Do not bolt points and badges on top (gamification of learning page, "intrinsic integration": align game elements with learning outcomes, use narrative and meaningful challenges, not only points and badges).
2. Give feedback that is immediate and informational: it says what was wrong and why, and points to the passage ("Show evidence"). Informational, non-controlling feedback supports motivation; controlling praise and arbitrary rewards can undermine it (Self-determination theory; Overjustification effect pages).
3. Penalise wrong "presents" in the cross-examination boss, but cheaply and visibly (a patience or credibility meter, not game over). Ace Attorney ties a cost to wrong evidence; the series is also criticised for trial-and-error when only one item is accepted, so accept any valid disproving passage [design inference].
4. For deduction screens, give partial-correctness feedback ("2 or fewer slots are wrong") and lock confirmed segments. This was Golden Idol's fix for guess-fatigue and brute-forcing. Obra Dinn confirms in batches of three to discourage guessing.
5. Fewer, better clues. Golden Idol cut tangential detail because it overwhelmed players and removed a clue that let testers skip steps. Only passages that matter for the answer (or are a deliberate misdirection) go on the evidence board.
6. Streaks: use light-touch, forgiving habit support. Duolingo's own data say rigid rules can be less motivating than slack (streak freeze), and that broken or feared streaks can backfire. No guilt copy, no panicked mascot. Avoid confirmshaming (Dark pattern page).
7. Keep dialogue short and skippable, and let the player control pace. The visual-novel standard set is: advance on tap, text-speed setting, auto-forward, skip-seen-only, and a history/backlog (Ren'Py docs). Never lock a first-time player out of the learning content behind unskippable chat [design inference].
8. Adult tone: informational, calm, specific. NN/g error-message guidance: positive, non-blaming, explain the cause, offer a next step. Characters should be dry or professional, not cute [design inference; Duolingo's sarcastic Lily persona shows that a persona with edge can work, see Q4].
9. End-of-level recap = what you got wrong and which page proves it, plus a scheduled review of exactly those items. Spaced repetition and retrieval practice are the best-supported memory effects (Spaced repetition; Testing effect pages). Re-surface missed items, not a generic "come back!".
10. Frames are skins over the same content. The mechanic must stay identical across Case, Escape room, Rookie, Companion, Classic; only fiction, portraits, and the "progress object" (evidence board, room, notes, partner) change [design inference]. Two-level disclosure maximum on any screen (NN/g progressive disclosure).

---

## 2. Q1. What makes a learning game feel like a game and not a quiz

### 2.1 Theory that applies
- Two families exist: serious games (learning goals built into play) vs structural gamification (points/badges/leaderboards on top of existing lessons). Points-badges-leaderboards alone are "not reliably effective"; critics call the approach simplistic. Source: https://en.wikipedia.org/wiki/Gamification_of_learning
- Best-practice list on that page: align elements with outcomes; use narrative and meaningful challenges; support autonomy (choice of task, pace, approach); immediate informative feedback; safe failure and retries; accessibility from the start; measure outcomes rather than assume. Source: same URL.
- Evidence is mixed in single studies. One course had gamified students do better on practical work but worse on written tasks; leaderboards raised calculus performance without changing motivation. Source: same URL. [design inference: so A2G should measure retention of the claims, not engagement alone.]
- Intrinsic integration in educational games: "the built-in learning process of games is what makes a game enjoyable"; players apply concepts in a partly fictional problem and learn from consequences in a low-risk setting. Source: https://en.wikipedia.org/wiki/Educational_game
- Flow conditions: clear goals and progress, clear immediate feedback, challenge-skill balance (central). Caveat: flow depends on perceived, not designer-estimated, difficulty. Source: https://en.wikipedia.org/wiki/Flow_(psychology)
- Self-determination theory: autonomy (meaningful choices raised intrinsic motivation; avoid controlling pressure such as strict deadlines), competence (informational feedback raised it; negative feedback lowered it), relatedness. Source: https://en.wikipedia.org/wiki/Self-determination_theory
- Overjustification: expected, task-contingent external rewards can crowd out intrinsic interest, especially when initial interest is high; rewards that convey real competence are less harmful. Source: https://en.wikipedia.org/wiki/Overjustification_effect
- [design inference] What this means for A2G: students are assigned the reading, so initial interest is often low. The overjustification page says rewards help more when initial interest is low or when mastery is needed first. Still keep rewards competence-based (e.g., "you caught 3 of 3 overclaims") rather than arbitrary coins.

### 2.2 Meaningful choice, stakes, tension (from investigation games)
- Ace Attorney loop: cross-examine, move between statements, press for detail, present evidence that contradicts a statement. A correct contradiction triggers a scripted payoff (the "Objection!" shout and a music shift). Wrong evidence costs the player (exclamation marks, a patience bar, or a badge depending on the entry). Criticisms: trial-and-error when only specific evidence is accepted, rigid pressing order. Source: https://en.wikipedia.org/wiki/Ace_Attorney (first 100k characters read of 188k).
- Danganronpa trials: "Nonstop Debate" where the player finds contradictions in real-time statements and fires "Truth Bullets" with the matching evidence; interleaved with other minigames. USGamer (as cited on the page) says the tension of not knowing the culprit is the most important part. Criticism: trials often easy. Source: https://en.wikipedia.org/wiki/Danganronpa
- [design inference] Our "the Summary overclaims" boss maps to: statements = the Summary's sentences; contradiction = a page; stake = a credibility bar. Variety (different minigame types inside the boss) is what Danganronpa uses to keep a repeated mechanic fresh.

### 2.3 Duolingo / Brilliant patterns
- Brilliant: each interactive problem gives "instant, custom feedback based on your answer"; learners are pretested so they attempt a solution before being taught; starts from "the simplest version of an idea" to reduce cognitive load; tutor "asks instead of tells"; each lesson focuses on one idea; practice sets are described as feeling like low-stakes quizzes; streaks and leagues support habit. Source: https://brilliant.org/about/
- Brilliant leagues: weekly groups of 30, rank by XP, promote or demote. Source: https://en.wikipedia.org/wiki/Brilliant_(website). Duolingo has the same league structure (groups of up to 30, Bronze to Diamond). Source: https://en.wikipedia.org/wiki/Duolingo
- Duolingo difficulty: Birdbrain predicts whether the learner will answer an exercise correctly, and the Session Generator picks exercises at "just the right difficulty"; rationale: too hard causes frustration, too easy implies mastery. Source: https://blog.duolingo.com/learning-how-to-help-you-learn-introducing-birdbrain/
- Duolingo end-of-lesson: mistakes from the lesson are reviewed at the end ("a little spaced repetition"). Source: https://blog.duolingo.com/spaced-repetition-for-learning/
- Duolingo chess "Game Review": focus on key takeaways, not completeness, to avoid "information overload"; thresholds adjust to the learner's rating; aim = "a coach that works with you instead of overwhelming you". Source: https://blog.duolingo.com/engineering-game-review/ [design inference: recap shows 2-3 key moments, not every miss.]
- Duolingo uses a bandit algorithm to pick each user's notification, and the mascot became a meme for stalking or threatening users. Source: https://en.wikipedia.org/wiki/Duolingo (first 100k of 176k read). This is the cautionary tale in Q5.

### 2.4 Streaks and their pitfalls (Duolingo's own words)
- Streak = a "tangible, measurable number" that encourages daily practice "even if it's just for five minutes". Early extensions feel large (2 to 3 days = +50%), later ones small (200 to 201 = 0.5%), so motivation shifts to loss aversion. Source: https://blog.duolingo.com/how-duolingo-streak-builds-habit/
- Pitfalls listed on that page: breaking a streak can be demotivating and have "the opposite effect"; fear of losing a streak may keep some learners from starting one; rigid rules can motivate less than slack. Streak Freeze (up to two) raised daily active learners by +0.38% "without encouraging more days off". The "7-day streak = 3.6x more likely to complete" figure is correlational. Source: same URL.
- Widget: Duo's mood gets "increasingly panicked" as midnight approaches. The page itself reports no anxiety measurement; the "possible anxiety" note in the fetch output was the tool's inference, not the page's. Source: https://blog.duolingo.com/widget-feature/
- Milestones: "on fire" metaphor didn't translate across cultures, so they moved to a phoenix power-up transformation; they ran several rough passes on animation timing because "timing and rhythm" matter as much as the art. Source: https://blog.duolingo.com/streak-milestone-design-animation/
- [design inference] For A2G: a commuter who misses two days should return to a neutral "pick up where you left off", not a broken-streak screen. Offer an opt-in streak or a weekly goal ("3 levels this week") instead of a mandatory daily one.

---

## 3. Q2. Investigation / deduction UI conventions

### 3.1 Generic genre language (safe to use)
- Two modes: exploring (collect) then thinking (assemble). Golden Idol: click characters, objects, documents to collect keywords; then fill blank slots in an incomplete account. Source: https://en.wikipedia.org/wiki/The_Case_of_the_Golden_Idol
- Obra Dinn: a logbook that auto-fills basic entries; the player names persons and picks causes of death; correct fates confirmed in sets of three to discourage guessing (the last six in twos); the book also holds the roster and a plan used to link faces to names; the logbook doubles as the timeline. Source: https://en.wikipedia.org/wiki/Return_of_the_Obra_Dinn
- Her Story: a search database of 271 clips; players view the clips and search words in their transcripts; a viewed/unviewed marker (red to green) tracks progress; player tags become searchable; ambiguity is deliberate; "the game ends when the player decides they have uncovered enough". Source: https://en.wikipedia.org/wiki/Her_Story_(video_game) [first 100k of 113k read].
- Contradiction mechanic: statement list, "press" for detail, "present" evidence. Sources: Ace Attorney and Danganronpa pages above.
- Court-style record that shows the evidence list was not described on the Ace Attorney page I read. [unverified]

### 3.2 How the aha is produced and kept readable (Golden Idol design article)
Source for this subsection: https://www.gamedeveloper.com/design/case-of-the-golden-idol
- Aim: sudden insight after being stuck; playtests showed confusion precedes breakthrough, so designers stay quiet and "let players struggle".
- Placing a set of phrases into slots worked better than building sentences from generic phrases; keep each scenario's phrase set small.
- Whole-solution right/wrong feedback frustrated players on complex scenarios; they added an indicator when "two or fewer slots are incorrect". Trade-off: lower difficulty and shorter length. Small side-puzzles (identify the people in a scene) gave a sense of "getting somewhere".
- Early rich tangential detail overwhelmed players; clues limited to those needed or those that create useful misdirection; multiple routes to the same conclusion.
- Anti-brute-force: "Given the opportunity, players will optimize the fun out of a game."
- The scroll text around the blanks supplies grammatical and semantic context so the player can place phrases without the UI doing the deduction.
- Difficulty is tuned by obscuring or adding clues (a written will made one case trivial).

Playtest lessons, https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving:
- Test with spreadsheets or placeholder art first; watch what players do and say rather than rely on metrics.
- Worst case = a player understands the main mystery at once, then gets stuck on a technicality; pacing is the first thing to fix.
- Remove clues that let testers bypass steps; testers lose context between sessions, so scenarios feel harder than intended (relevant to a commuter who plays 3 minutes at a time).

Obra Dinn critique, https://www.gamedeveloper.com/design/opinion-i-return-of-the-obra-dinn-i-is-a-work-of-art:
- Reasoning as a core mechanic gives "a peerless sense of ownership over discoveries".
- Pain points: the cause-of-death entry was opaque about multiple accepted answers ("isn't made terribly clear anywhere"); running corpse to corpse to revisit is tedious and crosses "to the wrong side" of challenge vs tedium.

Also reception complaints: Golden Idol some cases solved by guesswork, "extremely obscure" clues (https://en.wikipedia.org/wiki/The_Case_of_the_Golden_Idol); Danganronpa trials often too easy (Danganronpa page).

### 3.3 Mapping to A2G [design inference]
- Our "claim cards" are the keywords; the "evidence board" is the filled-in account. Make the board a persistent, scannable list of passages with page numbers, and let tapping a board item open the source page. This is the "Show evidence" button promoted to a core UI element.
- Deduction screen: show 3 to 5 blanks, 6 to 8 candidate phrases, confirm segment by segment; show "all but one correct" feedback rather than only pass/fail. State clearly when more than one answer is acceptable (the Obra Dinn complaint).
- Cross-examination: lay out the Summary's sentences as a list; the player taps the overclaim, then picks the page. Tell the player in the rules line how many wrong picks they have left.
- Resist a "hint" that does the deduction for the player; offer a hint that narrows the page range instead.

---

## 4. Q3. Narrative UI for short sessions

### 4.1 Conventions
- Visual novel text box formats: ADV (box covers part of the screen) vs NVL (box covers nearly the whole screen); progress by click, tap, or button; choices as menu options; branching vs kinetic (linear, no choices). Source: https://en.wikipedia.org/wiki/Visual_novel (first 100k of 152k; the page does not mention name tags, text speed, auto/skip, or backlog).
- Reference implementation (Ren'Py, a visual-novel engine): textbox anchored at the bottom with dialogue in the central part of the screen; a name box that appears only when the speaker has a name; per-character name colour; a skip indicator; a history screen (default 250 lines); choice buttons with idle and hover styles. Source: https://www.renpy.org/doc/html/gui.html
- Ren'Py say screen needs ids for name, dialogue, and window, and uses the dialogue element for auto-forward timing and click-to-continue. Source: https://www.renpy.org/doc/html/screen_special.html
- Player preferences standard in Ren'Py: text speed (default 0 = instant; otherwise characters per second), auto-forward time, "skip unseen text" (default off: skip only passes already-read text), "skip after choices" (default off: a choice stops skipping). Source: https://www.renpy.org/doc/html/preferences.html
- Dialogue-tree notes: replay lets players revisit missed information; the dialogue wheel existed so players "choose responses faster and read less text", but abbreviated labels can be misread and the spoken line may differ from what the player expected. Source: https://en.wikipedia.org/wiki/Dialogue_tree
- Game-writing guidance read: do not simply write shorter; "write smarter"; presentation (prompt animation, scrolling, timing) is a design decision; design for engagement, not consumption; trust the audience with nuance. Source: https://www.gamedeveloper.com/design/how-can-your-game-win-the-attention-war-focus-on-its-writing
- "Players don't want to read" is listed as a myth, but the page was only a podcast blurb with no detail. Source: https://www.gamedeveloper.com/design/busting-myths-in-game-writing-ft-bruno-dias-aysha-farah-andrea-saravia-perez (no usable numbers).
- Duolingo's character call (Lily) is structured as opener, first question, open conversation, closer; the closing prompt "keeps calls from running indefinitely". Source: https://blog.duolingo.com/ai-and-video-call/

### 4.2 Rules for A2G [design inference unless noted]
- Not found in any page I read: how many lines of dialogue players tolerate before skipping. [unverified] Set a house limit (for example 2 lines of at most about 140 characters between interactions) and test it in playtests.
- Tap-to-advance with the first tap completing the typewriter text and the second advancing is a common visual-novel behaviour [unverified; not stated on the pages read]. Offer instant text as default with a Settings option, since the Ren'Py default is instant.
- Provide a "log" (history) button that shows the last lines plus every evidence item collected. This doubles as the evidence board in the Case frame.
- Name tag on every character line; the narrator or system lines are untagged. Use portrait or avatar only for the partner/boss; for in-passage questions use no portrait (the page is the subject).
- Escape room: a briefing, goal-linked puzzles that unlock "new items or areas", hints that may cost something, a good vs bad ending. Source: https://en.wikipedia.org/wiki/Escape_room (the page does not use the terms linear/open/multilinear). Typical real rooms last 45 to 60 minutes (same URL), far longer than our 3 to 5 minutes, so show 3 to 4 "locks" per level at most [design inference]; the final lock is the synthesis question.
- Escape-the-room mobile games began as single-room "find clues and objects" Flash games (same URL). A single static room illustration with tappable hotspots is therefore a genre-standard layout for our Escape frame.
- Paper2Galgame and ahafrog: Paper2Galgame GitHub page returned 404; nothing read. ahafrog's home page (https://ahafrog.com/) says: topic in, game out; lessons about 5 minutes; 600+ game lessons; XP, leaderboards, streaks; a frog mascot; upbeat tone with "aha!" language; the page does not explain how generated games are structured. Their tone is the opposite of our maturity target.

---

## 5. Q4. Adult / university learners

- NN/g error-message guidance applies to wrong-answer feedback: take "a positive tone and don't blame the user"; avoid words like "invalid" or "incorrect" that imply fault; explain the specific cause in plain language; place the message next to the element; be constructive (offer remedies, preserve input); skip humour in routine errors "since users who see the same message repeatedly may find it stale". Source: https://www.nngroup.com/articles/error-message-guidelines/ (the article is about system errors, so applying it to quiz feedback is [design inference]).
- Feedback that works: informational and competence-conveying, not controlling praise. Verbal praise in one cited experiment enhanced intrinsic motivation while money reduced it (Deci, 1971), but the overjustification page also says verbal praise can feel controlling. Sources: https://en.wikipedia.org/wiki/Self-determination_theory and https://en.wikipedia.org/wiki/Overjustification_effect. Net: use specific, factual acknowledgement ("That passage on p.4 limits the claim to mice") not "Great job!".
- Feedback + difficulty: combining a desirable difficulty with feedback works better than studying or testing without feedback; unsuccessful retrieval attempts can still enhance later learning; short-answer tests gave bigger gains than multiple choice. Source: https://en.wikipedia.org/wiki/Testing_effect [design inference: this argues for including at least some constructed-response mechanics (deduce, order) over pure swipe].
- Misconception feedback: nothing I read was specific to misconceptions, so the following is [design inference]. For each wrong answer, store the near-miss reason authored at generation time ("This claim drops the 'in mice' qualifier") and show it with the passage. Duolingo's chess review principle of a few key takeaways, not every flaw, supports capping at one explanation per wrong answer.
- Tone evidence from Duolingo: the mascot is deliberately comic and self-aware, and brand memes about stalking or threatening users exist. Source: https://en.wikipedia.org/wiki/Duolingo. Lily is a "sarcastic emo teenage girl" persona with "delight and sass". Source: https://blog.duolingo.com/ai-and-video-call/. [design inference] Personality is OK, but for our users the teen/cute register is the wrong fit; use a dry, competent partner.
- Visual maturity: Duolingo's tab refresh argues "consistency must serve a purpose", "simplicity must not cost clarity", use whitespace instead of containers, fewer type styles. Source: https://blog.duolingo.com/core-tabs-redesign/. The page does not discuss adult vs child aesthetics. [design inference] Golden Idol (pixel art, 18th-century satire, Hogarth references) and Obra Dinn (1-bit monochrome) show that restrained palettes and period or noir art read as adult (https://en.wikipedia.org/wiki/The_Case_of_the_Golden_Idol and https://en.wikipedia.org/wiki/Return_of_the_Obra_Dinn). Do not copy their looks (see Do not copy).
- Sensitive content may not suit game framing; excessive visuals/sound can overload sensory-sensitive learners; competition can raise anxiety. Source: https://en.wikipedia.org/wiki/Gamification_of_learning. [design inference] No leaderboards in v1; sound off by default is the commute-appropriate choice.
- Tone checks I could not find a source for: specific survey data on adult learners disliking childish rewards. [unverified]

---

## 6. Q5. Recap screens and spaced review hooks without dark patterns

### 6.1 What the evidence supports
- Spacing and testing effects: spaced repetition makes new/hard items appear more often, old/easy ones less often; expanding intervals perform as well as or better than uniform spacing in many studies, but some meta-analyses find similar results and the "retrieval effort" explanation has little support; the best schedule may depend on the learner. Source: https://en.wikipedia.org/wiki/Spaced_repetition
- Early example schedule: reviews at 1, 2, 4, 8 days (Mace, 1932, as cited). Leitner boxes: correct cards move to less frequent boxes, failed cards return to box 1. Source: same URL. [design inference: a three-box Leitner scheme is simple enough to implement and explain.]
- Retrieval beats restudy for long-term retention; frequent low-stakes quizzes linked to better academic performance; do not drop cards too early. Source: https://en.wikipedia.org/wiki/Testing_effect
- Duolingo guidance on review: review "things you got wrong sooner"; wait longer on concepts you got right; use active recall. Source: https://blog.duolingo.com/spaced-repetition-for-learning/ (the page is general study advice plus a description of the in-product end-of-lesson mistake review).
- On-demand review lives inside the learning path; users choose between moving forward and reviewing targeted topics. Source: https://blog.duolingo.com/how-to-review-lessons-on-duolingo/

### 6.2 Recap-screen content [design inference]
1. Result in one line, framed as competence ("You found 3 of 4 overclaims").
2. 1 to 3 key moments, each: the claim, why it was wrong or limited, the page. One tap to "Show evidence".
3. Items to revisit, listed by name. Say when they will come back ("tomorrow, 2 questions").
4. Optional: "Continue" to next level or "Done for now". Both equal in weight (no guilt copy on the second).
5. Do not show XP-style numbers by default. A study-time tally ("12 min this week") is information, not reward.

### 6.3 Return hooks without dark patterns
- Taxonomy to avoid (Dark pattern page): confirmshaming (guilt wording on the decline option), misdirection (prominence tricks), roach motel (easy in, hard out). Source: https://en.wikipedia.org/wiki/Dark_pattern. The page did not define nagging, forced continuity, or guilt as categories.
- Notification principle [design inference]: user-set, tied to a concrete review item due ("2 claims from Lecture 6 are due"), one per day maximum, an easy off switch, no personified guilt.
- Forgiving streak: Duolingo's own data show slack (freeze) helps; and fear of losing a streak may stop users starting. Source: https://blog.duolingo.com/how-duolingo-streak-builds-habit/. Prefer a weekly goal or a "review due" counter.
- NN/g "visibility of system status" and "user control and freedom": tell users what is happening and give a clear exit. Source: https://www.nngroup.com/articles/ten-usability-heuristics/. [design inference: every level has a visible exit/save-and-quit with no penalty; the metro stop arrives mid-level.]
- Progress visibility helps competence: Edutopia headline "Helping High School Students Recognize Their Progress" exists but I did not read the article body. [unverified]

---

## 7. Pattern to frames and mechanics

Mechanics: S = Swipe supported/not, B = Bins sort, D = Deduce, O = Order steps, X = Cross-examination boss. Frames: Case, Escape, Rookie, Companion, Classic.

| Pattern | Applies to mechanics | Applies to frames | Source |
|---|---|---|---|
| Learning act = game act (intrinsic integration) | all | all | Gamification of learning; Educational game |
| Informational, non-blaming feedback with cause + next step | all | all (Rookie: the junior reacts to your fix) | NN/g error messages; SDT |
| "Show evidence" opens the source passage (page-cited) | all | all; central in Case (board) | design inference; Golden Idol clue discipline |
| Partial-correctness hint ("all but one slot right") | D, O, B | Case, Escape (lock) | Golden Idol design article |
| Segment confirm / lock in batches to deter guessing | D, B, O | Case, Escape | Obra Dinn; Golden Idol |
| Limited clue set, clues are needed or deliberate misdirection | D, X | Case | Golden Idol design article |
| Contradiction: pick statement, present page, cost for wrong | X | Case (partner objects), Rookie (junior's notes are the "statements") | Ace Attorney; Danganronpa pages |
| Variety of minigames inside the climax | X, D | Case, Escape | Danganronpa page |
| Evidence board that fills as clues are solved, doubling as log/timeline | D, X | Case, Companion | Obra Dinn logbook as timeline; Her Story viewed-marker |
| Room hotspots, locks, final lock = synthesis, optional hints | D, O, B | Escape | Escape room page |
| Briefing then goal then ending (good/bad ending) | boss, final lock | Escape, Case | Escape room page |
| Name box on speaker lines; textbox at bottom; tap to advance; history log | dialogue between mechanics | Case, Companion, Rookie | Ren'Py GUI docs |
| Text speed, auto-forward, skip-seen-only settings | dialogue | Case, Companion, Rookie | Ren'Py preferences |
| Controlled endings (a closing line, no endless chat) | dialogue | Companion, Rookie | Duolingo Lily post |
| Pretest-then-teach and one idea per lesson | S, B | Classic | Brilliant about |
| Difficulty adaptation (too hard = frustration, too easy = mastered) | all | all | Duolingo Birdbrain; Flow |
| End-of-level mistake review (key moments only) | all | all | Duolingo spaced repetition; chess Game Review |
| Spaced re-surfacing of missed items (Leitner-style) | S, B, D | all | Spaced repetition; Testing effect |
| Forgiving streak or weekly goal; no guilt copy | meta | all | Duolingo streak; Dark pattern |
| Progressive disclosure, 2 levels max | UI | all | NN/g progressive disclosure |
| Playtest with placeholders and watch players | process | all | Golden Idol testing article |

---

## 8. Do not copy (specific to one product)

- Ace Attorney: the "Objection!" shout and speech-bubble styling, the "Hold it!/Take that!" phrasing, the courtroom setting, the judge, the penalty-meter art, the "Present" button look and the named characters. Generic and safe: statement list, press for detail, present evidence, a cost for wrong evidence. (Source for the specifics: Ace Attorney page.)
- Danganronpa: Truth Bullets, Nonstop Debate branding, class-trial staging, the Monokuma-style antagonist, its pop-art style. (Danganronpa page.)
- Return of the Obra Dinn: 1-bit dithered monochrome look, the pocket-watch memento mechanic, the three-at-a-time "ding" confirmation as a signature, the ship/logbook framing. (Obra Dinn page.) The batch-confirm idea itself is a mechanic, not an identity.
- The Case of the Golden Idol: its word-bank-into-scroll layout is close to a signature screen; the 18th-century Hogarth pixel-art look. Use our own fill-in layout. (Golden Idol page.)
- Her Story: fake 1990s desktop OS database chrome. (Her Story page.)
- Duolingo: Duo and the other characters, the green palette, streak flame/phoenix and milestone transformations, "Duo is sad" push copy, the leagues names, hearts/energy, Gems economy. (Duolingo pages.)
- Brilliant: league names (Hydrogen to Einsteinium), the tutor Koji. (Brilliant pages.)
- ahafrog: frog mascot and "aha!" tagline; NotebookLM: audio overview format; Paper2Galgame: could not read, so do not assume anything about its look.
- Ren'Py: its default theme assets and file names. Conventions (name box, choice buttons) are generic; the shipped art is not ours.

---

## 9. Open questions / could not verify

- No web search was available; I only fetched known URLs. Several guessed URLs returned 404: nngroup.com gamification and microlearning articles, Paper2Galgame GitHub, brilliant.org/blog, a Duolingo "gamification explained" post, blog.duolingo.com/hub/learning-and-science/.
- design.duolingo.com redirects to blog.duolingo.com/hub/design/, which lists only a few posts (core tabs, widget). I found no Duolingo document on character or illustration guidelines, motion language, or feedback-screen copy. [unverified] for any claim about those.
- Brilliant feedback screens, correct/incorrect animation, and how it explains misconceptions: not found; only brilliant.org/about was read.
- The WebFetch tool returns a model-written summary of each page, not raw text. Quotes above are as reported by the tool. For decisions that depend on exact wording (Duolingo statistics such as +1.7%, +0.38%, 3.6x), re-open the page.
- Several Wikipedia pages were only read up to the first 100,000 characters (Visual novel, Ace Attorney, Danganronpa, Duolingo, Her Story, SDT, Testing effect, Flow). Gamification of learning was read up to the same limit (104k total). Things in later sections (e.g., Ace Attorney "court record") were not seen.
- Visual-novel UI facts: name tag, text speed, auto/skip, and backlog are described only in Ren'Py's docs (one engine), not in genre literature. Typical dialogue length before skipping: not found in any source. [unverified]
- Mobile portrait-specific visual-novel layout (portrait sprites, safe areas, thumb zones): not found. [unverified]
- Evidence that investigation-style framing improves retention of reading material: not found. The only effect-size numbers read are gamification meta-analyses (Hedges' g about 0.78 to 0.82 as reported on the Gamification of learning page, a Wikipedia summary), which are about gamification in general, not detective framing. Mark as hypothesis; plan an A/B of Classic vs Case frame on recall.
- Whether the "Summary overclaims" boss feels fair depends on the quality of generated overclaims; no source covers generating plausible overclaims. [unverified] Golden Idol notes that obscure or guessy clues were the top criticism, so human review of generated disproving passages is advisable.
- Adult-learner attitudes to gamified rewards, and misconception-targeted feedback specifically: no source read. [unverified]
- Date-sensitivity: some Wikipedia pages mention 2026 events; they reflect the snapshot at fetch time.
