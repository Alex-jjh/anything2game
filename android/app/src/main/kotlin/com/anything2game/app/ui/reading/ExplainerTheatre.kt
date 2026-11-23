package com.anything2game.app.ui.reading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anything2game.app.R
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.SourceRef
import com.anything2game.contract.tutor.SPEAKER_PLAYER
import com.anything2game.contract.tutor.TutorCheck
import com.anything2game.contract.tutor.TutorIntent
import com.anything2game.contract.tutor.TutorMessage
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.TutorSession

/** Message cap of a session (kept from the chat screen: the server and the local example both stop here). */
private const val MESSAGE_LIMIT = 80

/** One screenful of dialogue: a page of the player's question or of the explainer's answer. */
private data class Beat(
    val player: Boolean, val text: String, val refs: List<SourceRef>,
    /** Concept name (dialogue-box title) and the personalised lead-in, for a scripted turn; the lead-in only on its first page. */
    val title: String? = null, val leadIn: String? = null,
    /** Where in the document this line is (the latest section named so far), and a quick check asked on this page. */
    val section: String? = null, val check: TutorCheck? = null,
)

/**
 * Phase 3 as a scene (visual-novel layout): the explainer on stage at the top with the player's own character in the
 * corner, one page of dialogue in a box below, and the choice bar docked at the bottom once the last page is read.
 * Tap the stage or the box to turn the page. Everything that used to be a chat bubble is still in the Log sheet.
 */
