package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.app.ui.theme.AllSkins
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The same three screens under each candidate skin, for the team's side-by-side choice (docs/design/design-review). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DirectionsScreenshotTest {
    private val dir = File(System.getProperty("contract.samples")!!)
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun render(name: String, doc: String, f: FrameId, setup: (LevelSession, SessionState) -> SessionState) {
        val p = ContractJson.decodePackage(File(dir, "package.demo.$doc.json").readText())
        val o = ContractJson.decodeFrame(File(dir, "frame.demo.$doc.${f.name.lowercase()}.json").readText())
        val e = LevelSession(p, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(f), overlay = o)
        val s = setup(e, e.start(p.levels[0].levelId))
        val ready = PlayUiState.Ready(p, f, o, s, e.sceneLines(s), e.room(s), e.solvedUnitIds(s), e.unit(s), e.item(s), null, still, false)
        AllSkins.forEachIndexed { i, skin ->
            captureRoboImage("build/outputs/roborazzi/directions/${name}_$i.png") {
                A2gTheme(skin) { Box(Modifier.fillMaxSize().background(skin.paper)) { PlayContent(ready, {}, {}, {}) } }
            }
        }
    }

    private fun at(s: SessionState, kind: SlotKind) = s.copy(position = s.queue.indexOfFirst { it.kind == kind })

    @Test fun swipe() = render("a_swipe", "attention", FrameId.CASE) { _, s -> at(s, SlotKind.UNIT) }
    @Test fun crossexam() = render("b_crossexam", "attention", FrameId.CASE) { _, s -> at(s, SlotKind.BOSS) }
    @Test fun companion() = render("c_companion", "mapreduce", FrameId.COMPANION) { e, s -> e.reduce(s, Action.Next).state }
}
