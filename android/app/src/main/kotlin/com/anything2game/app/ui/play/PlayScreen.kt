package com.anything2game.app.ui.play

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.character.AvatarSignals
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.LocalAvatarSignals
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.play.mechanics.MechanicRenderers
import com.anything2game.app.ui.play.mechanics.RenderContext
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.castColor
import com.anything2game.contract.CastRole
import com.anything2game.contract.Mechanic
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Overlay
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind
import com.anything2game.contract.FrameSpec
import com.anything2game.app.data.source.CourseRef
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceText
import com.anything2game.app.ui.source.OpenSourceViewer

/** Stateless play screen. All input becomes an engine [Action]; nothing here decides game flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayContent(
    state: PlayUiState.Ready,
    onAction: (Action) -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    simulator: (@Composable () -> Unit)? = null,
    /** Where evidence is opened from: the course's own PDF when the phone has it (the real page), its text when not. */
    sources: com.anything2game.app.data.source.SourceLibrary = com.anything2game.app.data.source.TextOnlySources,
) {
    val skin = A2g.skin
    val session = state.session
    val level = state.pkg.level(session.levelId)
    var showSimulator by rememberSaveable { mutableStateOf(false) }
    var showBoard by rememberSaveable { mutableStateOf(false) }
    val board = state.overlay?.board

    BackHandler { if (showBoard) showBoard = false else if (session.overlay != null) onAction(Action.CloseOverlay) else onBack() }

    // The player's character: renderers write live input into fresh signals for each slot; the mood is derived here.
    val signals = androidx.compose.runtime.remember(session.position, session.phase::class) { AvatarSignals() }
    val avatar = LocalAvatar.current
    val mood = avatarMood(state, signals)

    androidx.compose.runtime.CompositionLocalProvider(LocalAvatarSignals provides signals) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = skin.paper,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = skin.paper),
                title = { Text(level?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (board != null) IconButton(onClick = { showBoard = true }) { Icon(Icons.Filled.Hub, stringResource(R.string.board_open), tint = skin.evidence) }
                    if (!session.isFinished) {
                        TextButton(onClick = { onAction(Action.OpenAsk) }) {
                            Icon(Icons.Filled.PanTool, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.action_ask))
                        }
                    }
                    if (simulator != null) IconButton(onClick = { showSimulator = !showSimulator }) { Icon(Icons.Filled.Sensors, stringResource(R.string.sim_title)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSimulator) simulator?.invoke()
            val recap = state.recap
            when {
                // Finished screens scroll as a whole.
                session.phase == Phase.Failed -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FailedContent(state.frameId, onReplay = onRestart, onLibrary = onBack)
                }
                recap != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RecapContent(recap, unitCount = level?.units?.size ?: 0, title = stringResource(recapTitle(state.frameId)), frameId = state.frameId, onReplay = onRestart, onLibrary = onBack,
                        hero = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { avatar.Draw(mood, Modifier.size(150.dp)) }
                            avatarLine(avatar, mood, session.position)?.let { SpeechBubble(avatar, it) }
                        }) {
                        if (board != null) EvidenceBoard(board, state.solvedUnitIds)
                    }
                }
                // Scenes: stage on top, dialogue docked at the bottom (visual-novel layout).
                state.scene != null -> {
                    ProgressHeader(state, onAction, avatar = null)
                    SceneStage(
                        key = "${session.position}",
                        lines = state.scene, cast = state.overlay?.cast.orEmpty(), room = state.room,
                        onEvidence = { onAction(Action.OpenEvidence(it)) }, onContinue = { onAction(Action.Next) },
                        player = { m -> avatar.Draw(Mood.IDLE, m, lookAt = androidx.compose.ui.geometry.Offset(0.9f, 0f), useTilt = true) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Challenges: each renderer owns its scroll and keeps its main action docked at the bottom.
                else -> {
                    ProgressHeader(state, onAction) { avatar.Draw(mood, Modifier.size(50.dp), useTilt = true) }
                    Box(Modifier.weight(1f)) { UnitBody(state, onAction, avatarSays = avatarLine(avatar, mood, session.position)?.let { { SpeechBubble(avatar, it) } }) }
                }
            }
        }
    }
    when (val overlay = session.overlay) {
        is Overlay.Evidence -> {
            val refs = state.item?.allSourceRefs.orEmpty()
            val pkg = state.pkg
            OpenSourceViewer(sources, SourceKey(pkg.documentId, pkg.documentVersion, CourseRef(pkg.packageId, pkg.contentVersion)), SourceText(pkg.title, pkg.sources),
                refs.filter { it.chunkId in overlay.chunkIds }.ifEmpty { refs },
                startPage = overlay.chunkIds.firstNotNullOfOrNull { pkg.chunk(it)?.pageIndex }, onDismiss = { onAction(Action.CloseOverlay) })
        }
        Overlay.Asking -> AskPanel(offline = !state.context.askAvailable, onClose = { onAction(Action.CloseOverlay) })
        null -> Unit
    }
    if (showBoard && board != null) BottomPanel(onDismiss = { showBoard = false }) {
        EvidenceBoard(board, state.solvedUnitIds)
        Button(onClick = { showBoard = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.evidence_close)) }
    }
    if (session.isPaused) PausedOverlay(onContinue = { onAction(Action.Resume) }) {
        avatar.Draw(Mood.SLEEPY, Modifier.size(150.dp))
        avatarLine(avatar, Mood.SLEEPY, session.position)?.let { SpeechBubble(avatar, it) }
    }
    }
    }
}