@Composable
internal fun ExplainerTheatre(
    session: TutorSession, theme: ExplainerTheme, busy: Boolean, enabled: Boolean, offline: Boolean, problem: String?,
    lastIntent: TutorIntent?, input: String, onInput: (String) -> Unit,
    onTurn: (TutorIntent, String) -> Unit, onSource: (List<SourceRef>) -> Unit,
    onRetry: () -> Unit, onReload: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = A2g.skin
    val explainer = stringResource(theme.personaNameRes)
    val player = LocalAvatar.current
    val playerName = stringResource(player.nameRes)

    val latest = session.messages.lastOrNull { it.role != "user" } ?: session.messages.last()
    val latestIndex = session.messages.indexOf(latest)
    // The player's own character speaks for the player: their typed question, or what a button press asks for.
    val question = session.messages.getOrNull(latestIndex - 1)?.takeIf { it.role == "user" }
    val playerLine = when (lastIntent) {
        TutorIntent.QUESTION -> question?.text
        TutorIntent.REPHRASE, TutorIntent.EXAMPLE -> sidekickLine(lastIntent, session.language == "zh")
        else -> null
    }
    // Pages fill what the box holds: larger system text means fewer characters per page, never clipped text.
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val latinBudget = (DialoguePaging.LATIN_BUDGET / scale).toInt()
    val cjkBudget = (DialoguePaging.CJK_BUDGET / scale).toInt()
    // A scripted turn is "concept, lead-in, explanation": the first two become the box's title lines, the pages are the explanation.
    val parts = remember(latest.id) { TutorRules.splitScripted(latest.text, session.questions.map { it.concept }) }
    val beats = remember(latest.id, playerLine, scale) {
        buildList {
            playerLine?.let { line -> DialoguePaging.pages(line, latinBudget, cjkBudget).forEach { add(Beat(true, it, emptyList())) } }
            if (parts == null) DialoguePaging.pages(latest.text, latinBudget, cjkBudget).forEach { add(Beat(false, it, latest.sourceRefs)) }
            else if (latest.beats.isNotEmpty()) {
                // A scene: one line per tap, each with its own speaker, place in the document and passage.
                val share = DialoguePaging.titledShare(scale)
                var section: String? = null
                latest.beats.forEach { b ->
                    section = b.section ?: section
                    val player = b.speaker == SPEAKER_PLAYER
                    val pages = DialoguePaging.pages(b.text, (latinBudget * share).toInt(), (cjkBudget * share).toInt())
                    pages.forEachIndexed { i, text ->
                        add(Beat(player, text, if (player) emptyList() else b.sourceRefs.ifEmpty { latest.sourceRefs }, parts.title,
                            if (isEmpty()) parts.leadIn else null, section, if (i == pages.lastIndex) b.check else null))
                    }
                }
            }
            else DialoguePaging.pages(parts, latinBudget, cjkBudget, DialoguePaging.titledShare(scale))
                .forEachIndexed { i, text -> add(Beat(false, text, latest.sourceRefs, parts.title, if (i == 0) parts.leadIn else null)) }
        }
    }
    // Answers to the quick checks of this reply, by page (kept across rotation; a new reply starts fresh).
    var checked by rememberSaveable(latest.id) { mutableStateOf(emptyMap<Int, String>()) }
    var page by rememberSaveable(latest.id) { mutableIntStateOf(0) }
    val index = page.coerceIn(0, beats.lastIndex)
    val beat = beats[index]
    val last = index >= beats.lastIndex
    val atLastConcept = session.conceptPosition >= session.questions.lastIndex
    val full = session.messages.size >= MESSAGE_LIMIT
    val explaining = lastIntent == null || lastIntent == TutorIntent.START || lastIntent == TutorIntent.CONTINUE

    val expression = when {
        busy -> ExplainerExpression.THINKING
        lastIntent == TutorIntent.EXAMPLE -> ExplainerExpression.EXAMPLE
        lastIntent == TutorIntent.REPHRASE -> ExplainerExpression.REPHRASE
        lastIntent == TutorIntent.QUESTION -> ExplainerExpression.ANSWER
        last && atLastConcept -> ExplainerExpression.DONE
        else -> ExplainerExpression.EXPLAINING
    }
    // The player's sidekick: listens, wonders while a reply is awaited, jumps at an example, cheers when a concept is told.
    val answered = beat.check?.let { k -> checked[index]?.let { it == k.correctOptionId } }
    val playerMood = when {
        busy -> Mood.THINKING
        answered == true -> Mood.HAPPY
        answered == false -> Mood.SURPRISED
        lastIntent == TutorIntent.EXAMPLE -> Mood.SURPRISED
        last && explaining -> Mood.HAPPY
        else -> Mood.IDLE
    }

    var askOpen by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    // The end of the walk is a screen of its own: what was covered, where each idea stood, and the pages to go back to.
    var wrapOpen by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = wrapOpen) { wrapOpen = false }
    val advance = { if (!last && !busy) page = index + 1 }
    // A tap turns the page. Not `clickable`: that would merge the box's texts and chips into one accessibility node.
    val nextLabel = stringResource(R.string.explainer_tap_next)
    val tap = Modifier.pointerInput(index, last, busy) { detectTapGestures { advance() } }.semantics { onClick(label = nextLabel) { advance(); true } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Reserve more of the screen for reading, including when the choice bar is visible.
        // Cap the decorative stage; larger system text gives the dialogue still more room.
        val stageHeight = ((maxHeight - 48.dp) * (if (scale >= 1.3f) 0.18f else 0.28f)).coerceAtMost(240.dp)
        val topBar = 48.dp
        val stageModifier = Modifier.then(tap).testTag("explainer_stage")
        val stage: @Composable (Modifier) -> Unit = { m ->
            Stage(theme, expression, player, playerMood, session, explainer, stageModifier.then(m))
        }
        val dialogue: @Composable (Modifier) -> Unit = { m ->
            DialogueBox(
                beat, index, beats.size, last, busy, if (beat.player) playerName else explainer, atLastConcept && last && !beat.player,
                intent = lastIntent, onNext = advance, onSkip = { page = beats.lastIndex }, onSource = onSource, session = session, tap = tap.then(m),
                chosen = checked[index], onCheck = { option -> checked = checked + (index to option) },
            )
        }
        val bottom: @Composable () -> Unit = {
            Notices(offline, problem, busy, onRetry, onReload)
            ChoiceBar(
                visible = last || busy, busy = busy, enabled = enabled && !full, atLastConcept = atLastConcept, full = full,
                onTurn = { intent -> onTurn(intent, "") }, onAsk = { askOpen = true }, onCancel = onCancel, onFinish = { wrapOpen = true },
            )
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack, onLog = { logOpen = true }, height = topBar) {
                Progress(session.conceptPosition, session.questions.size, (index + 1f) / beats.size, finished = atLastConcept && last, Modifier.weight(1f))
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    stage(Modifier.weight(0.32f).fillMaxHeight())
                    Column(Modifier.weight(0.68f).fillMaxHeight().padding(end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dialogue(Modifier.weight(1f).fillMaxWidth())
                        bottom()
                    }
                }
            } else {
                stage(Modifier.fillMaxWidth().height(stageHeight))
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialogue(Modifier.weight(1f).fillMaxWidth())
                    bottom()
                }
            }
        }
        if (wrapOpen) WrapUp(session, theme, explainer, onSource, onShelf = onBack, onStay = { wrapOpen = false }, Modifier.fillMaxSize())
    }

    if (askOpen) AskSheet(
        name = explainer, session = session, input = input, onInput = onInput,
        canSend = enabled && !full && input.isNotBlank(),
        onSend = { onTurn(TutorIntent.QUESTION, input); onInput(""); askOpen = false },
        onDismiss = { askOpen = false },
    )
    if (logOpen) LogSheet(session, explainer, playerName, onSource = { refs -> onSource(refs) }, onDismiss = { logOpen = false })
}

