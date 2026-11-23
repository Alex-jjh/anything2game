package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import com.anything2game.app.R
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class TutorFeedbackJourneysTest : LearnJourneyBase() {
    @Test fun example_is_identified_after_reopening_and_rephrase_changes_the_label_with_the_reply() {
        reachScene(attention)
        readPages("first explanation")
        choose(attention, "tutor_example")
        assertEquals("Example", textOf("dialogue_mode"))
        val example = lastTutorMessage(attention)
        restartProcess(); waitForTag("home_LEARN"); startExplanation(attention); sceneReady()
        assertEquals(example, lastTutorMessage(attention))
        assertEquals("Example", textOf("dialogue_mode"))
        readPages("saved example")
        choose(attention, "tutor_rephrase")
        assertEquals("Rephrased", textOf("dialogue_mode"))
        assertNotEquals(example.text, lastTutorMessage(attention).text)
    }

    @Test fun a_reader_can_turn_a_page_back_go_back_to_an_earlier_concept_and_take_the_whole_walk_again() {
        reachScene(attention)
        val concepts = conceptsInTeachingOrder(attention)
        // A page can be turned back, and that is only paging: the session does not move.
        val first = textOf("dialogue_text")
        val before = cache(attention).session
        rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle()
        assertNotEquals(first, textOf("dialogue_text"))
        rule.onNodeWithTag("dialogue_back").performClick(); rule.waitForIdle()
        assertEquals(first, textOf("dialogue_text"))
        assertFalse("the first page has nothing to go back to", has("dialogue_back"))
        assertEquals(before, cache(attention).session)

        // Walk to the third concept, then go back to the first from the concept list.
        readPages("c1"); choose(attention, "tutor_continue")
        readPages("c2"); choose(attention, "tutor_continue")
        assertEquals(conceptLine(3, 4), textOf("concept_count"))
        rule.onNodeWithTag("concept_progress_button").performClick()
        waitForTag("concept_sheet")
        assertTrue("the current concept says so: ${textOf("concept_item_2")}", textOf("concept_item_2").contains(str(R.string.explainer_concept_here)))
        assertTrue("a concept not reached yet cannot be jumped to", rule.onNodeWithTag("concept_item_3").fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) != null)
        rule.onNodeWithTag("concept_item_0").performClick()
        waitFor("the first concept again") { textOf("concept_count") == conceptLine(1, 4) && !has("typing_indicator") }
        assertEquals("the explanation of that concept", concepts.first().concept, textOf("dialogue_title"))
        assertEquals(str(R.string.explainer_mode_again), textOf("dialogue_mode"))

        // Going back did not undo the walk: the three concepts already heard are still offered.
        assertEquals(3, cache(attention).session.conceptsIntroduced)
        rule.onNodeWithTag("concept_progress_button").performClick(); waitForTag("concept_sheet")
        rule.onNodeWithTag("concept_item_2").assertIsEnabled()
        assertFalse("already at the first concept, so there is nothing to restart", has("concept_restart"))

        // Taking the whole walk again from somewhere in the middle.
        readPages("c1 again"); choose(attention, "tutor_continue")
        assertEquals(conceptLine(2, 4), textOf("concept_count"))
        rule.onNodeWithTag("concept_progress_button").performClick(); waitForTag("concept_sheet")
        rule.onNodeWithTag("concept_restart").performClick()
        waitFor("the first concept from the restart") { textOf("concept_count") == conceptLine(1, 4) && !has("typing_indicator") }
        assertEquals("the walk it already made is still remembered", 3, cache(attention).session.conceptsIntroduced)
    }

    @Test fun page_navigation_keeps_the_concept_and_only_next_concept_advances_learning() {
        RuntimeEnvironment.setFontScale(2f)
        reachScene(attention)
        val original = cache(attention).session
        val first = textOf("dialogue_text")
        rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle()
        assertNotEquals(first, textOf("dialogue_text"))
        assertEquals(original, cache(attention).session)
        assertTrue("this long reply still has several pages", has("dialogue_skip"))
        rule.onNodeWithTag("dialogue_skip").performClick(); rule.waitForIdle()
        assertTrue("jump ends this reply, not the concept", has("tutor_continue"))
        assertEquals(original, cache(attention).session)
        choose(attention, "tutor_continue")
        assertEquals(original.conceptPosition + 1, cache(attention).session.conceptPosition)
        assertTrue(cache(attention).session.revision > original.revision)
        // At the penultimate page, Next and Last page would do the same thing: offer only Next.
        repeat(20) {
            val page = rule.onNodeWithTag("dialogue_page").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ContentDescription)!!.single()
            val numbers = Regex("\\d+").findAll(page).map { it.value.toInt() }.toList()
            if (numbers[1] - numbers[0] == 1) {
                assertFalse("no redundant last-page action", has("dialogue_skip"))
                rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle()
                assertTrue(has("tutor_continue"))
                assertEquals(original.conceptPosition + 1, cache(attention).session.conceptPosition)
                return
            }
            rule.onNodeWithTag("dialogue_next").performClick(); rule.waitForIdle()
        }
        fail("did not reach the penultimate page")
    }
}
