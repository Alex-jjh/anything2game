package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.anything2game.app.ui.play.mechanics.OrderRenderer
import com.anything2game.app.ui.play.mechanics.RenderContext
import com.anything2game.app.ui.play.mechanics.SortRenderer
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.Item
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.grading.SortAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real drags (down, hold past the long-press time, move, up) on the two arrangement mechanics, on the demo package's own
 * sort and order units. The tap and button paths are covered here too, since the drag layer sits on top of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DragAndDropTest {
    @get:Rule val rule = createComposeRule()

    private val pkg = ContractJson.decodePackage(File(System.getProperty("contract.samples")!!, "package.demo.attention.json").readText())
    private val units = pkg.levels[0].units
    private val sort = units.first { it.item is SortItem }.item as SortItem
    private val order = units.first { it.item is OrderItem }.item as OrderItem
    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /** Some tests switch animations off; put the system setting back so no other test inherits it. */
    @org.junit.After fun restoreAnimations() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private val haptics = mutableListOf<HapticFeedbackType>()
    private val recorder = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType } }
    private var submitted: Answer? = null

    private fun show(item: Item, oneHanded: Boolean = false, heightDp: Int? = null) {
        val renderer = com.anything2game.app.ui.play.mechanics.MechanicRenderers.of(item.mechanic)!!
        rule.setContent {
            A2gTheme {
                CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                    Box(Modifier.fillMaxSize().background(A2g.skin.paper).padding(16.dp)) {
                        Box(if (heightDp != null) Modifier.height(heightDp.dp) else Modifier.fillMaxSize()) {
                            renderer.Question("slot-1", item, RenderContext(oneHanded = oneHanded)) { submitted = it }
                        }
                    }
                }
            }
        }
    }

    // ---- gestures ----------------------------------------------------------------------------------------

    private fun SemanticsNodeInteraction.centre(): Offset = fetchSemanticsNode().boundsInRoot.center
    private val longPress get() = 600L

    /** Press, hold past the long-press time, travel to [to] (root coordinates) in steps, and optionally lift. */
    private fun SemanticsNodeInteraction.dragTo(to: Offset, lift: Boolean = true, steps: Int = 8) {
        val from = centre()
        performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(longPress)
        val step = (to - from) / steps.toFloat()
        performTouchInput { repeat(steps) { moveBy(step) } }
        rule.waitForIdle()
        if (lift) { performTouchInput { up() }; rule.waitForIdle() }
    }

    private fun bin(id: String) = rule.onNode(hasText(sort.bins.first { it.id == id }.label), useUnmergedTree = true)
    private fun card(text: String) = rule.onNode(hasText(text) and hasClickAction())
    private fun submit() = rule.onNodeWithText(str(R.string.action_submit))
    private fun cardText(id: String) = sort.cards.first { it.id == id }.text

    private fun runAction(node: SemanticsNodeInteraction, label: String) {
        val action = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    private fun gradedCorrect(item: Item, answer: Answer?) = GraderRegistry.default().grade(item, answer!!).correct

    // ---- sort --------------------------------------------------------------------------------------------

    @Test fun sort_drag_each_card_into_its_bin_then_submit_is_graded_correct() {
        show(sort)
        submit().assertIsNotEnabled()
        sort.cards.forEach { c -> card(c.text).dragTo(bin(c.binId).centre()) }
        submit().assertIsEnabled().performClick()
        assertTrue(gradedCorrect(sort, submitted))
        assertEquals(sort.cards.associate { it.id to it.binId }, (submitted as SortAnswer).placements)
        // A pick-up each, a tick for entering each bin, and a confirm on each drop.
        assertEquals(sort.cards.size, haptics.count { it == HapticFeedbackType.LongPress })
        assertTrue(haptics.count { it == HapticFeedbackType.SegmentTick } >= sort.cards.size)
        assertEquals(sort.cards.size, haptics.count { it == HapticFeedbackType.Confirm })
    }

    @Test fun sort_drag_into_the_wrong_bin_is_graded_wrong() {
        show(sort)
        val wrong = sort.cards.associate { it.id to sort.bins.first { b -> b.id != it.binId }.id }
        sort.cards.forEach { c -> card(c.text).dragTo(bin(wrong.getValue(c.id)).centre()) }
        submit().performClick()
        assertFalse(gradedCorrect(sort, submitted))
    }

    @Test fun sort_a_filed_card_drags_to_another_bin_and_back_to_the_pool() {
        show(sort)
        val c = sort.cards.first()
        val other = sort.bins.first { it.id != c.binId }
        card(c.text).dragTo(bin(c.binId).centre())
        // The filed chip is a different node now; drag it across to the other bin.
        card(c.text).dragTo(bin(other.id).centre())
        sort.cards.drop(1).forEach { card(it.text).dragTo(bin(it.binId).centre()) }
        submit().assertIsEnabled().performClick()
        assertEquals(other.id, (submitted as SortAnswer).placements[c.id])

        // And back to the pool: the card is no longer filed and submit is disabled again.
        submitted = null
        card(c.text).dragTo(rule.onNodeWithText(str(R.string.sort_pool_empty)).centre())
        submit().assertIsNotEnabled()
        assertNull(submitted)
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.sort_left, 1)).assertExists()
    }

    @Test fun sort_dropped_on_nothing_springs_back_and_files_nothing() {
        show(sort)
        val c = sort.cards.first()
        card(c.text).dragTo(rule.onNodeWithText(sort.prompt).centre())
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.sort_left, sort.cards.size)).assertExists()
        assertEquals(0, haptics.count { it == HapticFeedbackType.Confirm })
        card(c.text).assertExists()
    }

    @Test fun sort_card_is_dimmed_in_place_and_bin_ticks_only_when_entered() {
        show(sort)
        val c = sort.cards.first()
        card(c.text).dragTo(bin(c.binId).centre(), lift = false)
        val ticks = haptics.count { it == HapticFeedbackType.SegmentTick }
        assertEquals(1, ticks)
        // Jiggling inside the same bin does not tick again.
        card(c.text).performTouchInput { moveBy(Offset(4f, 4f)); moveBy(Offset(-4f, -4f)) }
        assertEquals(ticks, haptics.count { it == HapticFeedbackType.SegmentTick })
        card(c.text).performTouchInput { up() }
    }

    @Test fun sort_tap_card_then_tap_bin_still_works() {
        show(sort)
        sort.cards.forEach { c ->
            card(c.text).performClick()
            bin(c.binId).performClick()
        }
        submit().assertIsEnabled().performClick()
        assertTrue(gradedCorrect(sort, submitted))
    }

    @Test fun sort_holding_a_card_and_letting_go_in_place_does_not_select_it() {
        show(sort)
        val c = sort.cards.first()
        card(c.text).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(longPress)
        card(c.text).performTouchInput { up() }
        rule.waitForIdle()
        // Not selected: tapping a bin now files nothing.
        bin(c.binId).performClick()
        rule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.sort_left, sort.cards.size)).assertExists()
    }

    @Test fun sort_every_card_offers_a_put_in_action_per_bin() {
        show(sort)
        sort.cards.forEach { c ->
            val labels = card(c.text).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.map { it.label }
            assertEquals(sort.bins.map { RuntimeEnvironment.getApplication().getString(R.string.sort_put_in, it.label) }, labels)
        }
        // Using one files the card; a filed card then offers the other bins and "take back".
        val c = sort.cards.first()
        val put = RuntimeEnvironment.getApplication().getString(R.string.sort_put_in, sort.bins.first { it.id == c.binId }.label)
        runAction(card(c.text), put)
        rule.waitForIdle()
        val filed = card(c.text).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.map { it.label }
        assertTrue(str(R.string.sort_take_back) in filed)
        assertFalse(put in filed)
    }

    @Test fun sort_drag_works_one_handed_and_with_animations_off() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(sort, oneHanded = true)
        sort.cards.forEach { c -> card(c.text).dragTo(bin(c.binId).centre()) }
        submit().performClick()
        assertTrue(gradedCorrect(sort, submitted))
    }

    // ---- order -------------------------------------------------------------------------------------------

    private fun stepText(id: String) = order.steps.first { it.id == id }.text
    private fun step(id: String) = rule.onNode(hasText(stepText(id)))
    private fun handle(id: String) = rule.onNodeWithTag("order_handle_$id")
    private fun shown(): List<String> = order.steps.sortedBy { step(it.id).fetchSemanticsNode().positionInRoot.y }.map { it.id }
    private fun SemanticsNodeInteraction.dragHandleTo(to: Offset, lift: Boolean = true, steps: Int = 10) {
        val from = centre()
        performTouchInput { down(center) }
        val step = (to - from) / steps.toFloat()
        performTouchInput { repeat(steps) { moveBy(step) } }
        rule.waitForIdle()
        if (lift) { performTouchInput { up() }; rule.waitForIdle() }
    }

    @Test fun order_drag_the_last_step_to_the_top_by_its_handle() {
        show(order)
        val before = shown()
        val last = before.last()
        val top = step(before.first()).centre()
        handle(last).dragHandleTo(Offset(handle(last).centre().x, top.y - 20f))
        val expected = listOf(last) + before.dropLast(1)
        assertEquals(expected, shown())
        rule.onNodeWithText(str(R.string.action_submit)).performClick()
        assertEquals(expected, (submitted as OrderAnswer).stepIds)
        // One tick for each neighbour passed.
        assertEquals(before.size - 1, haptics.count { it == HapticFeedbackType.SegmentTick })
    }

    @Test fun order_press_and_hold_anywhere_on_the_row_then_drag() {
        show(order)
        val before = shown()
        val first = before.first()
        // Drag the first step to the bottom by pressing on its text.
        step(first).dragTo(Offset(step(first).centre().x, step(before.last()).centre().y + 20f))
        assertEquals(before.drop(1) + first, shown())
        assertEquals(1, haptics.count { it == HapticFeedbackType.LongPress })
    }

    @Test fun order_drag_all_the_way_to_the_right_order_is_graded_correct() {
        show(order)
        val want = order.steps.map { it.id }
        // Selection sort by drags: bring each wanted step up to its place.
        want.forEachIndexed { target, id ->
            val now = shown()
            if (now.indexOf(id) != target) {
                val y = step(now[target]).centre().y - 20f
                handle(id).dragHandleTo(Offset(handle(id).centre().x, y))
            }
        }
        assertEquals(want, shown())
        rule.onNodeWithText(str(R.string.action_submit)).performClick()
        assertTrue(gradedCorrect(order, submitted))
    }

    @Test fun order_a_short_drag_that_passes_nobody_changes_nothing() {
        show(order)
        val before = shown()
        handle(before[1]).dragHandleTo(handle(before[1]).centre() + Offset(0f, 12f))
        assertEquals(before, shown())
        assertEquals(0, haptics.count { it == HapticFeedbackType.SegmentTick })
    }

    @Test fun order_arrow_buttons_and_talkback_actions_still_move_steps() {
        show(order)
        val before = shown()
        rule.onAllNodesWithContentDescription(str(R.string.order_up))[1].performClick()
        rule.waitForIdle()
        assertEquals(listOf(before[1], before[0]) + before.drop(2), shown())
        rule.onAllNodesWithContentDescription(str(R.string.order_down))[0].performClick()
        rule.waitForIdle()
        assertEquals(before, shown())
        // The step text carries "Move up / Move down" as accessibility actions (first has no "up", last has no "down").
        fun actions(id: String) = step(id).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.map { it.label }
        assertEquals(listOf(str(R.string.order_down)), actions(before.first()))
        assertEquals(listOf(str(R.string.order_up), str(R.string.order_down)), actions(before[1]))
        assertEquals(listOf(str(R.string.order_up)), actions(before.last()))
        runAction(step(before.last()), str(R.string.order_up))
        rule.waitForIdle()
        assertEquals(before.dropLast(2) + listOf(before.last(), before[before.size - 2]), shown())
        rule.onNodeWithText(str(R.string.action_submit)).performClick()
        assertEquals(shown(), (submitted as OrderAnswer).stepIds.map { it })
    }

    @Test fun order_drag_works_one_handed_and_with_animations_off() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(order, oneHanded = true)
        val before = shown()
        val last = before.last()
        handle(last).dragHandleTo(Offset(handle(last).centre().x, step(before.first()).centre().y - 20f))
        assertEquals(listOf(last) + before.dropLast(1), shown())
    }

    @Test fun order_dragging_near_the_bottom_edge_scrolls_the_list_and_carries_the_row_over_the_steps_below() {
        // A short window: the steps do not all fit, so the last starts below the fold.
        show(order, heightDp = 280)
        val before = shown()
        val first = before.first()
        val lastTopBefore = step(before.last()).fetchSemanticsNode().positionInRoot.y
        val h = handle(first)
        val edgeY = rule.onNodeWithText(str(R.string.action_submit)).fetchSemanticsNode().boundsInRoot.top - 8f
        h.performTouchInput { down(center) }
        h.performTouchInput { repeat(10) { moveBy(Offset(0f, (edgeY - h.centre().y) / 10f)) } }
        repeat(60) { rule.mainClock.advanceTimeBy(50) }
        val lastTopAfter = step(before.last()).fetchSemanticsNode().positionInRoot.y
        assertTrue("list should have scrolled ($lastTopBefore -> $lastTopAfter)", lastTopAfter < lastTopBefore - 20f)
        h.performTouchInput { up() }
        rule.waitForIdle()
        // Held at the bottom edge while the list scrolled, the row has passed the steps below it and landed last.
        assertEquals(before.drop(1) + first, shown())
    }
}
