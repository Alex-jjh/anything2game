package com.anything2game.app.ui.space

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.character.LocalAvatar
import com.anything2game.app.ui.character.Mood
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.Skin
import com.anything2game.app.ui.theme.castColor
import com.anything2game.contract.CastRole
import kotlin.math.sin

/** Each unit gets its own accent from the skin's role colours, so the map is not one colour. Banner text is white on all of them. */
@Composable
internal fun sectionAccent(index: Int): Color {
    val skin = A2g.skin
    return listOf(skin.action, skin.evidence, skin.hint, skin.correct, skin.paused, skin.castColor(CastRole.COMPANION))[index % 6]
}

private val NodeSize = 72.dp
private val CurrentSize = 84.dp
private val Depth = 6.dp
private val Amplitude = 54.dp

@Composable
internal fun StepKind.label() = stringResource(when (this) {
    StepKind.EXPLAIN -> R.string.route_kind_explain
    StepKind.CLASSIC -> R.string.route_kind_classic
    StepKind.CASE -> R.string.route_kind_case
    StepKind.ROOKIE -> R.string.route_kind_rookie
    StepKind.ESCAPE -> R.string.route_kind_escape
    StepKind.GAME -> R.string.route_kind_game
})

@Composable
internal fun StepState.label() = stringResource(when (this) {
    StepState.DONE -> R.string.route_state_done
    StepState.REVIEW -> R.string.route_state_review
    StepState.CURRENT -> R.string.route_state_current
    StepState.AVAILABLE -> R.string.route_state_available
    StepState.LOCKED -> R.string.route_state_locked
    StepState.UPDATE -> R.string.route_state_update
})

internal fun StepKind.icon(): ImageVector = when (this) {
    StepKind.EXPLAIN -> Icons.AutoMirrored.Filled.MenuBook
    StepKind.CLASSIC -> Icons.Filled.Checklist
    StepKind.CASE -> Icons.Filled.Search
    StepKind.ROOKIE -> Icons.Filled.School
    StepKind.ESCAPE -> Icons.Filled.MeetingRoom
    StepKind.GAME -> Icons.Filled.SportsEsports
}

private fun Color.darker(by: Float = 0.28f) = lerp(this, Color.Black, by)

/** A section banner plus its winding path. [firstIndex] continues the zig-zag from the previous section. */
@Composable
internal fun SectionPath(section: RouteSection, firstIndex: Int, onStep: (RouteStep) -> Unit) {
    val accent = sectionAccent(section.number - 1)
    Column(Modifier.fillMaxWidth().testTag("route_section_${section.number}")) {
        SectionBanner(section, accent)
        StepPath(section, firstIndex, accent, onStep)
    }
}

@Composable
private fun SectionBanner(section: RouteSection, accent: Color) {
    val skin = A2g.skin
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = section.title
            val header = if (section.material != null) stringResource(R.string.route_unit_in, section.number, section.material) else stringResource(R.string.route_unit, section.number)
            Text(header, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (section.fixture) Text(stringResource(R.string.route_fixture_badge), Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))
                .padding(horizontal = 10.dp, vertical = 2.dp).testTag("route_fixture_${section.number}"), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Text(stringResource(R.string.route_section_progress, section.done, section.steps.size), Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("route_section_progress_${section.number}"),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun StepPath(section: RouteSection, firstIndex: Int, accent: Color, onStep: (RouteStep) -> Unit) {
    val skin = A2g.skin
    val centres = remember(section.steps.size) { mutableStateMapOf<Int, Offset>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lockedLine = skin.outline
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp)
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val width = 8.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 12.dp.toPx()))
            for (i in 0 until section.steps.size - 1) {
                val a = centres[i]?.minus(origin) ?: continue
                val b = centres[i + 1]?.minus(origin) ?: continue
                val mid = (a.y + b.y) / 2f
                val curve = Path().apply { moveTo(a.x, a.y); cubicTo(a.x, mid, b.x, mid, b.x, b.y) }
                val travelled = section.steps[i].state == StepState.DONE || section.steps[i].state == StepState.REVIEW
                val ahead = section.steps[i].state == StepState.CURRENT || section.steps[i].state == StepState.AVAILABLE
                when {
                    travelled && section.steps[i + 1].state != StepState.LOCKED -> drawPath(curve, accent, style = Stroke(width, cap = StrokeCap.Round))
                    ahead -> drawPath(curve, accent.copy(alpha = 0.5f), style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash))
                    else -> drawPath(curve, lockedLine.copy(alpha = 0.45f), style = Stroke(width, cap = StrokeCap.Round, pathEffect = dash))
                }
            }
        }, verticalArrangement = Arrangement.spacedBy(26.dp)) {
        section.steps.forEachIndexed { i, step ->
            val wave = sin((firstIndex + i) * 0.95).toFloat()
            StepRow(step, accent, Amplitude * wave, onClick = { onStep(step) }) { centres[i] = it }
        }
    }
}

