# Actual Android screenshots — 2026-11-23

Unaltered captures from the debug APK on the dedicated API 37 arm64 emulator (1080 × 2400). The sample profile was upgraded in place from the original APK. These are application screens, not design mockups. See the [acceptance record](../../concept-games-spaces-acceptance.md) for scenarios and limits.

| Playground | Partition game | Resource allocation |
|---|---|---|
| <img src="01-playground-zh.png" width="240" alt="Chinese Playground with installed native games"> | <img src="02-partition-zh.png" width="240" alt="Partition board after a saved query"> | <img src="04-allocation-zh.png" width="240" alt="Allocation board with real output and opportunity cost"> |

| Migrated Spaces | Shared progress | Large text |
|---|---|---|
| <img src="00-spaces-upgrade-zh.png" width="240" alt="Original courses migrated into Spaces"> | <img src="05-me-zh.png" width="240" alt="Me showing two completed games and one learning day"> | <img src="04b-allocation-large-text-zh.png" width="240" alt="Allocation controls at font scale 1.5"> |

Additional captures: [transfer completion](03-partition-complete-zh.png), [large-text Me](06-me-large-text-zh.png), [English Playground](07-playground-en.png). The reproducible game acceptance results are in [games-result.json](games-result.json).

| Three-PDF route | Materials | Correct PDF source |
|---|---|---|
| <img src="17-three-pdf-route.png" width="240" alt="Space with three PDFs and the actual learning route"> | <img src="19-space-materials.png" width="240" alt="Uploaded PDF reading and practice actions"> | <img src="18-notes-source-page.png" width="240" alt="The repository-authored notes fixture with its cited sentence highlighted"> |

The route uses the explicit fake provider: `mock` labels are visible. The source screenshot shows the repository-authored test PDF, not a third-party paper. More evidence: [upload confirmation](12-multi-pdf-confirmation.png), [independent file outcomes](13-import-results.png), [local-material exclusions](15-route-exclusions.png), [retained Rookie](10-legacy-rookie.png), [retained Escape](11-legacy-escape.png).

| Generated native game | Unsupported topic | In-flight cancellation |
|---|---|---|
| <img src="21-generated-native-game.png" width="240" alt="API-generated native game after an actual split"> | <img src="22-unsupported-topic.png" width="240" alt="Unsupported topic message and retry"> | <img src="23-generation-cancelled.png" width="240" alt="Cancelled generation retains an explicit cancelled state"> |

[PDF Tutor explanation](20-notes-tutor.png) uses the repository-authored notes. Successful generation uses explicit fake fixtures; cancellation used a controlled loopback delay endpoint. Neither establishes live-model educational quality.

[External PDF sharing confirmation](24-external-share-confirmation.png) remains visible above a previously scrolled Materials list after rotation.
