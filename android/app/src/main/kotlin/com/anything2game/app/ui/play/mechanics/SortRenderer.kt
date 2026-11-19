package com.anything2game.app.ui.play.mechanics

import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.anything2game.app.ui.character.rememberReducedMotion
import com.anything2game.contract.SortCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.anything2game.contract.SortItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import com.anything2game.engine.grading.SortAnswer

/**
 * Three ways to file a card, all ending in the same placement:
 *  - press and hold a card, drag it over a bin, let go (the native feel: it lifts, bins light up under the finger, a tick on
 *    entering each one, a springy return if dropped on nothing);
 *  - tap a card, then tap a bin (one-handed, switch access);
 *  - TalkBack custom actions "Put in <bin>" on every card.
 * Filed cards move the same way: to another bin, or back to the pool.
 */
object SortRenderer : MechanicRenderer {
    override val mechanic = Mechanic.SORT

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val oneHanded = ctx.oneHanded
        val sort = item as SortItem
        val skin = A2g.skin
        val haptic = LocalHapticFeedback.current
        val reduced = rememberReducedMotion()
        // Cards are shown in a stable shuffled order so the answer isn't the listing order.
        val order = remember(key) { sort.cards.shuffled(java.util.Random(key.hashCode().toLong())) }
        var placements by rememberSaveable(key) { mutableStateOf(emptyList<String>()) } // "cardId:binId"
        var selected by rememberSaveable(key) { mutableStateOf<String?>(null) }
        val placed = placements.associate { it.substringBefore(':') to it.substringAfter(':') }
        val unplaced = order.filter { it.id !in placed }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val drag = remember(key) { SortDrag(scope, haptic, reduced) }
        var boxOrigin by remember { mutableStateOf(Offset.Zero) }

        /** The one place a card changes bin: null = back to the pool. */
        fun file(cardId: String, binId: String?) {
            placements = placements.filterNot { it.startsWith("$cardId:") } + listOfNotNull(binId?.let { "$cardId:$it" })
            selected = null
        }
        val putIn = sort.bins.associate { it.id to stringResource(R.string.sort_put_in, it.label) }
        val takeBack = stringResource(R.string.sort_take_back)
        fun actionsFor(card: SortCard): List<CustomAccessibilityAction> =
            sort.bins.filter { it.id != placed[card.id] }.map { bin ->
                CustomAccessibilityAction(putIn.getValue(bin.id)) { file(card.id, bin.id); haptic.performHapticFeedback(HapticFeedbackType.Confirm); true }
            } + listOfNotNull(placed[card.id]?.let { CustomAccessibilityAction(takeBack) { file(card.id, null); true } })
        val onDrop: (String, String?) -> Unit = { cardId, zone -> file(cardId, zone.takeIf { it != POOL }) }

        AutoScrollWhileDragging(active = drag.held, fingerY = { drag.finger.y }, viewport = { drag.viewport }, scroll = scroll, onScrolled = drag::updateHover)

        Box(Modifier.fillMaxSize().onGloballyPositioned { boxOrigin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).onGloballyPositioned { drag.viewport = it.boundsInRoot() }.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            Text(sort.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.sort_instruction), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            // The pool is also a drop zone (take a filed card back), so it stays on screen even when empty: nothing shifts under the finger.
            val poolHot = drag.hover == POOL
            val poolBorder by animateColorAsState(if (poolHot) skin.action else Color.Transparent, if (reduced) snap() else spring(), label = "pool")
            Column(
                Modifier.fillMaxWidth().dropZone(POOL, drag).border(2.dp, poolBorder, skin.cardShape).padding(2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (unplaced.isNotEmpty()) {
                    Text(stringResource(R.string.sort_left, unplaced.size), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        unplaced.forEach { card ->
                            FilterChip(
                                selected = selected == card.id,
                                onClick = { selected = if (selected == card.id) null else card.id },
                                label = { Text(card.text, Modifier.padding(vertical = if (oneHanded) 12.dp else 8.dp)) },
                                modifier = Modifier.dragSource(card, null, drag, onDrop).semantics { customActions = actionsFor(card) },
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.sort_pool_empty), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
                }
            }
            sort.bins.forEach { bin ->
                val dragging = drag.cardId != null
                val target = selected != null || dragging
                val hot = drag.hover == bin.id
                val container by animateColorAsState(if (hot) lerp(skin.card, skin.action, 0.10f) else skin.card, if (reduced) snap() else spring(), label = "bin")
                Card(
                    Modifier.fillMaxWidth().heightIn(min = if (oneHanded) 96.dp else 80.dp).dropZone(bin.id, drag).clickable(enabled = selected != null) {
                        selected?.let { id ->
                            file(id, bin.id)
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    colors = CardDefaults.cardColors(containerColor = container),
                    border = BorderStroke(if (hot) 3.dp else if (target) 2.dp else 1.dp, if (target) skin.action else skin.outline),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(bin.label, fontWeight = FontWeight.SemiBold, color = skin.ink)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            order.filter { placed[it.id] == bin.id }.forEach { card ->
                                InputChip(
                                    selected = false,
                                    onClick = { file(card.id, null) },
                                    label = { Text(card.text, Modifier.padding(vertical = if (oneHanded) 6.dp else 0.dp)) },
                                    modifier = Modifier.dragSource(card, bin.id, drag, onDrop).semantics { customActions = actionsFor(card) },
                                )
                            }
                        }
                    }
                }
            }
            if (placed.isNotEmpty() && unplaced.isNotEmpty()) {
                Text(stringResource(R.string.sort_tap_to_return), style = MaterialTheme.typography.labelMedium, color = skin.inkMuted)
            }
            }
            // Docked submit: always in the thumb zone, never scrolled away.
            Button(
                onClick = { onSubmit(SortAnswer(placed)) },
                enabled = unplaced.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.action_submit)) }
        }
        drag.cardId?.let { DraggedCard(drag, boxOrigin, reduced) }
        }
    }

    @Composable
    override fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext) {
        val sort = item as SortItem
        val bins = sort.bins.associate { it.id to it.label }
        val placed = (answer as SortAnswer).placements
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sort.cards.forEach { card ->
                val ok = card.id !in grade.wrongPartIds
                ReviewRow(
                    correct = ok,
                    title = card.text,
                    detail = if (ok) bins[placed[card.id]] else stringResource(R.string.sort_correct_bin, bins[card.binId].orEmpty()),
                    explanation = null,
                )
            }
            Text(sort.why, style = MaterialTheme.typography.bodyLarge, color = A2g.skin.ink)
        }
    }
}

