package com.anything2game.app.ui.play.mechanics

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.anything2game.app.ui.character.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import com.anything2game.app.R
import com.anything2game.app.ui.theme.A2g
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.OrderItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import com.anything2game.engine.grading.OrderAnswer

/**
 * Rebuild a sequence. Drag a step by its handle (or press and hold anywhere on it): the others slide out of the way, a tick
 * marks each neighbour passed, and letting go settles it with a spring. The up/down buttons and the TalkBack actions
 * "Move up / Move down" do the same move without a gesture.
 */
object OrderRenderer : MechanicRenderer {
    override val mechanic = Mechanic.ORDER

    @Composable
    override fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit) {
        val order = item as OrderItem
        val skin = A2g.skin
        val haptic = LocalHapticFeedback.current
        val reduced = rememberReducedMotion()
        var ids by rememberSaveable(key) {
            // Deterministic shuffle that is never already solved.
            val shuffled = order.steps.map { it.id }.shuffled(java.util.Random(key.hashCode().toLong()))
            mutableStateOf(if (shuffled == order.steps.map { it.id }) shuffled.reversed() else shuffled)
        }
        val byId = order.steps.associateBy { it.id }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val spacing = with(LocalDensity.current) { 10.dp.toPx() }
        val reorder = remember(key) { Reorder(scope, haptic, reduced, scroll, spacing, { ids }, { ids = it }) }
        AutoScrollWhileDragging(active = reorder.held, fingerY = { reorder.fingerY }, viewport = { reorder.viewport }, scroll = scroll, onScrolled = reorder::track)

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).onGloballyPositioned { reorder.viewport = it.boundsInRoot() }.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            Text(order.prompt, style = MaterialTheme.typography.titleLarge, color = skin.ink)
            Text(stringResource(R.string.order_instruction), style = MaterialTheme.typography.bodyMedium, color = skin.inkMuted)
            ids.forEachIndexed { i, id ->
                key(id) { StepRow(id, i, ids.size, byId[id]?.text.orEmpty(), reorder, ctx.oneHanded, reduced) }
            }
            }
            // Docked submit: always in the thumb zone, never scrolled away.
            Button(onClick = { onSubmit(OrderAnswer(reorder.current)) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 56.dp)) { Text(stringResource(R.string.action_submit)) }
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

@Composable
private fun StepRow(id: String, index: Int, count: Int, text: String, reorder: Reorder, oneHanded: Boolean, reduced: Boolean) {
    val skin = A2g.skin
    val up = stringResource(R.string.order_up)
    val down = stringResource(R.string.order_down)
    val rowOffset = remember(id) { reorder.rowOffset(id) }
    val lifted = reorder.draggedId == id
    val lift by animateFloatAsState(if (lifted && reorder.held) 1f else 0f, if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "lift")
    val row = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val handle = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shape = skin.cardShape
    val arrow = if (oneHanded) 44.dp else 38.dp
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Outside the graphicsLayer on purpose: the touch coordinates must not move with the row that follows the finger.
            .onGloballyPositioned { row[0] = it }
            .pointerInput(id) {
                detectPickUpAndDrag(
                    onPickUp = { down -> row[0]?.let { reorder.pickUp(id, it.localToRoot(down.position).y) } },
                    onMove = { change -> row[0]?.let { reorder.moveTo(it.localToRoot(change.position).y) } },
                    onDrop = reorder::drop,
                    onCancel = reorder::cancel,
                )
            }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = if (lifted) reorder.dragT else rowOffset.value
                if (!reduced) { val s = 1f + 0.03f * lift; scaleX = s; scaleY = s }
                shadowElevation = 14.dp.toPx() * lift
                this.shape = shape
                clip = false
            }
            .onSizeChanged { reorder.heights[id] = it.height.toFloat() },
        colors = CardDefaults.cardColors(containerColor = skin.card),
        border = BorderStroke(if (lifted) 2.dp else 1.dp, if (lifted) skin.action else skin.outline),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (oneHanded) 64.dp else 56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StepNumber(index + 1)
            Text(
                text,
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp).semantics {
                    customActions = listOfNotNull(
                        if (index > 0) CustomAccessibilityAction(up) { reorder.nudge(id, -1); true } else null,
                        if (index < count - 1) CustomAccessibilityAction(down) { reorder.nudge(id, 1); true } else null,
                    )
                },
                // Long steps (generated text can run to 30+ words) use the smaller body size so a row stays draggable.
                color = skin.ink, style = if (text.length > 90) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            )
            // Press and drag: takes the touch at once, so it never fights the list's own scrolling.
            Box(
                Modifier.size(if (oneHanded) 56.dp else 48.dp).testTag("order_handle_$id")
                    .onGloballyPositioned { handle[0] = it }
                    .pointerInput(id) {
                        detectPickUpAndDrag(
                            afterLongPress = false,
                            onPickUp = { down -> handle[0]?.let { reorder.pickUp(id, it.localToRoot(down.position).y) } },
                            onMove = { change -> handle[0]?.let { reorder.moveTo(it.localToRoot(change.position).y) } },
                            onDrop = reorder::drop,
                            onCancel = reorder::cancel,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DragHandle, contentDescription = null, tint = skin.inkMuted) }
            // The step-by-step path stays, smaller and at the end.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides arrow) {
                Column {
                    IconButton({ reorder.nudge(id, -1) }, Modifier.size(arrow), enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, up) }
                    IconButton({ reorder.nudge(id, 1) }, Modifier.size(arrow), enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, down) }
                }
            }
        }
    }
}

