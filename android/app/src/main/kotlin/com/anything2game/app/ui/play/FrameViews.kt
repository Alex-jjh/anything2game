package com.anything2game.app.ui.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.castColor
import com.anything2game.contract.Board
import com.anything2game.contract.CastMember
import com.anything2game.contract.FrameId
import com.anything2game.contract.Line
import com.anything2game.contract.Room
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A frame scene: lines of dialogue (or a room's description). Lines that teach carry an evidence chip. */
@Composable
internal fun SceneContent(lines: List<Line>, cast: List<CastMember>, room: Room?, onEvidence: (List<String>) -> Unit, onContinue: () -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (room != null) {
            Card(colors = CardDefaults.cardColors(containerColor = skin.evidenceSoft)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.MeetingRoom, null, tint = skin.evidence, modifier = Modifier.size(40.dp))
                    Column {
                        Text(stringResource(R.string.scene_room), style = MaterialTheme.typography.labelMedium, color = skin.evidence)
                        Text(room.title, style = MaterialTheme.typography.headlineSmall, color = skin.ink)
                    }
                }
            }
        }
        lines.forEach { line ->
            val member = cast.firstOrNull { it.id == line.speaker }
            val color = member?.let { skin.castColor(it.role) } ?: skin.inkMuted
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(member?.name?.trim('「', '」')?.take(1).orEmpty(), color = skin.onAction, fontWeight = FontWeight.Bold)
                }
                Card(
                    Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = skin.card),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        member?.let { Text(it.name, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold) }
                        Text(line.text, style = MaterialTheme.typography.bodyLarge, color = skin.ink)
                        if (line.sourceRefs.isNotEmpty()) {
                            AssistChip(
                                onClick = { onEvidence(line.sourceRefs.map { it.chunkId }.distinct()) },
                                label = { Text(stringResource(R.string.action_show_evidence)) },
                                leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = skin.evidence, leadingIconContentColor = skin.evidence),
                                border = BorderStroke(1.dp, skin.evidence),
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_continue)) }
    }
}

/** Credibility as pips: what is at stake in this level, at a glance. */
@Composable
internal fun CredibilityPips(left: Int, max: Int) {
    val skin = A2g.skin
    val label = stringResource(R.string.credibility_left, left)
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(max) { i ->
            Icon(
                if (i < left) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (i == 0) label else null,
                tint = if (i < left) skin.wrong else skin.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The evidence board: one pin per clue, unsolved pins stay silhouettes; edges appear once both ends are solved.
 * A mind map the player earns (docs/product/game-design.md §6.3).
 */
@Composable
internal fun EvidenceBoard(board: Board, solvedUnitIds: Set<String>, modifier: Modifier = Modifier) {
    val skin = A2g.skin
    val solvedNodes = board.nodes.filter { it.unitId in solvedUnitIds }.map { it.id }.toSet()
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = skin.evidenceSoft)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.board_title), style = MaterialTheme.typography.titleMedium, color = skin.ink)
            BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp)) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.toPx() }
                val h = with(density) { maxHeight.toPx() }
                val nodeW = with(density) { 120.dp.toPx() }
                val nodeH = with(density) { 56.dp.toPx() }
                val n = board.nodes.size.coerceAtLeast(1)
                val r = min(w - nodeW, h - nodeH) / 2f
                val centers = board.nodes.mapIndexed { i, node ->
                    val a = -Math.PI / 2 + 2 * Math.PI * i / n
                    node.id to Offset((w / 2 + r * cos(a)).toFloat(), (h / 2 + r * sin(a)).toFloat())
                }.toMap()
                Canvas(Modifier.matchParentSize()) {
                    board.edges.forEach { e ->
                        val a = centers[e.from] ?: return@forEach
                        val b = centers[e.to] ?: return@forEach
                        val shown = e.from in solvedNodes && e.to in solvedNodes
                        drawLine(
                            color = if (shown) skin.wrong else skin.outline,
                            start = a, end = b, strokeWidth = if (shown) 5f else 3f,
                            pathEffect = if (shown) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                        )
                    }
                }
                board.edges.forEach { e ->
                    val a = centers[e.from] ?: return@forEach
                    val b = centers[e.to] ?: return@forEach
                    if (e.from in solvedNodes && e.to in solvedNodes) {
                        val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                        Text(
                            e.label, style = MaterialTheme.typography.labelSmall, color = skin.wrong,
                            modifier = Modifier.offset { IntOffset((mid.x - 40.dp.toPx()).roundToInt(), (mid.y - 10.dp.toPx()).roundToInt()) }
                                .width(80.dp).background(skin.evidenceSoft, RoundedCornerShape(4.dp)),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                board.nodes.forEach { node ->
                    val c = centers.getValue(node.id)
                    val solved = node.id in solvedNodes
                    Card(
                        Modifier.offset { IntOffset((c.x - nodeW / 2).roundToInt(), (c.y - nodeH / 2).roundToInt()) }.width(120.dp).heightIn(min = 56.dp),
                        colors = CardDefaults.cardColors(containerColor = if (solved) skin.card else skin.paper),
                        border = BorderStroke(1.dp, if (solved) skin.evidence else skin.outline),
                        elevation = CardDefaults.cardElevation(if (solved) 3.dp else 0.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (solved) node.label else "? " + stringResource(R.string.board_locked),
                                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                color = if (solved) skin.ink else skin.inkMuted,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(0.dp))
            }
        }
    }
}

/** Shown when credibility runs out. Failure is part of the game, not a dead end. */
@Composable
internal fun FailedContent(frameId: FrameId, onReplay: () -> Unit, onLibrary: () -> Unit) {
    val skin = A2g.skin
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(when (frameId) { FrameId.CASE -> R.string.failed_title_case; FrameId.ESCAPE -> R.string.failed_title_escape; else -> R.string.failed_title_default }),
            style = MaterialTheme.typography.headlineSmall, color = skin.wrong,
        )
        Text(stringResource(R.string.failed_body), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onLibrary, Modifier.weight(1f)) { Text(stringResource(R.string.recap_library)) }
            Button(onClick = onReplay, Modifier.weight(1f)) { Text(stringResource(R.string.recap_replay)) }
        }
    }
}

internal fun recapTitle(frameId: FrameId) = when (frameId) {
    FrameId.CLASSIC -> R.string.recap_title_classic
    FrameId.CASE -> R.string.recap_title
    FrameId.ESCAPE -> R.string.recap_title_escape
    FrameId.ROOKIE -> R.string.recap_title_rookie
    FrameId.COMPANION -> R.string.recap_title_companion
}

internal fun bossLabel(frameId: FrameId) = when (frameId) {
    FrameId.CASE -> R.string.boss_case
    FrameId.ESCAPE -> R.string.boss_escape
    FrameId.ROOKIE -> R.string.boss_rookie
    else -> null
}

internal fun frameName(frameId: FrameId) = when (frameId) {
    FrameId.CLASSIC -> R.string.frame_classic
    FrameId.CASE -> R.string.frame_case
    FrameId.ESCAPE -> R.string.frame_escape
    FrameId.ROOKIE -> R.string.frame_rookie
    FrameId.COMPANION -> R.string.frame_companion
}

internal fun frameDescription(frameId: FrameId) = when (frameId) {
    FrameId.CLASSIC -> R.string.frame_classic_desc
    FrameId.CASE -> R.string.frame_case_desc
    FrameId.ESCAPE -> R.string.frame_escape_desc
    FrameId.ROOKIE -> R.string.frame_rookie_desc
    FrameId.COMPANION -> R.string.frame_companion_desc
}
