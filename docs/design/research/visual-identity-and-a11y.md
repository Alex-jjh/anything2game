# Visual identity, bilingual type, colour and accessibility (Compose / Material 3)

Date: 2026-11-19. Scope: research only, no code changed. Evidence labels: a claim without a tag was read on the cited page. `[unverified]` means I did not read it on a page (own knowledge, heuristic, or the page could not be fetched). `[computed]` means I calculated it with the WCAG formula (script in /tmp, formula from the contrast-minimum page).

Fetch caveat: WebFetch returns a small-model summary of each page, so quotes are summaries, not byte-exact. The m3.material.io pages (color, typography, accessible-design, color-contrast) are client-rendered and returned only a title, so nothing in this file comes from m3.material.io itself.

## 1. Summary (10 lines)

1. The current look is a stack of the same defaults: cream paper + ink + muted teal + amber + system Serif. Any one is fine; the stack is the "AI default" tell. The fix is a point of view, not a different hex.
2. The existing `Skin` token class is the right seam (role colours via `staticCompositionLocalOf`, which is the pattern Android documents). Keep it, widen it, and add dark.
3. Measured on the current palette [computed]: amber evidence text is 3.23:1 on paper (fails 4.5), green "correct" is 4.48:1 (just fails), outline is 1.36:1 (fails the 3:1 UI rule), and correct/wrong have a luminance ratio of only 1.09, so under protanopia simulation they are close (dE 11).
4. Cast roles reuse semantic colours (suspect = wrong red, companion = correct green, narrator = evidence amber). A green companion bubble reads as "correct". Cast identity must come from avatar shape + name; colour is only a tint.
5. Never signal right/wrong by hue alone: icon + word (正确/错误) + shape, and separate the two in lightness. Blue/orange survives colour-blindness better than green/red [computed].
6. Fonts: Noto Sans SC and Noto Serif SC are OFL; but a full Han file is 8.3 MB (Sans Regular) to 11.6 MB (Serif Regular) per weight. Bundle Latin only, let Han fall back to the system CJK font for body, and bundle at most one subsetted Han display weight.
7. Downloadable Google Fonts go through the Google Play services provider; mainland-China Android devices often lack it [unverified], so always keep a bundled fallback.
8. Han text needs more leading (clreq: line gap 50-100% of the character frame, so line height 1.5-2.0x), never negative tracking, and no italics/synthetic slant.
9. Dynamic colour (Material You): off for the game surfaces. It cannot guarantee that evidence/correct/wrong stay distinct and branded. At most apply it to neutral chrome (settings).
10. Three token sets below (Lab Tag, Night Desk, Cyanotype) with computed contrast. Recommendation: build Lab Tag first (best contrast, cheapest fonts), borrow Night Desk's dark scheme. All three pair colour with icon/shape/word.

## 2. Q1 Bilingual EN/ZH typography

