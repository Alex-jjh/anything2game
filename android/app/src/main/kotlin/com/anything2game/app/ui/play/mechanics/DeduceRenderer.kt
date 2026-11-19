package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.play.CastPortrait
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.castColor
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.Grade
import androidx.compose.ui.res.stringResource

object DeduceRenderer : MechanicRenderer {
    override val mechanic = Mechanic.DEDUCE

    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val oneHanded = ctx.oneHanded
        val deduce = item as DeduceItem
        val skin = A2g.skin
        var choice by rememberSaveable(key) { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Scenario(deduce.scenario, ctx)
            Text(deduce.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            deduce.options.forEach { option ->
                val selected = choice == option.id
                Card(
                    Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { choice = option.id },
                    colors = CardDefaults.cardColors(containerColor = skin.card),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) skin.action else skin.outline),
                ) {
                    Row(Modifier.padding(12.dp).heightIn(min = if (oneHanded) 56.dp else 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null)
                        Text(option.text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                    }
                }
            }
            }
            // Docked submit: always in the thumb zone, never scrolled away.
            Button(
                onClick = { choice?.let { onSubmit(DeduceAnswer(it)) } },
                enabled = choice != null,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.action_submit)) }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val deduce = item as DeduceItem
        val chosen = (answer as DeduceAnswer).optionId
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ctx.scenarioSpeaker != null) Scenario(deduce.scenario, ctx)
            Text(deduce.prompt, style = MaterialTheme.typography.titleMedium, color = A2g.skin.ink)
            // Show the player's choice and the right one; other options stay out of the way.
            deduce.options.filter { it.id == chosen || it.correct }.forEach { option ->
                ReviewRow(correct = option.correct, title = option.text, detail = null, explanation = option.feedback)
            }
        }
    }

    /** The situation card; when a character speaks it (the rookie in a teach-back) it sits beside their portrait, as a line of dialogue. */
    @Composable
    private fun Scenario(text: String, ctx: RenderContext) {
        val skin = A2g.skin
        val speaker = ctx.scenarioSpeaker
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (speaker != null) CastPortrait(speaker, size = 64.dp, dim = false)
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = skin.paper),
                border = BorderStroke(if (speaker != null) 1.5.dp else 1.dp, speaker?.let { skin.castColor(it.role) } ?: skin.outline),
            ) {
                Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
            }
        }
    }
}