@Composable
private fun TopBar(onBack: () -> Unit, onLog: () -> Unit, height: androidx.compose.ui.unit.Dp, middle: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        middle()
        TextButton(onClick = onLog, modifier = Modifier.testTag("explainer_log")) { Text(stringResource(R.string.explainer_log)) }
    }
}

@Composable
private fun Stage(
    theme: ExplainerTheme, expression: ExplainerExpression, player: com.anything2game.app.ui.character.AvatarCharacter, playerMood: Mood,
    session: TutorSession, explainerName: String, modifier: Modifier,
) {
    val skin = A2g.skin
    val description = stringResource(R.string.explainer_stage_desc, explainerName)
    Box(modifier.semantics { contentDescription = description }) {
        theme.Backdrop(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth * 0.92f, maxHeight * 0.88f)
            Box(Modifier.align(Alignment.BottomCenter).size(side)) { theme.Standee(expression, Modifier.fillMaxSize()) }
        }
        Badge(
            stringResource(when (session.mode) { "local-mock" -> R.string.badge_pre_generated; "mock" -> R.string.tutor_server_mock_badge; else -> R.string.tutor_model_badge }),
            skin.evidence, skin.evidenceSoft, Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 280.dp),
        )
        // The player's own character, small in the corner; poking it makes it react (it must not turn the page).
        player.Draw(playerMood, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp).size(64.dp).testTag("player_avatar"), pokeable = true)
    }
}

