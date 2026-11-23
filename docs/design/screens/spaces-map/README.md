# Spaces learning map — 2026-11-23

Roborazzi captures from the unit-test renderer (393 × 852 dp at 3x, Robolectric native graphics), produced by `SpacesMapScreenshotTest`. They show the Route tab of a Space as a winding path of nodes grouped into units, replacing the list of cards in [17-three-pdf-route](../2026-11-23-concept-games-spaces/17-three-pdf-route.png). Lin is the sample student; the map opens at the unit of the next step.

| Lin's Attention Space (opens at the next step) | Whole Attention map | Chinese Space with progress |
|---|---|---|
| <img src="s1_lin_attention_en.png" width="240" alt="Unit 2 with the Start bubble and the player's character beside the current node"> | <img src="t1_attention_tall_en.png" width="240" alt="Whole Attention route: done units, current node, locked nodes"> | <img src="s2_zh_map.png" width="240" alt="Chinese map with two done steps and the current step"> |

| Node sheet | Locked node | Empty Space |
|---|---|---|
| <img src="s4_node_sheet_en.png" width="240" alt="Bottom sheet with title, kind, minutes, cited page and Start"> | <img src="s5_locked_feedback_en.png" width="240" alt="Snackbar naming the step to finish first"> | <img src="s6_empty_en.png" width="240" alt="Empty route with the character and one primary action"> |

| Continue a practice (ResNet, Chinese UI) | Font scale 2.0 | Landscape |
|---|---|---|
| <img src="t2_resnet_tall_zh.png" width="240" alt="Resnet map with a Case practice step as the current node"> | <img src="s7_font2_en.png" width="240" alt="Large text: banner and labels wrap, nodes keep their size"> | <img src="s8_landscape_en.png" width="480" alt="Landscape: the path stays centred in a 560 dp column"> |

| Spaces list, English UI | Spaces list, Chinese UI |
|---|---|
| <img src="s9_spaces_list_en.png" width="240" alt="Cards with plural-correct counts, the app's language first"> | <img src="s10_spaces_list_zh.png" width="240" alt="Chinese Spaces first; English ones carry an English chip"> |

The tabs and the progress strip are one sticky header: the strip stays in view while the map scrolls. Preset routes include one related Playground game step per Space (for example ResNet: "Which layer broke?"); finishing it from the route offers "Back to <Space>" ([p20](../playground/p20_completed_from_route_en.png)).

Step titles are the course's own concept and level names. Test generators once put "(mock)" in every concept title; the map removes that suffix from old saved titles and shows a "Test content" badge on the unit instead.
