package com.anything2game.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import com.anything2game.app.R
import com.anything2game.app.platform.SimulatedContextSource
import com.anything2game.contract.FrameId
import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.*
import org.junit.Test

/** Everyday things a student does between the big journeys: miss a clue, walk, change character. */
class EverydayJourneysTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"
    private val level1 = "Why the Transformer Replaces Recurrence"

    @Test fun a_missed_clue_comes_back_in_the_same_run_and_shows_up_as_shaky_in_the_recap() {
        launch(); chooseGuest(); openPractice()
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CLASSIC)
        val queue0 = level.session()!!.queue
        level.step { _, _ -> false }              // first clue wrong
        waitForText(str(R.string.retry_note))
        val s = level.session()!!
        assertEquals("one retry slot queued", queue0.size + 1, s.queue.size)
        val retry = s.queue.filter { it.retry }
        assertEquals(1, retry.size)
        assertEquals(queue0[0].unitId, retry.single().unitId)
        assertTrue("the retry is behind the current clue", s.queue.indexOf(retry.single()) > s.position)

        level.play()                              // everything else right, including the retry
        val done = level.session()!!
        assertEquals(Phase.Recap, done.phase)
        waitForText(str(R.string.recap_shaky, 1))
        val missed = level.mastery().getValue(queue0[0].unitId)
        assertEquals("one miss, one help-assisted success", 2, missed.attempts)
        assertEquals(1, missed.misses)
        assertEquals("a missed clue stays in box 0", 0, missed.box)
    }

    @Test fun by_default_walking_does_not_pause_it_goes_one_handed() {
        launch(); chooseGuest(); openPractice()
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CLASSIC)
        level.play(steps = 1)
        val sensors = deps.contextSource as SimulatedContextSource
        sensors.setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.one_handed_badge))
        assertTrue("no pause by default", !textShown(str(R.string.paused_title)))
        assertNull(level.session()!!.pausedFor)
        sensors.setActivity(ContextSignals.Activity.STILL)
    }

    @Test fun walking_pauses_the_level_and_the_student_can_continue_and_finish() {
        launch(); chooseGuest()
        deps.homePreferences.pauseWhenWalking = true // opt-in since the team review
        openPractice()
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CLASSIC)
        level.play(steps = 2)
        val at = level.here()
        val sensors = deps.contextSource as SimulatedContextSource
        sensors.setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.paused_title))
        assertEquals("pausing never moves the step", at, level.here())
        tapText(str(R.string.paused_continue))
        waitFor("overlay gone") { !textShown(str(R.string.paused_title)) }
        assertEquals(at, level.here())
        assertNull("the pause is cleared on disk too", level.session()!!.pausedFor)
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
        sensors.setActivity(ContextSignals.Activity.STILL)
    }

    @Test fun a_level_left_paused_does_not_greet_the_student_with_a_pause_after_they_have_stopped() {
        launch(); chooseGuest()
        deps.homePreferences.pauseWhenWalking = true
        openPractice()
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CLASSIC)
        level.play(steps = 2)
        (deps.contextSource as SimulatedContextSource).setActivity(ContextSignals.Activity.WALKING)
        waitForText(str(R.string.paused_title))
        // The phone is put away mid-walk and the app is later reopened while the student sits still.
        restartProcess()
        waitForTag("tab_SPACES"); openPractice()
        waitFor("open level") { levelStatus(attentionTitle, level1).startsWith("In progress") }
        tapText(str(R.string.home_resume))
        waitFor("play screen") { exists(hasContentDescription(str(R.string.action_back))) }
        Thread.sleep(300); rule.waitForIdle()
        assertFalse("still shows 'Paused while you walk' although nobody is walking", textShown(str(R.string.paused_title)))
    }

    @Test fun each_profile_keeps_its_own_character() {
        launch(); chooseGuest(); openMe()
        val pick = com.anything2game.app.ui.character.Avatars.KUMO
        fun chosen(): List<String> = rule.onAllNodes(androidx.compose.ui.test.isSelectable()).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        scrollMeTo(str(R.string.avatar_pick_title))
        tapText(str(pick.nameRes))
        waitFor("Kumo picked") { str(pick.nameRes) in chosen() }
        assertEquals("kumo", deps.avatarPreferences.get())

        switchProfile(); chooseLin(); openMe()
        scrollMeTo(str(R.string.avatar_pick_title))
        waitFor("Lin has Pip") { str(com.anything2game.app.ui.character.Avatars.PIP.nameRes) in chosen() }
        assertEquals("pip", deps.avatarPreferences.get())

        switchProfile(); chooseGuest(); openMe()
        scrollMeTo(str(R.string.avatar_pick_title))
        waitFor("guest still has Kumo") { str(pick.nameRes) in chosen() }
        restartProcess()
        waitForTag("tab_PROFILE")
        assertEquals("kumo", deps.avatarPreferences.get())
    }
}
