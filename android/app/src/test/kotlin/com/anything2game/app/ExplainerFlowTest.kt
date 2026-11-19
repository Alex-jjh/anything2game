package com.anything2game.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.anything2game.app.ui.reading.ReadingContent
import com.anything2game.app.ui.theme.A2gTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The fixed example, end to end, through the explainer scene: diagnostic → starting point → pages → choices → ask → log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplainerFlowTest {
    @get:Rule val rule = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Before fun still() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String): String = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun settled() = rule.waitUntil(10000) { !has("typing_indicator") && has("dialogue_text") }

    /** Taps the dialogue box until the last page is showing (the choice bar is then on screen). */
    private fun readToEnd() {
        settled()
        var guard = 0
        while (!has("tutor_rephrase") && guard++ < 12) { rule.onNodeWithTag("dialogue_box").performClick(); rule.waitForIdle() }
        rule.onNodeWithTag("tutor_rephrase").assertIsDisplayed()
    }

    private fun choose(tag: String) {
        val before = textOf("dialogue_text")
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(10000) { has("dialogue_text") && !has("typing_indicator") && textOf("dialogue_text") != before }
    }

    @Test fun fixed_example_end_to_end() {
        val deps = AndroidAppContainer(app)
        rule.setContent { A2gTheme { ReadingContent(deps, "pkg_demo_retrieval", onBack = {}) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Pre-generated sample").fetchSemanticsNodes().isNotEmpty() }

        // Every step before the scene is introduced by the explainer, the focus question included.
        rule.onNodeWithTag("explainer_header").assertExists()
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_continue"))
        rule.onNodeWithTag("focus_continue").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("FOUNDATION 1 / 3").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("explainer_header").assertExists()
        repeat(3) {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("answer_unknown"))
            rule.onNodeWithTag("answer_unknown").performClick()
            rule.onNodeWithTag("diagnostic_next").performScrollTo()
            rule.waitUntil(10000) { rule.onAllNodes(hasTestTag("diagnostic_next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("diagnostic_next").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText(if (it < 2) "FOUNDATION ${it + 2} / 3" else "Your starting point").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNodeWithTag("explainer_header").assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("start_tutor"))
        rule.onNodeWithTag("start_tutor").performClick()

        // The scene: explainer on stage, the player's own character in the corner, one page in the dialogue box.
        rule.waitUntil(10000) { has("explainer_stage") && has("dialogue_text") }
        rule.onNodeWithTag("explainer_stage").assertIsDisplayed()
        rule.onNodeWithTag("player_avatar").assertIsDisplayed()
        assertEquals("Aya", textOf("dialogue_speaker"))
        assertTrue("the first page of a two-page message hides the choice bar", !has("tutor_rephrase"))
        val firstPage = textOf("dialogue_text")
        rule.onNodeWithTag("dialogue_box").performClick()
        assertNotEquals("tap turns the page", firstPage, textOf("dialogue_text"))
        rule.onNodeWithTag("tutor_rephrase").assertIsDisplayed()
        rule.onNodeWithTag("message_source").assertExists()
        assertEquals("Concept 1/3", textOf("concept_count"))

        // Rephrase, example, continue.
        choose("tutor_rephrase"); readToEnd()
        choose("tutor_example"); readToEnd()
        choose("tutor_continue"); readToEnd()
        assertEquals("Concept 2/3", textOf("concept_count"))

        // Ask through the bottom sheet: the question is the player's line, then the explainer answers.
        rule.onNodeWithTag("tutor_ask").performClick()
        rule.onNodeWithTag("tutor_send").assertIsNotEnabled()
        rule.onNodeWithTag("tutor_input").performTextInput("什么是检索？")
        rule.onNodeWithTag("tutor_send").assertIsEnabled().performClick()
        rule.waitUntil(10000) { has("dialogue_text") && !has("typing_indicator") && textOf("dialogue_speaker") != "Aya" }
        assertEquals("Mochi", textOf("dialogue_speaker"))
        assertEquals("什么是检索？", textOf("dialogue_text"))
        rule.onNodeWithTag("dialogue_box").performClick()
        assertEquals("Aya", textOf("dialogue_speaker"))
        readToEnd()

        // Nothing is lost: the Log lists the whole conversation, with sources.
        rule.onNodeWithTag("explainer_log").performClick()
        rule.waitUntil(10000) { has("explainer_log_list") }
        // The log opens at the latest message; earlier ones are a scroll away.
        rule.onNodeWithText("什么是检索？").assertExists()
        listOf("按我的摸底结果开始讲解", "换个说法", "举个例子", "继续讲解").forEach {
            rule.onNodeWithTag("explainer_log_list").performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithTag("explainer_log_list").performScrollToNode(hasTestTag("message_source_log"))
        assertTrue(has("message_source_log"))
        rule.onNodeWithText("Start practice").assertDoesNotExist()
    }

    @Test fun last_concept_offers_to_go_back_to_the_shelf() {
        val deps = AndroidAppContainer(app)
        var back = 0
        rule.setContent { A2gTheme { ReadingContent(deps, "pkg_demo_retrieval", onBack = { back++ }) } }
        rule.waitUntil(10000) { rule.onAllNodesWithText("Pre-generated sample").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tutor_list").performScrollToNode(hasTestTag("focus_continue"))
        rule.onNodeWithTag("focus_continue").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("FOUNDATION 1 / 3").fetchSemanticsNodes().isNotEmpty() }
        repeat(3) {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("answer_unknown"))
            rule.onNodeWithTag("answer_unknown").performClick()
            rule.onNodeWithTag("diagnostic_next").performScrollTo()
            rule.waitUntil(10000) { rule.onAllNodes(hasTestTag("diagnostic_next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("diagnostic_next").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText(if (it < 2) "FOUNDATION ${it + 2} / 3" else "Your starting point").fetchSemanticsNodes().isNotEmpty() }
        }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("start_tutor"))
        rule.onNodeWithTag("start_tutor").performClick()
        rule.waitUntil(10000) { has("dialogue_text") }
        readToEnd(); choose("tutor_continue"); readToEnd(); choose("tutor_continue"); readToEnd()
        assertEquals("Concept 3/3", textOf("concept_count"))
        rule.onNodeWithTag("tutor_continue").assertDoesNotExist()
        rule.onNodeWithTag("tutor_finish").performClick()
        assertEquals(1, back)
    }
}