@Composable
private fun DialogueBox(
    beat: Beat, index: Int, total: Int, last: Boolean, busy: Boolean, speaker: String, closing: Boolean,
    intent: TutorIntent?, onNext: () -> Unit, onSkip: () -> Unit, onSource: (List<SourceRef>) -> Unit, session: TutorSession, tap: Modifier,
    chosen: String? = null, onCheck: (String) -> Unit = {},
) {
    val skin = A2g.skin
    val reduced = rememberReducedMotion()
    val accent = if (beat.player) skin.evidence else skin.action
    val fade = remember { Animatable(1f) }
    LaunchedEffect(index, beat.text) {
        if (reduced) fade.snapTo(1f) else { fade.snapTo(0f); fade.animateTo(1f, tween(180)) }
    }
    Card(
        Modifier.then(tap).testTag("dialogue_box"),
        colors = CardDefaults.cardColors(containerColor = skin.card),
        border = BorderStroke(1.5.dp, accent),
        elevation = CardDefaults.cardElevation(3.dp),
    ) {
        BoxWithConstraints {
            // On short screens, keep the concept and body prominent; the full lead-in remains in Log.
            val compact = maxHeight < 320.dp
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(speaker, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("dialogue_speaker"))
                    // The label names the exchange, so it also sits on the sidekick's request ("Can you give me an example?").
                    val mode = if (busy || (beat.player && intent == TutorIntent.QUESTION)) null else when (intent) {
                        TutorIntent.EXAMPLE -> R.string.explainer_mode_example
                        TutorIntent.REPHRASE -> R.string.explainer_mode_rephrase
                        TutorIntent.QUESTION -> R.string.explainer_mode_answer
                        else -> null
                    }
                    if (mode != null) Text(stringResource(mode), style = MaterialTheme.typography.labelMedium, color = skin.action,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("dialogue_mode").semantics { liveRegion = LiveRegionMode.Polite })
                    else Spacer(Modifier.weight(1f))
                }
                // Where in the document this line is, as the document names it ("3.2 Attention", "Table 2").
                if (!busy && beat.section != null) Text("§ " + beat.section, style = MaterialTheme.typography.labelMedium, color = skin.evidence, modifier = Modifier.testTag("dialogue_section"))
                if (!busy && beat.title != null) Text(beat.title, style = MaterialTheme.typography.titleMedium, color = skin.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("dialogue_title"))
                if (!busy && !compact && beat.leadIn != null) Text(beat.leadIn, style = MaterialTheme.typography.bodySmall, color = skin.inkMuted, modifier = Modifier.testTag("dialogue_lead_in"))
                Box(Modifier.weight(1f, fill = true).verticalScroll(rememberScrollState())) {
                    if (busy) TypingIndicator(speaker, intent)
                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.graphicsLayer { alpha = fade.value }) {
                        Text(beat.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 28.sp), color = skin.ink, modifier = Modifier.testTag("dialogue_text"))
                        beat.check?.let { QuickCheck(it, beat.text, chosen, onCheck) }
                        if (closing) Text(stringResource(R.string.tutor_all_introduced), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
                    }
                }
                if (!busy && !compact && beat.refs.isNotEmpty()) SourceButton(session, beat.refs, false, onSource)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!busy && compact && beat.refs.isNotEmpty()) SourceButton(session, beat.refs, true, onSource)
                    PageDots(index, total, Modifier.weight(1f).testTag("dialogue_page"))
                    if (index < total - 2 && !busy) TextButton(onClick = onSkip, modifier = Modifier.testTag("dialogue_skip")) { Text(stringResource(R.string.explainer_last_page), color = skin.inkMuted) }
                    if (!last && !busy) TextButton(onClick = onNext, modifier = Modifier.testTag("dialogue_next")) {
                        Text(stringResource(R.string.explainer_tap_next) + "  ▸", color = skin.action, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceButton(session: TutorSession, refs: List<SourceRef>, compact: Boolean, onSource: (List<SourceRef>) -> Unit) {
    val skin = A2g.skin
    val description = sourceLabel(session, refs)
    AssistChip(
        onClick = { onSource(refs) },
        label = { Text(if (compact) stringResource(R.string.explainer_source_short) else description) },
        leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(labelColor = skin.evidence, leadingIconContentColor = skin.evidence),
        border = BorderStroke(1.dp, skin.evidence),
        modifier = Modifier.testTag("message_source").then(if (compact) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

@Composable
private fun PageDots(index: Int, total: Int, modifier: Modifier) {
    val skin = A2g.skin
    val description = stringResource(R.string.explainer_page_of, index + 1, total)
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (total <= 8) repeat(total) { i -> Box(Modifier.size(8.dp).background(if (i <= index) skin.action else skin.outline.copy(alpha = 0.4f), CircleShape)) }
        else Text(description, style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
    }
}

@Composable
private fun TypingIndicator(who: String, intent: TutorIntent?) {
    val skin = A2g.skin
    val reduced = rememberReducedMotion()
    val label = if (intent == TutorIntent.EXAMPLE) stringResource(R.string.explainer_preparing_example) else stringResource(R.string.explainer_thinking, who)
    val phase = if (reduced) 0f else rememberInfiniteTransition(label = "typing").animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dots").value
    Row(Modifier.semantics { contentDescription = label }.testTag("typing_indicator"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            val lit = reduced || phase.toInt() == i
            Box(Modifier.size(10.dp).background(skin.action.copy(alpha = if (lit) 1f else 0.3f), CircleShape))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
    }
}

@Composable
private fun Notices(offline: Boolean, problem: String?, busy: Boolean, onRetry: () -> Unit, onReload: () -> Unit) {
    val skin = A2g.skin
    if (offline) Text(stringResource(R.string.tutor_offline), color = skin.hint, style = MaterialTheme.typography.bodySmall)
    if (problem != null) Card(colors = CardDefaults.cardColors(containerColor = skin.card), border = BorderStroke(1.dp, skin.wrong)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.tutor_error, problem), color = skin.wrong, style = MaterialTheme.typography.bodyMedium)
            Row { TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.home_retry)) }; TextButton(onClick = onReload, enabled = !busy) { Text(stringResource(R.string.tutor_reload)) } }
        }
    }
}

