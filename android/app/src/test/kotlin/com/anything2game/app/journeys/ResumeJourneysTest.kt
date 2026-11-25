package com.anything2game.app.journeys

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.anything2game.app.MainActivity
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.session.Phase
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/** Journey 6: leaving a level any way a phone allows must bring the student back to the same step. */
class ResumeJourneysTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"
    private val level1 = "Why the Transformer Replaces Recurrence"

    private fun startClassic(): Level {
        launch(); chooseGuest(); openPractice()
        startLevel(attentionTitle, level1)
        return Level("demo_attention", FrameId.CLASSIC)
    }
    private fun inProgressText(l: Level) = l.session()!!.let { str(R.string.status_in_progress, it.position + 1, it.queue.size) }

    @Test fun back_button_mid_level_then_resume_shows_the_same_step() {
        val level = startClassic()
        level.play(steps = 3)                     // clue 1 answered, next, clue 2 on screen
        val at = level.here()
        systemBack()
        waitForTag("space_materials")
        waitFor("Practice to show the open level") { levelStatus(attentionTitle, level1) == inProgressText(level) }
        assertEquals(at, level.here())

        tapText(str(R.string.home_resume))
        waitFor("play screen") { exists(hasContentDescription(str(R.string.action_back))) }
        assertEquals("resuming must not restart or skip", at, level.here())
        // The verdict of clue 2 is on screen again (not a fresh question), with the way forward.
        val s = level.session()!!
        assertTrue(s.phase is Phase.Feedback)
        waitFor("the verdict's Next button") { exists(hasText(str(R.string.action_next)) and hasClickAction()) }
        // The student can carry on from here to the end.
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
    }

    @Test fun recreating_the_activity_keeps_the_verdict_on_screen_and_half_swiped_cards() {
        val level = startClassic()
        // First clue is a swipe set: decide card 1 only, then the system recreates the activity.
        val first = level.item(level.session()!!) as SwipeItem
        assertTrue("this journey needs a multi-card swipe first", first.cards.size >= 2)
        waitForText(first.cards[0].statement)
        level.swipeCard(first.cards[0].statement, first.cards[0].supported)
        waitForText(first.cards[1].statement)
        scenario!!.recreate()
        rule.waitForIdle()
        waitFor("second card after recreation") { textShown(first.cards[1].statement) }
        assertFalse("the card already decided came back", textShown(first.cards[0].statement))
        assertEquals(0, level.session()!!.results.size)

        // Finish the clue, get a verdict, recreate again: the verdict is still there.
        first.cards.drop(1).forEach { level.swipeCard(it.statement, it.supported) }
        waitFor("verdict on disk") { level.session()!!.results.size == 1 }
        val verdict = level.session()!!
        assertTrue(verdict.phase is Phase.Feedback)
        val label = str(R.string.action_next)
        scenario!!.recreate()
        waitFor("verdict after recreation") { exists(hasText(label) and hasClickAction()) }
        assertEquals(verdict, level.session())
    }

    @Test fun rotating_the_phone_mid_level_stays_on_the_same_step() {
        val level = startClassic()
        level.play(steps = 2)                     // clue 1 answered and next pressed: clue 2 on screen
        val at = level.here()
        val before = scenario!!.let { var a: MainActivity? = null; it.onActivity { act -> a = act }; a }
        RuntimeEnvironment.setQualifiers("+land")
        rule.waitForIdle()
        val after = scenario!!.let { var a: MainActivity? = null; it.onActivity { act -> a = act }; a }
        assertNotSame("rotation must recreate the activity for this test to mean anything", before, after)
        waitFor("play screen in landscape") { exists(hasContentDescription(str(R.string.action_back))) }
        assertEquals(at, level.here())
        RuntimeEnvironment.setQualifiers("+port")
        rule.waitForIdle()
        waitFor("play screen in portrait") { exists(hasContentDescription(str(R.string.action_back))) }
        assertEquals(at, level.here())
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
    }

    @Test fun killing_the_app_mid_level_resumes_from_practice_at_the_same_step() {
        val level = startClassic()
        level.play(steps = 3)
        val at = level.here()
        restartProcess()
        waitForTag("tab_SPACES")
        openPractice()
        waitFor("open level after restart") { levelStatus(attentionTitle, level1) == inProgressText(level) }
        tapText(str(R.string.home_resume))
        waitFor("play screen") { exists(hasContentDescription(str(R.string.action_back))) }
        assertEquals(at, level.here())
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
    }

    @Test fun evidence_panel_closes_with_back_and_leaves_the_step_alone() {
        val level = startClassic()
        level.play(steps = 1)                     // answer clue 1 -> verdict on screen
        val at = level.here()
        tapText(str(R.string.action_show_evidence))
        waitForTag("source_viewer")
        systemBack()                              // first back closes the panel ...
        waitFor("panel closed") { !exists(androidx.compose.ui.test.hasTestTag("source_viewer")) }
        assertEquals(at, level.here())
        assertTrue("... and stays in the level", exists(hasContentDescription(str(R.string.action_back))))
    }

    @Test fun playing_a_finished_level_again_from_practice_starts_a_new_run() {
        val level = startClassic()
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
        tapText(str(R.string.recap_library))
        waitForTag("space_materials")
        waitFor("Practice to refresh") { levelStatus(attentionTitle, level1) != str(R.string.status_new) }
        startLevel(attentionTitle, level1)
        // A fresh run: first clue, nothing answered yet — not the old recap.
        waitFor("a fresh session on disk") { level.session()!!.let { it.phase == Phase.Presenting && it.position == 0 && it.results.isEmpty() } }
        val first = level.item(level.session()!!)!!
        assertFalse("the old recap is shown instead of a new run", textShown(str(R.string.recap_library)))
        if (first is SwipeItem) waitForText(first.cards.first().statement)
    }

    @Test fun a_walking_signal_while_levels_are_closed_does_not_rewrite_the_open_session() {
        val level = startClassic()
        val level2 = "How Attention Actually Works"
        level.play(steps = 2)                     // L1 touched ...
        systemBack(); waitForTag("space_materials")
        startLevel(attentionTitle, level2)
        waitFor("level 2 to open") { level.session()!!.levelId == "L2" }
        level.play(steps = 2)                     // ... then L2 touched (one session per course: this replaces L1's)
        systemBack(); waitForTag("space_materials")
        startLevel(attentionTitle, level1)
        waitFor("level 1 to open again") { level.session()!!.levelId == "L1" }
        level.play(steps = 3)                     // ... and L1 again: this is the session the student left open
        val open = level.session()!!
        assertEquals("L1", open.levelId)
        systemBack(); waitForTag("space_materials")

        // The phone says "walking" while the student is on the home screen.
        val sensors = deps.contextSource as com.anything2game.app.platform.SimulatedContextSource
        sensors.setActivity(com.anything2game.engine.context.ContextSignals.Activity.WALKING)
        rule.waitForIdle(); Thread.sleep(500); rule.waitForIdle()
        val now = level.session()!!
        assertEquals("a closed level rewrote the course's open session", "L1", now.levelId)
        assertEquals(open.results, now.results)
        assertEquals(open.position, now.position)
        sensors.setActivity(com.anything2game.engine.context.ContextSignals.Activity.STILL)
    }
}
