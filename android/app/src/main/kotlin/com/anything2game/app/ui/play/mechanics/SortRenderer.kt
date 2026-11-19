package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.SortItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import com.anything2game.engine.grading.SortAnswer

/** Tap a card, tap a bin. Tap-to-place works one-handed and with TalkBack; drag-and-drop can be layered on later. */
object SortRenderer : MechanicRenderer {
    override val mechanic = Mechanic.SORT

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val oneHanded = ctx.oneHanded
        val sort = item as SortItem
        val skin = A2g.skin
        val haptic = LocalHapticFeedback.current
        // Cards are shown in a stable shuffled order so the answer isn't the listing order.
        val order = remember(key) { sort.cards.shuffled(java.util.Random(key.hashCode().toLong())) }
        var placements by rememberSaveable(key) { mutableStateOf(emptyList<String>()) } // "cardId:binId"
        var selected by rememberSaveable(key) { mutableStateOf<String?>(null) }
        val placed = placements.associate { it.substringBefore(':') to it.substringAfter(':') }
        val unplaced = order.filter { it.id !in placed }

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(sort.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.sort_instruction), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            if (unplaced.isNotEmpty()) {
                Text(stringResource(R.string.sort_left, unplaced.size), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    unplaced.forEach { card ->
                        FilterChip(
                            selected = selected == card.id,
                            onClick = { selected = if (selected == card.id) null else card.id },
                            label = { Text(card.text, Modifier.padding(vertical = 8.dp)) },
                        )
                    }
                }
            }
            sort.bins.forEach { bin ->
                val target = selected != null
                Card(
                    Modifier.fillMaxWidth().heightIn(min = if (oneHanded) 96.dp else 80.dp).clickable(enabled = target) {
                        selected?.let { id ->
                            placements = placements.filterNot { it.startsWith("$id:") } + "$id:${bin.id}"
                            selected = null
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    colors = CardDefaults.cardColors(containerColor = skin.card),
                    border = BorderStroke(if (target) 2.dp else 1.dp, if (target) skin.action else skin.outline),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(bin.label, fontWeight = FontWeight.SemiBold, color = skin.ink)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            order.filter { placed[it.id] == bin.id }.forEach { card ->
                                InputChip(
                                    selected = false,
                                    onClick = { placements = placements.filterNot { it.startsWith("${card.id}:") } },
                                    label = { Text(card.text) },
                                )
                            }
                        }
                    }
                }
            }
            if (placed.isNotEmpty() && unplaced.isNotEmpty()) {
                Text(stringResource(R.string.sort_tap_to_return), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
            }
            }
            // Docked submit: always in the thumb zone, never scrolled away.
            Button(
                onClick = { onSubmit(SortAnswer(placed)) },
                enabled = unplaced.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.action_submit)) }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val sort = item as SortItem
        val bins = sort.bins.associate { it.id to it.label }
        val placed = (answer as SortAnswer).placements
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sort.cards.forEach { card ->
                val ok = card.id !in grade.wrongPartIds
                ReviewRow(
                    correct = ok,
                    title = card.text,
                    detail = if (ok) bins[placed[card.id]] else stringResource(R.string.sort_correct_bin, bins[card.binId].orEmpty()),
                    explanation = null,
                )
            }
            Text(sort.why, style = MaterialTheme.typography.bodyLarge, color = A2g.skin.ink)
        }
    }
}
