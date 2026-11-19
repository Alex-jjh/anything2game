package com.anything2game.app.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.*
import com.anything2game.contract.tutor.*

/** Middle-tab teaching surface. Practice has its own tab and never launches from this flow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingContent(deps: AppDependencies, packageId: String, onBack: () -> Unit) {
    val profileId by deps.profiles.active.collectAsState()
    val vm = viewModel(key = "tutor:${profileId?.id}:$packageId") { TutorViewModel(deps.tutors, packageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler { if (state.busy) vm.cancelPending(); onBack() }
    val c = state.cache
    val s = c?.session
    val skin = A2g.skin
    val list = rememberLazyListState()
    var input by rememberSaveable(packageId) { mutableStateOf("") }
    var sourceRefs by remember { mutableStateOf<List<SourceRef>>(emptyList()) }
    // What the player last asked of the explainer: it decides the explainer's expression and whether the question is shown as a line.
    var lastIntent by rememberSaveable(packageId) { mutableStateOf<TutorIntent?>(null) }
    val context = LocalContext.current
    val theme = remember(profileId, context) { ExplainerThemes.resolve(context, deps.explainerPreferences.get()) }
    val turn: (TutorIntent, String) -> Unit = { intent, question -> lastIntent = intent; vm.turn(intent, question) }
    var unsupported by remember { mutableStateOf<LearningPackage?>(null) }
    LaunchedEffect(state.unsupported) {
        if (state.unsupported) unsupported = deps.packages.load(packageId).getOrNull()
    }
    val phase = when { s == null -> 0; s.assessment.isEmpty() -> 1; s.messages.isEmpty() -> 2; else -> 3 }
    LaunchedEffect(phase, c?.questionIndex) { list.scrollToItem(0) }
    Scaffold(containerColor = skin.paper) { padding ->
        if (phase == 3 && s != null) ExplainerTheatre(
            s, theme, busy = state.busy, enabled = !state.busy && state.problem == null, offline = state.offline, problem = state.problem,
            lastIntent = lastIntent, input = input, onInput = { input = it }, onTurn = turn, onSource = { sourceRefs = it },
            onRetry = vm::retry, onReload = vm::reload, onCancel = vm::cancelPending, onBack = { if (state.busy) vm.cancelPending(); onBack() },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) else LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("tutor_list"), state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (state.busy) vm.cancelPending(); onBack() }) { Text(stringResource(R.string.action_back)) }
                    Text(stringResource(if (phase == 2) R.string.tutor_assessment_title else R.string.tutor_diagnostic_title), style = MaterialTheme.typography.titleLarge, color = skin.ink)
                }
            }
            if (s != null && phase in 1..2) item { ExplainerHeader(theme, when { phase == 2 -> stringResource(R.string.explainer_assess_line); focusDue(c) -> stringResource(R.string.explainer_focus_line); else -> stringResource(R.string.explainer_diag_line, s.questions.size) }, state.busy) }
            if (s != null) item {
                Text(s.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted)
                Badge(stringResource(when (s.mode) { "local-mock" -> R.string.badge_pre_generated; "mock" -> R.string.tutor_server_mock_badge; else -> R.string.tutor_model_badge }), skin.evidence, skin.evidenceSoft)
                Text(stringResource(if (s.mode == "local-mock") R.string.tutor_scripted_note else R.string.tutor_coverage_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            }
            if (state.offline) item { Text(stringResource(R.string.tutor_offline), color = skin.hint) }
            if (state.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (s != null) TextButton(onClick = vm::cancelPending) { Text(stringResource(R.string.tutor_cancel)) }
            }
            if (state.problem != null && !state.unsupported) item {
                PaperCard {
                    Text(stringResource(R.string.tutor_error, state.problem!!), color = skin.wrong)
                    Row { TextButton(onClick = vm::retry, enabled = !state.busy) { Text(stringResource(R.string.home_retry)) }; TextButton(onClick = vm::reload, enabled = !state.busy) { Text(stringResource(R.string.tutor_reload)) } }
                }
            }
            if (state.unsupported) {
                item { Text(stringResource(R.string.tutor_unavailable), color = skin.inkMuted) }
                items(unsupported?.sources.orEmpty()) { chunk -> PaperCard { Text("${chunk.pageLabel ?: (chunk.pageIndex + 1)} · ${chunk.chunkId}", color = skin.evidence); Text(chunk.text, color = skin.ink) } }
            }
            val enabled = !state.busy && state.problem == null
            val askingFocus = focusItems(c, enabled, vm::setFocus)
            if (s != null && !askingFocus) when (phase) {
                1 -> {
                    val q = s.questions[c.questionIndex]
                    item {
                        Text(stringResource(R.string.tutor_question_count, c.questionIndex + 1, s.questions.size), style = MaterialTheme.typography.labelLarge, color = skin.action)
                        LinearProgressIndicator(progress = { (c.questionIndex + 1f) / s.questions.size }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.tutor_diagnostic_note), color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    item { PaperCard {
                        Text(q.concept, color = skin.action, style = MaterialTheme.typography.labelLarge)
                        Text(q.prompt, color = skin.ink, style = MaterialTheme.typography.titleLarge)
                        (q.options + TutorOption(UNKNOWN_ANSWER, stringResource(R.string.tutor_unknown))).forEach { option ->
                            val selected = c.draftAnswers[q.id] == option.id
                            OutlinedButton(onClick = { vm.choose(option.id) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag(if (option.id == UNKNOWN_ANSWER) "answer_unknown" else "answer_choice_${option.id}")
                                .semantics { this.selected = selected }, // the radio dot alone says nothing to a screen reader
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) skin.evidenceSoft else skin.card)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected, onClick = null)
                                    Spacer(Modifier.width(8.dp)); Text(option.text, Modifier.weight(1f), color = skin.ink)
                                }
                            }
                        }
                    } }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (c.questionIndex > 0) OutlinedButton(onClick = vm::previous, enabled = enabled) { Text(stringResource(R.string.tutor_previous)) }
                        Button(onClick = vm::next, enabled = enabled && q.id in c.draftAnswers,
                            modifier = Modifier.weight(1f).testTag("diagnostic_next")) { Text(stringResource(if (c.questionIndex == s.questions.lastIndex) R.string.tutor_evaluate else R.string.tutor_next)) }
                    } }
                }
                2 -> {
                    item { Text(stringResource(R.string.tutor_assessment_note), color = skin.inkMuted) }
                    items(s.questions) { q ->
                        val evidence = s.assessment.first { it.questionId == q.id }.evidence
                        PaperCard {
                            Text(q.concept, style = MaterialTheme.typography.titleMedium, color = skin.ink)
                            Badge(stringResource(when (evidence) { Understanding.DEMONSTRATED -> R.string.tutor_correct; Understanding.NEEDS_CLARIFICATION -> R.string.tutor_clarify; Understanding.NOT_YET_ASSESSED -> R.string.tutor_not_assessed }), skin.action, skin.evidenceSoft)
                        }
                    }
                    item { Button(onClick = { turn(TutorIntent.START, "") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("start_tutor")) { Text(stringResource(R.string.tutor_start)) } }
                }
            }
        }
    }
    if (sourceRefs.isNotEmpty() && s != null) AlertDialog(onDismissRequest = { sourceRefs = emptyList() },
        title = { Text(stringResource(R.string.tutor_source_title)) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(sourceRefs) { ref ->
            val chunk = s.sources.first { it.chunkId == ref.chunkId }
            Text(stringResource(R.string.evidence_page, chunk.pageLabel ?: (chunk.pageIndex + 1).toString()), color = skin.evidence)
            Text(ref.quote, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            Text(chunk.text, style = MaterialTheme.typography.bodySmall)
        }; item { Text(stringResource(R.string.tutor_source_note), style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton(onClick = { sourceRefs = emptyList() }) { Text(stringResource(R.string.tutor_close)) } })
}
