package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