@Composable
private fun StepRow(step: RouteStep, accent: Color, offset: Dp, onClick: () -> Unit, centre: (Offset) -> Unit) {
    val skin = A2g.skin
    val current = step.state == StepState.CURRENT
    val size = if (current) CurrentSize else NodeSize
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.offset(x = offset), horizontalAlignment = Alignment.CenterHorizontally) {
            if (current) {
                StartBubble(stringResource(R.string.route_start), accent)
                Spacer(Modifier.height(6.dp))
            }
            StepNode(step, accent, size, avatarOnLeft = offset > (-8).dp, onClick, centre)
            Spacer(Modifier.height(Depth + 6.dp))
            Text(step.title, Modifier.widthIn(max = 176.dp).clip(RoundedCornerShape(12.dp)).background(skin.paper).padding(horizontal = 8.dp, vertical = 3.dp).testTag("route_label_${step.id}"), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (step.state == StepState.LOCKED || step.state == StepState.UPDATE) skin.inkMuted else skin.ink)
        }
    }
}

@Composable
private fun StartBubble(text: String, accent: Color) {
    val skin = A2g.skin
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, Modifier.clip(RoundedCornerShape(14.dp)).background(skin.card).border(2.dp, accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp).testTag("route_start_bubble"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
        Canvas(Modifier.size(16.dp, 8.dp)) {
            val tip = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }
            drawPath(tip, accent)
        }
    }
}

@Composable
private fun StepNode(step: RouteStep, accent: Color, size: Dp, avatarOnLeft: Boolean, onClick: () -> Unit, centre: (Offset) -> Unit) {
    val skin = A2g.skin
    val state = step.state
    val reduced = rememberReducedMotion()
    val grey = skin.outline.copy(alpha = 0.28f)
    val (fill, face, edge, rim) = when (state) {
        StepState.DONE, StepState.REVIEW, StepState.CURRENT -> NodeLook(accent, Color.White, accent.darker(), null)
        StepState.AVAILABLE -> NodeLook(skin.card, accent, accent.copy(alpha = 0.55f), accent)
        StepState.LOCKED, StepState.UPDATE -> NodeLook(grey.compositeOver(skin.paper), skin.inkMuted, skin.outline.copy(alpha = 0.45f), null)
    }
    val description = stringResource(R.string.route_step_description, step.number, step.title, step.kind.label(), state.label())
    Box(Modifier.size(size)
        .onGloballyPositioned { centre(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
        .drawBehind { drawCircle(edge, radius = this.size.minDimension / 2f, center = Offset(this.size.width / 2f, this.size.height / 2f + Depth.toPx())) },
        contentAlignment = Alignment.Center) {
        if (state == StepState.CURRENT && !reduced) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(1f, 1.45f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "scale")
            Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale; alpha = (1.45f - scale) * 0.9f }.background(accent.copy(alpha = 0.5f), CircleShape))
        } else if (state == StepState.CURRENT) {
            Box(Modifier.size(size + 14.dp).background(accent.copy(alpha = 0.18f), CircleShape))
        }
        Box(Modifier.size(size).clip(CircleShape).background(fill).then(if (rim != null) Modifier.border(4.dp, rim, CircleShape) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }.testTag("route_node_${step.id}")
            .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            val icon = when (state) {
                StepState.DONE, StepState.REVIEW -> Icons.Filled.Check
                StepState.LOCKED -> Icons.Filled.Lock
                StepState.UPDATE -> Icons.Filled.CloudDownload
                else -> step.kind.icon()
            }
            Icon(icon, null, Modifier.size(if (state == StepState.CURRENT) 40.dp else 34.dp), tint = face)
        }
        if (state == StepState.REVIEW) Icon(Icons.Filled.Refresh, null, Modifier.align(Alignment.TopEnd).size(24.dp).background(skin.card, CircleShape).border(2.dp, accent, CircleShape).padding(3.dp), tint = accent)
        if (state == StepState.CURRENT) {
            val avatar = LocalAvatar.current
            Box(Modifier.requiredSize(76.dp).offset(x = if (avatarOnLeft) -(size / 2 + 42.dp) else size / 2 + 42.dp).align(Alignment.Center).testTag("route_avatar")) {
                avatar.Draw(Mood.IDLE, Modifier.fillMaxSize(), pokeable = false)
            }
        }
    }
}

private data class NodeLook(val fill: Color, val face: Color, val edge: Color, val rim: Color?)
