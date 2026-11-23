package com.anything2game.app.ui.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.tutor.*

/** A route names a concept, while the tutor owns diagnostic ordering and introduced concepts.
 * The parent may show the ordinary tutor only after the target is reached or its prerequisite is accepted.
 */
@Composable
internal fun showRoutedTutorPrerequisite(target: String, session: TutorSession, state: TutorUiState,
    vm: TutorViewModel, onBack: () -> Unit): Boolean {
    var assessmentAccepted by rememberSaveable(session.sessionId, target) { mutableStateOf(false) }
    var followSequence by rememberSaveable(session.sessionId, target) { mutableStateOf(false) }
    var reached by rememberSaveable(session.sessionId, target) { mutableStateOf(false) }
    val ordered = TutorRules.orderedQuestions(session)
    val requested = ordered.indexOfFirst { it.conceptId == target }
    val current = ordered[session.conceptPosition]
    val teaching = session.messages.any { it.role == "tutor" }
    if (teaching && current.conceptId == target) {
        LaunchedEffect(Unit) { reached = true }
        return false
    }
    if (reached || followSequence || (session.assessment.isEmpty() && assessmentAccepted)) return false

    val label = ordered.getOrNull(requested)?.concept
    val revisit = teaching && requested >= 0 &&
        runCatching { TutorRules.positionAfter(session, TutorIntent.REVISIT, requested) }.isSuccess
    Scaffold(containerColor = A2g.skin.paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onBack) { Text(stringResource(R.string.action_back)) }
            Text(session.title, style = MaterialTheme.typography.headlineSmall)
            Text(when {
                label == null -> stringResource(R.string.route_unknown)
                session.assessment.isEmpty() -> stringResource(R.string.route_diagnostic, label)
                revisit || current.conceptId == target -> stringResource(R.string.route_revisit, label)
                else -> stringResource(R.string.route_prerequisite, label, current.concept)
            }, Modifier.testTag("route_prerequisite"))
            if (state.busy) LinearProgressIndicator()
            state.problem?.let { Text(stringResource(R.string.tutor_error, it)); TextButton(vm::retry) { Text(stringResource(R.string.home_retry)) } }
            if (label != null) Button({
                when {
                    session.assessment.isEmpty() -> assessmentAccepted = true
                    revisit -> vm.turn(TutorIntent.REVISIT, targetPosition = requested)
                    !teaching -> { followSequence = true; vm.turn(TutorIntent.START) }
                    else -> followSequence = true
                }
            }, Modifier.fillMaxWidth().testTag("route_follow"), enabled = !state.busy && state.problem == null) {
                Text(if (session.assessment.isEmpty()) stringResource(R.string.route_assess)
                    else stringResource(R.string.route_follow, if (revisit) label else current.concept))
            }
        }
    }
    return true
}