/** The id the pool answers to as a drop zone; real bin ids can never collide with a NUL. */
private const val POOL = "\u0000pool"

/**
 * One card in the air. The card itself never leaves its place: a ghost follows the finger in the screen-wide overlay (so it can
 * cross the scroll edge, sit above everything and keep following while the list auto-scrolls), and the original dims.
 */
@Stable
internal class SortDrag(private val scope: CoroutineScope, private val haptic: HapticFeedback, private val reduced: Boolean) {
    var cardId by mutableStateOf<String?>(null); private set
    var label by mutableStateOf(""); private set
    var finger by mutableStateOf(Offset.Zero); private set
    var hover by mutableStateOf<String?>(null); private set
    private var size by mutableStateOf(IntSize.Zero)
    private var settle by mutableStateOf<Animatable<Offset, *>?>(null)
    private var grab = Offset.Zero
    private var from: String? = null
    private var source: LayoutCoordinates? = null
    private var job: Job? = null
    private val zones = HashMap<String, LayoutCoordinates>()
    var viewport: Rect? = null

    /** Finger down and carrying (as opposed to springing back). */
    val held get() = cardId != null && settle == null
    val topLeft get() = settle?.value ?: (finger - grab)
    val sizePx get() = size

    fun zone(id: String, coords: LayoutCoordinates) { zones[id] = coords }

    fun pickUp(id: String, text: String, fromBin: String?, coords: LayoutCoordinates, local: Offset) {
        job?.cancel(); settle = null
        cardId = id; label = text; from = fromBin; source = coords; size = coords.size
        finger = coords.localToRoot(local); grab = finger - coords.positionInRoot(); hover = null
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        updateHover()
    }

    fun moveTo(local: Offset) {
        val c = source ?: return
        finger = c.localToRoot(local)
        updateHover()
    }

    fun updateHover() {
        val box = viewport
        val zone = if (box == null || !box.contains(finger)) null else
            zones.entries.firstOrNull { (id, c) -> c.isAttached && !(id == POOL && from == null) && c.boundsInRoot().contains(finger) }?.key
        if (zone != hover) {
            hover = zone
            if (zone != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    /** Finger up. Over a different bin (or the pool): file it. Anywhere else: spring home. */
    fun drop(onFile: (String, String?) -> Unit) {
        val id = cardId ?: return
        val target = hover
        hover = null
        if (target != null && target != (from ?: POOL)) {
            clear()
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onFile(id, target)
        } else springHome()
    }

    fun cancel() { if (cardId != null) springHome() }

    private fun springHome() {
        hover = null
        val home = source?.takeIf { it.isAttached }?.positionInRoot()
        if (home == null || reduced) { clear(); return }
        val a = Animatable(finger - grab, Offset.VectorConverter)
        settle = a
        job = scope.launch {
            a.animateTo(home, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium))
            clear()
        }
    }

    private fun clear() { job?.cancel(); settle = null; cardId = null; hover = null; source = null }
}

/** Marks a region as somewhere a dragged card can land. */
private fun Modifier.dropZone(id: String, drag: SortDrag): Modifier = onGloballyPositioned { drag.zone(id, it) }

/** Makes a card chip draggable after a long press (taps and the TalkBack actions are untouched). */
@Composable
private fun Modifier.dragSource(card: SortCard, fromBin: String?, drag: SortDrag, onDrop: (String, String?) -> Unit): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dropNow by rememberUpdatedState(onDrop)
    return this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(card.id, fromBin) {
            detectPickUpAndDrag(
                onPickUp = { down -> coords[0]?.let { drag.pickUp(card.id, card.text, fromBin, it, down.position) } },
                onMove = { drag.moveTo(it.position) },
                onDrop = { drag.drop(dropNow) },
                onCancel = drag::cancel,
            )
        }
        .alpha(if (drag.cardId == card.id) 0.35f else 1f)
}

/** The lifted card: scaled up and shadowed (no scale with animations off), clear of the semantics tree so tests and TalkBack see one card. */
@Composable
private fun DraggedCard(drag: SortDrag, origin: Offset, reduced: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(Unit) { if (!reduced) scale.animateTo(LIFT_SCALE, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
    val density = LocalDensity.current
    val size = drag.sizePx
    val chipShape = FilterChipDefaults.shape
    FilterChip(
        selected = true,
        onClick = {},
        label = { Text(drag.label, Modifier.padding(vertical = 8.dp)) },
        modifier = Modifier
            .offset { (drag.topLeft - origin).round() }
            .requiredSize(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            .zIndex(10f)
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                shadowElevation = 12.dp.toPx()
                shape = chipShape
                clip = false
            }
            .clearAndSetSemantics { },
    )
}
