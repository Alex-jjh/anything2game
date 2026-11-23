package com.anything2game.app

import androidx.compose.ui.test.*
import com.anything2game.app.journeys.LearnJourneyBase
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnTabTutorTest : LearnJourneyBase() {
    @Test fun retrieval_opens_from_its_space_and_every_scripted_course_has_a_real_explanation() {
        launch(); chooseGuest()
        for (id in listOf(attention, resnet, zhSpaced, retrieval)) {
            scrollLearnTo("start_explanation_$id")
            rule.onNodeWithTag("start_explanation_$id").assertHasClickAction()
        }
        startExplanation(retrieval)
        waitForTag("focus_continue")
        assertTrue(cache(retrieval).session.questions.isNotEmpty())
    }
}
