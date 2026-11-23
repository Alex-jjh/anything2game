package com.anything2game.app.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anything2game.app.R
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.app.ui.play.mechanics.detectPickUpAndDrag
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.game.AllocationConfig
import com.anything2game.contract.game.GameAction
import com.anything2game.contract.game.Zone
import com.anything2game.engine.game.AllocationBoard
import com.anything2game.engine.game.StageStatus
import kotlin.math.roundToInt

/**
 * The allocation game: the player's tokens sit in a tray and are dragged onto one of two zones. Each zone fills an output bar
 * towards its goal, and every move says what it cost. Tap buttons and accessibility actions place a token without dragging.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllocationBoardView(b: AllocationBoard, config: AllocationConfig, last: GameAction?, status: StageStatus, enabled: Boolean,
    onMove: (GameAction) -> Unit, modifier: Modifier = Modifier) {
    val skin = A2g.skin
    val look = config.look()
    val density = LocalDensity.current
    val canDrag = enabled && b.available > 0
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var over by remember { mutableStateOf<Zone?>(null) }
    var board by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tray by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val bounds = remember { mutableStateMapOf<Zone, Rect>() }
    val place by rememberUpdatedState { zone: Zone -> onMove(GameAction.Place(zone)) }
    val slop = with(density) { 10.dp.toPx() }
    fun overAt(root: Offset): Zone? = bounds.entries.firstOrNull { (_, r) -> r.inflate(slop).contains(root) }?.key

    Box(modifier.fillMaxWidth().onGloballyPositioned { board = it }) {
        PixelPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.games_tokens_left, b.available, look.tokens), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = skin.ink)
            }
            FlowRow(Modifier.testTag("token_tray").onGloballyPositioned { tray = it }
                .pointerInput(canDrag) {
                    if (canDrag) detectPickUpAndDrag(afterLongPress = false,
                        onPickUp = { down -> tray?.let { finger = it.localToRoot(down.position); over = overAt(finger) }; dragging = true },
                        onMove = { change -> tray?.let { finger = it.localToRoot(change.position); over = overAt(finger) } },
                        onDrop = { val target = over; dragging = false; over = null; if (target != null) place(target) },
                        onCancel = { dragging = false; over = null })
                }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(config.tokens) { i -> TokenChip(look.tokenIcon, filled = i < b.available, lifted = dragging && i == b.available - 1) }
            }
            val zones = if (b.firstOnLeft) listOf(Zone.FIRST, Zone.SECOND) else listOf(Zone.SECOND, Zone.FIRST)
            val stacked = density.fontScale > 1.3f
            val cards: @Composable (Modifier) -> Unit = { m ->
                zones.forEach { zone ->
                    ZoneCard(zone, config, look, b, status, highlighted = dragging && over == zone, enabled = enabled && b.available > 0,
                        onPlace = { onMove(GameAction.Place(zone)) }, modifier = m.onGloballyPositioned { bounds[zone] = it.boundsInRoot() })
                }
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.weight(1f)) }
            CostLine(last, config, look, b)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton(stringResource(R.string.games_undo), { onMove(GameAction.Undo) }, enabled = enabled && b.placements.isNotEmpty())
                PixelButton(stringResource(R.string.games_deliver), { onMove(GameAction.Submit) }, Modifier.weight(1f).testTag("allocation_submit"),
                    enabled = enabled, primary = true)
            }
        }
        if (dragging) {
            val origin = board?.positionInRoot() ?: Offset.Zero
            val half = with(density) { 18.dp.toPx() }
            Box(Modifier.offset { IntOffset((finger.x - origin.x - half).roundToInt(), (finger.y - origin.y - half * 2.2f).roundToInt()) }
                .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.testTag("dragged_token")) { TokenChip(look.tokenIcon, filled = true, lifted = true) }
        }
    }
}

@Composable
private fun TokenChip(icon: String, filled: Boolean, lifted: Boolean) {
    val skin = A2g.skin
    Box(Modifier.size(36.dp).background(if (filled) skin.evidenceSoft else skin.paper, pixelShape(6.dp))
        .border(2.dp, if (filled) skin.ink else skin.outline.copy(alpha = 0.5f), pixelShape(6.dp)), contentAlignment = Alignment.Center) {
        if (filled) PixelSprite(icon, 26.dp, Modifier.graphicsLayer { translationY = if (lifted) -2.dp.toPx() else 0f })
        else PixelSprite(icon, 26.dp, dim = true, alpha = 0.35f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneCard(zone: Zone, config: AllocationConfig, look: AllocationLook, b: AllocationBoard, status: StageStatus, highlighted: Boolean,
    enabled: Boolean, onPlace: () -> Unit, modifier: Modifier) {
    val skin = A2g.skin
    val first = zone == Zone.FIRST
    val reduced = rememberReducedMotion()
    val yield = if (first) config.firstYield else config.secondYield
    val goal = if (first) config.firstGoal else config.secondGoal
    val output = if (first) b.firstOutput else b.secondOutput
    val placed = b.placements.count { it == zone }
    val reached = output >= goal
    val fill = if (first) skin.evidenceSoft else skin.correctSoft
    val ratio by animateFloatAsState((output / goal.toFloat()).coerceIn(0f, 1f), if (reduced) snap() else tween(500), label = "output")
    val lift by animateFloatAsState(if (highlighted) 1.04f else 1f, if (reduced) snap() else tween(140), label = "zone-lift")
    val placeLabel = stringResource(R.string.games_place_in, look.zone(first))
    val shape = pixelShape(8.dp)
    Column(modifier.graphicsLayer { scaleX = lift; scaleY = lift }
        .semantics { customActions = listOf(CustomAccessibilityAction(placeLabel) { if (enabled) { onPlace(); true } else false }) }
        .background(fill, shape).border(if (highlighted) 4.dp else 2.dp, if (highlighted) skin.action else skin.ink, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelSprite(look.icon(first), 30.dp)
            Text(look.zone(first), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = skin.ink)
        }
        Text(stringResource(R.string.games_yield, look.token, yield, look.good(first, true)), style = MaterialTheme.typography.bodySmall, color = skin.ink)
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(placed) { PixelSprite(look.tokenIcon, 20.dp) }
        }
        OutputBar(ratio, yield, goal, reached, if (first) skin.evidence else skin.correct, look.icon(first))
        Text(stringResource(R.string.games_output, output, goal, look.good(first, true)), color = skin.ink, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("output_${zone.name}"))
        if (status == StageStatus.LOST && !reached) Text(stringResource(R.string.games_short, goal - output, look.good(first, true)), color = skin.wrong,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        PixelButton(stringResource(R.string.games_place), onPlace, Modifier.fillMaxWidth().testTag("place_${zone.name}"), enabled = enabled)
    }
}

/** A segmented bar: one notch per token's worth of output, a flag at the goal. */
@Composable
private fun OutputBar(ratio: Float, yield: Int, goal: Int, reached: Boolean, colour: androidx.compose.ui.graphics.Color, icon: String) {
    val skin = A2g.skin
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val h = size.height; val radius = 3.dp.toPx()
            drawRect(skin.card, Offset.Zero, size)
            drawRect(if (reached) skin.correct else colour, Offset.Zero, Size(size.width * ratio, h))
            var mark = yield
            while (mark < goal) { val x = size.width * mark / goal; drawRect(skin.ink.copy(alpha = 0.35f), Offset(x - 1f, 0f), Size(2f, h)); mark += yield }
            drawRect(skin.ink, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.7f))
        }
        PixelSprite(if (reached) Sprites.star else Sprites.of(icon), 24.dp, dim = false)
    }
}

/** "One more cup costs 1.5 bowls": the rate between the zones, taken from the rules, shown after a token is placed. */
@Composable
private fun CostLine(last: GameAction?, config: AllocationConfig, look: AllocationLook, b: AllocationBoard) {
    val skin = A2g.skin
    if (last is GameAction.Place) {
        val first = last.zone == Zone.FIRST
        val gain = if (first) config.firstYield else config.secondYield
        val give = if (first) config.secondYield else config.firstYield
        val ratio = give.toDouble() / gain
        val text = stringResource(R.string.games_tradeoff, look.good(first, false), ratioText(ratio), look.good(!first, ratioText(ratio) != "1"))
        Text(text, Modifier.fillMaxWidth().background(skin.hintSoft, pixelShape(5.dp)).border(1.5.dp, skin.hint, pixelShape(5.dp)).padding(10.dp)
            .testTag("cost_line"), color = skin.ink, fontWeight = FontWeight.Bold)
    } else if (b.available == config.tokens) {
        Text(stringResource(R.string.games_drag_hint), color = skin.inkMuted, style = MaterialTheme.typography.bodyMedium)
    }
}