### Rules
- Licence: Noto Sans CJK repo LICENSE = SIL OFL 1.1 (read raw LICENSE). Google Fonts metadata lists Noto Sans SC (wght 100-900) and Noto Serif SC (wght 200-900) as "OFL", subsets include chinese-simplified. IBM Plex repo LICENSE = OFL 1.1 (Copyright 2017 IBM). Fraunces, Bricolage Grotesque, Courier Prime: OFL 1.1 (each OFL.txt read). Space Grotesk: metadata says OFL, variable wght 300-700; IBM Plex Sans Condensed: metadata OFL, no Han glyphs.
- OFL practical meaning for an app: embedding and redistributing is allowed; do not sell the font alone; modified versions must not use the Reserved Font Name [unverified summary of OFL, not read]. Subsetting counts as modification, so rename the subset file (e.g. `a2g_display_sc_bold.otf`) [unverified].
- LXGW WenKai (a Han "humanist" face that would give real personality): OFL 1.1 plus an "[ADDITIONAL PERMISSION]" clause (read, content not read) and Reserved Font Names; TTF is 25.9 MB per weight (v1.530 assets). Too heavy to bundle; only consider it subsetted. Read the additional-permission clause before use.
- Size cost (bytes, GitHub API, Subset OTF for SC): Noto Sans SC Regular 8,331,336, Bold 8,543,168, Light 8,299,104, Black 8,855,916; Noto Serif SC Regular 11,625,800, Bold 12,094,336. Regular+Bold Sans = 16.9 MB. Variable file size not measured [unverified].
- Compose bundling: put fonts in `res/font`, build `FontFamily(Font(R.font.x, FontWeight.Normal), ...)`. Variable fonts work via `FontVariation.Settings` and are supported on Android O+ (our minSdk is 26, so safe); variable fonts are NOT supported through downloadable fonts; filename must be lowercase, no special characters.
- Downloadable fonts: dependency `androidx.compose.ui:ui-text-google-fonts` (page shows 1.12.1), `GoogleFont.Provider(providerAuthority = "com.google.android.gms.fonts", providerPackage = "com.google.android.gms", certificates = ...)`. Needs the Play services package; custom providers unsupported; new Google fonts take months to reach Android. Chain fallbacks per weight: `Font(googleFont, provider)` then `Font(R.font.x)`; the resolver tries online first, then the resource. Use `isAvailableOnDevice` to test the provider.
- M3 `Typography` has no `defaultFontFamily`; set `fontFamily` in each `TextStyle` (stated on the M3 Compose page). Default scale (read): display 57/64, 45/52, 36/44; headline 32/40, 28/36, 24/32; title 22/28, 16/24, 14/20; body 16/24, 14/20, 12/16; label 14/20, 12/16, 11/16.
- Recommended font strategy (own judgement, [unverified] on-device):
  - Latin: bundle one variable sans (e.g. IBM Plex Sans or Space Grotesk) and optionally one mono for labels. A Latin-only file leaves Han to Android's glyph fallback [unverified, verify on emulator + a Xiaomi/Huawei device].
  - Han body and the student's reading passages: system CJK font (zero MB, but OEM look varies).
  - Han display (headings, stamps): bundle ONE bold weight of Noto Sans SC subset to the glyphs of fixed UI strings + common characters (pyftsubset) [unverified tool/size, measure]; anything outside falls back.
  - Pass the content language to the text (`TextStyle(localeList = LocaleList("zh-Hans-CN"))` for Chinese blocks) so Han unification picks Simplified glyph forms; choose by the language of the text block, not the device locale, because content is bilingual [unverified API detail, test].
