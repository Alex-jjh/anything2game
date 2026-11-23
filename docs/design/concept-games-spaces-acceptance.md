# Concept games and Spaces — acceptance record

**Scope:** Verification of the API edition's 2026-11-23 update. This record distinguishes automated behavior, Android device observations and untested provider quality. **Final verification in progress:** device scenarios below have run; the Classic selector finding and final branch review are still pending.

Design: [approved user journeys and repository research](../superpowers/specs/2026-11-23-concept-games-spaces-design.md). Implementation: [shared architecture and API](../architecture/concept-games-spaces.md).

## Environment and baseline

- Base revision: `3462393101e9a8f42b48d680a163fa73937450c9`; branch `codex/concept-games-spaces`.
- Dedicated emulator `emulator-5578`, Android API 37.1, arm64, 1080 × 2400 at 420 dpi. This is emulator evidence, not a physical-device test.
- A separate AVD preserves the developer's existing emulator data. The original base APK was installed first. The Chinese sample profile has a saved PRACTICE tab, original mastery files and an unfinished ResNet Case. Its files and preferences were backed up before upgrade.
  The saved run is `demo_resnet`, version `1`, level `L1`, frame `case`, queue position `3`, with two correct unit results and credibility `3`.
- Dedicated PostgreSQL 14.18 at loopback port 55439. The store harness creates and drops only its UUID-named test databases. Docker was unavailable; the separate SDK-free server distribution build passed, but Docker image execution is not established by that check.
- Local API on loopback port 18080 with explicit fake compile/frame/flash providers, reached over `adb reverse`. No live model credentials were used. Generated fixture content must retain its label in the app and screenshots.
- Three valid PDF fixtures and one malformed file are staged for the multi-document import test. Third-party PDF bytes are not added to this repository.

## Automated verification

| Revision / scope | Result |
|---|---|
| Original baseline | 61 contract + 49 engine + 187 server tests, zero failures; 27 PostgreSQL tests skipped before the dedicated DB was available. 228 Android tests passed separately. |
| `6b400d2`, server and shared modules | 222 server + 68 contract + 58 engine tests passed, zero skips, including 42 PostgreSQL tests. SDK-free `server:installDist` passed. |
| `f9a6740`, Android data and shared modules | 257 Android + 58 engine + 68 contract tests passed, zero skips. One existing Compose source-reader journey had a transient missing-button failure on the first full run, then passed in a focused run and the unchanged full repeat. |
| `9180838`, server against final shared runtime | 222 server tests passed, zero failures/errors/skips, including the 42 PostgreSQL cases; fresh run in 29s. |
| `09c4b74`, final Space migration corrections | 32 recovery/integration/data tests passed, zero skips; normal removal and explicit dismissal both discard unsent membership changes derived from a rejected ADD, and lost acknowledgements preserve the original request. |
| `9180838`, UI recovery round 1 | 286 Android + 58 engine + 68 contract tests passed, zero failures/errors/skips. Debug APK assembly and direct-AI-access guard passed; lint has zero errors and 66 warnings. The controller independently counted the XML results. Task review approved after the focused sharing correction below; device/final-branch gate pending. |

The UI full run initially exposed two fixture timing races (profile switch after refresh and tapping Start after a diagnostic transition). Their waits were corrected without removing the avatar/profile or source attachment assertions; the fresh complete run passed in 4m 21s. Existing Compose test API deprecation and JDK/Robolectric native-access warnings remain.

The final Space UI correction at `a5c204d` passed 14 focused share, picker, navigation and batch tests, zero skips, plus lint and assembly. It reproduces a previously scrolled destination and verifies that exactly one confirmation survives recreation outside the lazy list, with no queue mutation before confirmation. Independent task review approved the fix.

Counts from different runs overlap. They must not be added together as independent tests.

The data regressions exercise interrupted practice and tutor writes, concurrent player initialization, unknown PDF upload outcomes, durable generation rejection, Space edit recovery, authenticated package provenance, publication barriers, exact-version source geometry and concurrent PDF imports. Shared tests exercise deterministic mechanics, changed transfer rules, strict archive validation and historical v1 compatibility. Server tests cover both stores, ownership, quotas, cancellation, stale leases and path activation.

## Device acceptance and screenshots

The real APK was upgraded in place at `a5c204d`. [Screenshot gallery](screens/2026-11-23-concept-games-spaces/) contains unaltered emulator captures, visually inspected; the earlier HTML prototype is not acceptance evidence.

