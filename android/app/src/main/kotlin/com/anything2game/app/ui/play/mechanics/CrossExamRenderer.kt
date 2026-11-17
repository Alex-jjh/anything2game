package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.castColor
import com.anything2game.contract.CastRole
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.CrossExamGrader
import com.anything2game.engine.grading.Grade

/**
 * The signature mechanic: a character makes claims; pick the one that goes beyond the evidence,
 * then back the objection by presenting the passage. Two steps on purpose — the objection needs evidence.
 */
object CrossExamRenderer : MechanicRenderer {
    override val mechanic = Mechanic.CROSS_EXAM

    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val cx = item as CrossExamItem
        val skin = A2g.skin
        val haptic = LocalHapticFeedback.current
        val speaker = ctx.cast.firstOrNull { it.id == cx.speaker }
        val color = speaker?.let { skin.castColor(it.role) } ?: skin.wrong
        var challenged by rememberSaveable(key) { mutableStateOf<String?>(null) }
        var picking by rememberSaveable(key) { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(cx.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            if (!picking) {
                Text(stringResource(R.string.crossexam_instruction), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
                cx.statements.forEach { st ->
                    val selected = challenged == st.id
                    Card(
                        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { challenged = st.id },
                        colors = CardDefaults.cardColors(containerColor = if (selected) skin.wrongSoft else skin.card),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else skin.outline),
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.FormatQuote, null, tint = color)
                            Column {
                                speaker?.let { Text(it.name, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold) }
                                Text(st.text, style = MaterialTheme.typography.bodyLarge, color = skin.ink, fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                }
                Button(
                    onClick = { picking = true; haptic.performHapticFeedback(HapticFeedbackType.Confirm) },
                    enabled = challenged != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = skin.evidence),
                ) { Icon(Icons.Filled.Description, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.crossexam_present)) }
            } else {
                val st = cx.statements.firstOrNull { it.id == challenged }
                Card(colors = CardDefaults.cardColors(containerColor = skin.wrongSoft)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(stringResource(R.string.crossexam_selected), style = MaterialTheme.typography.labelMedium, color = color)
                        Text(st?.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = skin.ink, fontStyle = FontStyle.Italic)
                    }
                }
                Text(stringResource(R.string.crossexam_pick_evidence), style = MaterialTheme.typography.titleMedium, color = skin.ink)
                ctx.chunks.forEach { chunk ->
                    Card(
                        Modifier.fillMaxWidth().selectable(false, role = Role.Button) {
                            challenged?.let { onSubmit(CrossExamAnswer(it, chunk.chunkId)) }
                        },
                        colors = CardDefaults.cardColors(containerColor = skin.paper),
                        border = BorderStroke(1.dp, skin.evidence),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.evidence_page, chunk.pageLabel ?: (chunk.pageIndex + 1).toString()),
                                style = MaterialTheme.typography.labelMedium, color = skin.evidence, fontWeight = FontWeight.SemiBold,
                            )
                            Text(chunk.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = skin.ink)
                        }
                    }
                }
                OutlinedButton(onClick = { picking = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_back)) }
            }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val cx = item as CrossExamItem
        val chosen = (answer as CrossExamAnswer).statementId
        val skin = A2g.skin
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (CrossExamGrader.WRONG_EVIDENCE in grade.wrongPartIds) {
                Text(stringResource(R.string.crossexam_wrong_evidence), style = MaterialTheme.typography.bodyLarge, color = skin.evidence)
            }
            cx.statements.filter { it.flawed || it.id == chosen }.forEach { st ->
                ReviewRow(
                    correct = st.flawed && grade.correct || (!st.flawed && st.id != chosen),
                    title = st.text,
                    detail = when {
                        st.flawed -> stringResource(R.string.crossexam_flawed)
                        else -> stringResource(R.string.crossexam_you_chose)
                    },
                    explanation = st.why,
                )
            }
        }
    }
}

