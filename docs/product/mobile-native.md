# Mobile-native by design — using the phone, not just fitting on it

**Scope**: which phone capabilities become part of the product and the games, ranked for the remaining time. The rule: a feature earns its place only if it serves a user moment (game-concepts §1) *and* would be weaker or impossible on a laptop. Architecture rules at the end. Related: ahafrog wins on "real games, not quizzes" (why-and-market §5) — mobile input is where our games can feel like games.

## 1. Moments → phone capabilities

| User moment (game-concepts §1) | Phone capability | Feature | Status |
|---|---|---|---|
| PDF lands in the group chat | **Share sheet** in | "Make it a game" from any app | ✅ built (UI), needs server link |
| A page of a printed book / a whiteboard | **Camera + on-device OCR** (ML Kit) | snap → text → game; the image never leaves the phone | ❌ |
| 20 s in a queue, a lift, before class | **Interactive home-screen widget** (Glance actions) | answer one clue *on the home screen* without opening the app; misses go to the boss | ❌ |
| "Do I get it before Thursday?" | **Notifications + WorkManager** (+ optional calendar read) | "Ready by Thursday": pick a deadline → spaced sessions scheduled until then; Case-frame notifications read as story ("New evidence came in") | ❌ |
| On the metro, one hand | **Activity Recognition** (in-vehicle) | one-hand layout, bigger targets | ✅ simulator → real adapter ❌ |
| Starts walking | **Activity Recognition** (walking) | pause + "look up"; the risk becomes a feature | ✅ simulator → real adapter ❌ |
| "Can I say it in my own words?" (seminar) | **Microphone + on-device speech recognition** | *Say it back*: the rookie asks, you answer aloud for 30 s; the server checks your answer against the document's claims and shows what you missed, with pages | ❌ (needs the claim layer) |
| Classmates have the same paper | **Share out / QR** | share a solved case card into chat; scan a QR to play the same package ("compile once, the class plays") | ❌ |
| Any answer | **Gestures + haptics** | swipe verdicts ✅; *drag* cards into bins with a haptic snap; **hold to object** in cross-exam (haptic ramp, no accidental objections); distinct haptic "words" for right / wrong / combo | partial |
| No signal underground | **Offline-first storage** | play, evidence, recap offline; Ask explains why it is off | ✅ |

## 2. Game mechanics that only make sense on a phone

| Mechanic | Knowledge it fits | Input | Why it is better on a phone |
|---|---|---|---|
| **Hold to object** (cross-exam) | overclaims, limits | long-press with haptic build-up, then pick the page | the weight of an objection in your thumb; one-handed |
| **Drag to file** (sort) | categories | drag + haptic snap into a bin | direct manipulation instead of tap-tap |
| **Say it back** (new) | definitions, mechanisms, "explain why" | voice, 30 s | free recall beats recognition (Rowland 2014); speaking on the go with earphones is natural on a phone, awkward at a desk |
| **Tilt to compare** (P2) | comparisons ("which has lower error?") | tilt the phone toward the larger side | a physical metaphor for "more / less" |
| **Evidence hunt** (P2) | for printed textbooks | camera finds the passage in *your* book; OCR matches the quote | turns the physical book into part of the game |
| **Widget clue** | any swipe-able claim | one tap on the home screen | a 10-second session, impossible on a laptop |

## 3. Priority for the remaining time (Android owners)

| P | Feature | Learning value | Demo wow | Effort |
|---|---|---|---|---|
| P0 | App ↔ server (prerequisite for everything below) | — | — | M |
| P0 | Interactive widget clue + boss queue | high (spacing, retrieval) | high | M |
| P0 | Real Activity Recognition adapter (walking pause, metro one-hand) | safety + fit | high (live on a phone) | S |
| P0 | Camera OCR import | — (access) | high ("anything") | M |
| P0 | Hold-to-object + drag-to-file + haptic language | game feel | medium | S |
| P1 | "Ready by Thursday" deadline scheduling + story notifications | high (spacing) | medium | S–M |
| P1 | Say it back (voice recall, graded by the server) | **highest** (free recall, seminar job) | high | M — after the claim layer |
| P1 | Share solved case card / QR package | — (growth) | medium | S |
| P2 | Tilt to compare, evidence hunt, simulations | medium | high | M–L |

## 4. Architecture rules (so these stay decoupled)

- **Sensors are adapters** behind `ContextSource` (already the seam): simulator for screenshots/emulator, real adapters for Activity Recognition, connectivity, battery. The engine's `ContextPolicy` stays pure and tested.
- **Scheduling decisions are pure engine code** (which clue, when, until which deadline); WorkManager / notifications / widget are thin Android adapters that render the engine's decision.
- **Input modality is a renderer concern**: hold, drag, tilt are new `MechanicRenderer`s or renderer options over the *same* item types — the contract and graders do not change.
- **Voice grading never calls an AI from the phone** (ADR-013): on-device speech-to-text → text to our server → the server grades against the claims → result with pages. The audio stays on the phone.
- **Permissions in context**: camera, microphone, notifications, activity recognition are each asked at the moment of first use, with a one-line reason; everything works without them.

## 5. Follow-up

- When ahafrog.com is back online, study how its games are built (generated code in a WebView? templated simulations?) and how it keeps them correct — input for the P2 simulation mechanic.