/** Docked at the bottom, in reach of the thumb: it appears when the last page of a message is on screen. */
@Composable
private fun ChoiceBar(
    visible: Boolean, busy: Boolean, enabled: Boolean, atLastConcept: Boolean, full: Boolean,
    onTurn: (TutorIntent) -> Unit, onAsk: () -> Unit, onCancel: () -> Unit, onFinish: () -> Unit,
) {
    val skin = A2g.skin
    AnimatedVisibility(visible) {
        if (busy) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tutor_cancel")) { Text(stringResource(R.string.tutor_cancel)) }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("choice_bar")) {
            if (full) Text(stringResource(R.string.tutor_limit), color = skin.hint, style = MaterialTheme.typography.bodySmall)
            val rephrase: @Composable (Modifier) -> Unit = { m -> ChoiceButton(stringResource(R.string.tutor_rephrase), m.testTag("tutor_rephrase"), enabled) { onTurn(TutorIntent.REPHRASE) } }
            val example: @Composable (Modifier) -> Unit = { m -> ChoiceButton(stringResource(R.string.tutor_example), m.testTag("tutor_example"), enabled) { onTurn(TutorIntent.EXAMPLE) } }
            val ask: @Composable (Modifier) -> Unit = { m -> ChoiceButton(stringResource(R.string.explainer_ask), m.testTag("tutor_ask"), enabled) { onAsk() } }
            val next: @Composable (Modifier) -> Unit = { m ->
                if (atLastConcept) ChoiceButton(stringResource(R.string.explainer_finish), m.testTag("tutor_finish"), true, primary = true) { onFinish() }
                else ChoiceButton(stringResource(R.string.tutor_continue), m.testTag("tutor_continue"), enabled, primary = true) { onTurn(TutorIntent.CONTINUE) }
            }
            BoxWithConstraints {
                val gap = Arrangement.spacedBy(6.dp)
                // One row keeps the dialogue box tall; only very large system text needs two rows to stay readable.
                val oneRow = maxWidth >= 300.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.3f
                if (oneRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
                    rephrase(Modifier.weight(1f)); example(Modifier.weight(1f)); ask(Modifier.weight(0.8f)); next(Modifier.weight(1.25f))
                } else Column(verticalArrangement = gap) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) { rephrase(Modifier.weight(1f)); example(Modifier.weight(1f)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) { ask(Modifier.weight(1f)); next(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(label: String, modifier: Modifier, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val m = modifier.heightIn(min = 48.dp)
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    val text: @Composable () -> Unit = { Text(label, maxLines = 2, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) { text() }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) { text() }
}

@Composable
private fun sourceLabel(session: TutorSession, refs: List<SourceRef>): String {
    val pages = refs.mapNotNull { r -> session.sources.firstOrNull { it.chunkId == r.chunkId }?.let { it.pageLabel ?: (it.pageIndex + 1).toString() } }.distinct()
    return if (pages.isEmpty()) stringResource(R.string.reading_source) else stringResource(R.string.explainer_show_source, pages.joinToString(", "))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(name: String, session: TutorSession, input: String, onInput: (String) -> Unit, canSend: Boolean, onSend: () -> Unit, onDismiss: () -> Unit) {
    val skin = A2g.skin
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = skin.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding().testTag("ask_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.explainer_ask_title, name), style = MaterialTheme.typography.titleLarge, color = skin.ink)
            OutlinedTextField(
                value = input, onValueChange = { onInput(it.take(1000)) }, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("tutor_input"),
                label = { Text(stringResource(R.string.tutor_input)) }, minLines = 2, maxLines = 5,
            )
            Text(stringResource(if (session.mode == "model") R.string.tutor_model_note else R.string.tutor_script_note), color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.explainer_cancel)) }
                Button(onClick = onSend, enabled = canSend, modifier = Modifier.heightIn(min = 52.dp).testTag("tutor_send")) { Text(stringResource(R.string.tutor_send)) }
            }
        }
    }
}

/** Every earlier message, read-only: the chat is not gone, it is just not the interface any more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(session: TutorSession, explainer: String, player: String, onSource: (List<SourceRef>) -> Unit, onDismiss: () -> Unit) {
    val skin = A2g.skin
    val list = rememberLazyListState(initialFirstVisibleItemIndex = session.messages.size)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = skin.paper) {
        LazyColumn(Modifier.fillMaxWidth().testTag("explainer_log_list"), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.explainer_log_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                Text(session.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted)
                Text(stringResource(if (session.mode == "local-mock") R.string.tutor_scripted_note else R.string.tutor_coverage_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            }
            items(session.messages, key = { it.id }) { message -> LogMessage(message, session, explainer, player, onSource) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun LogMessage(message: TutorMessage, session: TutorSession, explainer: String, player: String, onSource: (List<SourceRef>) -> Unit) {
    val skin = A2g.skin
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(if (user) .9f else 1f).background(if (user) skin.evidenceSoft else skin.card, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (user) player else explainer, style = MaterialTheme.typography.labelMedium, color = skin.action)
            Text(message.text, color = skin.ink, style = MaterialTheme.typography.bodyLarge)
            if (message.sourceRefs.isNotEmpty()) TextButton(onClick = { onSource(message.sourceRefs) }, modifier = Modifier.testTag("message_source_log")) { Text(sourceLabel(session, message.sourceRefs)) }
        }
    }
}

/** The explainer on a small stage with one line: the compact header that opens the diagnostic and the starting-point screens. */
@Composable
internal fun ExplainerHeader(theme: ExplainerTheme, line: String, thinking: Boolean, modifier: Modifier = Modifier) {
    val skin = A2g.skin
    Row(modifier.fillMaxWidth().testTag("explainer_header"), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(84.dp)) { theme.Standee(if (thinking) ExplainerExpression.THINKING else ExplainerExpression.EXPLAINING, Modifier.fillMaxSize()) }
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = skin.card, border = BorderStroke(1.5.dp, skin.action), shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(theme.personaNameRes), style = MaterialTheme.typography.labelLarge, color = skin.action, fontWeight = FontWeight.Bold)
                Text(line, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), color = skin.ink)
            }
        }
    }
}

