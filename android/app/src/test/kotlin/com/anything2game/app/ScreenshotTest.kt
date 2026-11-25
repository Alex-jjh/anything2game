package com.anything2game.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.SessionState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders key screens to PNG (build/outputs/roborazzi) so UI can be reviewed without an emulator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    /** Still characters: these captures run without a compose test rule, so looping animations would never settle. */
    @org.junit.Before fun stillCharacters() {
        android.provider.Settings.Global.putFloat(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private val dir = File(System.getProperty("contract.examples")!!)
    private val en = ContractJson.decodePackage(File(dir, "package.v1.example.json").readText())
    private val zh = ContractJson.decodePackage(File(dir, "package.v1.courtyard.zh.json").readText())

    private fun overlay(pkg: LearningPackage, f: FrameId): FrameOverlay? = dir.listFiles { x -> x.name.startsWith("frame.") }!!
        .map { ContractJson.decodeFrame(it.readText()) }.firstOrNull { it.packageId == pkg.packageId && it.frameId == f }

    private fun engine(pkg: LearningPackage, f: FrameId = FrameId.CLASSIC) =
        LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy(), frame = Frames.of(f), overlay = overlay(pkg, f))
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun ready(pkg: LearningPackage, s: SessionState, ctx: ContextDecision = still): PlayUiState.Ready {
        val e = engine(pkg, s.frameId)
        val slot = s.currentSlot
        return PlayUiState.Ready(
            pkg = pkg, frameId = s.frameId, overlay = overlay(pkg, s.frameId), session = s,
            scene = e.sceneLines(s), room = e.room(s), solvedUnitIds = e.solvedUnitIds(s),
            unit = e.unit(s), item = e.item(s), recap = if (s.isFinished && s.phase != com.anything2game.engine.session.Phase.Failed) e.recap(s) else null,
            context = ctx, retryQueued = slot != null && s.queue.drop(s.position + 1).any { it.unitId == slot.unitId && it.retry },
        )
    }

    private fun SessionState.act(pkg: LearningPackage, vararg actions: Action) = actions.fold(this) { s, a -> engine(pkg, frameId).reduce(s, a).state }
    private val enSwipeRight = Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to false, "s3" to false)))
    private val enSortRight = Action.Submit(SortAnswer(mapOf("k1" to "retrieval", "k2" to "restudy", "k3" to "retrieval", "k4" to "restudy")))
    private val zhSwipeRight = Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to false, "s3" to false)))
    private val zhSortRight = Action.Submit(SortAnswer(mapOf("k1" to "recorded", "k2" to "needs_more", "k3" to "recorded", "k4" to "needs_more")))

    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("build/outputs/roborazzi/$name.png") {
        A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { content() } }
    }

    private fun play(name: String, pkg: LearningPackage, s: SessionState, ctx: ContextDecision = still) =
        shot(name) { PlayContent(ready(pkg, s, ctx), onAction = {}, onBack = {}, onRestart = {}) }

    @Test fun swipe_question() = play("02_swipe_question_en", en, engine(en).start("L1").act(en, Action.RevealHint))

    @Test fun swipe_review() = play("03_swipe_review_en", en,
        engine(en).start("L1").act(en, Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to true, "s3" to false)))))

    @Test fun sort_question() {
        val s = engine(en).start("L1").act(en, Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to false, "s3" to false))), Action.Next)
        play("04_sort_question_en", en, s)
    }

    @Test fun sort_review_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val s = engine(zh).start("L1").act(zh,
            Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to false, "s3" to false))), Action.Next,
            Action.Submit(SortAnswer(mapOf("k1" to "recorded", "k2" to "recorded", "k3" to "recorded", "k4" to "needs_more"))))
        play("05_sort_review_zh", zh, s)
    }

    @Test fun deduce_question_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val s = engine(zh).start("L1").copy(position = 2)
        play("06_deduce_question_zh", zh, s)
    }

    @Test fun deduce_wrong_with_retry() {
        val s = engine(en).start("L1").copy(position = 2).act(en, Action.Submit(DeduceAnswer("o1")))
        play("07_deduce_wrong_en", en, s)
    }

    @Test fun evidence_overlay() {
        val d = en.levels[0].units[2].item as DeduceItem
        val s = engine(en).start("L1").copy(position = 2).act(en, Action.Submit(DeduceAnswer("o2")), Action.OpenEvidence(d.sourceRefs.map { it.chunkId }))
        play("08_evidence_en", en, s)
    }

    @Test fun recap() {
        var s = engine(en).start("L1")
        s = s.act(en, Action.Submit(SwipeAnswer(mapOf("s1" to true, "s2" to false, "s3" to false))), Action.Next,
            Action.Submit(SortAnswer(mapOf("k1" to "retrieval", "k2" to "restudy", "k3" to "retrieval", "k4" to "restudy"))), Action.Next,
            Action.Submit(DeduceAnswer("o1")), Action.Next)
        s = s.act(en, Action.Submit(DeduceAnswer("o2")), Action.Next)
        play("09_recap_en", en, s)
    }

    @Test fun paused_walking_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        val walking = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.WALKING))
        val s = engine(zh).start("L1").act(zh, Action.Pause(ContextDecision.PauseReason.WALKING))
        play("10_paused_zh", zh, s, walking)
    }

    @Test fun import_shared_text() = shot("11_import_en") {
        ImportContent(ImportRequest.SharedText("Lecture 7 — Context-aware computing", "Context-aware systems adapt their behaviour to the user's location, activity and surroundings. "
            .repeat(6)), onCancel = {})
    }

    // ---- frames ----
    @Test fun case_intro_scene() = play("20_case_intro_en", en, engine(en, FrameId.CASE).start("L1"))

    @Test fun case_crossexam_question() {
        val s = engine(en, FrameId.CASE).start("L1")
        val boss = s.queue.indexOfFirst { it.kind == com.anything2game.engine.session.SlotKind.BOSS }
        play("21_case_crossexam_en", en, s.copy(position = boss))
    }

    @Test fun case_crossexam_wrong_evidence() {
        val s0 = engine(en, FrameId.CASE).start("L1")
        val boss = s0.queue.indexOfFirst { it.kind == com.anything2game.engine.session.SlotKind.BOSS }
        play("22_case_crossexam_review_en", en, s0.copy(position = boss).act(en, Action.Submit(CrossExamAnswer("st3", "c1"))))
    }

    @Test fun case_recap_board_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        var s = engine(zh, FrameId.CASE).start("L1")
        s = s.act(zh, Action.Next, zhSwipeRight, Action.Next, zhSortRight, Action.Next, Action.Submit(DeduceAnswer("at_three")), Action.Next,
            Action.Submit(CrossExamAnswer("c", "c1")), Action.Next, Action.Next)
        play("23_case_recap_board_zh", zh, s)
    }

    @Test fun case_cold() {
        var s = engine(en, FrameId.CASE).start("L1").act(en, Action.Next)
        s = s.act(en, Action.Submit(SwipeAnswer(emptyMap())), Action.Next, Action.Submit(SortAnswer(emptyMap())), Action.Next,
            Action.Submit(DeduceAnswer("o1")), Action.Next)
        play("24_case_cold_en", en, s)
    }

    @Test fun escape_room_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        play("25_escape_room_zh", zh, engine(zh, FrameId.ESCAPE).start("L1").act(zh, Action.Next))
    }

    @Test fun escape_lock_en() {
        val s = engine(en, FrameId.ESCAPE).start("L1")
        val boss = s.queue.indexOfFirst { it.kind == com.anything2game.engine.session.SlotKind.BOSS }
        play("26_escape_lock_en", en, s.copy(position = boss))
    }

    @Test fun rookie_lead_in_en() = play("27_rookie_leadin_en", en, engine(en, FrameId.ROOKIE).start("L1").act(en, Action.Next))

    @Test fun companion_lead_in_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        play("28_companion_leadin_zh", zh, engine(zh, FrameId.COMPANION).start("L1").act(zh, Action.Next))
    }
}
