package com.anything2game.app.ui.play.mechanics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.OrderItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import com.anything2game.engine.grading.OrderAnswer

/** Rebuild a sequence with up/down moves (works one-handed and with TalkBack; drag can be layered on later). */
object OrderRenderer : MechanicRenderer {
    override val mechanic = Mechanic.ORDER

    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val order = item as OrderItem
        val skin = A2g.skin
        val haptic = LocalHapticFeedback.current
        var ids by rememberSaveable(key) {
            // Deterministic shuffle that is never already solved.
            val shuffled = order.steps.map { it.id }.shuffled(java.util.Random(key.hashCode().toLong()))
            mutableStateOf(if (shuffled == order.steps.map { it.id }) shuffled.reversed() else shuffled)
        }
        val byId = order.steps.associateBy { it.id }
        fun move(i: Int, d: Int) {
            val j = i + d
            if (j !in ids.indices) return
            ids = ids.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(order.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.order_instruction), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            ids.forEachIndexed { i, id ->
                Card(colors = CardDefaults.cardColors(containerColor = skin.card), border = BorderStroke(1.dp, skin.outline)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = if (ctx.oneHanded) 64.dp else 56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepNumber(i + 1)
                        Text(byId[id]?.text.orEmpty(), Modifier.weight(1f).padding(horizontal = 12.dp), color = skin.ink, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.order_up)) }
                        IconButton(onClick = { move(i, 1) }, enabled = i < ids.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.order_down)) }
                    }
                }
            }
            Button(onClick = { onSubmit(OrderAnswer(ids)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_submit)) }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val order = item as OrderItem
        val byId = order.steps.associateBy { it.id }
        val correctIndex = order.steps.mapIndexed { i, s -> s.id to i }.toMap()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (answer as OrderAnswer).stepIds.forEach { id ->
                val ok = id !in grade.wrongPartIds
                ReviewRow(ok, byId[id]?.text.orEmpty(), if (ok) null else stringResource(R.string.order_correct_position, (correctIndex[id] ?: 0) + 1), null)
            }
            Text(order.why, style = MaterialTheme.typography.bodyLarge, color = A2g.skin.ink)
        }
    }
}

@Composable
private fun StepNumber(n: Int) {
    val skin = A2g.skin
    Box(Modifier.size(28.dp).background(skin.paper, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", fontWeight = FontWeight.Bold, color = skin.action)
    }
}
