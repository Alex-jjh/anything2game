package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The pitch "hero flow" on real demo content (Attention paper, Case frame), driven by real gestures:
 * briefing → clue (swipe mid-drag) → verdict stamp → testimony → object → present evidence → overclaim caught → case closed.
 * Output: build/outputs/roborazzi/hero/ (composited into docs/design/screens/hero-flow.png).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class HeroFlowScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("contract.samples")!!)
    private val pkg = ContractJson.decodePackage(File(dir, "package.demo.attention.json").readText())
    private val overlay = ContractJson.decodeFrame(File(dir, "frame.demo.attention.case.json").readText())
    private val engine = LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(FrameId.CASE), overlay = overlay)
    private val start = engine.start(pkg.levels[0].levelId)
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))
    private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(s: SessionState) = rule.setContent {
        val ready = PlayUiState.Ready(pkg, FrameId.CASE, overlay, s, engine.sceneLines(s), engine.room(s),
            engine.solvedUnitIds(s) + pkg.levels[0].units.map { it.unitId }, engine.unit(s), engine.item(s),
            if (s.isFinished) engine.recap(s) else null, still, false)
        A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}) } }
    }
    private fun snap(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/outputs/roborazzi/hero/$name.png") }
    private fun at(kind: SlotKind) = start.copy(position = start.queue.indexOfFirst { it.kind == kind })
    private val boss get() = at(SlotKind.BOSS)

    @Test fun h1_briefing() { show(start); snap("h1_briefing") }

    @Test fun h2_clue_drag() {
        val s = at(SlotKind.UNIT); show(s)
        val statement = (engine.item(s) as SwipeItem).cards[0].statement
        rule.onNodeWithText(statement).performTouchInput { down(center); moveBy(Offset(140f, 0f)); moveBy(Offset(140f, 0f)); moveBy(Offset(120f, 0f)) }
        snap("h2_clue_drag")
    }

    @Test fun h3_clue_verdict() {
        val s = at(SlotKind.UNIT)
        val swipe = engine.item(s) as SwipeItem
        val right = swipe.cards.associate { it.id to it.supported }
        rule.mainClock.autoAdvance = true
        show(engine.reduce(s, Action.Submit(SwipeAnswer(right))).state); rule.mainClock.advanceTimeBy(1500); snap("h3_clue_verdict")
    }

    @Test fun h4_testimony_pick() {
        show(boss)
        rule.onNodeWithText("The paper proves the Transformer is superior across many NLP benchmarks.").performClick()
        snap("h4_testimony_pick")
    }

    @Test fun h5_object_hold() {
        show(boss)
        rule.onNodeWithText("The paper proves the Transformer is superior across many NLP benchmarks.").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText(str(R.string.crossexam_hold)).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(380)
        snap("h5_object_hold")
    }

    @Test fun h6_present_evidence() {
        show(boss)
        rule.onNodeWithText("The paper proves the Transformer is superior across many NLP benchmarks.").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText(str(R.string.crossexam_hold)).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        snap("h6_present_evidence")
    }

    @Test fun h7_overclaim_caught() {
        rule.mainClock.autoAdvance = true
        show(engine.reduce(boss, Action.Submit(CrossExamAnswer("st4", "c1"))).state); rule.mainClock.advanceTimeBy(1500); snap("h7_overclaim_caught")
    }

    /** Plays the whole level for real: one honest miss on the first clue (so the recap has something to bring back), the rest right. */
    private fun playThrough(): SessionState {
        var s = start
        var missed = false
        var guard = 0
        while (!s.isFinished && guard++ < 100) {
            val item = engine.item(s)
            s = if (item == null) engine.reduce(s, Action.Next).state else {
                val answer = if (!missed && item is SwipeItem) { missed = true; SwipeAnswer(item.cards.associate { it.id to (it.supported xor (it == item.cards.last())) }) } else correct(item)
                val fed = engine.reduce(s, Action.Submit(answer)).state
                engine.reduce(fed, Action.Next).state
            }
        }
        return s
    }

    private fun correct(item: com.anything2game.contract.Item): com.anything2game.engine.grading.Answer = when (item) {
        is SwipeItem -> SwipeAnswer(item.cards.associate { it.id to it.supported })
        is com.anything2game.contract.SortItem -> com.anything2game.engine.grading.SortAnswer(item.cards.associate { it.id to it.binId })
        is com.anything2game.contract.OrderItem -> com.anything2game.engine.grading.OrderAnswer(item.steps.map { it.id })
        is com.anything2game.contract.DeduceItem -> com.anything2game.engine.grading.DeduceAnswer(item.options.first { it.correct }.id)
        is com.anything2game.contract.CrossExamItem -> item.statements.first { it.flawed }.let { CrossExamAnswer(it.id, it.sourceRefs.first().chunkId) }
        else -> error("no scripted answer for ${item::class.simpleName}")
    }

    @Test fun h8_case_closed() {
        rule.mainClock.autoAdvance = true
        show(playThrough()); rule.mainClock.advanceTimeBy(1500); snap("h8_case_closed")
    }
}
