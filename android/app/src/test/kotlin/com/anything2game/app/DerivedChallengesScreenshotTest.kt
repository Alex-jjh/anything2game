package com.anything2game.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.FrameId
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The challenges Rookie and Escape derive from a level's own clues (docs/product/frames.md §4), on real demo content:
 * the rookie's teach-back and an Escape room lock, asked and answered wrong (to show the review with its evidence).
 * Output: build/outputs/roborazzi/derived/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DerivedChallengesScreenshotTest {
    /** Still characters: these captures run without a compose test rule, so looping animations would never settle. */
    @Before fun stillCharacters() {
        android.provider.Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private val dir = File(System.getProperty("contract.samples")!!)
    private fun pkg(d: String) = ContractJson.decodePackage(File(dir, "package.demo.$d.json").readText())
    private fun frame(d: String, f: FrameId) = ContractJson.decodeFrame(File(dir, "frame.demo.$d.${f.name.lowercase()}.json").readText())
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun shot(name: String, demo: String, f: FrameId, kind: SlotKind, answered: Boolean = false, check: (LevelSession, SessionState) -> Unit = { _, _ -> }) {
        val p: LearningPackage = pkg(demo)
        val o = frame(demo, f)
        val e = LevelSession(p, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(f), overlay = o)
        val start = e.start(p.levels[0].levelId)
        var s = start.copy(position = start.queue.indexOfFirst { it.kind == kind })
        check(e, s)
        if (answered) s = e.reduce(s, Action.Submit(ScriptedPlayer.wrongAnswer(e.item(s)!!))).state
        val ready = PlayUiState.Ready(p, f, o, s, e.sceneLines(s), e.room(s), e.solvedUnitIds(s), e.unit(s), e.item(s), null, still, retryQueued = false)
        captureRoboImage("build/outputs/roborazzi/derived/$name.png") {
            A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { PlayContent(ready, {}, {}, {}) } }
        }
    }

    /** A teach-back has no unit of its own (no hints, no mastery) but is rendered from its derived item. */
    private val teachBackChecks = { e: LevelSession, s: SessionState ->
        assertNull(e.unit(s)); assertTrue(e.item(s) is DeduceItem); assertEquals("Sam", e.rookieName())
    }
    private val lockChecks = { e: LevelSession, s: SessionState ->
        assertNull(e.unit(s)); assertNotNull(e.item(s) as SwipeItem)
    }

    @Test fun d1_attention_teach_back() = shot("d1_attention_teach_back", "attention", FrameId.ROOKIE, SlotKind.TEACH_BACK, check = teachBackChecks)
    @Test fun d2_attention_teach_back_wrong() = shot("d2_attention_teach_back_wrong", "attention", FrameId.ROOKIE, SlotKind.TEACH_BACK, answered = true, check = teachBackChecks)
    @Test fun d3_resnet_teach_back() = shot("d3_resnet_teach_back", "resnet", FrameId.ROOKIE, SlotKind.TEACH_BACK, check = teachBackChecks)
    @Test fun d4_attention_room_lock() = shot("d4_attention_room_lock", "attention", FrameId.ESCAPE, SlotKind.ROOM_CHECK, check = lockChecks)
    @Test fun d5_attention_room_lock_wrong() = shot("d5_attention_room_lock_wrong", "attention", FrameId.ESCAPE, SlotKind.ROOM_CHECK, answered = true, check = lockChecks)
    @Test fun d6_zh_teach_back() {
        RuntimeEnvironment.setQualifiers("+zh")
        shot("d6_zh_teach_back", "zh-spaced", FrameId.ROOKIE, SlotKind.TEACH_BACK, check = { e, s -> assertNull(e.unit(s)); assertEquals("小萌", e.rookieName()) })
    }
    @Test fun d7_zh_room_lock() {
        RuntimeEnvironment.setQualifiers("+zh")
        shot("d7_zh_room_lock", "zh-spaced", FrameId.ESCAPE, SlotKind.ROOM_CHECK, check = lockChecks)
    }
}
