package com.anything2game.app.ui.reading

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.anything2game.app.ui.common.problemText
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
import com.anything2game.app.AppDependencies
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.home.PaperCard
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.data.source.CourseRef
import com.anything2game.app.data.source.SourceKey
import com.anything2game.app.data.source.SourceText
import com.anything2game.app.ui.source.OpenSourceViewer
import com.anything2game.contract.*
import com.anything2game.contract.tutor.*

/** From this system font scale up, the diagnostic's header collapses to one line (see [ReadingContent]). */
internal const val COMPACT_FONT_SCALE = 1.3f

/** Middle-tab teaching surface. Practice has its own tab and never launches from this flow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingContent(deps: AppDependencies, packageId: String, conceptId: String? = null, sourcePackage: String = packageId, contentVersion: String? = null, onBack: () -> Unit) {
    val profileId by deps.profiles.active.collectAsStateWithLifecycle()
    val vm = viewModel(key = "tutor:${profileId?.id}:$packageId") { TutorViewModel(deps.tutors, packageId) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler { if (state.busy) vm.cancelPending(); onBack() }
    val c = state.cache
    val s = c?.session
    // A route step opens the explanation at its concept directly (no starting assessment). Once per visit: the saved flag keeps
    // a rotation from jumping the reader back after they moved on.
    val routedConcept = conceptId?.takeIf { id -> s?.questions?.any { it.conceptId == id } == true }
    var opened by rememberSaveable(packageId, conceptId) { mutableStateOf(false) }
    LaunchedEffect(routedConcept, state.busy, state.problem) {
        if (routedConcept != null && !opened && !state.busy && state.problem == null) { opened = true; vm.openAt(routedConcept) }
    }
    val skin = A2g.skin
    val list = rememberLazyListState()
    var input by rememberSaveable(packageId) { mutableStateOf("") }
    // Saveable: attaching a PDF leaves for the system picker, and the activity may be recreated behind it.
    var sourceRefs by rememberSaveable(packageId, stateSaver = SourceRefsSaver) { mutableStateOf<List<SourceRef>>(emptyList()) }
    // Pending intent is only for loading feedback; the displayed reply's mode comes from its saved metadata.
    var requestedIntent by rememberSaveable(packageId) { mutableStateOf<TutorIntent?>(null) }
    val context = LocalContext.current
    val theme = remember(profileId, context) { ExplainerThemes.resolve(context, deps.explainerPreferences.get()) }
    val turn: (TutorIntent, String, Int?) -> Unit = { intent, question, target -> requestedIntent = intent; vm.turn(intent, question, target) }
    var unsupported by remember { mutableStateOf<LearningPackage?>(null) }
    var readOriginal by rememberSaveable(packageId) { mutableStateOf(false) }
    LaunchedEffect(state.unsupported) {
        if (state.unsupported) unsupported = deps.packages.load(packageId).getOrNull()
    }
    // Large system text: the explainer standee, badge and notes would push the first question off the screen.
    val compact = androidx.compose.ui.platform.LocalDensity.current.fontScale >= COMPACT_FONT_SCALE
    val phase = when { s == null -> 0; s.assessment.isEmpty() -> 1; s.messages.isEmpty() -> 2; else -> 3 }
    LaunchedEffect(phase, c?.questionIndex) { list.scrollToItem(0) }
    val opening = conceptId != null && phase != 3 && state.problem == null && (s == null || (routedConcept != null && (!opened || state.busy)))
    Scaffold(containerColor = skin.paper) { padding ->
        if (opening) Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).testTag("route_opening"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { if (state.busy) vm.cancelPending(); onBack() }) { Text(stringResource(R.string.action_back)) }
            s?.let { Text(it.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted) }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (phase == 3 && s != null) ExplainerTheatre(
            s, theme, busy = state.busy, enabled = !state.busy && state.problem == null, offline = state.offline, problem = state.problem,
            lastIntent = if (state.busy) requestedIntent else c.replyIntent, input = input, onInput = { input = it }, onTurn = turn, onSource = { sourceRefs = it },
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
            if (s != null && phase in 1..2 && !compact) item { ExplainerHeader(theme, when { phase == 2 -> stringResource(R.string.explainer_assess_line); focusDue(c) -> stringResource(R.string.explainer_focus_line); else -> stringResource(R.string.explainer_diag_line, s.questions.size) }, state.busy) }
            if (s != null && compact) item {
                val badge = stringResource(s.mode.badgeRes())
                Text("$badge · ${s.title}", style = MaterialTheme.typography.labelLarge, color = skin.inkMuted, modifier = Modifier.testTag("compact_header"))
            } else if (s != null) item {
                Text(s.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted)
                Badge(stringResource(s.mode.badgeRes()), skin.evidence, skin.evidenceSoft)
                Text(stringResource(s.mode.coverageNoteRes()), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            }
            if (state.offline) item { Text(stringResource(R.string.tutor_offline), color = skin.hint) }
            if (state.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (s != null) TextButton(onClick = vm::cancelPending) { Text(stringResource(R.string.tutor_cancel)) }
            }
            if (state.problem != null && !state.unsupported) item {
                PaperCard {
                    Text(stringResource(R.string.tutor_error, problemText(state.problem)), color = skin.wrong)
                    Row { TextButton(onClick = vm::retry, enabled = !state.busy) { Text(stringResource(R.string.home_retry)) }; TextButton(onClick = vm::reload, enabled = !state.busy) { Text(stringResource(R.string.tutor_reload)) } }
                }
            }
            if (state.unsupported) {
                item { Text(stringResource(R.string.tutor_unavailable), color = skin.inkMuted) }
                if (unsupported != null) item { OutlinedButton(onClick = { readOriginal = true }, Modifier.fillMaxWidth().testTag("read_original")) { Text(stringResource(R.string.tutor_read_original)) } }
            }
            val enabled = !state.busy && state.problem == null
            val askingFocus = focusItems(c, enabled, allowNote = s?.mode?.takesFocusNote == true, onSet = vm::setFocus)
            if (s != null && !askingFocus) when (phase) {
                1 -> {
                    val q = s.questions[c.questionIndex]
                    item {
                        Text(stringResource(R.string.tutor_question_count, c.questionIndex + 1, s.questions.size), style = MaterialTheme.typography.labelLarge, color = skin.action)
                        LinearProgressIndicator(progress = { (c.questionIndex + 1f) / s.questions.size }, modifier = Modifier.fillMaxWidth())
                        if (!compact) Text(stringResource(R.string.tutor_diagnostic_note), color = skin.inkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    item { PaperCard {
                        Text(q.concept, color = skin.action, style = MaterialTheme.typography.labelLarge)
                        Text(q.prompt, color = skin.ink, style = MaterialTheme.typography.titleLarge)
                        (q.options + TutorOption(UNKNOWN_ANSWER, stringResource(R.string.tutor_unknown))).forEach { option ->
                            val selected = c.draftAnswers[q.id] == option.id
                            OutlinedButton(onClick = { vm.choose(option.id) }, enabled = enabled, shape = RoundedCornerShape(16.dp), // a pill turns into a circle that clips the text when large text wraps
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
                    // Choosing an answer moves on by itself; these two are for a question that was already answered (after "Previous").
                    if (c.questionIndex > 0 || q.id in c.draftAnswers) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (c.questionIndex > 0) OutlinedButton(onClick = vm::previous, enabled = enabled, modifier = Modifier.testTag("diagnostic_previous")) { Text(stringResource(R.string.tutor_previous)) }
                        if (q.id in c.draftAnswers) Button(onClick = vm::next, enabled = enabled,
                            modifier = Modifier.weight(1f).testTag("diagnostic_next")) { Text(stringResource(if (c.questionIndex == s.questions.lastIndex) R.string.tutor_evaluate else R.string.tutor_next)) }
                    } }
                }
                2 -> {
                    item { Text(stringResource(R.string.tutor_assessment_note), color = skin.inkMuted) }
                    items(s.questions) { q ->
                        val evidence = s.assessment.first { it.questionId == q.id }.evidence
                        PaperCard {
                            Text(q.concept, style = MaterialTheme.typography.titleMedium, color = skin.ink)
                            Badge(stringResource(evidence.labelRes()), skin.action, skin.evidenceSoft)
                        }
                    }
                    item { Button(onClick = { turn(TutorIntent.START, "", null) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("start_tutor")) { Text(stringResource(R.string.tutor_start)) } }
                }
            }
        }
    }
    if (sourceRefs.isNotEmpty() && s != null) {
        OpenSourceViewer(deps.sources, SourceKey(s.documentId, s.documentVersion, CourseRef(sourcePackage, contentVersion)), SourceText(s.title, s.sources), sourceRefs,
            onDismiss = { sourceRefs = emptyList() })
    }
    // A sample with no explanation yet: its original is still there to read.
    if (readOriginal) unsupported?.let { pkg ->
        OpenSourceViewer(deps.sources, SourceKey(pkg.documentId, pkg.documentVersion, CourseRef(pkg.packageId, pkg.contentVersion)), SourceText(pkg.title, pkg.sources),
            onDismiss = { readOriginal = false })
    }
}

/** The cited refs of an open viewer as a flat chunkId/quote list, so the viewer survives activity recreation. */
private val SourceRefsSaver = androidx.compose.runtime.saveable.listSaver<List<SourceRef>, String>(
    save = { refs -> refs.flatMap { listOf(it.chunkId, it.quote) } },
    restore = { flat -> flat.chunked(2).filter { it.size == 2 }.map { (chunkId, quote) -> SourceRef(chunkId, quote) } },
)
