# Mobile game feel and UX for short, one-handed, interruptible puzzle games (Android, Compose / M3)

Research brief for the Anything2Game redesign. Research date: 2026-11-19.

**How to read this file.**
- Every rule is followed by the URL it came from. I read each URL during this session.
- `[unverified]` means the claim, number or design choice is NOT stated on that page. It is my judgement or general knowledge, and you should test it before treating it as fact.
- WebFetch returns a model-summarised view of each page, not raw HTML. Only one page was read raw: the custom-haptics page, which I checked against the saved text.
- Several key sources (m3.material.io pages, Apple's designing-for-games page as HTML, two NN/g URLs) returned nothing usable. Details are in "Open questions / could not verify".

## Summary: the 10 most important rules

1. **Feedback must be instant (about 100 ms) and must start on touch-down, not on result.** Anything over about 1 s needs a progress indicator. Use a press state of about 0.9x scale on spring. Sources: NN/g response times, Compose interaction docs.
2. **Haptics: use `HapticFeedbackConstants` / Compose `HapticFeedbackType`, not `createOneShot`.** Map them consistently: `GestureThresholdActivate` when a swipe passes its commit point, `Confirm` for correct, `Reject` only for a real failure, `SegmentTick` for bin hover. Android says "less is more", and buzzy haptics are worse than none.
3. **Sound defaults to off.** Apple HIG says players on silent expect game sound effects to be silenced. Offer sound as an opt-in toggle. Every cue must also exist visually and, where possible, haptically.
4. **Motion: durations of 100-300 ms for routine feedback, up to about 400 ms for large movements.** Entrances use ease-out, exits are shorter, and linear is avoided. Prefer springs for anything the finger drives, because they stay continuous when interrupted.
5. **Reserve "expressive" motion for hero moments.** `MotionScheme.expressive()` is meant for "prominent UI elements and hero interactions". `standard()` is for "recurring interactions". Use expressive only for the end-of-level "case closed" moment and use standard for everything else.
6. **One-handed layout.** Put primary actions in the lower reachable area. Every target is at least 48 dp and primary targets are larger (NN/g: about 2 cm). Keep swipe areas away from system-gesture edges using `systemGestures` insets. Test at 200% font scale.
7. **Every gesture has a visible, tappable alternative and an accessibility action.** Swipe true/false gets two buttons plus `customActions`. Drag-to-bin gets tap-card-then-tap-bin. Use cues other than colour (shape and text, not just green and red).
8. **Resume, don't restart.** Persist game progress on every answer. Restore the exact card on return. Pause on sleep or lock. Show "card 3 of 7" progress. This is an Android quality criterion (State_Preservation).
9. **Onboard by playing, not by tutorial.** The first card is a playable, low-stakes example. Skip is possible, and the tutorial is never repeated. NN/g found tutorial decks don't improve task performance, and Apple says "teach through play".
10. **Spend juice on moments, not on every tap.** Apple: "avoid adding motion to UI interactions that occur frequently" and "let people cancel motion". Cap per-answer feedback at one visual, one haptic, and no sound by default. Spend the big orchestration once per level.

---

## 1. Game feel for tap / swipe / drag

### 1.1 Feedback timing

- Respond within about 0.1 s so the result itself is the feedback. Between 0.1 and 1 s users notice the delay but keep their train of thought. Delays beyond 10 s need a percent-done indicator. https://www.nngroup.com/articles/response-times-3-important-limits/
- Indicators: show feedback immediately after an action ("without any visual change... most users will assume the action was not registered"). Use a progress indicator for anything over about 1 s. Use a looped spinner only for roughly 2-10 s. https://www.nngroup.com/articles/progress-indicators/
- Swink's definition: game feel is "the tactile sensation of manipulating a digital agent", and it is "mostly subconscious". His layers are Input, Response, Context, Polish, Metaphor and Rules. Polish is "the interactive impression of physicality created by the harmony of animation, sounds, and effects with input-driven motion". https://www.gamedeveloper.com/design/game-feel-the-secret-ingredient
- Swink (via the same article): add only the polish needed to convey physicality, such as particles, screen shake or squash and stretch, because "polish can be a time sink". He also says to include game feel from preproduction. https://www.gamedeveloper.com/design/game-feel-the-secret-ingredient
- Wikipedia lists Polish types as animation, visual effects, sound effects, cinematic effects and tactile effects. It gives no latency numbers. https://en.wikipedia.org/wiki/Game_feel
- Compose: a press-scale effect to about 0.9 on `spring()` is shown in the official handling-interactions sample. It animates to pressed on `PressInteraction.Press` and back on `Release` or `Cancel`. https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions
- Compose: a press that starts and ends within one frame never shows in `collectIsPressedAsState()`. For ripples or press animations, launch the animation directly from the interaction flow and give it a minimum visible duration. https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions
- Apple: "Aim for brevity and precision in feedback animations... brief and precise, it tends to feel lightweight and unobtrusive." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json
- Apple: "Handle gestures as responsively as possible... provide feedback that helps them predict its results." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/gestures.json

### 1.2 Anticipation, squash, easing, duration

- NN/g duration ranges: about 100 ms for simple feedback such as toggles, 200-300 ms for substantial changes, 100-400 ms for most animations, up to 400 ms for big movements. About 500 ms starts to feel "like a real drag". Entrances are slightly longer than exits. https://www.nngroup.com/articles/animation-duration/
- NN/g easing: ease-out for entrances, ease-in for exits, and avoid linear motion. https://www.nngroup.com/articles/animation-duration/
- Compose: `spring` "guarantees the continuity of velocity when target value changes amid animations", so it handles interruptions better than `tween`. Use it for anything the finger drives. `tween` is for fixed-duration effects. https://developer.android.com/develop/ui/compose/animation/customize
- Compose: spring defaults are `DampingRatioNoBouncy` and `StiffnessMedium`. Built-in easings include `FastOutSlowInEasing`, `LinearOutSlowInEasing`, `FastOutLinearInEasing`, `EaseOutBack` and others. https://developer.android.com/develop/ui/compose/animation/customize
- Compose: use `Modifier.graphicsLayer { ... }` for drag-driven translation, scale and corner radius. It runs in the draw phase and avoids recomposition during the drag. The official drawer sample scales content from 1.0 to 0.8 while dragging. https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- Compose: read drag offsets inside `Modifier.offset { }` or `graphicsLayer { }` lambdas to defer reads to the layout/draw phase. https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- Squash and stretch "can make an object appear softer, heavier, or more physically expressive". [Wikipedia, generic animation principle.] https://en.wikipedia.org/wiki/Game_feel
- Anticipation and overshoot (for example a card dips about 2-4% scale on touch-down and settles with a slightly bouncy spring on drop) are my recommendation, not stated on any page above. [unverified]

### 1.3 Haptic patterns (Android)

- Principles: "less is more". Too much vibration "can be annoying and even numbing to the hands... may lead the user to quickly turn off all haptics". https://developer.android.com/develop/ui/views/haptics/haptics-principles
- "Given the choice of buzzy haptics or no haptics for touch feedback, choose no haptics." https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Strength should follow importance and frequency. Very frequent events should be "very subtle". Submitting should be stronger than toggling. Ticks can "become stronger as the interaction reaches a target" for drag, drop and snap. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Be consistent: the same effect for the same kind of interaction, so users associate a meaning with it. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Prefer `HapticFeedbackConstants` via `View.performHapticFeedback`. It needs no `VIBRATE` permission, honours the user's touch-feedback setting, and the platform supplies fallbacks. https://developer.android.com/develop/ui/views/haptics/haptic-feedback
- Avoid `createOneShot` and `createWaveform` for feedback. A good keyclick is 10-20 ms, but the actuator can ring for another 20-50 ms afterward. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- `CONFIRM` is "short and light", `REJECT` "might be a stronger feedback to signal failure". Both are API 30. https://developer.android.com/develop/ui/views/haptics/haptic-feedback and https://developer.android.com/reference/android/view/HapticFeedbackConstants
- `GESTURE_THRESHOLD_ACTIVATE` and `GESTURE_THRESHOLD_DEACTIVATE` (API 34) fire when a swipe or drag crosses the point where release will commit, or crosses back to cancel. This matches swipe cards exactly. https://developer.android.com/reference/android/view/HapticFeedbackConstants
- `DRAG_START` (API 34): "The drag target has just been picked up." Use it for sort-card pickup. https://developer.android.com/reference/android/view/HapticFeedbackConstants
- `SEGMENT_TICK` (API 34): switching between discrete choices. `SEGMENT_FREQUENT_TICK` is "expected to be very soft"; the device "may not make any vibration" if it can't be soft enough. Use `SEGMENT_TICK` when a dragged card enters a different bin. https://developer.android.com/reference/android/view/HapticFeedbackConstants
- `TOGGLE_ON` and `TOGGLE_OFF` (API 34) exist for switch-like state changes. `GESTURE_START` and `GESTURE_END` are API 30. https://developer.android.com/reference/android/view/HapticFeedbackConstants
- Compose equivalents (compose-ui 1.8.0): `HapticFeedbackType.Confirm`, `Reject`, `GestureThresholdActivate`, `SegmentTick`, `SegmentFrequentTick`, `ToggleOn`, `ToggleOff`, `ContextClick`, `GestureEnd`, `LongPress`. There is no Compose `DragStart` or `GestureThresholdDeactivate` in the list I read. Fall back to the View constants via `LocalView.current.performHapticFeedback(...)` for those. https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType
- Behaviour of new constants on devices below their API level (for example API 30 or 34 on older phones) is not documented in the pages I read. Guard with `Build.VERSION.SDK_INT` and degrade to a plain `LongPress` or nothing. [unverified]
- Custom primitives: gaps of 5-10 ms are too short to be detected, so use 50 ms or more for a discernible gap. Use scale ratios of 1.4 or more, and no more than 3 intensity levels (0.5, 0.7, 1.0). https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- Custom primitives: `PRIMITIVE_SLOW_RISE` and `QUICK_RISE` ramp up, `QUICK_FALL` ramps down. People notice the rising part more than the falling part, so a shorter rise shifts emphasis to the fall. `PRIMITIVE_SPIN` repeated gives a wobble, and a 10-50 ms gap between spins loosens it. https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- Fallbacks: `VibrationEffect.Composition` has no automatic fallback, and one unsupported primitive makes the whole effect fail. Check support first. The newer `VibrationEffect.Builder` (the page says Android 16) falls back automatically. https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- Haptics should be co-designed with visuals and audio. Out-of-sync haptics make the user think the actuator is broken. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Apple: use system patterns for their documented meanings, keep a clear cause-and-effect link, and note that using a failure pattern for level completion confuses people. "Make haptics optional." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/playing-haptics.json
- Apple: "Often, the best haptic experience is one that people may not be conscious of, but miss when it's turned off." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/playing-haptics.json
- Suggested mapping for the app (my synthesis of the sources above) [unverified]:

  | Moment | Constant |
  |---|---|
  | Card picked up | `DRAG_START` |
  | Swipe passes commit point | `GESTURE_THRESHOLD_ACTIVATE` |
  | Swipe returns under the threshold | `GESTURE_THRESHOLD_DEACTIVATE` |
  | Card enters a different bin | `SEGMENT_TICK` |
  | Correct answer | `CONFIRM` |
  | Wrong answer | A softer-than-REJECT cue, or `REJECT` only if you want it to read as failure |
  | Level complete | A single composed effect (see 5.4) |

### 1.4 Sound defaults for commuters

- Apple: people on silent mode "want to silence nonessential sounds, such as keyboard clicks, sound effects, game soundtracks, and other audible feedback". Respect the system volume and ringer state. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/playing-audio.json
- Apple: "If your interface conveys information through audio cues... consider pairing that sound with matching haptics for people who... have their audio turned off." Do the reverse as well: never carry information in sound alone. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- Android quality: request audio focus when audio starts, abandon it when it stops, and handle other apps' focus changes. https://developer.android.com/docs/quality-guidelines/core-app-quality
- Default sound off (opt-in toggle in the first-run or in a visible in-game control). There is no source that states an Android sound default. This is my design decision, backed by the Apple silent-mode guidance above. [unverified]
- If sound is on, use short sound effects that mix with other audio (Apple "ambient" category: mixes, respects silence switch) and do not stop the user's music or podcast. Android equivalent is a transient, ducking focus request. [unverified for Android specifics]

### 1.5 How much juice before it becomes noise

- Apple motion: "Add motion purposefully... Gratuitous or excessive animation can distract people." "In apps, generally avoid adding motion to UI interactions that occur frequently." "Let people cancel motion... don't make people wait for an animation to complete." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json
- Apple haptics: "Avoid overusing haptics. Sometimes a haptic can feel just right when it happens occasionally, but become tiresome when it plays frequently." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/playing-haptics.json
- Android haptics: "Be mindful of frequency of use, and importance." https://developer.android.com/develop/ui/views/haptics/haptics-principles
- Budget proposal: per answer at most one visual change, one haptic and no sound by default. Per level one orchestrated moment. Nothing blocks input for more than about 300 ms, and the next card must be interactable while the previous one is still leaving. [unverified]
- The "Juice it or lose it" talk and any article that quantifies juice could not be reached. See Open questions.

---

## 2. One-handed portrait layout

### 2.1 Thumb zones and where actions go

- Android layout guidance: "Keep essential interactions, like primary navigation, in a reachable screen area." FABs give "a prominent and reachable interaction point". Don't overwhelm with too many actions per view. https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-basics
- NN/g: touch targets must be larger than mouse targets, accidental touches are easy, and undo support matters even more on touch. https://www.nngroup.com/articles/mobile-ux/
- NN/g, on target size: go larger "for primary calls to action, for use while moving", and for people with full hands. https://www.nngroup.com/articles/touch-target-size/
- Concrete thumb-zone maps (lower arc easy, upper corner hard) are not in the pages I could read. Treat "primary actions in the bottom 40% of the screen" as my recommendation. [unverified]
- For a swipe-card game the card sits in the centre and the commit buttons (True / False) sit at the bottom, within thumb reach. The question or evidence text sits at the top, where it is only read. [unverified]

### 2.2 Touch target sizes

- Android: "Ensure all touch targets are at least 48 dp, even if this extends past the UI element visual." https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
- Android quality criterion Touch_Target_Size: "Touch targets are at least 48 dp." https://developer.android.com/docs/quality-guidelines/core-app-quality
- Compose: use `IconButton`, or order modifiers as `.clickable{}.padding(12.dp).size(24.dp)` so the padding is part of the clickable area. https://developer.android.com/codelabs/jetpack-compose-accessibility
- NN/g: minimum 1 cm x 1 cm physical size. Fingertips are about 1.6-2 cm wide and the thumb impact area averages 2.5 cm. Primary buttons in good examples were about 2 cm. Spacing can't compensate for a small target. https://www.nngroup.com/articles/touch-target-size/
- Apple: default 44 x 44 pt, with 28 x 28 pt as the absolute minimum for games. About 12 pt of padding around bezeled elements and 24 pt around unbezeled ones. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-games.json and https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- Suggested sizes for the app: 48 dp absolute floor, 56-64 dp for answer buttons, and bin drop zones as large as the layout allows. About 2 cm is roughly 56-64 dp on typical phones. [unverified conversion]

### 2.3 Bottom sheets vs dialogs

- Modal bottom sheets block the background. Non-modal sheets let you keep interacting. Sheets are "for interruptions or a fork in the road, rather than the expected happy path". Sheets are transient and for quick interactions. https://www.nngroup.com/articles/bottom-sheet/
- Show a visible close (X), because the grab handle is easy to miss. Support Back. Never stack sheets. https://www.nngroup.com/articles/bottom-sheet/
- Compose: `ModalBottomSheet(onDismissRequest = ...)` with a `SheetState` from `rememberModalBottomSheetState()`. Remove it from composition after `hide()`. https://developer.android.com/develop/ui/compose/components/bottom-sheets
- Use a bottom sheet for evidence peeks, hints and the "Pause / Leave?" menu. Reserve a full dialog for a destructive confirmation (for example abandon level), and prefer undo to confirmation where possible. [unverified]

### 2.4 Safe areas and system gestures

- Targeting SDK 35 enforces edge-to-edge on Android 15. Call `enableEdgeToEdge()` for earlier versions. Handle system-bar and cutout insets for tappable content. https://developer.android.com/develop/ui/views/layout/edge-to-edge
- `WindowInsetsCompat.Type.systemGestures()`: "Use these insets to move or pad swipeable views away from the edges. Common use cases include bottom sheets, swiping in games, and carousels." On Android 10+ there is a bottom inset for the home gesture and left and right insets for back. https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Apple: rely on platform safe areas, make in-game menus adapt to aspect ratios, and avoid fixed layouts. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-games.json
- Practical rule: do not start a card-swipe gesture within the left and right system-gesture inset. Keep the card inset from the screen edge, and keep bottom buttons above the home-gesture inset. [unverified inference from the two sources above]

### 2.5 Large font scales

- Android 14 supports font scaling to 200% with a nonlinear curve. Use `sp` for text and for line height. Do not use `sp` for padding. Test at 200%. https://developer.android.com/about/versions/14/features
- Android: body text should be no smaller than 12 sp. Text contrast at least 4.5:1, non-text contrast at least 3:1. https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
- Apple: allow text enlargement of at least 200%. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- Card text should scroll or shrink within the card at 200%. Never clip the claim, and never let the answer buttons leave the screen. Design the layout with a scrollable card body and pinned bottom actions. [unverified]
- Turn on `enableAccessibilityChecks()` in Compose UI tests. It flags small touch targets, contrast and missing labels. https://developer.android.com/develop/ui/compose/accessibility/testing

---

## 3. Short-session and interruption design

### 3.1 Resume cues and state

- Android quality State_Preservation: the app "preserves user or app state when leaving the foreground... game progress, menus..." and restores it. Sleep_Resume and Lock_Resume: the app pauses when the device sleeps or locks and resumes on wake. https://developer.android.com/docs/quality-guidelines/core-app-quality
- Apple: "Restore the previous state when your app restarts so people can continue where they left off." "Launch instantly." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/launching.json
- Rules for the app: persist after every answer (not at level end). Use `rememberSaveable` or a persisted ViewModel for the current card and score. Pause any timer on `ON_PAUSE`. On return, show a short "Welcome back - card 4 of 7" cue and the exact card, never the level intro. [unverified, derived from the two sources above]

### 3.2 Progress visibility

- NN/g: "Show step counts when percentages are hard to estimate", for example "3 of 50". A bar that stalls near the end frustrates people. https://www.nngroup.com/articles/progress-indicators/
- NN/g: a progress indicator reduces uncertainty and can make waiting feel shorter. One study found people waited about three times longer with a moving bar. https://www.nngroup.com/articles/progress-indicators/
- Use a segmented bar (one segment per card or step) with a text label such as "3 / 7" for TalkBack. Segments, unlike a smooth percent, make it obvious when a session is nearly over. [unverified]

### 3.3 Session length

- No page I could read gives a recommended session length for casual puzzle games. The 3-5 minute target is the app's own design choice.
- Design for the session to be "stoppable at any card". That is the real constraint, not total length, and it follows from the Android state-preservation criterion. [unverified]

### 3.4 Streak and reward pitfalls

- I could not reach a source on streak mechanics, loss aversion or notification pitfalls. Apple's advice to wait until people have "spent quality time with your game before you ask them for a rating or review" is the only related guidance I read. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-games.json
- My view, to be tested: a visible streak that can silently break creates guilt, which is a poor fit for study material that a student reads on their own schedule. Prefer a "weekly rhythm" or a no-penalty "days played this week" count. Do not gate content on a streak. [unverified]

### 3.5 Onboarding in under 30 seconds

- No page gives a "30 seconds" number. Treat "under 30 s to first meaningful action" as a goal to measure yourself. [unverified]
- NN/g: tutorials "didn't improve task performance". Deck-of-cards tutorials are not recommended. Interactive walkthroughs that let people "learn by doing" in a low-stakes setting work better. Keep it "brief, optional". https://www.nngroup.com/articles/mobile-app-onboarding/
- Apple: "Teach through play." Let people play as soon as installation completes. Provide great defaults. "Defer requests until the right time." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-games.json
- Apple onboarding: "fast, fun, and optional". Don't re-show a skipped tutorial, but keep it findable in settings. Ask for permissions at the point of use. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/onboarding.json
- Plan: first card is the lesson. A ghost swipe animation on card 1 only, with the buttons visible, and no text instructions beyond one line. Defer sound, notifications and account setup. [unverified]

---

## 4. Card/swipe and drag-and-drop patterns

### 4.1 Making swipe discoverable

- NN/g: gestures are "a hidden, alternate user interface" with "low memorability and discoverability". https://www.nngroup.com/articles/mobile-ux/
- Apple: custom gestures must be discoverable and "Not the only way to perform an important action". "Offer moments... to help people quickly learn". "If you're finding it difficult to use simple language and graphics to describe a gesture, it may mean people will find the gesture difficult." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/gestures.json
- Apple: "Avoid using a familiar gesture like tap or swipe to perform an action that's unique to your app." Use swipe for the thing swipe means (dismiss, move, choose a side). Here swipe-left or right as false/true is a natural fit, but label the two edges. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/gestures.json
- Feedback during the drag: tilt and tint the card toward the chosen side, and show "TRUE" / "FALSE" labels (not colour alone) fading in with drag distance. Use `GESTURE_THRESHOLD_ACTIVATE` at the commit threshold. [unverified, combines the Apple "communicate extent of movement" rule with the Android constant]
- Compose building blocks: `SwipeToDismissBox` for standard behaviour, `Modifier.anchoredDraggable` for snapping between discrete positions (resting, left, right) with fling handling. https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling

### 4.2 Undo

- Apple: "Prefer letting people undo a drag-and-drop operation." Offer undo when an accidental drop is possible. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/drag-and-drop.json
- Apple undo: show the result of the undo and be predictable. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/undo-and-redo.json
- NN/g: accidental touches are easy on touchscreens, so undo matters more. https://www.nngroup.com/articles/mobile-ux/
- In a quiz, "undo after the answer is revealed" would remove the point of the game. Practical compromise: allow "undo last swipe" only before the feedback appears, using a short 1-2 s snackbar-like affordance, or make the commit a two-step "swipe then confirm" only on high-stakes steps. The timing is my suggestion. [unverified]

### 4.3 Accessibility alternatives

- Android: "Don't rely on gestures to complete all actions; create accessibility actions to support all user flows." Show a visible button alongside a swipe (Android's figure shows a trash button next to swipe-to-delete). https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
- Compose: `Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Mark true") { ...; true }, ...) }` exposes swipe or drag actions to TalkBack, Voice Access and Switch Access. https://developer.android.com/guide/topics/ui/accessibility/principles
- Compose: use `onClickLabel` / `onLongClickLabel` so TalkBack announces a meaningful action instead of "double tap and hold". https://developer.android.com/guide/topics/ui/accessibility/principles
- Compose: give list or grid items unique labels, merge related text with `mergeDescendants`, and put `paneTitle` on sheets (unique across the app). https://developer.android.com/guide/topics/ui/accessibility/principles
- Use cues other than colour: shapes and text, not only a green and a red button. https://developer.android.com/guide/topics/ui/accessibility/principles
- Apple: "if you use a swipe gesture to dismiss a view, also make a button available". https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- Announce results for TalkBack users with a live region or `announceForAccessibility`, for example "Correct. Card 4 of 7." [unverified, general Compose knowledge]

### 4.4 Drag-and-drop for sorting into bins

- Compose's `dragAndDropSource` and `dragAndDropTarget` are designed to share `ClipData` between composables and apps. They give `onStarted`, `onEntered`, `onExited`, `onEnded` and `onDrop` callbacks, which is enough for hover highlighting. https://developer.android.com/develop/ui/compose/touch-input/user-interactions/drag-and-drop
- The drag-swipe-fling page says `draggable2D` is for moving UI inside one composable hierarchy and "shouldn't be confused with the Drag and Drop pattern". For an in-game card sort, `draggable2D` or `pointerInput` plus your own hit-testing is the simpler route, with full control over the lifted-card visual. https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- Apple drop-target rules: highlight a destination only while the dragged item is above it, only if it can accept the item, and "provide visual cues that help people identify one at a time". https://developer.apple.com/tutorials/data/design/human-interface-guidelines/drag-and-drop.json
- Apple: when a drop is invalid, animate the item back to its source (or scale up and fade out) rather than leaving it stranded. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/drag-and-drop.json
- Apple (iOS): show a translucent drag image once the drag exceeds about 3 points. A translucent card lets the player see the bins underneath. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/drag-and-drop.json
- Offer tap-to-select-then-tap-bin as an alternative, and expose each bin as a `customAction` on the card for TalkBack. [unverified, derived from Apple's "offer alternative ways" rule and the Android custom actions page]
- Make bins at least 48 dp wide in every dimension, and keep the lifted card's hit point offset above the finger so the thumb does not hide the target. [unverified]

---

## 5. Motion

### 5.1 Material 3 Expressive in Compose

- Compose supports M3 Expressive, described as "research-backed updates to theming, components, motion, typography". https://developer.android.com/develop/ui/compose/designsystems/material3
- `MotionScheme` (material3 1.5.0) supplies every `FiniteAnimationSpec` for a `MaterialTheme`. There are two built-in schemes. https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme
  - `expressive()`: "Material's recommended motion scheme for prominent UI elements and hero interactions. It provides a visually engaging motion feel."
  - `standard()`: "Material's basic motion scheme for utilitarian UI elements and recurring interactions. It provides a linear motion feel."
- Specs come in "spatial" (may change shape or bounds) and "effects" (colour, alpha, no bounds change) types, each in fast, default and slow variants. Use effects specs for colour and alpha. https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme
- How to apply it here: use `standard()` for in-level cards, bins and progress. Apply `expressive()` only to the level-complete moment and the title and results screens. Mixing the two inside one level is allowed (it is a per-spec choice), but keep the rule simple. [unverified]
- The M3 Expressive blog and motion spec pages (damping and stiffness values, duration tokens) could not be read. See Open questions.

### 5.2 Reduced motion

- `ValueAnimator.areAnimatorsEnabled()` (API 26) returns false when the user sets the animator duration scale to 0 or when Battery Saver is on. https://developer.android.com/reference/android/animation/ValueAnimator
- Apple reduce-motion guidance: reduce automatic and repetitive animation (zooming, scaling, peripheral motion), tighten springs to reduce bounce, replace x/y/z transitions with fades, and be careful with fast or blinking motion. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- Apple: "Make motion optional... supplement visual feedback by also using alternatives like haptics and audio." https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json
- NN/g: respect "reduce motion" by removing animations. https://www.nngroup.com/articles/animation-duration/
- Plan: expose one `LocalReducedMotion` composition-local, set from `areAnimatorsEnabled()` plus an in-app toggle. When on, swap spatial moves for 100-150 ms fades, remove overshoot, and keep the haptic and state-change cues so the game stays understandable. How Compose's built-in animations react to the duration scale is not documented on the pages I read. [unverified]

### 5.3 Interruptible and continuous motion

- Prefer springs and `Animatable` so a new touch during an animation continues from current velocity. https://developer.android.com/develop/ui/compose/animation/customize
- Apple: make feedback motion follow the finger realistically and let people cancel motion rather than wait. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json
- Compose has `AnimatedVisibility`, `AnimatedContent`, `animate*AsState`, `Transition` and `animateContentSize` for the standard cases. https://developer.android.com/develop/ui/compose/animation/introduction

### 5.4 One orchestrated "moment" per level: "Case closed"

All timings below are my proposal, built from the cited ranges. They are not from any page. [unverified]

- Constraints from the sources: substantial changes 200-300 ms, big movements up to 400 ms, and nothing near 500 ms for a single move (NN/g). Brief and precise feedback (Apple). The player must be able to cancel or skip it (Apple). One haptic effect, in sync with the visuals (Android).
- Proposed timeline, about 1.2 s total, skippable by tap:
  1. 0 ms: input locks only for the last answer. The last card exits (ease-in, about 200 ms).
  2. 150 ms: the evidence board or "case file" scales in with `MotionScheme.expressive()` spatial spec (one spring, one overshoot).
  3. 300 ms: the stamp ("CLOSED" or the frame-specific equivalent) lands. This is the single big beat. One `QUICK_RISE` to `CLICK` composition (or `CONFIRM` if primitives are unavailable) fires exactly at impact.
  4. 450-900 ms: the summary rows (what you got right, one-line takeaway) fade and stagger in, 60-80 ms apart.
  5. Settle: the primary button ("Next case" / "Review mistakes") fades in at the bottom, within thumb reach.
- Frame variants (detective, escape room, rookie teaching, companion) change the stamp, copy and colour, not the timeline. That keeps the code and the player's rhythm identical across frames.
- Haptic composition: use `VibrationEffect.Builder` (automatic fallback, per Android) if available, otherwise `Composition` guarded by a primitive-support check, otherwise `HapticFeedbackType.Confirm`. https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- Reduced motion: replace steps 1-3 with a 150 ms cross-fade to the result card and keep the single haptic.

---

## 6. Anti-patterns that make learning apps feel childish or quiz-like

Each item gives the evidence I could read. Items without a source are marked.

- **Tutorial decks and long onboarding.** NN/g does not recommend deck-of-cards tutorials, and found tutorials "didn't improve task performance". https://www.nngroup.com/articles/mobile-app-onboarding/
- **Colour-only right/wrong.** Android shows red and green round buttons as the wrong pattern, and says to add shapes and text. https://developer.android.com/guide/topics/ui/accessibility/principles
- **Gesture-only actions.** Android: "Don't rely on gestures to complete all actions". https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
- **Buzzy or constant haptics.** Android says choose no haptics over buzzy ones. https://developer.android.com/develop/ui/views/haptics/haptics-principles
- **Using a failure haptic for a non-failure.** Apple says it confuses people. For a learning game a wrong answer is information, not punishment, so prefer a gentler cue. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/playing-haptics.json
- **Gratuitous or blocking animation.** Apple says to avoid it, and to avoid motion on frequent interactions. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json
- **Slow animations (500 ms and up) on repeated actions.** NN/g: "a real drag". https://www.nngroup.com/articles/animation-duration/
- **Static "Loading..." text.** NN/g says it gives no sense of progress. https://www.nngroup.com/articles/progress-indicators/
- **Rating or purchase prompts before the player is engaged.** Apple says to wait. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/onboarding.json
- **Unexplained gestures and unavailable gestures.** Apple: indicate when a gesture isn't available, or users may think the app has frozen. https://developer.apple.com/tutorials/data/design/human-interface-guidelines/gestures.json
- **Stacked sheets.** NN/g says stacking confuses what is being dismissed. https://www.nngroup.com/articles/bottom-sheet/
- Not sourced, my design judgement, to be tested with students [unverified]:
  - Mascot-style cheering, confetti and star ratings on every correct answer. They make a university-level reading task feel like a children's quiz.
  - Point inflation (+10 for everything) and cosmetic currencies with no link to the material.
  - Exclamation-mark copy and praise that isn't about the reasoning ("Great job!" instead of "You spotted that the claim ignored the control group").
  - Quiz framing: a score as the headline, with a pass or fail. Instead, make the frame matter: the cross-examination should feel like building a case, and feedback should be about the argument, not the grade.
  - Timers that punish careful reading. A student who is reading papers should never be rushed by a visible countdown.
  - A fictional frame that obscures the learning content (for example hiding the claim inside theatre). The frame should add stakes and a role, while the claim, evidence and reasoning stay legible.

---

## Numbers

| Item | Value | Source |
|---|---|---|
| Instant response limit | 0.1 s | https://www.nngroup.com/articles/response-times-3-important-limits/ |
| Flow-of-thought limit | 1 s | https://www.nngroup.com/articles/response-times-3-important-limits/ |
| Attention limit; percent-done needed | 10 s | https://www.nngroup.com/articles/response-times-3-important-limits/ |
| Spinner use range | about 2-10 s | https://www.nngroup.com/articles/progress-indicators/ |
| Simple feedback animation | about 100 ms | https://www.nngroup.com/articles/animation-duration/ |
| Substantial screen change | 200-300 ms | https://www.nngroup.com/articles/animation-duration/ |
| Most animations | 100-400 ms | https://www.nngroup.com/articles/animation-duration/ |
| "Real drag" threshold | about 500 ms | https://www.nngroup.com/articles/animation-duration/ |
| Popup in / out | about 300 ms / 200-250 ms | https://www.nngroup.com/articles/animation-duration/ |
| Compose default tween example | 300 ms, `FastOutSlowInEasing` | https://developer.android.com/develop/ui/compose/animation/customize |
| Press-scale in Compose sample | 0.9, `spring()` | https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions |
| Drag-driven scale in Compose sample | 1.0 to 0.8, corners 0 to 32 dp | https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling |
| Good keyclick haptic length | 10-20 ms (actuator rings on 20-50 ms) | https://developer.android.com/develop/ui/views/haptics/haptics-principles |
| Min gap between haptic primitives | 50 ms or more (5-10 ms undetectable) | https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects |
| Haptic intensity scales | 0.5 / 0.7 / 1.0; ratio at least 1.4; max 3 levels | https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects |
| `CONFIRM` / `REJECT` / `GESTURE_START` / `GESTURE_END` | API 30 | https://developer.android.com/reference/android/view/HapticFeedbackConstants |
| `GESTURE_THRESHOLD_ACTIVATE/DEACTIVATE`, `DRAG_START`, `SEGMENT_TICK`, `SEGMENT_FREQUENT_TICK`, `TOGGLE_ON/OFF` | API 34 | https://developer.android.com/reference/android/view/HapticFeedbackConstants |
| Compose `HapticFeedbackType.Confirm` / `Reject` / `SegmentTick` / `GestureThresholdActivate` | compose-ui 1.8.0 | https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType |
| `MotionScheme.expressive()` / `standard()` | material3 1.5.0 | https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme |
| Android min touch target | 48 dp | https://developer.android.com/design/ui/mobile/guides/foundations/accessibility |
| Material `Button` min height | 36 dp + 8 dp padding = 48 dp | https://developer.android.com/codelabs/jetpack-compose-accessibility |
| NN/g min target; finger; thumb; good primary button | 1 cm; 1.6-2 cm; 2.5 cm; about 2 cm | https://www.nngroup.com/articles/touch-target-size/ |
| Apple default / min button | 44 pt / 28 pt (iOS games) | https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-games.json |
| Apple padding around controls | about 12 pt (bezel), 24 pt (no bezel) | https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json |
| Min body text | 12 sp (Android); 11 pt min, 17 pt default (iOS) | https://developer.android.com/design/ui/mobile/guides/foundations/accessibility |
| Contrast | 4.5:1 text, 3:1 non-text | https://developer.android.com/design/ui/mobile/guides/foundations/accessibility |
| Max system font scale | 200% (nonlinear since Android 14) | https://developer.android.com/about/versions/14/features |
| Apple drag image appears after | about 3 points of drag | https://developer.apple.com/tutorials/data/design/human-interface-guidelines/drag-and-drop.json |
| Frame rate target (Apple, games) | 30-60 fps | https://developer.apple.com/tutorials/data/design/human-interface-guidelines/motion.json |
| Audio playback should start or show indicator | within 1 s | https://developer.android.com/docs/quality-guidelines/core-app-quality |
| Progress bar study | people waited about 3x longer with a moving bar | https://www.nngroup.com/articles/progress-indicators/ |
| Our "case closed" timeline | about 1.2 s total | My proposal [unverified] |
| Our per-card feedback budget | 1 visual + 1 haptic + 0 sound | My proposal [unverified] |

---

## Open questions / could not verify

Pages that returned nothing usable:
- **m3.material.io** pages (motion specs, M3 Expressive blog, gestures, states, structure) return only the page title, because the site is rendered by JavaScript. So I have no first-hand damping and stiffness values for the expressive spatial and effects springs, no M3 duration tokens, no state-layer opacities, and no M3 "pick up and move" gesture guidance. The `MotionScheme` API reference and the Compose M3 page were readable and are used instead. The M3 touch target of 48 dp is confirmed only through the Android pages.
- **Apple designing-for-games** worked only via the JSON endpoint, and that page had nothing on interruptions or pause. Apple's pause and resume advice in the tool's first answer was the summariser's general knowledge, so I did not use it.
- **NN/g** gamification and gestures article URLs returned 404 (`/articles/gamification-ux/`, `/articles/mobile-gestures/`). No NN/g thumb-zone article was reachable.
- **Game Developer** "juice" article guesses returned nothing relevant. "Juice it or lose it" (Jonasson and Purho) was not reachable, so there is no first-hand text for a juice budget, screen-shake limits, or tween-based "juicing" techniques. Only Swink's article and the Wikipedia game-feel page were read.
- **Compose haptics page** (`.../compose/touch-input/haptics`) returned 404.

Unanswered questions:
1. **Thumb-zone geometry.** No page read gives a percent-of-screen map of reachable areas. The "bottom 40%" rule is a hypothesis. Test on two phone sizes with real one-handed use.
2. **Streaks and rewards.** No source read on streak psychology, loss aversion, or notification fatigue. Needs a separate literature pass (self-determination theory and overjustification research would be the starting point) before committing to any streak mechanic.
3. **Casual-game onboarding in under 30 s.** Only the principles (play-based, optional, brief) are sourced. The 30 s figure has no source. Measure time-to-first-answer in your own tests.
4. **Android sound defaults.** No Android page I read says what sound default games should use. The "off by default" rule rests on Apple's silent-mode guidance plus commuter context.
5. **Haptic behaviour below API 30 / 34.** Whether Compose and View constants degrade gracefully on older devices is not documented in the pages read. Needs a device test, especially on mid-range phones whose actuators may be weak.
6. **Compose reduced-motion.** `ValueAnimator.areAnimatorsEnabled()` is documented, but I did not find a page saying how Compose's own animations respond to the animator duration scale. Check in a device test with "Remove animations" on.
7. **Drag-and-drop API choice.** `dragAndDropSource/Target` versus `draggable2D` plus manual hit-testing for the sort-into-bins game: both exist, and the docs don't say which fits in-app games. Prototype both.
8. **"Childish" threshold.** All anti-patterns about tone, mascots and confetti are unsourced. They need testing with the target audience (university students), ideally by A/B on the same level with different feedback intensity.
9. **Apple HIG applicability.** Apple pages are used for cross-platform principles only. Sizes in pt are not dp. Do not copy the numbers across.
10. **WebFetch fidelity.** Quotes come from a summarising model. Before quoting any figure publicly (for example in the course report), open the primary page.
