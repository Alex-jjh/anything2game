package com.anything2game.app.ui.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.common.Badge
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.SourceRef
import com.anything2game.contract.tutor.TutorRules
import com.anything2game.contract.tutor.TutorSession
import com.anything2game.contract.tutor.Understanding

/**
 * Where the reader is, always in view: "Concept 2/4" with a word for the stage (just started, in progress, last concept,
 * all covered) and one bar segment per concept; the current segment fills as its pages are read.
 */
@Composable
internal fun Progress(position: Int, total: Int, pageFraction: Float, finished: Boolean, modifier: Modifier) {
    val skin = A2g.skin
    val stage = stringResource(when {
        finished -> R.string.explainer_stage_done
        position >= total - 1 -> R.string.explainer_stage_last
        position == 0 -> R.string.explainer_stage_start
        else -> R.string.explainer_stage_middle
    })
    val count = stringResource(R.string.explainer_concept, position + 1, total)
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(count, style = MaterialTheme.typography.labelLarge, color = skin.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("concept_count"))
            Text(stage, style = MaterialTheme.typography.labelMedium, color = if (finished) skin.correct else skin.action, modifier = Modifier.testTag("concept_stage"))
        }
        Row(Modifier.fillMaxWidth().height(6.dp).testTag("concept_progress").semantics { contentDescription = "$count · $stage" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(total) { i ->
                val fill = when { i < position || finished -> 1f; i == position -> pageFraction.coerceIn(0.1f, 1f); else -> 0f }
                Box(Modifier.weight(1f).fillMaxHeight().background(skin.outline.copy(alpha = 0.25f), CircleShape)) {
                    if (fill > 0f) Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(if (finished) skin.correct else skin.action, CircleShape))
                }
            }
        }
    }
}

/**
 * The end of the walk, full screen: the explainer says it is done, every concept in the order it was taught with where
 * the reader started and its page, and a plain note that these were the foundations, not the whole document.
 */
@Composable
internal fun WrapUp(
    session: TutorSession, theme: ExplainerTheme, explainer: String, onSource: (List<SourceRef>) -> Unit,
    onShelf: () -> Unit, onStay: () -> Unit, modifier: Modifier,
) {
    val skin = A2g.skin
    val concepts = TutorRules.orderedQuestions(session)
    val asked = session.messages.count { it.role == "user" && it.text.isNotBlank() } // every turn the reader took, button or question
    Surface(modifier.testTag("wrap_up"), color = skin.paper) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(96.dp)) { theme.Standee(ExplainerExpression.DONE, Modifier.fillMaxSize()) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.wrapup_title), style = MaterialTheme.typography.headlineSmall, color = skin.ink, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("wrapup_title"))
                        Text(stringResource(R.string.wrapup_line, explainer, concepts.size), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                    }
                }
                Text(session.title, style = MaterialTheme.typography.titleMedium, color = skin.inkMuted)
                Text(stringResource(R.string.wrapup_steps, asked), style = MaterialTheme.typography.labelLarge, color = skin.action)
                concepts.forEachIndexed { i, q ->
                    val evidence = session.assessment.firstOrNull { it.questionId == q.id }?.evidence
                    val page = q.sourceRefs.firstOrNull()?.let { r -> session.sources.firstOrNull { it.chunkId == r.chunkId } }?.let { it.pageLabel ?: (it.pageIndex + 1).toString() }
                    Surface(Modifier.fillMaxWidth().testTag("wrapup_concept"), color = skin.card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, skin.outline.copy(alpha = 0.4f))) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(28.dp).background(skin.correctSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = skin.correct, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(q.concept, style = MaterialTheme.typography.titleSmall, color = skin.ink, fontWeight = FontWeight.SemiBold)
                                if (evidence != null) Badge(stringResource(R.string.wrapup_start, stringResource(when (evidence) {
                                    Understanding.DEMONSTRATED -> R.string.tutor_correct; Understanding.NEEDS_CLARIFICATION -> R.string.tutor_clarify; Understanding.NOT_YET_ASSESSED -> R.string.tutor_not_assessed
                                })), skin.action, skin.evidenceSoft)
                            }
                            if (page != null) TextButton(onClick = { onSource(q.sourceRefs) }, modifier = Modifier.heightIn(min = 48.dp).testTag("wrapup_source")) {
                                Text(stringResource(R.string.evidence_page, page), color = skin.evidence)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.wrapup_note), style = MaterialTheme.typography.bodySmall, color = skin.inkMuted)
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onStay, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("wrapup_stay")) { Text(stringResource(R.string.wrapup_stay), textAlign = TextAlign.Center) }
                Button(onClick = onShelf, modifier = Modifier.weight(1f).widthIn(min = 120.dp).heightIn(min = 52.dp).testTag("wrapup_shelf")) { Text(stringResource(R.string.wrapup_shelf), textAlign = TextAlign.Center) }
            }
        }
    }
}