@Composable
private fun ProgressHeader(state: PlayUiState.Ready, onAction: (Action) -> Unit, avatar: (@Composable () -> Unit)?) {
    val skin = A2g.skin
    val s = state.session
    // Progress counts challenges only; scenes don't make the bar move.
    val answerable = s.queue.withIndex().filter { it.value.answerable }
    val done = answerable.count { it.index < s.position || (it.index == s.position && s.phase is Phase.Feedback) }
    val current = (answerable.indexOfFirst { it.index >= s.position } + 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { done.toFloat() / answerable.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
            color = skin.action,
            trackColor = skin.outline,
            drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            avatar?.invoke()
            val boss = s.currentSlot?.kind == SlotKind.BOSS
            val bossText = bossLabel(state.frameId)
            when {
                boss && bossText != null -> Badge(stringResource(bossText), skin.onAction, skin.wrong)
                s.currentSlot?.kind == SlotKind.TEACH_BACK -> Badge(
                    stringResource(R.string.badge_teach_back, rookieOf(state)?.name ?: stringResource(R.string.rookie_default_name)),
                    skin.onAction, skin.castColor(CastRole.ROOKIE),
                )
                s.currentSlot?.kind == SlotKind.ROOM_CHECK -> Badge(stringResource(R.string.badge_room_lock), skin.onAction, skin.evidence)
                state.unit != null -> Badge(stringResource(mechanicLabel(state.unit.mechanic)), skin.action, skin.card)
            }
            if (state.context.oneHanded) Badge(stringResource(R.string.one_handed_badge), skin.paused, skin.card)
            Spacer(Modifier.weight(1f))
            val hintsLeft = s.phase == Phase.Presenting && s.hintsShown < (state.unit?.hints?.size ?: 0)
            if (hintsLeft) {
                // Icon-only to leave room for the character; the label stays as the accessibility name.
                IconButton(onClick = { onAction(Action.RevealHint) }) {
                    Icon(Icons.Filled.Lightbulb, stringResource(R.string.action_hint), tint = skin.hint)
                }
            }
            s.credibility?.let { CredibilityPips(it, FrameSpec.of(state.frameId).credibility ?: it) }
            Text(stringResource(R.string.play_progress, current, answerable.size), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun UnitBody(state: PlayUiState.Ready, onAction: (Action) -> Unit, avatarSays: (@Composable () -> Unit)? = null) {
    val skin = A2g.skin
    val item = state.item ?: return
    // Derived slots (teach-back, room lock) have no unit of their own: the item alone says which renderer draws them.
    val hints = state.unit?.hints.orEmpty()
    val renderer = MechanicRenderers.of(item.mechanic)
    if (renderer == null) {
        Text(stringResource(R.string.unsupported_mechanic), color = skin.wrong)
        return
    }
    val s = state.session
    val ctx = RenderContext(
        oneHanded = state.context.oneHanded, chunks = state.pkg.sources, cast = state.overlay?.cast.orEmpty(),
        evidenceChunks = evidenceFor(state),
        scenarioSpeaker = if (s.currentSlot?.kind == SlotKind.TEACH_BACK) rookieOf(state) else null,
    )
    when (val phase = s.phase) {
        Phase.Presenting -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hints.take(s.hintsShown).forEachIndexed { i, hint ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = skin.hintSoft)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.hint_label, i + 1), style = MaterialTheme.typography.labelMedium, color = skin.hint, fontWeight = FontWeight.SemiBold)
                        Text(hint, color = skin.ink, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val slot = s.currentSlot
            Box(Modifier.weight(1f)) {
                renderer.Question(key = "${s.position}:${slot?.unitId}:${slot?.variant}", item = item, ctx = ctx, onSubmit = { onAction(Action.Submit(it)) })
            }
        }
        is Phase.Feedback -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                avatarSays?.invoke()
                ResultStamp(phase, state.retryQueued, isBoss = s.currentSlot?.kind == SlotKind.BOSS)
                renderer.Review(item, phase.answer, phase.grade, ctx)
                Spacer(Modifier.size(4.dp))
            }
            val chunkIds = item.allSourceRefs.map { it.chunkId }.distinct()
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onAction(Action.OpenEvidence(chunkIds)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    border = BorderStroke(1.5.dp, skin.evidence),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.evidence),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Icon(Icons.Filled.Description, null, Modifier.size(20.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.action_show_evidence), maxLines = 1) }
                val last = s.position + 1 >= s.queue.size
                Button(onClick = { onAction(Action.Next) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(stringResource(if (last) R.string.action_finish else R.string.action_next))
                }
            }
        }
        Phase.Recap, Phase.Failed -> Unit
    }
}

