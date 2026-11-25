package com.anything2game.app.journeys

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.engine.session.Phase
import org.junit.Assert.*
import org.junit.Test

/** Journey 1: a first launch as the sample student, then finishing the half-played case. */
class LinJourneyTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"

    @Test fun fresh_install_continue_as_lin_resume_the_open_case_and_finish_it() {
        launch()
        // First launch: the welcome screen, nothing else.
        waitForTag("welcome")
        assertTrue(textShown(str(R.string.profile_sample_action)))
        assertTrue(textShown(str(R.string.profile_guest_action)))
        chooseLin()
        openPractice()

        // Lin has history: Attention is due for review (one miss, answered with help), ResNet is half-way.
        assertEquals(str(R.string.status_due), levelStatus(attentionTitle, "Why the Transformer Replaces Recurrence"))
        val resnet = Level("demo_resnet", FrameId.CASE)
        val open = resnet.session()!!
        assertFalse(open.isFinished)
        val resnetLevel = resnet.pkg.level(open.levelId)!!
        openMaterial(Level("demo_resnet", FrameId.CASE).pkg.title); unfoldPractice(Level("demo_resnet", FrameId.CASE).pkg.title)
        scrollPracticeTo(str(R.string.status_in_progress, open.position + 1, open.queue.size))
        val before = resnet.mastery()

        tapText(str(R.string.home_resume))
        waitFor("play screen") { exists(androidx.compose.ui.test.hasContentDescription(str(R.string.action_back))) }
        // The resumed level opens in its own frame (Case), at the saved step, not at the start.
        assertEquals(open.position, resnet.session()!!.position)
        assertEquals(FrameId.CASE, resnet.session()!!.frameId)

        resnet.play()
        val done = resnet.session()!!
        assertEquals(Phase.Recap, done.phase)
        waitForText(str(R.string.stamp_case_closed))
        waitForText(str(R.string.recap_library))
        assertTrue("mastery moved on for the clues played after resuming", resnet.mastery() != before)
        assertTrue(resnetLevel.units.all { resnet.mastery()[it.unitId]!!.attempts > 0 })

        // Back home: the in-progress status is gone.
        tapText(str(R.string.recap_library))
        waitForTag("space_materials")
        waitFor("Practice to stop offering to resume a finished case") { !textShown(str(R.string.home_resume)) }
        assertNotEquals("In progress", levelStatus(resnet.pkg.title, resnetLevel.title).take(11))
    }
}