/**
 * The reorder in progress. While a step is held nothing is re-laid-out: the dragged row follows the finger by a translation,
 * and the rows it passes slide by exactly the height they swap (measured, so uneven rows work). Only at the end, once the
 * springs have settled, is the new order committed and all translations reset in the same frame, so there is no jump.
 */
@Stable
internal class Reorder(
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val reduced: Boolean,
    private val scroll: ScrollState,
    private val spacing: Float,
    private val committed: () -> List<String>,
    private val commitTo: (List<String>) -> Unit,
) {
    class RowOffset { var value by mutableFloatStateOf(0f); var job: Job? = null }

    var draggedId by mutableStateOf<String?>(null); private set
    var dragT by mutableFloatStateOf(0f); private set
    var fingerY by mutableFloatStateOf(0f); private set
    private var settling by mutableStateOf(false)
    private var live by mutableStateOf<List<String>?>(null)
    private var fingerStart = 0f
    private var scrollStart = 0
    private var settleJob: Job? = null
    private val rows = HashMap<String, RowOffset>()
    val heights = HashMap<String, Float>()
    var viewport: Rect? = null

    val held get() = draggedId != null && !settling
    /** What the list will be once any move in flight lands (the submit button reads this). */
    val current get() = live ?: committed()

    fun rowOffset(id: String) = rows.getOrPut(id) { RowOffset() }

    private fun tops(order: List<String>): Map<String, Float> {
        var y = 0f
        return order.associateWith { id -> y.also { y += (heights[id] ?: 0f) + spacing } }
    }

    fun pickUp(id: String, fingerRootY: Float) {
        finishNow()
        draggedId = id; live = committed(); dragT = 0f
        fingerStart = fingerRootY; fingerY = fingerRootY; scrollStart = scroll.value
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun moveTo(fingerRootY: Float) { fingerY = fingerRootY; track() }

    /** Follow the finger (and any auto-scroll) and swap with each neighbour whose middle the dragged row has passed. */
    fun track() {
        val d = draggedId ?: return
        if (settling) return
        dragT = fingerY - fingerStart + (scroll.value - scrollStart)
        val before = tops(committed())
        val centre = (before[d] ?: 0f) + dragT + (heights[d] ?: 0f) / 2
        var order = live ?: committed()
        var changed = false
        while (true) {
            val at = order.indexOf(d)
            val t = tops(order)
            val above = order.getOrNull(at - 1)?.takeIf { centre < (t[it] ?: 0f) + (heights[it] ?: 0f) / 2 }
            val below = order.getOrNull(at + 1)?.takeIf { centre > (t[it] ?: 0f) + (heights[it] ?: 0f) / 2 }
            val other = above ?: below ?: break
            order = order.toMutableList().also { it[at] = other; it[order.indexOf(other)] = d }
            changed = true
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        if (changed) { live = order; slideOthers() }
    }

    fun drop() {
        val d = draggedId ?: return
        if (settling) return
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
        settle(d)
    }

    fun cancel() {
        val d = draggedId ?: return
        if (settling) return
        live = committed(); slideOthers(); settle(d)
    }

    /** The up/down buttons and TalkBack actions: same swap, same slide, no finger. */
    fun nudge(id: String, by: Int) {
        finishNow()
        val ids = committed()
        val i = ids.indexOf(id)
        val j = i + by
        if (i < 0 || j !in ids.indices) return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        live = ids.toMutableList().also { it[i] = it[j]; it[j] = id }
        slideOthers(); settle(null)
    }

    private fun slideOthers() {
        val now = tops(committed()); val then = tops(live ?: committed())
        committed().forEach { id -> if (id != draggedId) rowOffsetTo(id, (then[id] ?: 0f) - (now[id] ?: 0f)) }
    }

    private fun rowOffsetTo(id: String, target: Float) {
        val r = rowOffset(id)
        r.job?.cancel()
        if (reduced) { r.value = target; return }
        r.job = scope.launch { animate(r.value, target, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) { v, _ -> r.value = v } }
    }

    /** Let the dragged row (if any) spring into its slot, wait for the others, then commit. */
    private fun settle(dragged: String?) {
        settling = true
        val target = dragged?.let { (tops(live ?: committed())[it] ?: 0f) - (tops(committed())[it] ?: 0f) } ?: 0f
        if (reduced) { dragT = target; commit(); return }
        settleJob = scope.launch {
            if (dragged != null) animate(dragT, target, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { v, _ -> dragT = v }
            rows.values.mapNotNull { it.job }.joinAll()
            commit()
        }
    }

    private fun finishNow() { if (live != null || draggedId != null) commit() }

    /** New order and zeroed translations land together, so the rows do not move on screen at this moment. */
    private fun commit() {
        settleJob?.cancel()
        live?.let(commitTo)
        live = null; draggedId = null; settling = false; dragT = 0f
        rows.values.forEach { it.job?.cancel(); it.value = 0f }
    }
}
