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
    @Test fun diagnostic_requires_answers_then_opens_tutor_not_practice() {
        val deps = AndroidAppContainer(RuntimeEnvironment.getApplication())
        rule.setContent { A2gTheme { ReadingContent(deps, "pkg_demo_retrieval", onBack = {}) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText("FIXED EXAMPLE / MOCK · NO AI").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("diagnostic_next"))
        rule.onNodeWithTag("diagnostic_next").assertIsNotEnabled()
        repeat(3) {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("answer_unknown"))
            rule.onNodeWithTag("answer_unknown").performClick()
            rule.onNodeWithTag("diagnostic_next").performScrollTo()
            rule.waitUntil(10000) { rule.onAllNodes(hasTestTag("diagnostic_next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("diagnostic_next").performClick()
            val nextHeading = if (it < 2) "FOUNDATION ${it + 2} / 3" else "Your starting point"
            rule.waitUntil(10000) { rule.onAllNodesWithText(nextHeading).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithText("Your starting point").assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("start_tutor"))
        rule.onNodeWithTag("start_tutor").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Talk through the document").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("tutor_example"))
        rule.onNodeWithTag("tutor_example").performClick()
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("message_source"))
        rule.onAllNodesWithTag("message_source").onFirst().assertExists()
        rule.onNodeWithText("Start practice").assertDoesNotExist()
    }
}
