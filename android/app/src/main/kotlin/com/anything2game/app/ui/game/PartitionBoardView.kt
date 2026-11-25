package com.anything2game.app.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anything2game.app.R
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.GameAction
import com.anything2game.contract.game.PartitionConfig
import com.anything2game.engine.game.PartitionBoard
import com.anything2game.engine.game.StageStatus
import kotlin.math.ceil

private enum class TileState { OUT, OPEN, LEFT, RIGHT, FOUND }

/**
 * The partition game: a row of themed tiles, a cut that is chosen by tapping or sliding across the row, and a budget of hearts.
 * What a question ruled out fades, greys and sinks; the survivors stay lit; the last one pops when it is found.
 */
@Composable
internal fun PartitionBoardView(b: PartitionBoard, config: PartitionConfig, status: StageStatus, enabled: Boolean, onAsk: (GameAction) -> Unit,
    modifier: Modifier = Modifier) {
    val skin = A2g.skin
    val look = config.look()
    val reduced = rememberReducedMotion()
    var choice by rememberSaveable(b.low, b.high) { mutableStateOf<Int?>(null) }
    val split = choice ?: ((b.low + b.high) / 2)
    val found = b.low == b.high && status == StageStatus.WON
    val queriesLeft = stringResource(R.string.games_queries_left, b.queriesLeft, look.query)
    PixelPanel(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            HeartsRow(config.queryBudget, b.queriesLeft, description = queriesLeft)
            Text(queriesLeft, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = skin.ink)
        }
        if (!found) Text(look.whereIs(b.low, b.high) ?: stringResource(R.string.games_where, b.low, b.high), Modifier.testTag("partition_where"),
            style = MaterialTheme.typography.titleSmall, color = skin.ink)
        TileGrid(b, config, look, split, choice, enabled, found, reduced) { choice = it }
        if (b.eliminated > 0 && !found) Text(stringResource(R.string.games_eliminated, b.eliminated, look.items), color = skin.correct,
            fontWeight = FontWeight.Bold)
        if (found) Text(stringResource(R.string.games_found, look.target, b.low), color = skin.correct, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("partition_found"))
        else if (b.low < b.high) {
            if (enabled && choice == null) Text(stringResource(R.string.games_choose_cut), color = skin.inkMuted)
            choice?.let { Text(stringResource(R.string.games_groups, it - b.low + 1, b.high - it), color = skin.ink) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val left = stringResource(R.string.games_less); val right = stringResource(R.string.games_more)
                PixelButton("−", { choice = (split - 1).coerceAtLeast(b.low) }, Modifier.sizeIn(minWidth = 48.dp).semantics { contentDescription = left },
                    enabled = enabled && (choice == null || split > b.low))
                Text(if (choice == null) "" else stringResource(R.string.games_pick, split), Modifier.weight(1f).testTag("partition_pick"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
                PixelButton("+", { choice = (split + 1).coerceAtMost(b.high - 1) }, Modifier.sizeIn(minWidth = 48.dp).semantics { contentDescription = right },
                    enabled = enabled && (choice == null || split < b.high - 1))
            }
            PixelButton(if (choice == null) stringResource(R.string.games_pick_cut) else look.askAbout(split), { onAsk(GameAction.Split(split)) },
                Modifier.fillMaxWidth().testTag("split_submit"), enabled = enabled && choice != null, primary = true)
        }
    }
}

@Composable
private fun TileGrid(b: PartitionBoard, config: PartitionConfig, look: PartitionLook, split: Int, choice: Int?, enabled: Boolean, found: Boolean,
    reduced: Boolean, onChoose: (Int) -> Unit) {
    val n = config.candidates
    val density = LocalDensity.current
    val maxCols = if (density.fontScale > 1.3f) 4 else 8
    val rows = ceil(n / maxCols.toFloat()).toInt()
    val cols = ceil(n / rows.toFloat()).toInt()
    val gap = 5.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - gap * (cols - 1)) / cols
        val cellPx = with(density) { cell.toPx() }; val gapPx = with(density) { gap.toPx() }
        fun pick(at: Offset) {
            val col = (at.x / (cellPx + gapPx)).toInt().coerceIn(0, cols - 1)
            val row = (at.y / (cellPx + gapPx)).toInt().coerceIn(0, rows - 1)
            onChoose((row * cols + col + 1).coerceIn(b.low, b.high - 1))
        }
        Column(Modifier.testTag("tile_grid").pointerInput(b.low, b.high, enabled, cols, rows) {
            if (enabled && b.low < b.high) detectHorizontalDragGestures(onDragStart = { pick(it) }, onHorizontalDrag = { change, _ -> pick(change.position) })
        }, verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until rows) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (c in 0 until cols) {
                    val i = r * cols + c + 1
                    if (i > n) { Box(Modifier.size(cell)); continue }
                    val active = i in b.low..b.high
                    val state = when {
                        !active -> TileState.OUT
                        found -> TileState.FOUND
                        choice == null -> TileState.OPEN
                        i <= split -> TileState.LEFT
                        else -> TileState.RIGHT
                    }
                    val cutLabel = stringResource(R.string.games_cut_after, i, look.item)
                    val group = stringResource(when (state) {
                        TileState.OUT -> R.string.games_door_out; TileState.FOUND -> R.string.games_door_found; TileState.OPEN -> R.string.games_door_in
                        TileState.LEFT -> R.string.games_door_left; TileState.RIGHT -> R.string.games_door_right })
                    Tile(i, cell, state, cut = choice != null && i == split && active && !found, icon = look.icon, reduced = reduced,
                        modifier = Modifier.testTag("cut_$i").semantics { stateDescription = group },
                        onClick = if (enabled && i in b.low until b.high) ({ onChoose(i) }) else null, clickLabel = cutLabel)
                }
            }
        }
    }
}

