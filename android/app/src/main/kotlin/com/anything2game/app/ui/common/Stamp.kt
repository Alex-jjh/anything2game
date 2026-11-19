package com.anything2game.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a stamp says, independent of colour: shape and icon carry the meaning too (colour-blind safe). */
enum class StampKind { YES, NO, PARTLY, CLOSED, COLD }

/**
 * The case-file skin's one device: an ink stamp that lands (scale 1.6 → 1, slight rotation) with one haptic.
 * Round = yes/closed, square = no/cold, rounded-square = partly. [animate] false for screenshots and reduced motion.
 */
@Composable
fun Stamp(text: String, kind: StampKind, color: Color, modifier: Modifier = Modifier, animate: Boolean = true, big: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(if (animate) 1.6f else 1f) }
    val alpha = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) {
            alpha.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
            haptic.performHapticFeedback(if (kind == StampKind.NO || kind == StampKind.COLD) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        }
    }
    val shape = when (kind) { StampKind.YES, StampKind.CLOSED -> CircleShape; StampKind.PARTLY -> RoundedCornerShape(10.dp); else -> RoundedCornerShape(2.dp) }
    val icon = when (kind) { StampKind.YES, StampKind.CLOSED -> Icons.Filled.Check; StampKind.NO, StampKind.COLD -> Icons.Filled.Close; StampKind.PARTLY -> Icons.Filled.PriorityHigh }
    val pad = if (big) 18.dp else 10.dp
    Box(
        modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value; rotationZ = if (kind == StampKind.NO || kind == StampKind.COLD) 4f else -6f }
            .border(if (big) 4.dp else 2.5.dp, color.copy(alpha = 0.9f), shape)
            .padding(3.dp)
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = pad + 6.dp, vertical = pad),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(if (big) 30.dp else 18.dp))
            Text(
                text,
                color = color,
                style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = if (big) 30.sp else 16.sp, letterSpacing = 2.sp),
            )
        }
    }
}