/** The rookie of the frame's cast (Rookie frame), who speaks the teach-back misconception. */
private fun rookieOf(state: PlayUiState.Ready) = state.overlay?.cast?.firstOrNull { it.role == CastRole.ROOKIE }

/**
 * Evidence the player can present in a cross-exam: what this level's clues cite (what they "collected"),
 * plus the boss's own passages so a correct answer always exists. Ordered by page; far fewer than the whole paper.
 */
private fun evidenceFor(state: PlayUiState.Ready): List<com.anything2game.contract.SourceChunk> {
    val level = state.pkg.level(state.session.levelId) ?: return state.pkg.sources
    // "Evidence you collected": the lines this level's clues cited (the boss's own refs only fill gaps),
    // shown as the cited quote rather than the start of the chunk, so the picker reads like pinned notes.
    val unitRefs = level.units.flatMap { u -> (listOf(u.item) + u.variants).flatMap { it.allSourceRefs } }
    val bossRefs = state.overlay?.level(level.levelId)?.boss?.item?.allSourceRefs.orEmpty()
    val quotes = LinkedHashMap<String, MutableList<String>>()
    (unitRefs + bossRefs.filter { b -> unitRefs.none { it.chunkId == b.chunkId } }).forEach { r ->
        val q = quotes.getOrPut(r.chunkId) { mutableListOf() }
        if (r.quote.isNotBlank() && r.quote !in q) q += r.quote
    }
    return state.pkg.sources.filter { it.chunkId in quotes }
        .map { c -> quotes[c.chunkId].orEmpty().takeIf { it.isNotEmpty() }?.let { c.copy(text = it.joinToString(" … ") { q -> "“$q”" }) } ?: c }
        .sortedWith(compareBy({ it.pageIndex }, { it.chunkId }))
}

/** Verdict as a stamp: shape + word + colour, never colour alone. */
@Composable
private fun ResultStamp(phase: Phase.Feedback, retryQueued: Boolean, isBoss: Boolean) {
    val skin = A2g.skin
    val g = phase.grade
    val partly = !g.correct && g.parts > 1 && g.wrongPartIds.size < g.parts
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            g.correct && isBoss -> com.anything2game.app.ui.common.Stamp(stringResource(R.string.stamp_overclaim), com.anything2game.app.ui.common.StampKind.YES, com.anything2game.app.ui.theme.StampRed, animate = false)
            g.correct -> com.anything2game.app.ui.common.Stamp(stringResource(R.string.stamp_correct), com.anything2game.app.ui.common.StampKind.YES, skin.correct, animate = false)
            partly -> com.anything2game.app.ui.common.Stamp(stringResource(R.string.stamp_partly), com.anything2game.app.ui.common.StampKind.PARTLY, skin.evidence, animate = false)
            else -> com.anything2game.app.ui.common.Stamp(stringResource(R.string.stamp_wrong), com.anything2game.app.ui.common.StampKind.NO, skin.wrong, animate = false)
        }
        Column {
            if (partly) Text(stringResource(R.string.result_partial, g.parts - g.wrongPartIds.size, g.parts), style = MaterialTheme.typography.titleMedium, color = skin.ink)
            if (!g.correct && retryQueued) Text(stringResource(R.string.retry_note), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
        }
    }
}

internal fun mechanicLabel(m: Mechanic) = when (m) {
    Mechanic.SWIPE -> R.string.mechanic_swipe
    Mechanic.SORT -> R.string.mechanic_sort
    Mechanic.DEDUCE -> R.string.mechanic_deduce
    Mechanic.ORDER -> R.string.mechanic_order
    Mechanic.CROSS_EXAM -> R.string.mechanic_crossexam
}
