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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
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

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.swipe_instruction), color = skin.inkMuted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(0.4f))
            val offset = remember(card.id) { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val threshold = with(LocalDensity.current) { 110.dp.toPx() }
            val lean = (offset.value / threshold).coerceIn(-1f, 1f)
            var crossed by remember(card.id) { mutableStateOf(false) }
            // Card stack: the next card peeks out behind the current one.
            Box(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                swipe.cards.getOrNull(index + 1)?.let {
                    Card(
                        Modifier.fillMaxWidth().heightIn(min = 280.dp).graphicsLayer { scaleX = 0.93f; scaleY = 0.93f; translationY = 34f },
                        colors = CardDefaults.cardColors(containerColor = skin.card.copy(alpha = 0.85f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, skin.outline.copy(alpha = 0.5f)),
                    ) {}
                }
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
                                // Threshold haptic when the drag crosses the commit point (once per crossing).
                                val past = abs(offset.value) > threshold
                                if (past != crossed) { crossed = past; if (past) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
                            }
                        },
                ) {
                    Card(
                        Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        colors = CardDefaults.cardColors(containerColor = skin.card),
                        border = androidx.compose.foundation.BorderStroke(1.dp, skin.outline.copy(alpha = 0.6f)),
                        elevation = CardDefaults.cardElevation(6.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.swipe_card_count, index + 1, swipe.cards.size), color = skin.inkMuted, style = MaterialTheme.typography.labelMedium)
                            Text(card.statement, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp), color = skin.ink)
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                    if (lean != 0f) {
                        val supported = lean > 0
                        com.anything2game.app.ui.common.Stamp(
                            stringResource(if (supported) R.string.stamp_supported else R.string.stamp_not_supported),
                            if (supported) com.anything2game.app.ui.common.StampKind.YES else com.anything2game.app.ui.common.StampKind.NO,
                            if (supported) skin.correct else skin.wrong,
                            animate = false,
                            modifier = Modifier.align(if (supported) Alignment.BottomStart else Alignment.BottomEnd).padding(20.dp).graphicsLayer { alpha = min(1f, abs(lean)) },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Buttons do the same as swiping: accessible, one-handed, in the thumb zone.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { decide(false) },
                    modifier = Modifier.weight(1f).heightIn(min = if (oneHanded) 68.dp else 60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = skin.wrongSoft, contentColor = skin.wrong),
                ) { Icon(Icons.Filled.Close, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.swipe_not_supported)) }
                Button(
                    onClick = { decide(true) },
                    modifier = Modifier.weight(1f).heightIn(min = if (oneHanded) 68.dp else 60.dp),
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
