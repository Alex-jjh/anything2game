I'll start by searching and fetching sources for all nine products in parallel.
# Anything2Game: mobile "learning as play" teardown (as of 19 Nov 2026)

**Method.** I used web search and web fetch only, with no hands-on use of any app. Quizlet's pages and Kahoot's help center returned 403. For those I relied on search-result snippets of the official pages, and I mark thin evidence. Nothing here is verified on Android. The Brilliant and Gizmo UI evidence comes from iOS-based teardowns. `[unverified]` marks a guess or a claim I could not confirm.

## Summary table

| Product | Core loop | Content source | Grounding / citations | Key source |
|---|---|---|---|---|
| ahafrog | Pick a topic, play a ~5-min game lesson, earn XP/streak | Catalog described as hand-crafted, plus an AI "Playground" | None stated | https://ahafrog.com/ |
| Duolingo | Path node, short lesson of varied exercises, check, XP/streak | Humans plan and write; AI builds most exercises | None. "Report" flag instead | https://blog.duolingo.com/how-duolingo-experts-work-with-ai/ |
| Brilliant | Interactive problem, manipulate, instant feedback, tutor hint | Authors design; AI builds puzzles; human review | Human review, not citations | https://blog.brilliant.org/hand-crafted-machine-made/ |
| Quizlet | Study a set via Learn / Flashcards / Match / Blast | User sets, plus AI tools | None found | https://quizlet.com/jp/teachers |
| NotebookLM (now Gemini Notebook) | Add sources, generate audio/flashcards/quiz, review | AI from your sources | In-line citations in chat | https://support.google.com/notebooklm/answer/16179559?hl=en-GB |
| Gizmo | Import, AI cards, quiz with hearts, spaced repetition | AI from your files | None found | https://makeheadway.com/blog/gizmo-app/ |
| Knowt | Upload, AI notes/cards/tests, Learn / Match; teacher-run Play | AI from your files, editable | None found | https://knowt.com/ai-flashcard-maker |
| Kahoot | AI-generate a quiz, join by PIN, answer fast | AI from PDF/URL/topic, teacher edits | None found | https://kahoot.com/ai-tools/ |
| Paper2Galgame | Upload, pick companion, play a 5–10 min AI visual novel with quizzes | AI generated live (DeepSeek) | None. Player told to verify | https://store.steampowered.com/app/4537400/Paper2Galgame/ |

---

## 1. ahafrog