/** What the player's own character says when a button is pressed, in the course's language. */
private fun sidekickLine(intent: TutorIntent, zh: Boolean): String = when (intent) {
    TutorIntent.REPHRASE -> if (zh) "能换个说法再讲一遍吗？" else "Could you say that another way?"
    else -> if (zh) "可以举个例子吗？" else "Can you give me an example?"
}

/**
 * A quick check in the middle of a scene: the options, then one line on the answer. Answering is optional; the next page
 * is always one tap away, and the result is not scored.
 */
@Composable
private fun QuickCheck(check: TutorCheck, lead: String, chosen: String?, onCheck: (String) -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("quick_check")) {
        if (check.prompt.filterNot { it.isWhitespace() } !in lead.filterNot { it.isWhitespace() })
            Text(check.prompt, style = MaterialTheme.typography.titleSmall, color = skin.ink, fontWeight = FontWeight.SemiBold)
        check.options.forEach { o ->
            val picked = chosen == o.id
            val right = o.id == check.correctOptionId
            val border = when { chosen == null -> skin.outline; right -> skin.correct; picked -> skin.wrong; else -> skin.outline.copy(alpha = 0.4f) }
            OutlinedButton(
                onClick = { onCheck(o.id) }, enabled = chosen == null, shape = RoundedCornerShape(14.dp), border = BorderStroke(if (picked || (chosen != null && right)) 2.dp else 1.dp, border),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("check_${o.id}").semantics { selected = picked },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = if (picked) skin.evidenceSoft else skin.card, disabledContentColor = skin.ink, disabledContainerColor = if (picked) skin.evidenceSoft else skin.card),
            ) { Text(o.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium) }
        }
        if (chosen != null) {
            val ok = chosen == check.correctOptionId
            Text(stringResource(if (ok) R.string.check_right else R.string.check_wrong) + "  " + (if (ok) check.right else check.wrong),
                style = MaterialTheme.typography.bodyMedium, color = if (ok) skin.correct else skin.wrong,
                modifier = Modifier.testTag("check_feedback").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
