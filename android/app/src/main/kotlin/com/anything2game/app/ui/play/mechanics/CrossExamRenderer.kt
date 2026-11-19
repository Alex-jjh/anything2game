package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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

        val avatarSignals = com.anything2game.app.ui.character.LocalAvatarSignals.current
        androidx.compose.runtime.SideEffect {
            avatarSignals.focus = when {
                picking -> com.anything2game.app.ui.character.Mood.OBJECT
                challenged != null -> com.anything2game.app.ui.character.Mood.DOUBT
                else -> null
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Testimony header: the speaker once, not on every statement.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    speaker?.let { com.anything2game.app.ui.play.CastPortrait(it, size = 52.dp, dim = false) }
                    Column {
                        Text(stringResource(R.string.crossexam_testimony), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
                        Text(cx.prompt, style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
                    }
                }
                if (!picking) {
                    cx.statements.forEachIndexed { i, st ->
                        val selected = challenged == st.id
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (selected) skin.wrongSoft else skin.card, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .border(if (selected) 2.dp else 1.dp, if (selected) com.anything2game.app.ui.theme.StampRed else skin.outline.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .selectable(selected, role = Role.RadioButton) { challenged = st.id; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = if (selected) com.anything2game.app.ui.theme.StampRed else skin.inkMuted, fontWeight = FontWeight.Black)
                            Text(st.text, style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                        }
                    }
                } else {
                    val st = cx.statements.firstOrNull { it.id == challenged }
                    Row(Modifier.fillMaxWidth().background(skin.wrongSoft, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.FormatQuote, null, tint = com.anything2game.app.ui.theme.StampRed)
                        Text(st?.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = skin.ink, fontStyle = FontStyle.Italic)
                    }
                    Text(stringResource(R.string.crossexam_collected), style = MaterialTheme.typography.titleMedium, color = skin.ink)
                    ctx.evidenceChunks.forEach { chunk ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(skin.paper, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .border(1.dp, skin.evidence, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .selectable(false, role = Role.Button) { challenged?.let { onSubmit(CrossExamAnswer(it, chunk.chunkId)) } }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("p.${chunk.pageLabel ?: (chunk.pageIndex + 1)}", style = MaterialTheme.typography.labelLarge, color = skin.evidence, fontWeight = FontWeight.Bold)
                            Text(chunk.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = skin.ink)
                        }
                    }
                }
            }
            // Docked action: hold to object (no accidental objections), or step back from presenting.
            if (!picking) {
                HoldToObject(enabled = challenged != null, label = stringResource(if (challenged == null) R.string.crossexam_choose_first else R.string.crossexam_hold)) {
                    picking = true
                }
            } else {
                OutlinedButton(onClick = { picking = false }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp)) { Text(stringResource(R.string.action_back)) }
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


/** Long-press button: fills over ~650 ms with a haptic tick at the start and a confirm at the end. Release early cancels. */
@Composable
private fun HoldToObject(enabled: Boolean, label: String, onDone: () -> Unit) {
    val skin = A2g.skin
    val haptic = LocalHapticFeedback.current
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val avatarSignals = com.anything2game.app.ui.character.LocalAvatarSignals.current
    androidx.compose.runtime.SideEffect { avatarSignals.charge = if (progress.value < 1f) progress.value else 0f }
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 60.dp)
            .background(if (enabled) com.anything2game.app.ui.theme.StampRed.copy(alpha = 0.12f) else skin.outline.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(30.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val job = scope.launch {
                        progress.animateTo(1f, androidx.compose.animation.core.tween(650))
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDone()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f) } } else scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress.value > 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(com.anything2game.app.ui.theme.StampRed.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(30.dp)))
            }
        }
        Text(label, color = if (!enabled) skin.inkMuted else if (progress.value > 0.5f) Color.White else com.anything2game.app.ui.theme.StampRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}