@Composable
private fun Tile(n: Int, size: androidx.compose.ui.unit.Dp, state: TileState, cut: Boolean, icon: String, reduced: Boolean, modifier: Modifier,
    onClick: (() -> Unit)?, clickLabel: String) {
    val skin = A2g.skin
    val out by animateFloatAsState(if (state == TileState.OUT) 1f else 0f,
        if (reduced) snap() else tween(520, easing = FastOutSlowInEasing), label = "tile-out")
    val pop = remember { Animatable(if (reduced || state != TileState.FOUND) 1f else 0.7f) }
    LaunchedEffect(state == TileState.FOUND) {
        if (state == TileState.FOUND && !reduced) pop.animateTo(1.14f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val fill = when (state) {
        TileState.OUT -> skin.paper; TileState.OPEN -> skin.evidenceSoft; TileState.LEFT -> skin.action
        TileState.RIGHT -> skin.card; TileState.FOUND -> Color(0xFFFFE9A6)
    }
    val ink = when (state) { TileState.LEFT -> skin.onAction; TileState.OUT -> skin.inkMuted; else -> skin.ink }
    val edge = when (state) {
        TileState.OUT -> skin.outline.copy(alpha = 0.5f); TileState.RIGHT -> skin.evidence; TileState.FOUND -> Color(0xFFB9861A); else -> skin.ink
    }
    val shape = pixelShape(4.dp)
    val bar = skin.evidence
    Box(modifier.size(size)
        .graphicsLayer {
            alpha = 1f - 0.62f * out
            translationY = out * 6.dp.toPx()
            val s = (1f - 0.16f * out) * pop.value
            scaleX = s; scaleY = s
        }
        .background(fill, shape).border(BorderStroke(if (state == TileState.FOUND) 3.dp else 1.5.dp, edge), shape)
        .drawBehind { if (cut) drawRect(bar, Offset(this.size.width - 4.dp.toPx(), 3.dp.toPx()), Size(4.dp.toPx(), this.size.height - 6.dp.toPx())) }
        .let { if (onClick != null) it.clickable(onClickLabel = clickLabel, onClick = onClick) else it },
        contentAlignment = Alignment.Center) {
        if (icon == "number") {
            Text(n.toString(), color = ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            PixelSprite(icon, size * 0.5f, dim = state == TileState.OUT)
            Text(n.toString(), color = ink, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp)
        }
        if (state == TileState.FOUND) {
            PixelSprite(Sprites.sparkle, size * 0.32f, Modifier.align(Alignment.TopEnd).offset(size * 0.1f, -size * 0.1f))
            PixelSprite(Sprites.sparkle, size * 0.24f, Modifier.align(Alignment.BottomStart).offset(-size * 0.08f, size * 0.08f))
        }
    }
}
