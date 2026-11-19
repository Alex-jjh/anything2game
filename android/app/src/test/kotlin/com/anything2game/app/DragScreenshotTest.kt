package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.SessionState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The two arrangement mechanics mid-drag, on the demo Attention package (Case frame), in the real play screen.
 * Output: build/outputs/roborazzi/drag/ (copied to docs/design/screens/drag/).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DragScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("contract.samples")!!)
    private val pkg = ContractJson.decodePackage(File(dir, "package.demo.attention.json").readText())
    private val overlay = ContractJson.decodeFrame(File(dir, "frame.demo.attention.case.json").readText())
    private val engine = LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(FrameId.CASE), overlay = overlay)
    private val start = engine.start(pkg.levels[0].levelId)
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun at(unitId: String): SessionState = start.copy(position = start.queue.indexOfFirst { it.unitId == unitId })

    private fun show(s: SessionState) = rule.setContent {
        val ready = PlayUiState.Ready(pkg, FrameId.CASE, overlay, s, engine.sceneLines(s), engine.room(s),
            engine.solvedUnitIds(s), engine.unit(s), engine.item(s), null, still, false)
        A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}) } }
    }

    private fun snap(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/drag/$name.png") }
    private fun SemanticsNodeInteraction.centre(): Offset = fetchSemanticsNode().boundsInRoot.center

    private val sortUnit get() = engine.item(at("L1U3")) as SortItem
    private val orderUnit get() = engine.item(at("L1U2")) as OrderItem

    @Test fun d1_sort_lift_over_the_pool() {
        show(at("L1U3"))
        val card = rule.onNode(hasText(sortUnit.cards[1].text) and hasClickAction())
        card.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(700)
        card.performTouchInput { moveBy(Offset(60f, 70f)) }
        rule.mainClock.advanceTimeBy(500)
        snap("d1_sort_lift")
        card.performTouchInput { up() }
    }

    @Test fun d2_sort_over_a_highlighted_bin() {
        show(at("L1U3"))
        val c = sortUnit.cards.first { it.binId == sortUnit.bins[1].id }
        val card = rule.onNode(hasText(c.text) and hasClickAction())
        val bin = rule.onNode(hasText(sortUnit.bins[1].label), useUnmergedTree = true)
        // File one card first so the screenshot shows a filed chip too.
        val other = sortUnit.cards.first { it.binId == sortUnit.bins[0].id }
        drag(rule.onNode(hasText(other.text) and hasClickAction()), rule.onNode(hasText(sortUnit.bins[0].label), useUnmergedTree = true).centre(), lift = true)
        val from = card.centre(); val to = bin.centre()
        card.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(700)
        val step = (to - from) / 8f
        card.performTouchInput { repeat(8) { moveBy(step) } }
        rule.mainClock.advanceTimeBy(500)
        snap("d2_sort_over_bin")
        card.performTouchInput { up() }
    }

    @Test fun d3_order_row_lifted_by_the_handle() {
        show(at("L1U2"))
        // The second step (fully on screen): grabbed by its handle and nudged down, not yet past its neighbour.
        val handle = rule.onNodeWithTag("order_handle_${shownIds()[1]}")
        handle.performTouchInput { down(center) }
        handle.performTouchInput { repeat(3) { moveBy(Offset(0f, 14f)) } }
        rule.mainClock.advanceTimeBy(500)
        snap("d3_order_lift")
        handle.performTouchInput { up() }
    }

    @Test fun d4_order_row_dragged_up_past_a_step() {
        show(at("L1U2"))
        // The third step dragged up over the second: the second has slid down out of its way, leaving a gap where it will land.
        val ids = shownIds()
        val handle = rule.onNodeWithTag("order_handle_${ids[2]}")
        val from = handle.centre()
        val to = rule.onNode(hasText(orderUnit.steps.first { it.id == ids[1] }.text)).centre()
        handle.performTouchInput { down(center) }
        val step = Offset(0f, (to.y - 90f - from.y) / 8f)
        handle.performTouchInput { repeat(8) { moveBy(step) } }
        rule.mainClock.advanceTimeBy(500)
        snap("d4_order_mid_drag")
        handle.performTouchInput { up() }
    }

    private fun shownIds() = orderUnit.steps.sortedBy { s -> rule.onNode(hasText(s.text)).fetchSemanticsNode().positionInRoot.y }.map { it.id }

    private fun drag(card: SemanticsNodeInteraction, to: Offset, lift: Boolean) {
        val step = (to - card.centre()) / 8f
        card.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(700)
        card.performTouchInput { repeat(8) { moveBy(step) } }
        if (lift) card.performTouchInput { up() }
        rule.waitForIdle()
    }
}
