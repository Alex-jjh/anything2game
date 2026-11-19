package com.anything2game.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
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
import com.anything2game.app.data.tutor.FIXED_TUTOR_PACKAGE
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

    private enum class Stage { FRESH, STARTED, TALKED }

    /** Puts the saved session in the wanted state through the repository, the same way the view model would. */
    private fun seed(stage: Stage): AppDependencies {
        val deps = AndroidAppContainer(app)
        if (stage == Stage.FRESH) return deps
        runBlocking {
            val repo = deps.tutors
            var cache = repo.open(FIXED_TUTOR_PACKAGE).cache
            cache = cache.copy(draftAnswers = cache.session.questions.associate { it.id to UNKNOWN_ANSWER }, questionIndex = 2)
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

    private fun show(deps: AppDependencies, fontScale: Float? = null, ready: String = "dialogue_text") {
        rule.setContent {
            A2gTheme {
                if (fontScale == null) ReadingContent(deps, FIXED_TUTOR_PACKAGE, onBack = {})
                else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { ReadingContent(deps, FIXED_TUTOR_PACKAGE, onBack = {}) }
            }
        }
        rule.waitUntil(10000) { rule.onAllNodesWithTag(ready).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun lastPage() { repeat(3) { if (rule.onAllNodesWithTag("tutor_rephrase").fetchSemanticsNodes().isEmpty()) { rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle() } } }

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
}
