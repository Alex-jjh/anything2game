package com.anything2game.app.ui.play

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.play.mechanics.MechanicRenderers
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.Mechanic
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Overlay
import com.anything2game.engine.session.Phase

/** Stateless play screen. All input becomes an engine [Action]; nothing here decides game flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayContent(
    state: PlayUiState.Ready,
    onAction: (Action) -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    simulator: (@Composable () -> Unit)? = null,
) {
    val skin = A2g.skin
    val session = state.session
    val level = state.pkg.level(session.levelId)
    var showSimulator by rememberSaveable { mutableStateOf(false) }

    BackHandler { if (session.overlay != null) onAction(Action.CloseOverlay) else onBack() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = skin.paper,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = skin.paper),
                title = { Text(level?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (showSimulator) simulator?.invoke()
                val recap = state.recap
                if (recap != null) {
                    RecapContent(recap, unitCount = level?.units?.size ?: 0, onReplay = onRestart, onLibrary = onBack)
                } else {
                    ProgressHeader(state, onAction)
                    UnitBody(state, onAction)
                }
            }
        }
    }
    when (val overlay = session.overlay) {
        is Overlay.Evidence -> EvidencePanel(
            chunks = overlay.chunkIds.mapNotNull(state.pkg::chunk),
            refs = state.item?.allSourceRefs.orEmpty(),
            documentTitle = state.pkg.title,
            onClose = { onAction(Action.CloseOverlay) },
        )
        Overlay.Asking -> AskPanel(offline = !state.context.askAvailable, onClose = { onAction(Action.CloseOverlay) })
        null -> Unit
    }
    if (session.isPaused) PausedOverlay(onContinue = { onAction(Action.Resume) })
    }
}

@Composable
private fun ProgressHeader(state: PlayUiState.Ready, onAction: (Action) -> Unit) {
    val skin = A2g.skin
    val s = state.session
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { (s.position + if (s.phase is Phase.Feedback) 1 else 0).toFloat() / s.queue.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
            color = skin.action,
            trackColor = skin.outline,
            drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.unit?.let { Badge(stringResource(mechanicLabel(it.mechanic)), skin.action, skin.card) }
            if (state.context.oneHanded) Badge(stringResource(R.string.one_handed_badge), skin.paused, skin.card)
            Spacer(Modifier.weight(1f))
            val hintsLeft = state.session.phase == Phase.Presenting && state.session.hintsShown < (state.unit?.hints?.size ?: 0)
            if (hintsLeft) {
                TextButton(onClick = { onAction(Action.RevealHint) }, colors = ButtonDefaults.textButtonColors(contentColor = skin.hint)) {
                    Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text(stringResource(R.string.action_hint))
                }
            }
            Text(stringResource(R.string.play_progress, s.position + 1, s.queue.size), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
        }
    }
}

@Composable
private fun UnitBody(state: PlayUiState.Ready, onAction: (Action) -> Unit) {
    val skin = A2g.skin
    val unit = state.unit ?: return
    val item = state.item ?: return
    val renderer = MechanicRenderers.of(unit.mechanic)
    if (renderer == null) {
        Text(stringResource(R.string.unsupported_mechanic), color = skin.wrong)
        return
    }
    val s = state.session
    when (val phase = s.phase) {
        Phase.Presenting -> {
            unit.hints.take(s.hintsShown).forEachIndexed { i, hint ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = skin.hintSoft)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.hint_label, i + 1), style = MaterialTheme.typography.labelMedium, color = skin.hint, fontWeight = FontWeight.SemiBold)
                        Text(hint, color = skin.ink)
                    }
                }
            }
            val slot = s.currentSlot
            renderer.Question(
                key = "${s.position}:${slot?.unitId}:${slot?.variant}",
                item = item,
                oneHanded = state.context.oneHanded,
                onSubmit = { onAction(Action.Submit(it)) },
            )
        }
        is Phase.Feedback -> {
            ResultBanner(phase, state.retryQueued)
            renderer.Review(item, phase.answer, phase.grade)
            val chunkIds = item.allSourceRefs.map { it.chunkId }.distinct()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onAction(Action.OpenEvidence(chunkIds)) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    border = BorderStroke(1.5.dp, skin.evidence),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = skin.evidence),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Icon(Icons.Filled.Description, null, Modifier.size(20.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.action_show_evidence), maxLines = 1) }
                val last = s.position + 1 >= s.queue.size
                Button(onClick = { onAction(Action.Next) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text(stringResource(if (last) R.string.action_finish else R.string.action_next))
                }
            }
        }
        Phase.Recap -> Unit
    }
}

@Composable
private fun ResultBanner(phase: Phase.Feedback, retryQueued: Boolean) {
    val skin = A2g.skin
    val g = phase.grade
    val (text, fg, bg) = when {
        g.correct -> Triple(stringResource(R.string.result_correct), skin.correct, skin.correctSoft)
        g.parts > 1 && g.wrongPartIds.size < g.parts -> Triple(stringResource(R.string.result_partial, g.parts - g.wrongPartIds.size, g.parts), skin.evidence, skin.evidenceSoft)
        else -> Triple(stringResource(R.string.result_wrong), skin.wrong, skin.wrongSoft)
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.titleLarge, color = fg)
            if (!g.correct && retryQueued) Text(stringResource(R.string.retry_note), style = MaterialTheme.typography.bodyMedium, color = skin.ink)
        }
    }
}

internal fun mechanicLabel(m: Mechanic) = when (m) {
    Mechanic.SWIPE -> R.string.mechanic_swipe
    Mechanic.SORT -> R.string.mechanic_sort
    Mechanic.DEDUCE -> R.string.mechanic_deduce
    Mechanic.ORDER -> R.string.mechanic_order
}
