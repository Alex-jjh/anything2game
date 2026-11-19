package com.anything2game.app.ui.play.mechanics

import androidx.compose.runtime.getValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sign

/**
 * Pick a card up (after a long press, or as soon as the finger travels when [afterLongPress] is false, as on a drag handle)
 * and carry it. Written for cards that are also tappable.
 *
 * The stock `detectDragGesturesAfterLongPress` listens in the Main pass, where a child `clickable` sees the pointer first and
 * fires a click when the finger lifts after the pick-up. Here, once the card is picked up, every later event is consumed in
 * the Initial pass (parents first), so the child's click and any ancestor scroll both stand down: lifting a held card never
 * toggles its selection.
 *
 * Before the pick-up nothing is consumed, so a quick tap still reaches the child and a quick swipe still scrolls the list.
 * [onPickUp] gets the touch-down, so the carried card is anchored where the finger first landed and does not lag by the slop.
 */
internal suspend fun PointerInputScope.detectPickUpAndDrag(
    afterLongPress: Boolean = true,
    onPickUp: (down: PointerInputChange) -> Unit,
    onMove: (PointerInputChange) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop
    fun PointerInputChange.travelled() = (position - down.position).getDistance()
    var current: PointerInputChange = down
    if (afterLongPress) {
        // Still for the whole long-press time? (A move past the slop, a lift or a consumer cancels it.)
        val gaveUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                if (change.isConsumed || !change.pressed || change.travelled() > slop) return@withTimeoutOrNull true
                current = change
            }
        }
        if (gaveUp != null) return@awaitEachGesture
    } else {
        // A handle: carry as soon as the finger has travelled past the slop (and take that move so the list does not scroll).
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.isConsumed || !change.pressed) return@awaitEachGesture
            if (change.travelled() > slop) { current = change; break }
        }
    }
    onPickUp(down)
    current.consume()
    onMove(current)
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
        if (change == null) { onCancel(); return@awaitEachGesture }
        change.consume()
        if (!change.pressed) { onDrop(); return@awaitEachGesture }
        if (change.positionChanged()) onMove(change)
    }
}

private fun PointerInputChange.positionChanged() = position != previousPosition

/**
 * Scrolls [scroll] while a drag is held near the top or bottom edge of [viewport] (root coordinates), faster the closer to the
 * edge. Runs only while the finger is in an edge zone, so an idle drag costs no frames. [onScrolled] lets the caller re-test
 * what is under the finger, since the content moved while the finger did not.
 */
@Composable
internal fun AutoScrollWhileDragging(
    active: Boolean,
    fingerY: () -> Float,
    viewport: () -> Rect?,
    scroll: ScrollState,
    onScrolled: () -> Unit,
) {
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxPerSecond = with(density) { 720.dp.toPx() }
    val onScrolledNow by rememberUpdatedState(onScrolled)
    val speed by remember(active, edge) {
        derivedStateOf {
            val box = viewport()
            if (!active || box == null) 0f else {
                val y = fingerY()
                when {
                    y < box.top + edge -> -((box.top + edge - y) / edge).coerceIn(0f, 1f)
                    y > box.bottom - edge -> ((y - (box.bottom - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
            }
        }
    }
    val direction = speed.sign
    LaunchedEffect(active, direction) {
        if (!active || direction == 0f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val moved = scroll.scrollBy(speed * maxPerSecond * seconds)
            if (moved == 0f && (if (direction < 0) !scroll.canScrollBackward else !scroll.canScrollForward)) break
            if (moved != 0f) onScrolledNow()
        }
    }
}

internal const val LIFT_SCALE = 1.05f
