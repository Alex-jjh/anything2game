package com.anything2game.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.theme.A2gTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingFlowTest {
    @get:Rule val rule = createComposeRule()
    private fun text(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)
    @Test fun an_answer_moves_on_and_the_last_one_opens_the_tutor_not_practice() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        rule.setContent { A2gTheme { ReadingContent(deps, "pkg_demo_retrieval", onBack = {}) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText(text(R.string.badge_pre_generated)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("FIXED EXAMPLE / MOCK · NO AI").assertDoesNotExist()
        // The example's questions carry facets, so the reader is asked what they want first; "let the explainer decide" is the default.
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_continue"))
        rule.onNodeWithTag("focus_decide").assertIsSelected()
        rule.onNodeWithTag("focus_continue").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText(text(R.string.tutor_question_count, 1, 3)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("diagnostic_next").assertDoesNotExist()   // nothing to move on with before an answer
        repeat(3) {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("answer_unknown"))
            rule.onNodeWithTag("answer_unknown").performClick()   // the answer itself moves on
            val nextHeading = if (it < 2) text(R.string.tutor_question_count, it + 2, 3) else text(R.string.tutor_assessment_title)
            rule.waitUntil(10000) { rule.onAllNodesWithText(nextHeading).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithText(text(R.string.tutor_assessment_title)).assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("start_tutor"))
        rule.onNodeWithTag("start_tutor").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("explainer_stage").fetchSemanticsNodes().isNotEmpty() }
        // The explanation is a scene now: page through the first message, then the choice bar offers the next step.
        rule.waitUntil(10000) { rule.onAllNodesWithTag("dialogue_text").fetchSemanticsNodes().isNotEmpty() }
        repeat(40) { if (rule.onAllNodesWithTag("tutor_example").fetchSemanticsNodes().isEmpty()) rule.onNodeWithTag("dialogue_next").performClick() }
        rule.onNodeWithTag("tutor_example").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("typing_indicator").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithTag("dialogue_text").fetchSemanticsNodes().isNotEmpty() }
        // The player's own character asks for the example first; the explainer's page after it carries the source.
        repeat(3) { if (rule.onAllNodesWithTag("message_source").fetchSemanticsNodes().isEmpty()) { rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle() } }
        rule.onAllNodesWithTag("message_source").onFirst().assertExists()
        rule.onNodeWithText(text(R.string.home_practice_action)).assertDoesNotExist()
    }

    @Test fun focus_puts_the_chosen_facet_first_and_a_scripted_course_offers_no_free_text() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        rule.setContent { A2gTheme { ReadingContent(deps, "pkg_demo_retrieval", onBack = {}) } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("focus_continue").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_limitation"))
        rule.onNodeWithTag("focus_limitation").performClick()
        rule.onNodeWithTag("focus_decide").assertIsNotSelected()
        // A pre-written course has nothing that could read a note: the field is not offered.
        rule.onNodeWithTag("focus_note").assertDoesNotExist()
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_continue"))
        rule.onNodeWithTag("focus_continue").performClick()
        // The first question is now the one about limits (the example's third concept); nothing was dropped.
        rule.waitUntil(10000) { rule.onAllNodesWithText(text(R.string.tutor_question_count, 1, 3)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("结论的适用范围").assertExists()
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_summary"))
        rule.onNodeWithTag("focus_summary").assertExists()
        rule.onNodeWithTag("focus_summary_note").assertDoesNotExist()
        rule.onNodeWithTag("focus_continue").assertDoesNotExist()
    }
}
