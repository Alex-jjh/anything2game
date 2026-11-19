# Demo courses — review before filming

**Scope**: the 5 pre-generated courses bundled in the app (`samples/demo/`, shown with the *Pre-generated sample* badge). They are real pipeline output (Claude Sonnet 5, 2026-11-18/19), picked as the best of several runs by the two-judge eval. Precision over recall: every unit below that a judge flagged must be checked by a person, and fixed or removed, before it appears in the video.

**Copyright**: the courses contain passages of third-party papers/slides. Fine for a private course demo; do **not** publish the APK or show long passages on screen. Sources: attention: https://arxiv.org/abs/1706.03762; resnet: https://arxiv.org/abs/1512.03385; mapreduce: https://research.google/pubs/mapreduce-simplified-data-processing-on-large-clusters/; cs231n-l1: http://cs231n.stanford.edu/ (2017 lecture 1 slides); zh-spaced: https://zh.wikipedia.org/wiki/间隔重复 (CC BY-SA 4.0).

## attention (from run `e2-cited`, 12 units)

No unit flagged. Spot-check 2 units and the Case boss.
- [ ] frame **case** low check {'boss_correct': 4, 'lines_faithful': 3, 'no_giveaway': 1, 'board_sensible': 2}: Partner line adds an unsupported permanence claim: "A paper claims it killed off recurrence and convolutions for good — using attention alone." The material says the Transformer dispenses with recurrence and convolutions in its own architecture; it does not claim they were "killed off ... for good."
- [ ] frame **companion** low check {'lines_faithful': 4, 'no_giveaway': 1, 'helpful': 4, 'character_consistent': 5}: L1U2 substantially reveals the requested construction order: "encoder layers, then a shared normalization trick, then what the decoder adds on top."

## resnet (from run `agRep`, 8 units)

No unit flagged. Spot-check 2 units and the Case boss.
- [ ] frame **case** low check {'boss_correct': 2, 'lines_faithful': 5, 'no_giveaway': 1, 'board_sensible': 2}: L2 boss statement st3 is not supported cleanly by its cited passage: "The 1202-layer ResNet trained with no optimization difficulty, reaching training error under 0.1%." The cited c37 first introduces a 1202-layer network, but then says "this 103-layer network is able to achieve training error <0.1%." Strictly on the supplied text, the citation does not directly establish the stated 1202-layer result. This leaves an additional unsupported non-flawed statement, violating the requirement that exactly one statement be flawed and all others supported.
- [ ] frame **escape** low check {'boss_correct': 5, 'lines_faithful': 4, 'no_giveaway': 1, 'rooms_sensible': 5}: L1 room r2 gives away core unit conclusions by displaying “two crossed-out culprits — OVERFITTING and VANISHING GRADIENTS,” directly signaling the answers for L1U2.
- [ ] frame **companion** low check {'lines_faithful': 4, 'no_giveaway': 2, 'helpful': 5, 'character_consistent': 5}: L1U2 gives away a unit answer by stating: "If it's not overfitting"; the unit explicitly asks whether degradation is caused by overfitting.

## mapreduce (from run `e2-cited`, 7 units)

- [ ] **L3U2** (sort) wrong parts -: The passage lists MapReduce applications but does not divide them into “core computing techniques” versus “web & query data mining.” The bin taxonomy and the claimed basis for sorting are unsupported by the cited text.; k3 is ambiguous: “clustering problems for the Google News and Froogle products” could reasonably be considered a machine-learning/computing technique as well as a product-specific data-mining use. The passage does not resolve this choice.
- [ ] **L3U3** (deduce) wrong parts -: Primary option o2 overstates the cited evidence by saying the job will finish “close to normal time.” The passages say backup tasks significantly reduce completion time lost to stragglers, but do not establish that completion will be close to normal.; Primary feedback for o1 says a large delay happens “only when backups are disabled.” The source gives an example of a 44% increase with backups disabled, but does not support this universal claim.
- [ ] frame **escape** low check {'boss_correct': 5, 'lines_faithful': 3, 'no_giveaway': 1, 'rooms_sensible': 5}: The L1 boss is a direct repeat of L1U2 rather than a new deduction: "Map outputs (URL, 1) for each log entry; Reduce adds together all the values for that URL and emits a total count pair." L1U2 already states that URL-access-frequency reduce "adds together all counts for the same URL."
- [ ] frame **companion** low check {'lines_faithful': 4, 'no_giveaway': 2, 'helpful': 5, 'character_consistent': 5}: L1U4 includes an uncited factual lead-in: "Now design word-count yourself: one pair per word from map, one total per word from reduce." It is supported by the material, but has no source reference despite the cited-lead-in requirement.

## cs231n-l1 (from run `e2-cited`, 8 units)

- [ ] **L1U4** (swipe) wrong parts ['vsw1']: Variant card vsw1 is marked supported, but the cited passage says CNNs are "an important tool," not that they are "essential." Essential is a stronger claim not supported by the citation.; The unit mostly tests wording-level distinctions ('important' versus 'only' or 'essential') rather than the level goal of tracing key breakthroughs and ideas in CNN history.
- [ ] frame **case** low check {'boss_correct': 5, 'lines_faithful': 3, 'no_giveaway': 3, 'board_sensible': 2}: L1 partner intro adds an unsupported evaluative claim: "a face detector that changed everything." The material identifies "Face Detection, Viola & Jones, 2001" but does not say it changed everything.
- [ ] frame **escape** low check {'boss_correct': 2, 'lines_faithful': 3, 'no_giveaway': 1, 'rooms_sensible': 5}: L1 lock ordering is not fully supported or unambiguous: "Convolutional Neural Networks become an important tool for object recognition." has no date in its cited source (c30), while the explanation claims, "Each milestone carries its own year or decade." The cited CNN statement does not carry a year or decade.
- [ ] frame **rookie** low check {'boss_correct': 4, 'lines_faithful': 3, 'no_giveaway': 2, 'character_consistent': 5}: Several lead-ins do not ask a question, violating the requirement that lead-ins ask: "I filed 'Tools' under 'Animals' in my notes... that doesn't sound right now that I say it out loud."
- [ ] frame **companion** low check {'lines_faithful': 3, 'no_giveaway': 2, 'helpful': 4, 'character_consistent': 5}: L1U1: "Three big moments, three different decades." The sole citation, c9 ("Hubel & Wiesel, 1959"), supports only one event and does not support the claim about all three milestones occurring in different decades.

## zh-spaced (from run `armAG-classic-gate`, 4 units)

No unit flagged. Spot-check 2 units and the Case boss.
- [ ] frame **case** low check {'boss_correct': 5, 'lines_faithful': 4, 'no_giveaway': 2, 'board_sensible': 3}: 结尾台词“间隔重复确实对记忆大量知识有效”将原文“特别适合……需要牢固记忆大量知识的情形”表述为确定的效果结论，略有强化。