1. **Core loop.** Pick a topic, play one game lesson of about 5 minutes, then earn XP, streaks and leaderboard rank, with a mastery percentage per subject (https://ahafrog.com/).
2. **Play-screen patterns.**
   - I could not see a play screen. The page only shows marketing captions such as "CH.3 EXPLORE THE CONCEPTS" (https://ahafrog.com/).
   - A testimonial describes a simulation where you control a curve's slope. That suggests manipulable simulations, but the testimonial is the only evidence (https://ahafrog.com/).
   - Layout, feedback and characters are `[unverified]`.
3. **Content.**
   - The catalog page says "Each course is a hand-crafted game experience" (https://ahafrog.com/courses/).
   - The home page also offers an AI "Playground" that generates a game from a typed topic, with a claim of 12 seconds (https://ahafrog.com/).
   - The AI game builder for educators is "coming soon", and the Studio page requires admin approval and your own API key (https://ahafrog.com/studio/).
   - No citations or source grounding are mentioned. Input is a topic, not a PDF (https://ahafrog.com/).
4. **Learn.** The constraint "one concept, one game, five minutes" matches our spec (https://ahafrog.com/).
   **Avoid.** The page makes self-reported claims I could not verify: 87% completion versus 15%, 4.9 store ratings and 52K learners. I found no store listing (https://ahafrog.com/). The site's first line is also a banner saying the server is temporarily offline.

## 2. Duolingo

Several sources below are from 2022–2023. I flag the year.

1. **Core loop.** Follow a single path of lessons, each with short varied exercises. Duolingo describes it as "the path" (Apple, 2023: https://developer.apple.com/news/?id=jhkvppla). Lessons are ordered to mix new and review content, using spaced repetition (2022: https://blog.duolingo.com/new-duolingo-home-screen-design/).
2. **Play-screen patterns.**
   - **Exercise variety:** fill-in-the-blank, word-bank sentence assembly with generated distractors, and "which word did you hear" with similar-sounding audio (https://blog.duolingo.com/how-duolingo-experts-work-with-ai/).
   - **Feedback:** after checking an answer, a "Report" flag appears next to the solution (2022: https://blog.duolingo.com/how-user-reports-improve-course-content/). A community forum confirms a green Check button appears once an answer is complete (https://forum.duome.eu/viewtopic.php?t=15293).
   - **Banner and progress bar:** the green or red banner and top progress bar are `[unverified]` from first-party sources. A third-party clone template describes a "Nice job!" or "Correct solution:" banner (https://www.vibecodeapp.com/templates/duolingo), but that is not Duolingo.
   - **Characters:** characters animate a reaction when you get an answer right (https://developer.apple.com/news/?id=jhkvppla). Rive state machines drive the character poses and the final reaction to right or wrong (https://blog.duolingo.com/world-character-visemes/). Lip-sync runs from phoneme timing, not hand animation (https://blog.duolingo.com/world-character-visemes/).
   - **Streaks:** milestones turn Duo into a phoenix "power-up", with a share card (2022: https://blog.duolingo.com/streak-milestone-design-animation/). Learner stays visible via a flame icon on the home top bar (https://blog.duolingo.com/how-streaks-keep-duolingo-learners-committed-to-their-language-goals/).
   - **Hearts replaced by Energy:** free learners lost a heart per mistake, and beginners were 2x more likely to run out mid-lesson. Energy is a battery that refills when you answer several in a row correctly (July 2025, tested on "a small number of users": https://blog.duolingo.com/duolingo-energy/).
3. **Content.**
   - Duolingo splits creation into four stages: curriculum design, raw content, exercise creation, and personalization. Humans lead the first two, and AI leads the later ones (https://blog.duolingo.com/how-duolingo-experts-work-with-ai/).
   - Most exercises are generated by algorithms from human-written raw content (https://blog.duolingo.com/how-duolingo-experts-work-with-ai/).
   - There are no source citations. Quality control is a user report flag, and about 200,000 reports arrive per day (2022: https://blog.duolingo.com/how-user-reports-improve-course-content/).
4. **Learn.** Make mistakes cheap and give wins visible, animated reactions. Duolingo says its hearts punished beginners, and its reaction animations come from a small asset set recombined at scale (https://blog.duolingo.com/duolingo-energy/, https://blog.duolingo.com/world-character-visemes/).
   **Avoid.** Punitive lives. Also, Duolingo found its flame metaphor was not understood across cultures (https://blog.duolingo.com/streak-milestone-design-animation/).

## 3. Brilliant

1. **Core loop.** Face a problem at the edge of your understanding, manipulate an interactive element, get immediate feedback, and ask the tutor if stuck. Lessons are "carefully sequenced questions, visual models, and hands-on problem solving" (https://brilliant.org/resources/choosing-brilliant/how-brilliant-teaches-math/).
2. **Play-screen patterns.**
   - **Input:** most problems are interactive. You type expressions with an in-app math keyboard shown below the question, or drag pieces. A "Start over" button sits beneath the problem (https://brilliant.org/help/features/how-do-i-use-interactives-on-brilliant/).
   - **Wrong answers:** explanations often include interactive elements. Lessons sit on a branching path, and completions show XP counters and animations. Loading screens use tangram-style animation (iOS teardown: https://screensdesign.com/showcase/brilliant-learn-by-doing).
   - **Tutor (Koji):** it sees the on-screen interactive state, can highlight or rearrange components, and does not give the answer (https://brilliant.org/help/features/how-does-koji-work/).
   - **Practice:** later practice removes scaffolding, varies surface details, and interleaves earlier topics (https://brilliant.org/resources/choosing-brilliant/how-brilliant-teaches-math/, https://brilliant.org/assessments/).
3. **Content.**
   - Authors own the learning objective and the "aha moment". AI generates the interactive puzzle implementation, and a separate tool makes practice variants (Jan 2025: https://blog.brilliant.org/hand-crafted-machine-made/).
   - Brilliant says puzzle success went from 0% to 93% after making game-engine representations more LLM-friendly. It also says every generated problem gets multiple rounds of human review (https://blog.brilliant.org/hand-crafted-machine-made/).
   - Brilliant chose not to ship a chatbot tutor early because of hallucinations (https://blog.brilliant.org/hand-crafted-machine-made/). It cites no sources to the learner.
4. **Learn.** Make the player act on the concept (drag, estimate, choose) before explaining it. Keep a tutor that answers from the on-screen state.
   **Avoid.** Anything that needs a hand-built interaction per concept. Brilliant's authors still design every level, and AI only fills in implementation (https://blog.brilliant.org/hand-crafted-machine-made/). Also, its onboarding puts sign-up and a paywall before the first lesson (https://screensdesign.com/showcase/brilliant-learn-by-doing).

## 4. Quizlet

Quizlet pages returned 403. This section relies on search snippets of official pages, and mobile layout is `[unverified]`.

1. **Core loop.** Study a set (terms and definitions) in Flashcards, Learn, Test, Match or Blast (https://quizlet.com/jp/teachers).
2. **Play-screen patterns.**
   - **Learn:** builds "a personalized study plan based on your familiarity with a study set" (https://quizlet.com/gb/features/study-modes). Write and Spell are now parts of Learn (https://help.quizlet.com/hc/en-us/articles/360030841732-Studying-on-Quizlet).
   - **Match:** students race to match terms and definitions (https://quizlet.com/jp/teachers).
   - **Blast:** terms or definitions appear on moving asteroids and you blast the right one. Most points when time ends wins (https://help.quizlet.com/hc/en-us/articles/27393733229965-Playing-a-game-of-Blast).
   - **Live:** a team game where the first team to answer twelve questions correctly wins (https://quizlet.com/gb/features/study-modes).
3. **Content.** Sets are user-made. Quizlet also lists AI practice tests, AI study guides, a PDF summarizer and an AI flashcard maker (https://quizlet.com/features/ai-study-tools). The Q-Chat tutor "is no longer available" as of June 2025 (https://quizlet.com/blog/meet-q-chat). I found no citation feature.
4. **Learn.** One content set reshaped into many play modes (flashcard, match, shooter) keeps variety cheap (https://quizlet.com/jp/teachers).
   **Avoid.** Paywalling the core study loop. Learn requires Plus, with one free session (https://help.quizlet.com/hc/en-us/articles/360030986971-Studying-with-Learn). A review of Knowt says it grew as a free home for students who left when Learn and tests went behind a paywall (https://tooltivity.com/extensions/knowt).

## 5. Google NotebookLM (renamed Gemini Notebook, July 2026)

1. **Core loop.** Add sources, generate a Studio output (Audio Overview, flashcards, quiz), then consume it. The renaming is reported by a third party (https://www.cloudspress.com/the-notebooklm-mobile-apps-flashcards-and-quizzes-make-studying-portable-now-gemini-notebook/). Google's own help pages now say "Gemini Notebook" (https://support.google.com/notebooklm/answer/16269187).
2. **Play-screen patterns (mobile).**
   - **Audio Overviews:** offline download, background playback, and a "Join" button to ask the hosts a question (May 2025: https://blog.google/technology/ai/notebooklm-app).
   - **Flashcards and quizzes (Nov 2025):** a full-screen portrait card where a tap reveals the answer, with arrows at the bottom. Quizzes are multiple choice with an optional Hint, and every answer, right or wrong, offers an explanation. You can set card count, difficulty, language and a custom prompt (https://9to5google.com/2025/11/06/notebooklm-app-flashcards-quizzes/).
   - **Progress:** mark cards "Got it!" or "Missed it!", then retry "Only cards you missed". An "Explain" button is available (https://support.google.com/notebooklm/answer/16958963?hl=en).
   - **Mind maps:** not supported in the mobile app (https://support.google.com/notebooklm/answer/16212283?hl=en-GB).
3. **Content and grounding.**
   - Output comes from your sources. Chat citations are direct quotes, and hovering shows the quoted text (https://support.google.com/notebooklm/answer/16179559?hl=en-GB).
   - You can temporarily deselect sources for an output (https://9to5google.com/2025/11/06/notebooklm-app-flashcards-quizzes/).
   - Very short sources get no per-passage citations (https://support.google.com/notebooklm/answer/16269187).
   - Citations inside quiz explanations are claimed only by a third-party article, so treat as `[unverified]` (https://www.cloudspress.com/the-notebooklm-mobile-apps-flashcards-and-quizzes-make-studying-portable-now-gemini-notebook/). Google says to double-check responses (https://support.google.com/notebooklm/answer/16212283?hl=en-GB).
4. **Learn.** Per-question "Explain", hint and "retry what I missed" fit a short session. Citation-on-hover is the strongest evidence pattern here.
   **Avoid.** A flat flip-card and quiz format has no game layer. It reads as a study tool, not play.

## 6. Gizmo

1. **Core loop.** Import a file or video with "Magic Import", get AI cards with key words highlighted in green, quiz in Memorize mode, then let spaced repetition schedule reviews (https://makeheadway.com/blog/gizmo-app/).
2. **Play-screen patterns.**
   - **Mascot:** an axolotl appears from launch to loading screens. An "Explain" button gives AI explanations during quizzes (iOS teardown: https://screensdesign.com/showcase/gizmo-ai-flashcards).
   - **Rewards:** collectible monster eggs hatch into creatures with XP bonuses. Live quiz games start from a shared code (https://screensdesign.com/showcase/gizmo-ai-flashcards).
   - **Progress and lives:** XP, levels, leagues and streaks. Wrong answers cost hearts, up to 15 on free (https://makeheadway.com/blog/gizmo-app/).
   - **Source conflict:** another review says running out of lives locks you out for ten minutes (https://ailistingtool.com/blog/gizmo-ai-review-2025-complete-guide).
3. **Content.**
   - AI only. Sources include PDFs, PowerPoint, YouTube and photos of notes (https://gizmo.ai/pdf/).
   - Quality drops on applied content, and medical students report editing many cards (https://ailistingtool.com/blog/gizmo-ai-review-2025-complete-guide).
   - An App Store reviewer said a quiz asked about nerves their notes had not covered (https://makeheadway.com/blog/gizmo-app/).
   - I found no citations. The help center advises importing big documents in smaller sections (https://makeheadway.com/blog/gizmo-app/).
4. **Learn.** Highlight the key words the learner will be tested on, and put the explanation button on the answer screen.
   **Avoid.** Hearts that lock out a cramming student, and questions that go beyond the source (https://ailistingtool.com/blog/gizmo-ai-review-2025-complete-guide, https://makeheadway.com/blog/gizmo-app/).

## 7. Knowt

1. **Core loop.** Upload up to 30 files, and "Kai" makes notes, flashcards, assessments and more. Then study in Learn, Practice Test, Spaced Repetition or Match. Knowt Play is a teacher-run live game (https://knowt.com/ai-flashcard-maker, https://help.knowt.com/en/articles/10722204-student-s-guide-to-getting-started).
2. **Play-screen patterns.**
   - **Learn:** multiple choice, written and true/false (https://knowt.com/flashcards).
   - **Grading:** a user review says it forgives close typos and lets you override a grade (https://apps.apple.com/us/app/knowt-ai-flashcards-notes/id6463744184).
   - **Play modes:** Classic, Race, Clash, Blitz, and Survival (a wrong answer knocks you out). Students join on their own devices with a 6-digit code (https://help.knowt.com/en/articles/16905166-how-to-host-a-knowt-play-game, https://help.knowt.com/en/articles/12010990-getting-started-with-knowt-play).
   - **Pop-ups:** a reviewer said streak and XP pop-ups were intrusive, and a patch fixed that (https://apps.apple.com/us/app/knowt-ai-flashcards-notes/id6463744184).
3. **Content.** AI-generated, fully editable (https://knowt.com/ai-flashcard-maker). Millions of user-made sets can also be imported, including from Quizlet (https://tooltivity.com/extensions/knowt). I found no citation feature.
4. **Learn.** Let the player contest a grade, and keep rewards from interrupting the question.
   **Avoid.** Intrusive streak/XP pop-ups during study.

## 8. Kahoot (AI quiz from PDF)

1. **Core loop.** Generate a kahoot from a topic, PDF, URL, Wikipedia article, slides or handwritten notes. Then host it or play it by PIN (https://kahoot.com/ai-tools/, https://kahoot.com/home/mobile-app/).
2. **Play-screen patterns.**
   - **Join and answer:** the app page shows a join screen with a QR code, PIN and "colorful answer buttons" on a space background (image captions: https://kahoot.com/home/mobile-app/).
   - **Answer limits:** 2–6 answers per question, each up to 75 characters or an image (https://support.kahoot.com/hc/en-us/articles/115002308428-Kahoot-question-types).
   - **Solo play:** progress shows in the bottom-right corner (https://support.kahoot.com/hc/en-us/articles/115016045187-How-to-play-kahoot-solo-in-the-Kahoot-app).
   - **AI formats:** Quiz, Presentation, Micro lesson (https://support.kahoot.com/hc/en-us/articles/34251401475101-How-to-choose-the-right-format-when-generating-a-kahoot-with-AI). A PDF-to-story option makes a swipeable story (https://kahoot.com/ai-tools/).
3. **Content.**
   - AI-generated, then teachers can refine by editing, adding and deleting questions (https://kahoot.com/blog/2025/03/18/leveling-up-learning-ai-tools-for-education/).
   - The mobile help page said PDF upload was coming "soon" (https://support.kahoot.com/hc/en-us/articles/32816978203283-How-to-use-Kahoot-AI-tools-in-mobile-app).
   - I found no source citations. The editor's Assistant panel mentions "accuracy checks" only in an image caption (https://kahoot.com/ai-tools/).
4. **Learn.** A clear editable review step between generation and play.
   **Avoid.** Speed-and-streak scoring aimed at groups. Teachers have asked for a way to disable the answer streak bonus (https://support.kahoot.com/hc/fr/community/topics/115000563307-Create-and-play?before=ZAAAAAAAAAAAaQsAAAAAAAAAZKN1M1wAAAAAaf0DF9NTAAAA&filter_by=completed&sort_by=votes). For a solo student, timer pressure is `[unverified]` as a fit.

## 9. Paper2Galgame (Steam)

This is a desktop game (Windows, macOS, SteamOS/Linux), not mobile (https://store.steampowered.com/app/4537400/Paper2Galgame/). The release date differs by source: 9 Aug 2026 on Steam, and 28 Jul 2026 on 4Gamer (https://www.4gamer.net/games/976/G097641/).

1. **Core loop.** Upload a PDF, TXT or DOCX, pick an anime companion, and the AI turns it into a 5–10 minute story advanced chapter by chapter (https://store.steampowered.com/app/4537400/Paper2Galgame/).
2. **Play-screen patterns.**
   - **Layout:** a companion walks you through structure, concepts and takeaways. There are quizzes, voice narration (English, Japanese, Chinese) and a "blackboard" for formulas (https://store.steampowered.com/app/4537400/Paper2Galgame/).
   - **Customization:** companion art, expressions, personality, speaking style and voice are customizable, with Steam Workshop sharing (https://store.steampowered.com/app/4537400/Paper2Galgame/).
   - **Quiz format:** one reviewer says it asks questions with three answer choices (https://steamcommunity.com/app/4537400/reviews/).
3. **Content.**
   - Generated live by AI, with no fixed script (https://store.steampowered.com/app/4537400/Paper2Galgame/). It connects to DeepSeek (https://store.steampowered.com/app/4537400/Paper2Galgame/).
   - The AI disclosure tells players "to independently verify any important information", and no citations are mentioned.
   - The store page warns that one huge file "can lower quality or fail entirely" and recommends splitting by chapter (https://store.steampowered.com/app/4537400/Paper2Galgame/).
   - Steam shows 83% positive of 98 reviews (https://store.steampowered.com/app/4537400/Paper2Galgame/).
4. **Learn.** A persistent companion plus a bounded 5–10 minute episode per upload is a strong frame, and one chapter per session keeps scope small.
   **Avoid.** Ungrounded generation with a "please verify" disclaimer. A separate open-source repo with a similar name uses characters from commercial games (https://github.com/ZenAlexa/Paper2GalGame). Whether it is related to the Steam product is `[unverified]`.

---

## Patterns relevant to Anything2Game (my inference from the above)

- **Evidence per answer is a gap.** Only NotebookLM shows quotes from your own sources (https://support.google.com/notebooklm/answer/16179559?hl=en-GB). Brilliant uses human review (https://blog.brilliant.org/hand-crafted-machine-made/). Paper2Galgame, Gizmo and Quizlet showed none that I found. Since nobody pairs game feel with per-answer citations, this is an opening `[unverified as a market claim]`.
- **Where AI helps most.** Brilliant and Duolingo use AI for implementation and variants, with humans owning the design (https://blog.brilliant.org/hand-crafted-machine-made/, https://blog.duolingo.com/how-duolingo-experts-work-with-ai/). Gizmo's and Paper2Galgame's reports of off-source content show the risk of generating freely from a file (https://makeheadway.com/blog/gizmo-app/, https://store.steampowered.com/app/4537400/Paper2Galgame/).
- **Free-session limits drew complaints.** Duolingo moved off hearts and Gizmo's lives drew criticism (https://blog.duolingo.com/duolingo-energy/, https://ailistingtool.com/blog/gizmo-ai-review-2025-complete-guide).

## Gaps

- No first-party view of ahafrog's play screen, or of Quizlet's and Kahoot's mobile layouts.
- Duolingo lesson-screen layout (progress bar, banner colors) is not confirmed from first-party sources.
- Adjacent products surfaced but not analyzed: StudyQuest (https://studyquest.app/), StudyFetch Arcade (https://www.studyfetch.com/arcade), AvaStudy (https://avastudy.com/) and EdGameClaw (https://www.github.com/yh2072/edgameclaw). All claim to turn PDFs or notes into games. They would be the nearest competitors to check next.
