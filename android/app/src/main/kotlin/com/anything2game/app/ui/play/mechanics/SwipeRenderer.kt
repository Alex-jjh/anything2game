package com.anything2game.app.ui.play.mechanics

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import com.anything2game.engine.grading.SwipeAnswer
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

object SwipeRenderer : MechanicRenderer {
    override val mechanic = Mechanic.SWIPE

    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val oneHanded = ctx.oneHanded
        val swipe = item as SwipeItem
        val skin = A2g.skin
        // Saved as "cardId:true" strings so rotation and process death keep decisions.
        var decided by rememberSaveable(key) { mutableStateOf(emptyList<String>()) }
        val index = decided.size
        val card = swipe.cards.getOrNull(index) ?: return
        val haptic = LocalHapticFeedback.current

        fun decide(supported: Boolean) {
            haptic.performHapticFeedback(if (supported) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            val next = decided + "${card.id}:$supported"
            decided = next
            if (next.size == swipe.cards.size) {
                onSubmit(SwipeAnswer(next.associate { it.substringBefore(':') to it.substringAfter(':').toBoolean() }))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.swipe_instruction), color = skin.inkMuted, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.swipe_card_count, index + 1, swipe.cards.size), color = skin.inkMuted, style = MaterialTheme.typography.labelMedium)

            val offset = remember(card.id) { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val threshold = with(LocalDensity.current) { 110.dp.toPx() }
            val lean = (offset.value / threshold).coerceIn(-1f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = offset.value; rotationZ = offset.value / 40f }
                    .pointerInput(card.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    val v = offset.value
                                    if (abs(v) > threshold) {
                                        offset.animateTo(v * 3)
                                        decide(v > 0)
                                    } else offset.animateTo(0f)
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        ) { change, drag ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + drag) }
                        }
                    },
            ) {
                Card(
                    Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    colors = CardDefaults.cardColors(containerColor = skin.card),
                    elevation = CardDefaults.cardElevation(4.dp),
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(card.statement, style = MaterialTheme.typography.titleLarge, color = skin.ink)
                    }
                }
                if (lean != 0f) {
                    val supported = lean > 0
                    Text(
                        stringResource(if (supported) R.string.swipe_supported else R.string.swipe_not_supported),
                        color = if (supported) skin.correct else skin.wrong,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(if (supported) Alignment.TopStart else Alignment.TopEnd)
                            .padding(12.dp)
                            .graphicsLayer { alpha = min(1f, abs(lean)) }
                            .background(if (supported) skin.correctSoft else skin.wrongSoft, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // Buttons do the same as swiping: accessible, and one-handed friendly.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { decide(false) },
                    modifier = Modifier.weight(1f).heightIn(min = if (oneHanded) 64.dp else 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = skin.wrongSoft, contentColor = skin.wrong),
                ) { Icon(Icons.Filled.Close, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.swipe_not_supported)) }
                Button(
                    onClick = { decide(true) },
                    modifier = Modifier.weight(1f).heightIn(min = if (oneHanded) 64.dp else 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = skin.correctSoft, contentColor = skin.correct),
                ) { Icon(Icons.Filled.Check, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.swipe_supported)) }
            }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val swipe = item as SwipeItem
        val verdicts = (answer as SwipeAnswer).verdicts
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            swipe.cards.forEach { card ->
                val said = verdicts[card.id]
                ReviewRow(
                    correct = card.id !in grade.wrongPartIds,
                    title = card.statement,
                    detail = stringResource(
                        R.string.swipe_you_said,
                        stringResource(if (said == true) R.string.swipe_supported else R.string.swipe_not_supported),
                    ),
                    explanation = card.why,
                )
            }
        }
    }
}
