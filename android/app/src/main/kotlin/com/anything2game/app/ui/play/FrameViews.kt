package com.anything2game.app.ui.play

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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

/**
 * A frame scene in visual-novel layout: the speaking character on stage (top), the current line in a dialogue panel
 * docked at the bottom (thumb zone). Tap anywhere to advance one line; Skip jumps to the end. Lines that teach carry
 * an evidence chip. [key] resets the line index per scene.
 */
@Composable
internal fun SceneStage(
    key: String, lines: List<Line>, cast: List<CastMember>, room: Room?,
    onEvidence: (List<String>) -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier,
    player: (@Composable (Modifier) -> Unit)? = null,
) {
    val skin = A2g.skin
    var index by androidx.compose.runtime.saveable.rememberSaveable(key) { androidx.compose.runtime.mutableIntStateOf(0) }
    val line = lines.getOrNull(index) ?: lines.lastOrNull()
    val speaker = line?.let { l -> cast.firstOrNull { it.id == l.speaker } }
    val last = index >= lines.lastIndex
    fun advance() { if (last) onContinue() else index++ }
    Column(
        modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { advance() },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        // Stage: everyone in the scene; the speaker is large and lit, the others small and dimmed.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (player != null) 10.dp else 20.dp), verticalAlignment = Alignment.Bottom) {
                // The player's own character stands on stage, facing whoever speaks.
                player?.invoke(Modifier.size(104.dp))
                cast.filter { m -> lines.any { it.speaker == m.id } }.forEach { m ->
                    val speaking = m.id == speaker?.id
                    CastPortrait(m, size = if (speaking) (if (player != null) 120.dp else 132.dp) else 72.dp, dim = !speaking)
                }
            }
        }
        // Dialogue panel, docked at the bottom.
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = skin.card),
            border = BorderStroke(1.5.dp, speaker?.let { skin.castColor(it.role) } ?: skin.outline),
            elevation = CardDefaults.cardElevation(3.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                speaker?.let { Text(it.name, style = MaterialTheme.typography.titleMedium, color = skin.castColor(it.role), fontWeight = FontWeight.Bold) }
                Text(line?.text.orEmpty(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(19f, androidx.compose.ui.unit.TextUnitType.Sp)), color = skin.ink)
                if (!line?.sourceRefs.isNullOrEmpty()) {
                    AssistChip(
                        onClick = { onEvidence(line!!.sourceRefs.map { it.chunkId }.distinct()) },
                        label = { Text(stringResource(R.string.action_show_evidence)) },
                        leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = skin.evidence, leadingIconContentColor = skin.evidence),
                        border = BorderStroke(1.dp, skin.evidence),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // progress dots: one per line
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                        lines.indices.forEach { i -> Box(Modifier.size(7.dp).background(if (i <= index) skin.action else skin.outline.copy(alpha = 0.4f), CircleShape)) }
                    }
                    if (!last) androidx.compose.material3.TextButton(onClick = onContinue) { Text(stringResource(R.string.scene_skip), color = skin.inkMuted) }
                    Text(stringResource(if (last) R.string.action_continue else R.string.scene_tap) + "  ▸", style = MaterialTheme.typography.labelLarge, color = skin.action, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A cast member as a portrait placeholder: role colour ring + initial on paper (art comes from the team). */
@Composable
internal fun CastPortrait(m: CastMember, size: androidx.compose.ui.unit.Dp, dim: Boolean) {
    val skin = A2g.skin
    val c = skin.castColor(m.role)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = if (dim) 0.45f else 1f }) {
        Box(
            Modifier.size(size).background(c.copy(alpha = 0.12f), CircleShape).border(3.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(m.name.trim('「', '」').take(1), color = c, fontWeight = FontWeight.Black,
                fontSize = androidx.compose.ui.unit.TextUnit(size.value * 0.42f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = skin.headingFont)
        }
        Spacer(Modifier.size(6.dp))
        Text(m.name, style = MaterialTheme.typography.labelLarge, color = skin.ink, maxLines = 1, softWrap = false)
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
            stringResource(frameId.presentation().failedTitle),
            style = MaterialTheme.typography.headlineSmall, color = skin.wrong,
        )
        Text(stringResource(R.string.failed_body), style = MaterialTheme.typography.bodyLarge, color = skin.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = onLibrary, Modifier.weight(1f)) { Text(stringResource(R.string.recap_library)) }
            Button(onClick = onReplay, Modifier.weight(1f)) { Text(stringResource(R.string.recap_replay)) }
        }
    }
}

/**
 * Frames ordered by the reader's state, not by build order: quick review → check what you know → teach it →
 * structure → first pass. The items are the same in every frame; what differs is what you do with them (frames.md).
 */
internal val framesByIntent = listOf(FrameId.CLASSIC, FrameId.CASE, FrameId.ROOKIE, FrameId.ESCAPE, FrameId.COMPANION)

/**
 * Which frames Practice offers for a course (docs/product/frames.md §4): only generated ones; Companion is folded
 * into the Learn tab (the tutor does "first pass" better); Escape only when the reading has real process content
 * to put in order, otherwise its rooms are Classic with walls. Open sessions in any frame still resume.
 */
internal fun offeredFrames(pkg: com.anything2game.contract.LearningPackage, available: Collection<FrameId>): List<FrameId> {
    val units = pkg.levels.flatMap { it.units }
    val process = units.count { it.knowledgeType == com.anything2game.contract.KnowledgeType.PROCESS || it.mechanic == com.anything2game.contract.Mechanic.ORDER }
    val processHeavy = process >= 2 || (units.isNotEmpty() && process * 4 >= units.size)
    return framesByIntent.filter { it in available && it != FrameId.COMPANION && (it != FrameId.ESCAPE || processHeavy) }
}
