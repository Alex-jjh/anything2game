package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.LearningPackage
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real pipeline output (samples/demo) on the current screens: long statements, many passages, big boards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DemoStressScreenshotTest {
    private val dir = File(System.getProperty("contract.samples")!!)
    private fun pkg(d: String) = ContractJson.decodePackage(File(dir, "package.demo.$d.json").readText())
    private fun frame(d: String, f: String) = ContractJson.decodeFrame(File(dir, "frame.demo.$d.$f.json").readText())
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun shot(name: String, p: LearningPackage, f: FrameId, setup: (LevelSession, SessionState) -> SessionState) {
        val o = if (f == FrameId.CLASSIC) null else frame(p.packageId.removePrefix("demo_").replace('_', '-'), f.name.lowercase())
        val e = LevelSession(p, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(f), overlay = o)
        val s = setup(e, e.start(p.levels[0].levelId))
        val ready = PlayUiState.Ready(p, f, o, s, e.sceneLines(s), e.room(s), e.solvedUnitIds(s) + p.levels[0].units.take(5).map { it.unitId },
            e.unit(s), e.item(s), if (s.isFinished) e.recap(s) else null, still, false)
        captureRoboImage("build/outputs/roborazzi/stress/$name.png") {
            A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}) } }
        }
    }

    private fun first(e: LevelSession, s: SessionState, kind: SlotKind) = s.copy(position = s.queue.indexOfFirst { it.kind == kind }.coerceAtLeast(0))

    @Test fun attention_case_intro() = shot("s01_attention_case_intro", pkg("attention"), FrameId.CASE) { _, s -> s }
    @Test fun attention_first_unit() = shot("s02_attention_unit", pkg("attention"), FrameId.CASE) { e, s -> first(e, s, SlotKind.UNIT) }
    @Test fun attention_crossexam() = shot("s03_attention_crossexam", pkg("attention"), FrameId.CASE) { e, s -> first(e, s, SlotKind.BOSS) }
    @Test fun attention_board() = shot("s04_attention_recap_board", pkg("attention"), FrameId.CASE) { _, s -> s.copy(phase = com.anything2game.engine.session.Phase.Recap) }
    @Test fun resnet_escape_lock() = shot("s05_resnet_escape_lock", pkg("resnet"), FrameId.ESCAPE) { e, s -> first(e, s, SlotKind.BOSS) }
    @Test fun mapreduce_companion() = shot("s06_mapreduce_companion_leadin", pkg("mapreduce"), FrameId.COMPANION) { e, s -> e.reduce(s, Action.Next).state }
    @Test fun slides_classic() = shot("s07_cs231n_unit", pkg("cs231n-l1"), FrameId.CLASSIC) { _, s -> s }
    @Test fun zh_case_crossexam() {
        RuntimeEnvironment.setQualifiers("+zh")
        shot("s08_zh_case_crossexam", pkg("zh-spaced"), FrameId.CASE) { e, s -> first(e, s, SlotKind.BOSS) }
    }
}
