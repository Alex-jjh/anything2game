package com.anything2game.app

import androidx.compose.ui.test.*
import com.anything2game.app.journeys.LearnJourneyBase
import org.junit.Assert.*
import org.junit.Test

class HomeFlowTest : LearnJourneyBase() {
    @Test fun three_tabs_select_their_destinations_and_spaces_offer_multi_pdf_import() {
        launch(); chooseGuest()
        rule.onNodeWithTag("tab_SPACES").assertIsSelected()
        tap(rule.onNodeWithTag("tab_PLAYGROUND"))
        rule.onNodeWithTag("tab_PLAYGROUND").assertIsSelected()
        waitForTag("home_PLAYGROUND")
        openMe()
        rule.onNodeWithTag("tab_PROFILE").assertIsSelected()
        openMaterial(title(attention))
        rule.onNodeWithTag("space_materials").performScrollToNode(hasTestTag("pick_pdfs"))
        rule.onNodeWithTag("pick_pdfs").assertHasClickAction()
    }
    @Test fun profile_shortcut_opens_spaces() {
        launch(); chooseGuest(); openMe()
        scrollMeTo(str(R.string.nav_spaces))
        tap(rule.onNode(hasText(str(R.string.nav_spaces)) and hasClickAction() and hasAnyAncestor(hasTestTag("home_PROFILE"))))
        rule.onNodeWithTag("tab_SPACES").assertIsSelected()
    }
    @Test fun practice_only_course_is_playable_without_going_through_a_tutor_or_self_rating() {
        launch(); chooseGuest()
        val level = Level("demo_mapreduce", com.anything2game.contract.FrameId.CLASSIC)
        openMaterial(level.pkg.title)
        assertFalse(has("start_explanation_demo_mapreduce"))
        startLevel(level.pkg.title, level.pkg.levels.first().title)
        assertEquals(level.pkg.levels.first().levelId, level.session()!!.levelId)
    }
    @Test fun the_material_action_shows_the_saved_explanation_position() {
        launch(); chooseGuest()
        assertLearnCard(attention, str(R.string.tutor_start_explanation))
        startExplanation(attention)
        chooseFocus()
        systemBack()
        assertLearnCard(attention, str(R.string.tutor_card_continue))
    }

    @Test fun old_unversioned_history_is_visible_without_granting_a_learning_day() {
        val directory = java.io.File(app.filesDir, "mastery").apply { mkdirs() }
        val record = com.anything2game.engine.mastery.MasteryRecord("old-unit", attempts = 3)
        java.io.File(directory, "demo_attention.json").writeText(com.anything2game.engine.session.SessionCodec.encodeMastery(listOf(record)))
        // Existing installations with history reopen as their guest profile.
        launch(); waitForTag("tab_PROFILE"); openMe()
        scrollMeTo(str(R.string.history_units, 1))
        assertTrue(textShown(str(R.string.history_units, 1)))
        scrollMeTo(str(R.string.completed_days, 0, 0))
        assertTrue(textShown(str(R.string.completed_days, 0, 0)))
    }
}
