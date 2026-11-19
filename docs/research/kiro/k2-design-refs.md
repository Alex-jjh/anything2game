I'll start with searches for all five areas in parallel.
Now I'll fetch the primary sources.
The Material 3 pages need JavaScript, so I'll look for fetchable alternatives. I'll also search for the detective, visual-novel and Brilliant sources.
# Design references for a Compose learning-detective game

**How to read this.**
- Source claims carry the URL I read them at.
- Lines starting "**Apply:**" are my proposals for your app. They are not sourced claims. Anything in them that assumes behavior I didn't read is marked [unverified].
- "Snippet only" means I saw the text in a search result but couldn't open the page. The m3.material.io pages return "This website requires JavaScript."
- Gaps are listed at the end.

---

## (a) Mobile learning-game UI

**A1. "Shape language: Duolingo's art style"**, Megan Barker, Duolingo, 2020. https://blog.duolingo.com/shape-language-duolingos-art-style/
- Illustrations use the fewest details needed. Too few confuses, and too many make shapes "less readable at a glance". (https://blog.duolingo.com/shape-language-duolingos-art-style/)
- Key shapes get room to be clearly silhouetted, and artwork is framed with white negative space because it lives on a small screen. (https://blog.duolingo.com/shape-language-duolingos-art-style/)
- Animated skill icons held attention. Learners spent more time watching the whole animation than glancing at a static illustration. (https://blog.duolingo.com/shape-language-duolingos-art-style/)
- Exaggeration and humor are used to reduce the fear of making mistakes. (https://blog.duolingo.com/shape-language-duolingos-art-style/)
- **Apply:** Design evidence and swipe-card art silhouette-first, and check it at thumbnail size. Use small motion on the one element you want the eye to go to.

**A2. "Art meets technology: the next step in bringing our characters to life"**, Jasmine Vahidsafa and Kevin Lenzo, Duolingo, 2022. https://blog.duolingo.com/world-character-visemes/
- Rive's State Machine lets them control which animation states are called and how they transition and blend. (https://blog.duolingo.com/world-character-visemes/)
- Each character has its own mouth shapes, 20+ in all, designed to follow that character's personality. (https://blog.duolingo.com/world-character-visemes/)
- At runtime, the challenge outcome (right or wrong) moves the character to a final reaction state. Idle behavior includes nods, blinking and eyebrows, and the character stops speaking in time if you finish early. (https://blog.duolingo.com/world-character-visemes/)
- Driving animation from timing data sends much less data than a small movie. (https://blog.duolingo.com/world-character-visemes/)
- **Apply:** Build the cross-examination witness as one rig with idle, speaking and reacting states, switched by answer correctness. A Rive Android runtime in Compose is [unverified], because I didn't read Rive's docs.

**A3. "Behind the Design: Duolingo"**, Apple Developer (quoting Ryan Sims, VP of design), 2023. https://developer.apple.com/news/?id=jhkvppla
- Sims says: "we're not an education company. We're a fun and motivation company." (https://developer.apple.com/news/?id=jhkvppla)
- The characters' subtle animations when people get something right "are a reward in themselves". They were informed by watching how people connected with Duo. (https://developer.apple.com/news/?id=jhkvppla)
- The cast is built so learners meet the same people through the whole experience, and staff keep the characters' stories consistent. (https://developer.apple.com/news/?id=jhkvppla)
- **Apply:** Reuse one recurring witness or boss across levels, so each reaction animation carries over from earlier encounters.

**A4. "Elevating craft: How we refreshed our core tabs"**, Leah Lee and Lokesh Fulfagar, Duolingo, 2026. https://blog.duolingo.com/core-tabs-redesign/
- They prototyped four extreme directions (punchy, soft, modular, flat) and tested them on phones. (https://blog.duolingo.com/core-tabs-redesign/)
- Consistency must be balanced with purpose. Art in every header felt forced when it signaled nothing useful. Simplicity must be balanced with clarity. (https://blog.duolingo.com/core-tabs-redesign/)
- Fixes: tiered header sizes, a minimal set of type styles, and whitespace around components instead of containers. (https://blog.duolingo.com/core-tabs-redesign/)
- QA used design overlays. They report higher engagement while core learning metrics held. (https://blog.duolingo.com/core-tabs-redesign/)
- **Apply:** Give each of your five mechanics a header tier and a fixed title position. Use art only where it carries information.

**A5. "About Wonder Blocks, Khan Academy's design system"**, Nefaur Khandker and May-Li Khoe, Khan Academy (via designsystems.com). The page is undated and describes 2017 work. https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/
- Four principles: Empowering, Credible, Flexible, Joyful. "Joyful" says to celebrate small wins, with color, shapes, animation and audio as "agents of contagious enthusiasm". (https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/)
- Color went from 58 to 18 values with functional roles. Blue denotes action, and red and green are accompanied by text. (https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/)
- Type styles dropped from 119+ to 14. Information density matters because many users have low-cost older hardware and weak connections. (https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/)
- The principles were turned into a scorecard used to rate each element. (https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/)
- **Apply:** Reserve one color for "tappable". Never signal correct or wrong with color alone, and pair it with text or an icon. Write a four-line principle scorecard and rate each screen.

**A6. "Game on: Solving for x-citement with interactive Algebra"**, Brilliant staff, 2024. https://blog.brilliant.org/solving-equations/
- Each lesson starts with a problem "right on the edge of your understanding" that should need "one small mental leap". Tools are provided to try different answers, such as drag-and-drop equation building. (https://blog.brilliant.org/solving-equations/)
- Explaining everything before letting the learner act is called a bad way to start a game and a bad way to start learning. Formal names for techniques come after intuition. (https://blog.brilliant.org/solving-equations/)
- Learned techniques are framed as equipment that helps against "the big boss". (https://blog.brilliant.org/solving-equations/)
- **Apply:** Open each level with a playable case and no tutorial text. Name the deduction technique after the player has used it. Frame earned techniques as boss-fight tools.

**A7. "Hand-crafted, machine-made: How we make learning games with AI"**, Brilliant staff, 2025. https://blog.brilliant.org/hand-crafted-machine-made/
- Their Mario example: you get no "you pressed jump too early" text. You fall in the pit and learn. The goal is understanding "through doing, not reading". (https://blog.brilliant.org/hand-crafted-machine-made/)
- A course with 50+ concepts needs 20+ problems per concept (1,000+ in total). That takes a difficulty ramp and edge cases. (https://blog.brilliant.org/hand-crafted-machine-made/)
- Human authors own the learning objective, progression and "aha moment". Every generated problem gets human review, since one wrong problem can shake a learner's confidence. (https://blog.brilliant.org/hand-crafted-machine-made/)
- **Apply:** Let consequences teach, such as a card landing in the wrong bin. Budget content as concepts × variants × edge cases. Review every deduction case for a unique, correct solution.

**A8. "How Brilliant.org motivates learners with animations"**, Valerie Veteto (Rive), interviewing Brilliant's Zack Davenport and Juliana Chen, 2024. https://rive.app/blog/how-brilliant-org-motivates-learners-with-rive-animations
- Brilliant chose Rive over Lottie because Rive's State Machine defines transitions between user-triggered states. Rive files stayed small. (https://rive.app/blog/how-brilliant-org-motivates-learners-with-rive-animations)
- The streak animation is aligned with the increasing number, using event triggers, so it feels tied to the user's action. (https://rive.app/blog/how-brilliant-org-motivates-learners-with-rive-animations)
- One Rive file across Android, iOS and web means a bug is fixed once. (https://rive.app/blog/how-brilliant-org-motivates-learners-with-rive-animations)
- **Apply:** Tie celebration timing to the exact moment the score or progress number changes.

---

## (b) Detective and investigation UI

**B1. "For Lucas Pope, *Return of the Obra Dinn* was a bunch of appealing design problems"**, Bryant Francis, Game Developer, 2018. https://www.gamedeveloper.com/design/for-lucas-pope-i-return-of-the-obra-dinn-i-was-a-bunch-of-appealing-design-problems
- Pope likes "rigid core mechanics". The core is filling out a matrix of features for a list of identities. (https://www.gamedeveloper.com/design/for-lucas-pope-i-return-of-the-obra-dinn-i-was-a-bunch-of-appealing-design-problems)
- The fun shifted late in development from how people died to who they are, which let environmental clues feed a logic puzzle. (https://www.gamedeveloper.com/design/for-lucas-pope-i-return-of-the-obra-dinn-i-was-a-bunch-of-appealing-design-problems)
- Dithering in moving images "gives you a headache", and making a four-hour game comfortable was a main problem. He fixed it by keeping the dither pattern fixed to the screen. (https://www.gamedeveloper.com/design/for-lucas-pope-i-return-of-the-obra-dinn-i-was-a-bunch-of-appealing-design-problems)
- **Apply:** Make "deduce" a small grid (suspect × motive × evidence). Keep any high-contrast flash effects short, and test them over a long session [unverified].

**B2. "How a book binds the Return of the Obra Dinn"**, Alex Wiltshire, Rock Paper Shotgun, 2018. https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn
- Pope says the book is the key interface, "using the book to figure shit out". It grew from a plain list of names. (https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn)
- A timeline UI felt cluttered. Chapter headings that sit in order made a book work "like a timeline". (https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn)
- D-pad selection was separated from page turning "to avoid accidental page turns". There is a bookmark per character and exactly one shortcut, to the table of contents, so little needs teaching. (https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn)
- A forced one-minute scene slows players and helps them notice details. Pope also deliberately inconveniences the player to hold the mood. (https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn)
- **Apply:** Keep one persistent case file that is always reachable. Don't put swipe-to-flip on the same surface as swipe-to-judge. Use a "timed look" moment before the player can answer.

**B3. "Pursuing the 'Aha!' moment with deductive reasoning game *The Case of the Golden Idol*"**, Joel Couture interviewing Andrejs Klavins, Game Developer, 2022. https://www.gamedeveloper.com/design/case-of-the-golden-idol
- Marking the whole fill-in-the-blank solution right or wrong frustrated players in complex cases, because they couldn't tell which deductions were correct. Extra puzzles (identifying people in the scene) gave a sense of progress. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
- An indicator saying "two or fewer slots are incorrect" rewards being close. It also reduced difficulty and game length. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
- Validating each slot invites brute force. With a limited input system they chose a middle path: a limited phrase set per scenario, plus grammatical and semantic context around the blanks. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
- Rich, lived-in spaces with tangential information backfired. They reduced content to what is needed for the solution or for interesting misdirection. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
- They playtested "at least 5-7 times", watching silently. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
- **Apply:** For the deduce and order-steps mechanics, show a "N of M in place" hint, or "within 2", rather than all-or-nothing. Give a bounded word bank per case.

**B4. "*The Case of the Golden Idol* used frequent testing to sharpen its mystery-solving"**, Joel Couture interviewing Andrejs Klavins, Game Developer, 2023. https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving
- They tested early with basic visuals or Excel tables, and used playtest interviews rather than quantitative data. (https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving)
- A "thought path" lists which facts are sequential and which parallel. If a player skips many steps through one clue, that clue is removed. (https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving)
- Pacing failure is "understands the main mystery immediately, then stuck for thirty minutes on a technicality". (https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving)
- **Apply:** Write a thought-path table per case and check that no single clue bypasses it. Mobile sessions are short, so cap the "stuck on a technicality" time [unverified].

**B5. "The GUI of the Golden Idol"** (a tutorial), Yuri Sizov, GitHub. This is a fan reimplementation, so it is secondary. The repo is archived and has no date I could read. https://github.com/YuriSizov/tutorial-gui-of-golden-idol/blob/main/TUTORIAL.md
- The mechanic is picking word clues from texts into a word bank, then dragging them into blanks in a dossier. (https://github.com/YuriSizov/tutorial-gui-of-golden-idol/blob/main/TUTORIAL.md)
- "All blanks of the same type are presented with an equal size to avoid giving up the information by the shape of the blank alone." (https://github.com/YuriSizov/tutorial-gui-of-golden-idol/blob/main/TUTORIAL.md)
- Validation is a per-blank check against allowed values, and several answers can be accepted for one blank. (https://github.com/YuriSizov/tutorial-gui-of-golden-idol/blob/main/TUTORIAL.md)
- **Apply:** Use equal-size slots and chips in the deduce and order-steps screens. Compose has drag-and-drop modifiers (`DragAndDropSourceModifierNode` and `DragAndDropTargetModifierNode` appear in the API index I read: https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api).

**B6. "Ace Attorney Trilogy: Interview with creator Takumi Shu"**, Frontline Gaming Japan (translating a Famitsu interview), 2019. This is secondary, and the part I read doesn't discuss the "present evidence" UI. https://www.frontlinejp.net/2019/02/14/ace-attorney-trilogy-interview-with-creator-takumi-shuu-part-1/
- Hardware limits shaped the style. Evidence photos and flashbacks were monochrome (16 colors) to save cartridge space. (https://www.frontlinejp.net/2019/02/14/ace-attorney-trilogy-interview-with-creator-takumi-shuu-part-1/)
- Takumi set line timing, character animation, SFX, music, and effects like flashes and camera shake. The programmers built a tool for this early. (https://www.frontlinejp.net/2019/02/14/ace-attorney-trilogy-interview-with-creator-takumi-shuu-part-1/)
- **Apply:** Build a small beat-timeline tool for boss scenes (text, animation, sound, haptic) before writing many encounters.

**B7. Ace Attorney fan wikis** (snippet only, secondary).
- The court record is reachable at nearly every prompt, via a "Court Record" or "Present" button. (https://aceattorney.fandom.com/wiki/Court_record)
- In the trial UI, one button "presses" a witness for more information, and another opens the record to present evidence. (https://strategywiki.org/wiki/Phoenix_Wright:_Ace_Attorney/Gameplay)
- The player can step through any statement of a testimony with left/right buttons on the touchscreen. (https://aceattorney.fandom.com/zh/wiki/%E5%AE%A1%E5%88%A4)
- **Apply:** The boss screen needs three controls: prev/next statement, press, and present.

---

## (c) Visual-novel and dialogue UI for short mobile sessions

I found no primary design write-up specific to short mobile VN sessions. These two are indie developers' own devlogs.

**C1. "Tips on making your VN more accessible"**, LadyIcepaw, itch.io devlog, about 2022 (the page shows "4 years ago"). https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible
- The text box is "the most important piece of UI in a visual novel". Use strong light-dark contrast and avoid elaborate patterns or flashy fonts. (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
- Offer font, size and text-spacing options. Check the UI through a colorblindness filter. Add a non-color cue (a pin) for selected toggles. (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
- Offer options to disable screenshake and "Ace Attorney-esque screen flashes". (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
- Consider making minigames optional or skippable. (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
- Almost 30% of one of her games' downloads were for Android, and the interface needed tweaks for small touch screens. (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
- **Apply:** Add settings for text size, shake/flash off, and reduced motion from day one. Make the boss's flash and shake effects toggleable.

**C2. "Slipstream Daydreams Devlog 02: UI Design and Accessibility"**, tackyvillain, itch.io / neocities, Feb 2025. https://tackyvillain.neocities.org/articles/devlogs/ssdd-02
- The developer used an NVL-style scrolling text column. The reasoning: "we are all so used to reading on our smartphones", and a column avoids walls of text and needs less eye movement. (https://tackyvillain.neocities.org/articles/devlogs/ssdd-02)
- Text-size options, a dyslexia-friendly font (Atkinson Hyperlegible) and a dark mode were costly to add late. (https://tackyvillain.neocities.org/articles/devlogs/ssdd-02)
- Mockups were made from screenshots of each screen, laid out on artboards to spot inconsistencies. (https://tackyvillain.neocities.org/articles/devlogs/ssdd-02)
- **Apply:** Use a short column of 1–3 lines per beat for testimony. Wire text scaling and dark theme into your theme from the start, since the devlog says adding dark mode late was hard.

**Snippet only:** A fuwanovel forum post says a translucent text box at the bottom eighth of the screen keeps text clear without fully hiding the background and sprites. (https://forums.fuwanovel.moe/blogs/entry/4226-ui-design-%E2%80%93-an-anatomy-of-visual-novels/)

---

## (d) Material 3 Expressive on Android

**D1. "Better, Easier, Emotional UX"**, Google Material research team (credited researchers and designers), Google Design, 2025 [year inferred from "past three years" counted from 2022 in the text]. https://design.google/library/expressive-material-design-google-research
- It is based on 46 studies with 18,000+ participants. The fundamentals of expressive design are color, shape, size, motion and containment. (https://design.google/library/expressive-material-design-google-research)
- The email example: a larger Send button, placed above the keyboard, in a secondary color, was spotted up to four times faster. Older users' fixation times matched younger users'. (https://design.google/library/expressive-material-design-google-research)
- A test that replaced a familiar list with scattered album art lowered usability. "No amount of expressive design will beat basic functionality." (https://design.google/library/expressive-material-design-google-research)
- A strong minority preferred calmer designs, so start from user need, follow accessibility standards, and iterate. (https://design.google/library/expressive-material-design-google-research)
- **Apply:** Make the single primary action per screen large and low on the screen. Use expressive shape and color for rewards, and keep drag targets and labels plain.

**D2. "Androidify: Building delightful UIs with Compose"**, Rebecca Franks, Android Developers Blog, 20 May 2025. https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html
- Opt in with `MaterialExpressiveTheme` and `motionScheme = MotionScheme.expressive()`. Stated version: `material3:1.4.0-alpha10`. (https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html)
- A press-scale animation uses `MaterialTheme.motionScheme.defaultSpatialSpec()`, described as the spec for animations that move something on screen, including scale and rotation. (https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html)
- `MaterialShapes` are presets "that allow for easy morphing between each other". A shared-element transition uses `Morph(RoundedPolygon...)` with `MotionScheme.sharedElementTransitionSpec` as the bounds transform. (https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html)
- **Apply:** Morph a card's shape into the bin it lands in. Morph the "present evidence" button from rectangle to circle when armed. Both use the theme's motion specs instead of hand-tuned durations.

**D3. Material "Motion" theming doc**, Material Components Android team, GitHub (undated; View-system docs for version 1.13.0 and later). https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md
- There are six springs: fast, default and slow, each in a spatial and an effects type. The speed is chosen by component size or distance covered. (https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)
- Default values include fast spatial (damping 0.9, stiffness 1400), slow spatial (0.9, 300) and default spatial (0.9, 700). Effects springs use damping 1 so they don't overshoot. (https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)
- Effects springs animate color and opacity "where the property's value should not be overshot". Their example for a pressed button is a fast spatial spring for shape and size, and a fast effects spring for color. (https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)
- **Apply:** Use spatial springs for card fling, bin snap and slot reorder, and effects springs for the correct/wrong color flash. Mapping these slots one-to-one to Compose `MotionScheme` names is [unverified].

**D4. Button group doc**, Material Components Android team, GitHub. https://raw.githubusercontent.com/material-components/material-components-android/master/docs/components/ButtonGroup.md
- Button groups "apply shape, motion, and width changes to buttons" to make them more interactive. The default size change for a pressed child is 15%. (https://raw.githubusercontent.com/material-components/material-components-android/master/docs/components/ButtonGroup.md)
- Segmented buttons are deprecated in favor of button groups. A connected group uses 2dp spacing and 8dp inner corners. (https://raw.githubusercontent.com/material-components/material-components-android/master/docs/components/ButtonGroup.md)
- **Apply:** Use a connected group for 3–4 answer options in cross-examination, and let the pressed option grow.

**Snippet only (m3.material.io pages require JavaScript):**
- The shape guidance says to morph shapes to communicate interaction states (like selection) and actions in progress. It suggests thinking about how shapes react to "tapping, swiping, scrolling, releasing, and long pressing". (https://m3.material.io/styles/shape/overview-principles)
- Shape morphing uses the expressive motion scheme by default. Standard is for utilitarian movement and expressive is "more bouncy", and it can be switched. (https://m3.material.io/styles/shape/shape-morph)
- Material says the motion physics system replaces non-customizable easing and duration values with a customizable, spring-based scheme. (https://m3.material.io/blog/m3-expressive-motion-theming)

---

## (e) Haptics on Android

**E1. "Haptics design principles"**, Android Developers (Google), page updated 2026-10-26. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- "Less is more." Too much vibration is annoying and numbing, and may lead users to turn haptics off. (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Buzzy haptics: "Given the choice of buzzy haptics or no haptics for touch feedback, choose no haptics." Prefer clear and rich haptics. (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Use `HapticFeedbackConstants` for consistency, with platform fallback. (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Very frequent events should be very subtle, and important events should be stronger. Combine the two so effects "become stronger as the interaction reaches a target" (ticks with rising amplitude when dragging, dropping or snapping). (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Co-design visual, audio and haptic effects. A haptic out of sync with visuals can be unsettling, and the user may think the actuator is broken. (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Avoid legacy one-shot vibrations. A good key-click lasts 10–20 ms, but the actuator may ring another 20–50 ms. (https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- **Apply:** Rank your events. Card-drag ticks are the most frequent and the faintest. Correct bin drop and boss contradiction are the strongest.

**E2. "UX foundation for haptic framework"**, Android Open Source Project (Google), updated 2026-10-09. https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation
- Repeated effects during drag or swipe should be "crisp and subtle". A buzzy effect wipes out the intervals into one long buzz, and a too-strong one builds up perceived energy. It recommends `CLOCK_TICK` and `TEXT_HANDLE_MOVE` for this. (https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation)
- Custom effects are built from `PRIMITIVE_CLICK` and `PRIMITIVE_TICK` via `addPrimitive(primitiveID, scale, delay)`. Support depends on device capability. (https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation)
- A long vibration should ease in, since a sudden one can startle users. Audio-coupled haptics need a sense of rhythm, or users treat them as "random buzzes" and ignore them. (https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation)
- `CONFIRM` and `REJECT` were added in Android 11. (https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation)
- **Apply:** Time the boss's "gotcha" haptic to the beat of its sound and animation, and ease any long vibration in.

**E3. `HapticFeedbackType` (Compose UI) reference.** I read a mirror at composables.com (content updated 2026-11-18), not developer.android.com. https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api
- `GestureThresholdActivate` is for swipe or drag gestures "where the gesture action is eligible at a certain threshold of movement, and can be cancelled by moving back past the threshold". (https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api)
- `Confirm` signals "confirmation or successful completion", and `Reject` signals "rejection or failure". (https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api)
- `SegmentTick` is for switching between potential choices such as list items or discrete points. `SegmentFrequentTick` is for many closely spaced choices. (https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api)
- **Apply:** Map swipe-past-threshold to `GestureThresholdActivate`, bin-drop and deduction outcomes to `Confirm`/`Reject`, and reorder slot changes to `SegmentTick`.

**Snippet only:** The "Add haptic feedback to events" page recommends the View-based approach as action-oriented with the widest support and no `VIBRATE` permission. It says methods respect the user's touch-feedback settings by default. It discourages `createOneshot` and `createWaveform`. (https://developer.android.com/develop/ui/views/haptics/haptic-feedback)

---

## Top 15 takeaways

1. **Start with a playable problem and no tutorial text.** One small mental leap per case, with room to try several answers. (https://blog.brilliant.org/solving-equations/)
2. **Teach through consequence, not text.** Use the "fall in the pit" model for wrong bins and wrong deductions. (https://blog.brilliant.org/hand-crafted-machine-made/)
3. **Use minimal-detail, silhouette-first art with generous whitespace.** It reads at small sizes. (https://blog.duolingo.com/shape-language-duolingos-art-style/)
4. **Drive the boss from a state machine.** The witness has idle, speaking and reacting states, switched by answer outcome. (https://blog.duolingo.com/world-character-visemes/)
5. **Color means function, and never carries correctness alone.** Pair red and green with text, and add a non-color "selected" cue. (https://www.designsystems.com/about-wonder-blocks-khan-academys-design-system-and-the-story-behind-it/, https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible)
6. **Give partial feedback in deduce and order-steps.** Show "N in place", or "two or fewer wrong", instead of only right or wrong. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
7. **Use equal-size slots** so the answer isn't leaked by blank width. (https://github.com/YuriSizov/tutorial-gui-of-golden-idol/blob/main/TUTORIAL.md)
8. **Keep each case lean.** Include only clues needed for the solution or for meaningful misdirection. Provide multiple routes where possible. (https://www.gamedeveloper.com/design/case-of-the-golden-idol)
9. **Write a thought-path per case.** Remove any clue that lets players skip steps, and playtest silently 5–7 times. (https://www.gamedeveloper.com/business/-the-case-of-the-golden-idol-i-used-frequent-testing-to-improve-its-mystery-solving, https://www.gamedeveloper.com/design/case-of-the-golden-idol)
10. **Keep one persistent case file with one shortcut, and avoid gesture conflicts.** Obra Dinn separated selection from page turning to prevent accidental page turns. (https://www.rockpapershotgun.com/how-a-book-binds-the-return-of-the-obra-dinn)
11. **Cross-examination needs press and present, plus statement paging.** Ace Attorney pairs a "press" action with a "present" action from the court record, and lets players step through statements. (https://strategywiki.org/wiki/Phoenix_Wright:_Ace_Attorney/Gameplay, https://aceattorney.fandom.com/zh/wiki/%E5%AE%A1%E5%88%A4)
12. **Treat the text box as the main UI.** Provide contrast, size options, dark mode, and toggles for flashes and shake. (https://ladyicepaw.itch.io/invisible-seams/devlog/415360/tips-on-making-your-vn-more-accessible, https://tackyvillain.neocities.org/articles/devlogs/ssdd-02)
13. **Theme motion with `MotionScheme.expressive()`.** Use spatial specs for movement and scale, and effects springs for color and opacity, with no overshoot. Morph `MaterialShapes` between states. (https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html, https://raw.githubusercontent.com/material-components/material-components-android/master/docs/theming/Motion.md)
14. **Make the primary action large and prominent, but don't break familiar patterns.** Google's tests found a larger Send button was spotted about four times faster, and that a scattered playlist layout hurt usability. (https://design.google/library/expressive-material-design-google-research)
15. **Scale haptics with event importance and frequency.** Use faint ticks while dragging, ramping up toward the target, then `Confirm` or `Reject`. Co-design them with animation and sound, and use no haptic over a buzzy one. (https://developer.android.com/develop/ui/views/haptics/haptics-principles, https://composables.com/jetpack-compose/androidx.compose.ui/ui/classes/HapticFeedbackType/api)

---

## Gaps in what I found

- **Duolingo "design system".** I found no public component or token documentation. Its design blog is a hub of posts (https://design.duolingo.com/), and the posts above are the primary material.
- **Brilliant interaction design.** I found no designer-authored case study. Brilliant's own blog posts and a Rive interview with two Brilliant designers are the closest primary sources.
- **Khan Academy.** I read the 2017-era Wonder Blocks write-up. A newer color-system post exists (https://blog.khanacademy.org/how-we-rebuilt-khan-academys-color-system-from-the-ground-up/), but I only saw its search snippet.
- **Ace Attorney UI.** I found no primary design write-up on the present-evidence UI. B7 relies on fan wikis, as snippets only.
- **Material 3 Expressive guidance pages.** The official m3.material.io pages need JavaScript, so D1–D4 come from the Google Design research page, an Android Developers Blog post and the Material team's GitHub docs.
- **Short-session mobile visual novels.** No primary source found. C1 and C2 are the closest, and they are indie devlogs.
