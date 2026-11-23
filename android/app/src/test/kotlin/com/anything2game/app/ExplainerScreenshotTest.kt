package com.anything2game.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.anything2game.app.data.tutor.EXAMPLE_TUTOR_PACKAGE
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.theme.A2gTheme
import com.anything2game.contract.tutor.TutorIntent
import com.anything2game.contract.tutor.UNKNOWN_ANSWER
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The explainer scene on the real fixed example (real stores, real view model): every state a student can reach. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ExplainerScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val out = "build/outputs/roborazzi/explainer"

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private enum class Stage { FOCUS, FRESH, STARTED, TALKED }

    /** Puts the saved session in the wanted state through the repository, the same way the view model would. */
    private fun seed(stage: Stage, pkg: String = EXAMPLE_TUTOR_PACKAGE): AppDependencies {
        val deps = AndroidAppContainer(app)
        if (stage == Stage.FOCUS) return deps
        runBlocking {
            val repo = deps.tutors
            var cache = repo.open(pkg).cache
            cache = repo.setFocus(cache, emptyList(), "")
            if (stage == Stage.FRESH) { repo.save(cache); return@runBlocking }
            cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = cache.session.questions.lastIndex)
            cache = cache.copy(session = repo.assess(cache, "a"))
            cache = cache.copy(session = repo.turn(cache, TutorIntent.START, "", "s"))
            if (stage == Stage.TALKED) {
                var n = 0
                for ((intent, q) in listOf(TutorIntent.REPHRASE to "", TutorIntent.EXAMPLE to "", TutorIntent.CONTINUE to "", TutorIntent.QUESTION to "这个例子是真实实验吗？"))
                    cache = cache.copy(session = repo.turn(cache, intent, q, "t${n++}"))
            }
            repo.save(cache)
        }
        return deps
    }

    private fun show(deps: AppDependencies, fontScale: Float? = null, ready: String = "dialogue_text", pkg: String = EXAMPLE_TUTOR_PACKAGE) {
        rule.setContent {
            A2gTheme {
                if (fontScale == null) ReadingContent(deps, pkg, onBack = {})
                else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { ReadingContent(deps, pkg, onBack = {}) }
            }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithTag(ready).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun lastPage() { repeat(40) { if (rule.onAllNodesWithTag("tutor_rephrase").fetchSemanticsNodes().isEmpty()) { rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle() } } }

    @Test fun focus_step() {
        show(seed(Stage.FOCUS), ready = "focus_continue")
        rule.onRoot().captureRoboImage("$out/00_focus.png")
    }

    // The two courses used in the product video: Attention (English) and spaced repetition (Chinese).
    @Test fun attention_en() {
        show(seed(Stage.FRESH, "demo_attention"), ready = "answer_unknown", pkg = "demo_attention")
        rule.onRoot().captureRoboImage("$out/10_attention_diagnostic.png")
    }

    @Test fun attention_en_scene() {
        show(seed(Stage.STARTED, "demo_attention"), pkg = "demo_attention")
        rule.onRoot().captureRoboImage("$out/11_attention_page.png")
        lastPage()
        rule.onRoot().captureRoboImage("$out/12_attention_choice_bar.png")
    }

    @Test fun attention_en_ask_sheet() {
        show(seed(Stage.STARTED, "demo_attention"), pkg = "demo_attention"); lastPage()
        rule.onNodeWithTag("tutor_ask").performClick()
        rule.onNodeWithTag("tutor_input").performTextInput("Does this hold for every task?")
        rule.waitForIdle()
        captureScreenRoboImage("$out/13_attention_ask_sheet.png")
    }

    @Test fun zh_spaced_diagnostic() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.FRESH, "demo_zh_spaced"), ready = "answer_unknown", pkg = "demo_zh_spaced")
        rule.onRoot().captureRoboImage("$out/14_zh_spaced_diagnostic.png")
    }

    @Test fun zh_spaced_scene() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.STARTED, "demo_zh_spaced"), pkg = "demo_zh_spaced")
        rule.onRoot().captureRoboImage("$out/15_zh_spaced_page.png")
        lastPage()
        rule.onRoot().captureRoboImage("$out/16_zh_spaced_choice_bar.png")
    }

    /** The scene line by line: the player's own character asking, the section the line is in, and a quick check answered wrong. */
    @Test fun zh_spaced_scene_lines() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.STARTED, "demo_zh_spaced"), pkg = "demo_zh_spaced")
        var shotPlayer = false
        repeat(40) {
            val speaker = rule.onAllNodesWithTag("dialogue_speaker").fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }
            if (!shotPlayer && speaker != null && speaker != "阿雅") { rule.onRoot().captureRoboImage("$out/20_zh_sidekick_line.png"); shotPlayer = true }
            if (rule.onAllNodesWithTag("quick_check").fetchSemanticsNodes().isNotEmpty()) {
                rule.onRoot().captureRoboImage("$out/21_zh_quick_check.png")
                val wrong = listOf("a", "b", "c").first { id -> rule.onAllNodesWithTag("check_$id").fetchSemanticsNodes().isNotEmpty() && id != "b" }
                rule.onNodeWithTag("check_$wrong").performClick(); rule.waitForIdle()
                rule.onRoot().captureRoboImage("$out/22_zh_quick_check_answered.png")
                return
            }
            if (rule.onAllNodesWithTag("dialogue_next").fetchSemanticsNodes().isEmpty()) return
            rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle()
        }
    }

    @Test fun zh_spaced_source_viewer() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.STARTED, "demo_zh_spaced"), pkg = "demo_zh_spaced"); lastPage()
        rule.onNodeWithTag("message_source").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("source_highlighted").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        captureScreenRoboImage("$out/17_zh_spaced_source_viewer.png")
    }

    @Test fun zh_spaced_wrap_up() {
        RuntimeEnvironment.setQualifiers("+zh")
        val deps = seed(Stage.STARTED, "demo_zh_spaced")
        runBlocking {
            var cache = deps.tutors.open("demo_zh_spaced").cache
            repeat(cache.session.questions.size - 1) { n -> cache = cache.copy(session = deps.tutors.turn(cache, TutorIntent.CONTINUE, "", "c$n")) }
            deps.tutors.save(cache)
        }
        show(deps, pkg = "demo_zh_spaced"); lastPage()
        rule.onRoot().captureRoboImage("$out/18_zh_spaced_last_concept.png")
        rule.onNodeWithTag("tutor_finish").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("wrap_up").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$out/19_zh_spaced_wrap_up.png")
    }

    @Test fun diagnostic() {
        show(seed(Stage.FRESH), ready = "answer_unknown")
        rule.onRoot().captureRoboImage("$out/01_diagnostic.png")
    }

    @Test fun explanation_page() {
        show(seed(Stage.STARTED))
        rule.onRoot().captureRoboImage("$out/02_explanation_page.png")
    }

    @Test fun choice_bar() {
        show(seed(Stage.STARTED)); lastPage()
        rule.onRoot().captureRoboImage("$out/03_choice_bar.png")
    }

    @Test fun ask_sheet() {
        show(seed(Stage.STARTED)); lastPage()
        rule.onNodeWithTag("tutor_ask").performClick()
        rule.onNodeWithTag("tutor_input").performTextInput("这个例子是真实实验的结果吗？")
        rule.waitForIdle()
        captureScreenRoboImage("$out/04_ask_sheet.png")
    }

    @Test fun log_sheet() {
        show(seed(Stage.TALKED))
        rule.onNodeWithTag("explainer_log").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("explainer_log_list").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        captureScreenRoboImage("$out/05_log_sheet.png")
    }

    @Test fun asked_question_as_the_players_line() {
        show(seed(Stage.TALKED))
        rule.onRoot().captureRoboImage("$out/06_after_answer_en.png")
    }

    @Test fun chinese() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.STARTED)); lastPage()
        rule.onRoot().captureRoboImage("$out/07_chinese_choice_bar.png")
    }

    @Test fun chinese_diagnostic() {
        RuntimeEnvironment.setQualifiers("+zh")
        show(seed(Stage.FRESH), ready = "answer_unknown")
        rule.onRoot().captureRoboImage("$out/07b_chinese_diagnostic.png")
    }

    @Test fun landscape() {
        RuntimeEnvironment.setQualifiers("w852dp-h393dp-land-xxhdpi")
        show(seed(Stage.STARTED)); lastPage()
        rule.onRoot().captureRoboImage("$out/08_landscape_choice_bar.png")
    }

    @Test fun landscape_first_page() {
        RuntimeEnvironment.setQualifiers("w852dp-h393dp-land-xxhdpi")
        show(seed(Stage.STARTED))
        rule.onRoot().captureRoboImage("$out/08b_landscape_page.png")
    }

    @Test fun font_scale_200() {
        show(seed(Stage.STARTED), fontScale = 2f); lastPage()
        rule.onRoot().captureRoboImage("$out/09_font200_choice_bar.png")
    }

    @Test fun font_scale_200_first_page() {
        show(seed(Stage.STARTED), fontScale = 2f)
        rule.onRoot().captureRoboImage("$out/09b_font200_page.png")
    }

    @Test fun font_scale_200_diagnostic() {
        show(seed(Stage.FRESH, "demo_attention"), fontScale = 2f, ready = "answer_unknown", pkg = "demo_attention")
        rule.onRoot().captureRoboImage("$out/09c_font200_diagnostic.png")
    }
}
