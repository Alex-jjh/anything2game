package com.anything2game.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.common.ContextSimulator
import com.anything2game.app.ui.importing.ImportContent
import com.anything2game.app.ui.library.LevelEntry
import com.anything2game.app.ui.library.LevelStatus
import com.anything2game.app.ui.library.LibraryContent
import com.anything2game.app.ui.library.LibraryEntry
import com.anything2game.app.ui.library.LibraryUiState
import com.anything2game.app.ui.play.PlayContent
import com.anything2game.app.ui.play.PlayUiState
import com.anything2game.app.ui.theme.A2g
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.ports.PackageSummary
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
    private val dir = File(System.getProperty("contract.examples")!!)
    private val en = ContractJson.decodePackage(File(dir, "package.v1.example.json").readText())
    private val zh = ContractJson.decodePackage(File(dir, "package.v1.courtyard.zh.json").readText())

    private fun engine(pkg: LearningPackage) = LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy())
    private val still = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.STILL))

    private fun ready(pkg: LearningPackage, s: SessionState, ctx: ContextDecision = still): PlayUiState.Ready {
        val e = engine(pkg)
        val slot = s.currentSlot
        return PlayUiState.Ready(pkg, s, e.unit(s), e.item(s), if (s.isFinished) e.recap(s) else null, ctx,
            slot != null && s.queue.drop(s.position + 1).any { it.unitId == slot.unitId && it.retry })
    }

    private fun SessionState.act(pkg: LearningPackage, vararg actions: Action) = actions.fold(this) { s, a -> engine(pkg).reduce(s, a).state }

    private fun shot(name: String, content: @Composable () -> Unit) = captureRoboImage("build/outputs/roborazzi/$name.png") {
        A2gTheme { Box(Modifier.fillMaxSize().background(A2g.skin.paper)) { content() } }
    }

    private fun play(name: String, pkg: LearningPackage, s: SessionState, ctx: ContextDecision = still) =
        shot(name) { PlayContent(ready(pkg, s, ctx), onAction = {}, onBack = {}, onRestart = {}) }

    private fun library() = LibraryUiState(
        loading = false,
        entries = listOf(en, zh).map { p ->
            LibraryEntry(
                PackageSummary(p.packageId, p.title, p.language, p.levels.size, preGenerated = true),
                p.levels.mapIndexed { i, l -> LevelEntry(l.levelId, i + 1, l.title, if (p === en) LevelStatus.InProgress(2, 3) else LevelStatus.New) },
            )
        },
    )

    @Test fun library_en() = shot("01_library_en") {
        LibraryContent(library(), onOpenLevel = { _, _ -> }, onPickPdf = {}, footer = {
            ContextSimulator(ContextSignals(activity = ContextSignals.Activity.STILL), {}, {})
        })
    }

    @Test fun library_zh() {
        RuntimeEnvironment.setQualifiers("+zh")
        shot("01_library_zh") { LibraryContent(library(), onOpenLevel = { _, _ -> }, onPickPdf = {}) }
    }

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
}