- Serif: reserve it for one job, "the voice of the source" (the student's passage and the evidence quote). Then serif means something and stops being the generic heading choice. Noto Serif SC is the Han partner (OFL, wght 200-900); cost is 11.6 MB per weight if bundled, so use the system serif fallback or subset [unverified].

### Han vs Latin metrics (clreq, fetched via summary)
- Line gap: "commonly set to a value between 50% and 100% of the height of the character frame" and line height = font size + line gap (clreq 7.1.1.5). Another section: single-sided setting line gap must not be smaller than half the font size (5.6.1). So Han line height is 1.5x to 2.0x the size. M3's 16/24 (1.5x) is the floor, not the target, for Han.
- Min size: clreq says smaller text is harder to read "given the complex structure of Han characters" (7.1.1.5). Don't use M3 labelSmall 11sp / bodySmall 12sp for Han.
- Mixed Han + Latin, horizontal: gap between Han and adjacent Western letters/digits at most 1/4 em, none at line start/end (6.3.3). Min Latin word space 1/4 em, max stretch half a Han width (6.2.2.3/4). Practical: author strings without ad-hoc spaces, or consistently use a thin space; do not justify (Compose justify does not compress punctuation [unverified]); use start alignment.
- Punctuation: two adjacent 2-em marks compress to 1.5 em (6.3.2.2); line-end full-width punctuation trimmed to half width (6.3.2.3); mainland China places pause/stop marks toward the lower-left of the square (2.1.2). Two-em dashes and ellipses must not be split (6.1.2.1). Compose line-break presets exist (`LineBreak`) [unverified]; test that a closing quote or 。 never starts a line.
- Letter-spacing: Han is square; clreq lists loose setting at 1/4, 1/3, 1/2, 1 em as a deliberate effect (6.3.1.1) and says tight setting is unsuitable (6.3.1.3). So `letterSpacing = 0.sp` for Han; do not inherit M3 tracking (e.g. +0.5sp label, +0.15sp title) onto Han runs.
- Emphasis: clreq lists emphasis dots and bold (7.1.3.2: headings "usually use bold"); italics were not discussed there. Avoid italic for Han; use weight or a highlighter fill. Do not use FontWeight below Regular for Han at small sizes (thin strokes) [heuristic].
- WCAG text spacing (1.4.12): content must survive line height 1.5x, paragraph 2x, letter 0.12 em, word 0.16 em. The page says scripts that don't use a property are exempt; no Han-specific text.

### Proposed starting scale (heuristic, tune on device) [unverified]
| role | Latin sp/line | Han sp/line | note |
|---|---|---|---|
| source passage | 18/28 | 18/32 | serif voice, start-aligned |
| body | 16/24 | 16/28 | |
| label / chip | 14/20 | 14/22 | min Han 14 |
| title | 22/28 | 22/32 | |
| display (stamp) | 28-36 | 28-36, bold subset | letterSpacing 0 for Han |
Set line heights in sp (they scale with font scale) and never fix a container height around text.

Sources: https://developer.android.com/develop/ui/compose/text/fonts ; https://developer.android.com/develop/ui/compose/designsystems/material3 ; https://www.w3.org/TR/clreq/ ; https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE ; https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/METADATA.pb ; https://raw.githubusercontent.com/google/fonts/main/ofl/notoserifsc/METADATA.pb ; https://api.github.com/repos/notofonts/noto-cjk/contents/Sans/SubsetOTF/SC ; https://api.github.com/repos/notofonts/noto-cjk/contents/Serif/SubsetOTF/SC ; https://api.github.com/repos/lxgw/LxgwWenKai/releases/latest ; https://raw.githubusercontent.com/lxgw/LxgwWenKai/main/OFL.txt ; https://raw.githubusercontent.com/IBM/plex/master/LICENSE.txt ; https://raw.githubusercontent.com/undercasetype/Fraunces/master/OFL.txt ; https://raw.githubusercontent.com/google/fonts/main/ofl/bricolagegrotesque/OFL.txt ; https://raw.githubusercontent.com/google/fonts/main/ofl/courierprime/OFL.txt

## 3. Q2 Colour: role-based palette that passes

### Thresholds (WCAG, read)
- Text 4.5:1; large text 3:1 (18pt, or 14pt bold; "CJK fonts have equivalent sizes"); ratios are not rounded (4.499 fails). Android's own page says under 18sp (or bold under 14sp) needs 4.5:1.
- UI components and graphics needed to understand content: 3:1 against adjacent colours (1.4.11), including state (selected/focused) and focus indicators; inactive controls exempt. A control with visible text needs no border.
- Colour must not be the only cue (1.4.1; failures F81: errors marked by colour only). Android: use "different shapes or sizes, text or visual patterns, or audio/haptic". A 3:1 lightness difference counts as an additional visual cue.
- Formula: L = 0.2126R + 0.7152G + 0.0722B on linearised sRGB; ratio = (L1+0.05)/(L2+0.05).
- Material tone rule of thumb "tone difference 40 = 3:1, 50 = 4.5:1" [unverified; m3 page unreadable].

### Audit of the current palette [computed]
| pair | ratio | verdict |
|---|---|---|
| ink #1F2A37 / paper #F6F1E7 | 12.91 | ok |
| muted #5B6575 / paper | 5.24 | ok |
| action #1F4E5F / paper; white on action | 8.06; 9.08 | ok |
| evidence #B7791F text / paper | 3.23 | FAIL text (3.55 on card) |
| white on evidence | 3.64 | FAIL |
| correct #2F7D4F / paper | 4.48 | FAIL by 0.02 |
| correct / correctSoft #DDF0E3 | 4.23 | FAIL |
| wrong #B4442F / wrongSoft | 4.30 | FAIL |
| outline #D9D0BF / paper | 1.36 | FAIL 3:1 (field/card edges) |
| correct vs wrong luminance ratio | 1.09 | near identical lightness |
| correct vs wrong dE under protan / deutan sim | 11.4 / 24.4 | weak (normal vision 81) |
| correctSoft vs wrongSoft dE, deutan | 4.4 | indistinguishable |
Cheap fixes if you keep this skin: evidence text #7A5200 (6.15 on paper), correct #2A7248 (5.18), outline #8C8372 (3.33). CVD simulation used Machado-2009 severity-1 matrices typed from memory [unverified]; treat dE as indicative.

### Rules
1. Two layers per role: a strong colour (text, icon, 3:1 edges, solid fill with on-colour) and a soft container. Put coloured text only on paper/card; on soft containers use ink (ink on soft fills measures 10-15:1 in all three directions below).
2. Evidence as a fill with ink text (highlighter / tag), not as coloured text. Contrast of ink on the fill is 8-14:1, and the fill needs a 1.5-2dp edge (fill alone is 1.1-1.4:1 on paper).
3. Correct/wrong: pair a blue-teal with a vermilion/orange (stay distinguishable in protan/deutan) and make them differ in lightness where possible; always add icon (check / cross in different silhouettes: circle vs diamond/jagged) and the word. In a Chinese-reading audience, red/green carry different cultural meaning (red is auspicious, finance red = up) [unverified, cultural knowledge, not read], another reason not to rely on hue.
4. Cast roles are not colours-with-meaning. Give each role a silhouette (circle, square, hex, ...) + initial/name tag; use a tint only as a ring and keep it out of the semantic hue families. Sample cast ring set for Lab Tag light on card #FAFCFD: partner #2037C2 8.55, suspect #A3166B 7.08, rookie #8A5A00 5.76, companion #2E6B2E 6.26, narrator #3E4C59 8.56 [computed]; dark on #16212B: #9DAEFF 7.72, #FF8FC8 7.78, #E3B04B 8.22, #8FD98A 9.68, #B7C3CE 9.10 [computed].
5. Paused / hint / disabled: neutral slate with a pause glyph; hint purple with a lightbulb glyph. Disabled controls are exempt from contrast but must not look like enabled "wrong".
6. Dark mode is its own scheme, not inversion: lighten accents until 4.5:1 on the dark card, raise on-colours to dark ink, and swap soft containers to dark tints (done in each direction below). Skin.kt only has `lightColorScheme` today.
7. Test with the Android Accessibility Test Framework checks (contrast, target size, traversal order) via `enableAccessibilityChecks()` in Compose 1.8+.

Sources: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html ; https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html ; https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html ; https://developer.android.com/guide/topics/ui/accessibility/apps ; https://developer.android.com/guide/topics/ui/accessibility/principles ; https://developer.android.com/develop/ui/compose/accessibility/testing

## 4. Q3 Material 3 theming in Compose

- M3 theme = colour scheme + typography + shapes (read). Five key colours, each with a 13-tone palette; generate with Material Theme Builder, which exports Color.kt/Theme.kt (read). Note: `lightColorScheme(...)` in Skin.kt sets only 9 roles; every unset role (containers, tertiary, surfaceContainer*, outlineVariant, on-containers) stays at the library default [unverified which values, but it is the baseline M3 palette]. M3 components (chips, cards, nav bar, text fields) will leak that look. Fix: set the full role list for light and dark, or generate from the key colours and then override.
- Use matching on-colours (on-primary on primary, on-primary-container on primary-container); the Android page shows tertiaryContainer-on-primary as a failing example.
- Extending: the documented options are (a) extension properties like `ColorScheme.snackbarAction`, (b) an `ExtendedColors` data class provided through `staticCompositionLocalOf` + `CompositionLocalProvider` wrapping `MaterialTheme`, with an accessor object, (c) replace one subsystem, (d) full custom system. The existing `Skin` + `LocalSkin` + `A2g.skin` is option (b). Keep it. Widen tokens to M3's pattern: `evidence`, `onEvidence`, `evidenceContainer`, `onEvidenceContainer`, same for correct/wrong/hint/paused, plus `castRing[role]`. Wrap Material components you restyle in your own composables (the page says to wrap and set values; extended values do not flow into components by themselves).
- Dynamic colour: Android 12+ (`dynamicLightColorScheme(LocalContext.current)` behind `Build.VERSION.SDK_INT >= S`); the page says it is designed to meet contrast and derives from wallpaper. Recommendation for a branded game: off for game screens. Reasons: identity is the product; wallpaper-derived primary can collide with evidence/correct/wrong hues; contrast guarantee covers M3 roles, not our extended roles. If offered, allow it only to tint neutral surfaces in Settings/library, keep semantic roles fixed [judgement].
- Shapes: M3 `Shapes(extraSmall..extraLarge)` as `RoundedCornerShape`; `RectangleShape`, `CircleShape` also available. Default M3 rounding is the second "template" tell; a decisive choice (square-cut, clipped corner via `CutCornerShape` [unverified], or very round) is more distinctive than 12dp everywhere. Shapes "express brand" (page).
- Typography: reduce to ~6 styles actually used; set `fontFamily` on each; line heights in sp.
- Skin.kt gaps vs. this research: no dark scheme, three styles customised, cast colours reuse semantic ones, headingFont = `FontFamily.Serif`.

Sources: https://developer.android.com/develop/ui/compose/designsystems/material3 ; https://developer.android.com/develop/ui/compose/designsystems/custom

## 5. Q4 Identity: avoiding the template, three directions

### What makes a look templated (judgement + user's quoted guidance)
- Default stacks: cream + serif display + muted teal/amber; M3 12dp rounded cards; gradient hero; icon-in-circle lists.
- A distinctive identity needs: one repeated "device" (an object the user touches), a restricted palette (one dominant, one sharp accent), a type voice with a job, a shape rule, and a motion signature. Test: swap the logo out; can you still tell the app from one screenshot?
- Investigation vernacular as language (not copying any game, I did not survey games): evidence tag with exhibit number (证物 A-07), evidence bag/label, chain-of-custody strip, case-file tab and paper clip, rubber stamp verdicts (已核实 / 夸大 / 驳回), redaction bar (hide the overclaim, reveal on "Show evidence"), index card, pin and string for "connect the cards", lab highlighter, scale ruler. Apply to mechanics: "Show evidence" = peeling a tag or lifting a redaction bar onto the source passage; the overclaiming Summary = a stamp that gets overruled.
- Bilingual identity: use a Han character as graphic (证 or 据 as a stamp) and a Latin word beside it; design every component for both scripts at 1.0 and 2.0 font scale.

### Direction 1: "Lab Tag" (cool paper, UV highlighter, mono labels)
Feel: forensic lab bench, evidence bag, highlighter on a printed report. Playful but clinical. Shape: clipped-corner tags (4 dp cut), square cards 4dp, pill only for chips. Device: tag with exhibit number in mono; verdict stamp. Type: IBM Plex Sans (body, variable if available) + IBM Plex Mono (labels, exhibit numbers) + Noto Sans SC (Han, system for body, bundled bold subset for display); serif only for source passages.

| token | light | dark |
|---|---|---|
| paper | #E9EEF1 | #0D141B |
| card | #FAFCFD | #16212B |
| ink | #0E1822 | #E6EDF2 |
| muted | #46586A | #9FB0BF |
| outline | #66788A | #60758A |
| action / on | #2037C2 / #FFFFFF | #9DAEFF / #0B1033 |
| evidence fill / on | #CCEF3A / #0E1822 | #CCEF3A / #0E1822 |
| evidence mark (text) | #5A6B00 | #CCEF3A |
| correct (soft) | #00697A (#D3EEF2) | #4FD1DF (#0F3A40) |
| wrong (soft) | #B93A0A (#FBE0D3) | #FF9B6A (#4A2314) |
| hint (soft) | #6A3FB5 (#E7DEF7) | #C3A8FF (#2E2350) |
| paused | #4B5B6B | #9AA9B8 |

Contrast [computed]: light ink/paper 15.32, muted/paper 6.27, outline/paper 3.89 (card 4.42), onAction/action 8.80, onEvidence/fill 13.63, evidence mark/paper 5.08, correct/paper 5.44 (on soft 5.23), wrong/paper 4.89 (on soft 4.55), hint 6.01, paused 5.97; white on correct 6.36, white on wrong 5.72. Dark: ink/paper 15.68, muted 8.33, outline 3.89 (card 3.43), action 8.77, evidence 14.10, correct 10.17, wrong 8.96, hint 9.15, paused 7.72. Fill-vs-paper for the evidence highlighter is 1.12 (light), so it always carries an ink edge.
CVD [computed, simulation from memory]: correct vs wrong dE protan 57.6 / deutan 73.5 / tritan 103.2 (normal 97). Luminance ratio only 1.11, so icon + word still mandatory.
Trade-offs: + best overall contrast, cheapest fonts (Latin only), unmistakably not cream; + lime is rare in learning apps. - Lime/cobalt can feel techy or "dev tool" if no warmth; mono labels in Han have no equivalent, so use Noto Sans SC Medium with small caps-style spacing and lock label height; lime fill glare in dark mode (use at small areas).

### Direction 2: "Night Desk" (dark-first, lamp pool of light)
Feel: late-night case reading under a desk lamp; evidence "lit" by a warm pool. Calm, immersive, good for long reading in the evening. Shape: large soft radii (20-28dp) plus a circular "lamp" focus on the active card. Device: lit evidence (the quote glows, the rest dims). Type: Bricolage Grotesque (display, variable wght) + IBM Plex Sans (body) + Noto Sans SC; Noto Serif SC (system or subset) for the source quote. Light scheme is a neutral warm-grey daylight variant, deliberately not cream.

| token | dark (primary) | light (daylight) |
|---|---|---|
| paper | #16120E | #F1F0EE |
| card | #221C16 | #FFFFFF |
| ink | #F1E9DA | #1B1712 |
| muted | #B3A793 | #5A5247 |
| outline | #7B6D58 | #857B6E |
| action / on | #F0B03A / #1C1305 | #8A4B00 / #FFFFFF |
| evidence fill / on | #3A2C12 / #FFE2A8 | #FBE3A8 / #1B1712 |
| evidence mark | #FFD98A | #7A5200 |
| correct (soft) | #62D6C0 (#12362F) | #0F6B5C (#D5EEE8) |
| wrong (soft) | #FF8F73 (#4A1F14) | #B03A1E (#F8DFD8) |
| hint (soft) | #BFA8FF (#2D2450) | #5B3FA8 (#E8E1F6) |
| paused | #8FB8E8 | #3F5F8A |

Contrast [computed]: dark ink/paper 15.45, muted 7.87, outline 3.70 (card 3.35), action/paper 9.74, onAction 9.59, onEvidence/fill 10.78, evidence mark 13.79, correct 10.56, wrong 8.37, hint 9.10, paused 9.04; ink on soft fills 10.9 / 11.6. Light ink 15.65, muted 6.75, outline 3.65, onAction 6.80, evidence mark 6.08, correct 5.63, wrong 5.31 (on soft 4.77), hint 6.78, paused 5.73.
CVD [computed]: dark correct vs wrong dE protan 25.6 / deutan 40.0 / tritan 99.3; light protan 29.4 (luminance ratio 1.06-1.26). Weakest of the three: soft pair dE protan 4.9 (light), so do not distinguish states by soft fill; use icon + stroke style.
Trade-offs: + most atmospheric, strong fit with "evidence under a lamp", dark mode is the hero. - Amber/brown is closest to the old warm palette, so the "not cream" claim rests on dark base and lighting device; two-font Latin pair plus optional serif risks inconsistency; dark-first needs outdoor/daylight check; weakest CVD separation.

### Direction 3: "Cyanotype" (one ink, one stamp)
Feel: blueprint / cyanotype print with a rubber stamp. One dominant ink blue does text, borders, action and "correct"; a vermilion stamp does verdicts; yellow marks evidence. Shape: 0-2dp radii, 2dp ink borders, hard offset shadows, rotated stamps (-3 degrees) that thump on landing (respect reduced motion). Device: the stamp. Type: Noto Sans SC Black/Bold for headings and Han stamps (Han weight carries the identity, single family keeps Latin/Han baselines matched) + Courier Prime for case-number labels (OFL read) + system/Noto Sans body.

| token | light | dark |
|---|---|---|
| paper | #F1F3F6 | #07163D |
| card | #FFFFFF | #0E2358 |
| ink | #0B2A6F | #EAF0FF |
| muted | #41578F | #A9BAE6 |
| outline | #0B2A6F | #7F97D6 |
| action / on | #0B2A6F / #FFFFFF | #FFC62B / #07163D |
| evidence fill / on | #FFC62B / #0B2A6F | #FFC62B / #07163D |
| evidence mark | #7A5A00 | #FFC62B |
| correct (soft) | #0B2A6F (#DCE6FA) | #9EC1FF (#17336F) |
| wrong (soft) | #BA2A0A (#FBDDD5) | #FF8466 (#4B1E1E) |
| hint (soft) | #5B3FA8 (#E8E1F6) | #C9B3FF (#33275E) |
| paused | #5B6B88 | #9AA9C9 |

Contrast [computed]: light ink/paper 11.99, muted 6.33, outline = ink 11.99, onAction 13.33, onEvidence/fill 8.49, evidence mark 5.74, wrong/paper 5.52 (on soft 4.79), hint 6.94, paused 4.84; white on wrong 6.13. Dark: ink 15.45, muted 9.11, outline 6.13 (card 5.21), action 11.22, correct 9.68, wrong 7.34, hint 9.55, paused 7.47.
CVD [computed]: correct vs wrong luminance ratio 2.17 (light) and dE protan 82.5 / deutan 100.3 / tritan 99.3: the most robust, because correct is dark ink-blue and wrong is a mid vermilion.
Trade-offs: + smallest palette (cheapest to keep consistent), strongest identity per screen, best CVD, prints well for marketing. - "correct" shares the ink colour, so correct must be shown as a filled stamp with check, never as plain text; hard-edged brutalist look is itself trending and can look like another template if the stamp device is not used consistently; heavy Han Black needs the bundled subset (8.9 MB full file) or falls back to system bold; dark mode swaps action to yellow, which changes button personality.

### Recommendation
Start with Lab Tag light as the default skin (it clears every AA/3:1 threshold with margin and needs only Latin font files), and adopt Night Desk's dark scheme idea as the dark variant only if Lab Tag dark (already computed above) feels cold. Keep Cyanotype as the choice if the team wants the boldest brand and accepts the Han bold subset. Whichever is picked, ship the same device (tag/stamp/lamp) in four places: verdict, "Show evidence", cast name tag, loading.

## 6. Q5 Accessibility checklist for our screens

Touch and targets
- [ ] Every interactive element at least 48dp x 48dp (Android guidance); use `minimumInteractiveComponentSize()` when the drawn size is smaller (reserves 48dp layout space, centred). Place `minimumInteractiveComponentSize` before size modifiers (page).
- [ ] No two touch targets overlap; do not rely on Compose's automatic touch-area expansion in dense rows (page advises larger min size).
- [ ] WCAG 2.5.8 AA minimum is 24x24 CSS px (our 48dp is stricter); 2.5.5 AAA is 44px.
- [ ] Bins, cards, step rows: 48dp min height, 8dp+ separation.

Gesture-only actions (swipe cards, drag into bins, reorder steps)
- [ ] Every swipe has a single-pointer alternative (WCAG 2.5.1 names swiping; dragging is 2.5.7): visible buttons (Keep / Discard, 对/错) next to the card.
- [ ] Drag-to-bin: tap-to-select then tap-bin; reorder: up/down buttons or select-then-tap position.
- [ ] Expose actions via `semantics { customActions = listOf(CustomAccessibilityAction("Sort into Evidence bin", { ...; true })) }`; move per-item buttons into the actions menu and `clearAndSetSemantics` on duplicates (pattern from Android docs, written for swipe-to-dismiss).
- [ ] `onClickLabel` on clickables so TalkBack says "double tap to open evidence" not "activate".

TalkBack / semantics
- [ ] Every icon-only control has a localised `contentDescription` (stringResource, EN and ZH); decorative icons `null`; purpose not "button" (role carries it).
- [ ] Descriptions unique within a list (e.g. include card title or exhibit number).
- [ ] Group each card's text with `semantics(mergeDescendants = true)`; make only the parent clickable.
- [ ] Headings marked `semantics { heading() }`; the verdict result uses `liveRegion = Polite` (not for timers); modals/sheets get a unique `paneTitle`.
- [ ] Right/wrong state announced in text (`stateDescription` or text: "Correct" / "Wrong"), not only colour or icon.
- [ ] "Show evidence" reveal announces the source passage and moves focus to it; a decorative redaction/stamp animation is `hideFromAccessibility`.
- [ ] Cast character: announce "Name, role" (e.g. "Rookie, says: ...") since colour/avatar carry no meaning for TalkBack.
- [ ] Set the content language on Chinese blocks so TalkBack reads Han with the Chinese voice [unverified API: `LocaleList` / `SpanStyle(localeList)` in AnnotatedString; verify].

Text scaling (WCAG 1.4.4: 200%, without loss of content or function)
- [ ] All text in sp; line heights in sp; no fixed heights/widths around text; cards grow, content scrolls; no `maxLines` on instructions without an expand path.
- [ ] Test at system font scale 1.0, 1.3, 2.0 (Android scales non-linearly, per Android page) and with display size large; check ZH and EN, longest strings, bottom bars, bin labels, chips.
- [ ] At 200% the primary action must remain visible without horizontal scroll; stack rows into columns at large scale.
- [ ] Han lines at 2.0 scale wrap with start-of-line punctuation checked.
- [ ] Support WCAG text-spacing overrides without clipping (1.4.12).
- [ ] How to set 2.0 in tests: `adb shell settings put system font_scale 2.0`, Compose preview `fontScale` [unverified].

Colour and contrast
- [ ] Text 4.5:1 (Android allows 3:1 for 18sp+/14sp bold; we hold 4.5 for Han readability) on every state, including pressed and selected.
- [ ] UI parts and icons carrying meaning 3:1; focus indicator 3:1; field/card borders 3:1.
- [ ] Right/wrong/evidence/hint/paused each have icon + word + shape; check screenshots in greyscale and a protan/deutan simulator.
- [ ] Cast roles identified by silhouette + name, not tint.
- [ ] Both light and dark pass; confirm in each skin with the numbers above.

Motion (WCAG 2.3.3 AAA: animation from interaction can be turned off unless essential)
- [ ] Stamp thump, card fling, evidence peel: honour the OS "Remove animations" setting (animator duration scale 0) and provide an in-app "Reduce motion" toggle; essential state change shown instantly [OS setting name and how Compose reads it: unverified; the animation intro page had nothing on it].
- [ ] No parallax, no auto-advancing content; any auto-timed element is pausable (the "paused" role) [WCAG 2.2.2 not read].
- [ ] Avoid flashing; stamps do not blink.

Testing
- [ ] Turn on TalkBack, Switch Access, Voice Access and play every mechanic with no touch drag.
- [ ] Automated: `ui-test-junit4-accessibility` + `enableAccessibilityChecks()` (checks labels, contrast, target size, traversal order; Compose 1.8+; project BoM 2026.09.00).
- [ ] Accessibility Scanner on emulator and one low-end device; Layout Inspector semantics tree (merged and unmerged).
- [ ] Screenshot tests (Roborazzi is already in the project) at fontScale 1.0 and 2.0, light/dark, EN/ZH.

Sources: https://developer.android.com/develop/ui/compose/accessibility/api-defaults ; https://developer.android.com/develop/ui/compose/accessibility/semantics ; https://developer.android.com/guide/topics/ui/accessibility/principles ; https://developer.android.com/guide/topics/ui/accessibility/apps ; https://developer.android.com/develop/ui/compose/accessibility/testing ; https://developer.android.com/develop/ui/compose/accessibility/scalable-content ; https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html ; https://www.w3.org/WAI/WCAG22/Understanding/pointer-gestures.html ; https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html ; https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html ; https://www.w3.org/WAI/WCAG22/Understanding/text-spacing.html

## 7. Open questions / could not verify

- m3.material.io pages (color system, key colours and tones, typography, accessible design, color-contrast, dynamic colour) returned only titles; the tone-difference contrast rule and the M3 Expressive details are therefore unverified. Android's M3 page was read instead.
- developer.android.com/guide/topics/ui/look-and-feel/text-scaling and compose/accessibility/key-types returned 404. Max system font scale on Android (200% in recent versions?), and `font_scale` adb setting: unverified.
- fonts.google.com/noto page returned title only. Noto Sans SC licence confirmed through the repo LICENSE and Google Fonts METADATA, not the specimen page; Noto Serif SC licence from METADATA only (the repo README did not state it).
- Whether Android falls back to a system CJK font cleanly when a bundled Latin variable font lacks Han glyphs, per OEM (Xiaomi, Huawei, Oppo), and which regional glyph variant is chosen: unverified; needs on-device test.
- Whether downloadable Google Fonts work for Han and on GMS-less devices: only the provider package name (Play services) was read; the GMS-less claim is inference.
- Size of a subsetted Noto Sans SC Bold (GB2312 level 1, ~3.7k chars) and of the variable TTF: not measured. Do it with pyftsubset before choosing.
- OFL subsetting/renaming rules, and LXGW's additional-permission clause text: not read.
- Whether Compose `TextAlign.Justify` compresses CJK punctuation, `LineBreak` strictness presets, `LineHeightStyle` trimming with mixed scripts: not read; test.
- CVD matrices (Machado 2009) were typed from memory; dE values are indicative. A tool such as Android Studio's colour-blind overlay or Sim Daltonism should confirm.
- Cultural colour meaning for Chinese users (red = good/up, green connotations) is general knowledge, not from a source; run a quick check with 3-5 Chinese-reading testers.
- clreq excerpts were obtained through chunked summaries (parts of the 260k-character page were skimmed: sections 2.1, 5.x, 6.x, 7.1). Section numbers are as reported by the fetch tool; re-check before quoting.
- I did not survey existing games' trade dress; verify the stamp/tag/redaction language against any specific title before shipping.
- Anthropic's frontend-design guidance (cream #F4F1EA + high-contrast serif as the common AI tell) was taken from the brief, not fetched.
- The Kotlin recommendations (full `ColorScheme`, extended roles) are design proposals; none was compiled.