| Scenario | Observed result |
|---|---|
| Upgrade | Old PRACTICE opens Spaces; four legacy mastery files and avatar preferences are byte-identical. ResNet v1 Case retains question 3, two prior correct results and credibility 3. |
| Native games | Both examples completed their practice and changed-condition transfer through actual controls. Tested explicit partition selection, allocation failure/retry/help/undo, and idempotent helped completion. |
| Recovery / offline | Selected cut survives rotation; committed checkpoint survives background and force-stop/relaunch. Installed games play with the API reverse rule removed. |
| Accessibility / language | Both Chinese and English navigation render. At font scale 1.5, allocation controls and Me remain reachable. |
| Three-PDF Space | Multi-selected three valid PDFs plus one malformed fixture, rotated the confirmation, then imported. Three independent successes and one failure; retrying the failed row left all three successful rows byte-identical. Renamed the Space to Learning Lab. |
| Route / membership | Added a local MapReduce pack; exclusion was visible before generation. Real local API returned and installed a fixture path. Removing that membership left the original source and its original Space intact. |
| Citation / Tutor | The own-notes citation opens original PDF page 1 of 1 with the quoted sentence highlighted. Three diagnostic answers lead into the actual Tutor explanation, visibly labeled as a test provider. |
| Generation cancellation | During an actual request to a loopback-only provider deliberately delaying its response, tapping Cancel persisted `cancelled` and installed no artifact. The API was then restored to fake mode. |
| External PDF share | System Files → share the own-notes PDF → choose the existing, deeply scrolled Learning Lab. Exactly one confirmation survives rotation; cancelling leaves the queue unchanged. |
| Legacy practice | MapReduce full source reader and Rookie, and Attention Escape open. Classic availability is a known final-fix item. |
| Concept generation | Binary search produces an installed native game through the local API; a real split action creates its versioned checkpoint. Unsupported quantum gravity fails with a useful message; explicit retry creates a new request and preserves the installed game. |

Reproduce the scripted portion on a dedicated emulator with an installed APK and an initially unplayed sample profile:

```sh
python3 -B android/qa/concept_games_spaces_smoke.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5578 \
  --output /tmp/a2g-game-qa --profile sample \
  --baseline /tmp/a2g-before-upgrade-files.json
```

The optional baseline inventory is a JSON list of `{path, sha256}` for original app files; omit `--baseline` when not testing an in-place upgrade. The script never installs, clears data or silently selects an emulator. Its nine successful checks are in [games-result.json](screens/2026-11-23-concept-games-spaces/games-result.json). `home_navigation_smoke.py` delegates to the same driver rather than duplicating it.

Manual API/PDF reproduction:

1. Run the API with explicit fake compile/frame/flash providers; configure the debug app and `adb reverse tcp:18080 tcp:18080`. Use a dedicated QA account/profile.
2. Create a Space, choose multiple PDFs in the system picker (device storage → Download if adb-staged files are absent from the Downloads index). Select three valid PDFs and a deliberately malformed file. Rotate before confirming; verify there is no queue mutation before confirmation.
3. Confirm, inspect each result, retry only the malformed file, and compare completed queue entries. Add a local bundled material and inspect its exclusion before generating the route. Remove that membership and reopen the original Space.
4. Open a source link, verify its document and page, then enter Tutor and complete its diagnostic prerequisites. Reopen old Case/Rookie/Escape from their migrated Space materials.
5. In Playground enter `binary search`, wait for installation and perform a native action. Submit `quantum gravity`, inspect the unsupported message and retry. Verify the installed game remains.

For cancellation, point only the compile tier at a loopback OpenAI-compatible test endpoint that accepts the request then delays for 30 seconds. Cancel from the app after that endpoint reports receipt; assert the persisted ticket is `cancelled` and `installed=false`. This checks in-flight cancellation, not model quality. Restore fake mode afterward.

For the share regression, leave the target Space Materials scrolled below its toolbar, then share a PDF from system Files, choose that existing Space, rotate the confirmation and cancel. Compare the import queue before and after.

Use an English IME for ASCII adb text fixtures and verify the committed field text. Pinyin composition is not equivalent to saved text. UI actions use current hierarchy bounds; dialog text can appear twice (title and action), and separate dialog windows do not expose all Compose test tags.

## Decisions made during implementation

- The user's explicit approval covers implementation, tests, screenshots and a feature-branch push. Historical proposal wording does not reopen approval. An incorrect interpretation would cost reversible feature rework; the branch is not merged into main automatically.
- The app's native worktree tool did not return or create a checkout. A Git worktree inside the writable project directory provides the same isolation without removing the original design edits.
- The first release supports two reviewed mechanisms. Unsupported concepts are explicit rather than generating arbitrary executable code. Expanding coverage requires a reviewed source and, where needed, a new shared mechanism.
- The legacy upload API has no idempotency key. After an uncertain response, the client asks for a deliberate new upload instead of silently repeating it. This adds one recovery decision after an interrupted upload.
- An unsigned SERVER label cannot prove ownership. Historical and sideloaded packs without an authenticated receipt stay local even if IDs match an owned source; this can leave separate cards until provenance is independently established.
- The Space list shows one clearly labeled profile-wide weekly learning-day summary. Each card has its own material count, node completion and next activity. Per-Space calendars would need filtering through the same progress implementation; global days are not relabeled as a particular Space's days.
- A game route node must reference the final transfer activity that the engine can credit. Intermediate practice-stage references are rejected before activation. Supporting separate stage completion later would require explicit progress semantics, not fabricated credit.
- New game and Space-path results are published as one validated archive. They do not support regenerating just failed parts. An unexpected partial result needs an explicit whole-request regeneration, including the current Space exclusions. Per-file PDF retries and the legacy compile flow remain separate existing capabilities.

## Limits

Live-model teaching quality, physical-device behavior and Docker container execution remain outside the evidence above. Preset and fake-provider success does not establish arbitrary-topic coverage or generated educational accuracy.
