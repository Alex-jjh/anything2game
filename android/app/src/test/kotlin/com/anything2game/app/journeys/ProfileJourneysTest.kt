package com.anything2game.app.journeys

import androidx.compose.ui.test.hasText
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.engine.session.Phase
import org.junit.Assert.*
import org.junit.Test

/** Journey 5: two people share one phone. */
class ProfileJourneysTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"
    private val level1 = "Why the Transformer Replaces Recurrence"

    private data class Me(val documents: Int, val practiced: Int, val due: Int)
    private fun me(): Me = Me(stat(R.string.home_stat_documents), stat(R.string.home_stat_practiced), stat(R.string.home_stat_review))

    @Test fun switching_profile_never_mixes_progress_and_a_restart_keeps_the_chosen_profile() {
        launch()
        chooseLin()
        openMe()
        waitForText("Lin")
        val lin = me()
        assertTrue("Lin has history", lin.practiced > 0)
        openPractice()
        val resume = Level("demo_resnet", FrameId.CASE).session()!!
        val resumeText = str(R.string.level_status_in_progress, resume.position + 1, resume.queue.size)
        openMaterial(Level("demo_resnet", FrameId.CASE).pkg.title)
        scrollPracticeTo(resumeText)

        // Lin -> welcome -> guest: nothing of Lin's is visible.
        switchProfile()
        assertTrue(textShown(str(R.string.profile_sample_action)))
        chooseGuest()
        openPractice()
        assertFalse("Lin's open case leaks into the guest", textShown(str(R.string.home_resume)))
        assertEquals("a guest's course is brand new", str(R.string.level_status_new), levelStatus(attentionTitle, level1))
        openMe()
        assertFalse("Lin's name leaks into the guest", textShown("Lin"))
        assertTrue(textShown(str(R.string.home_guest)))
        assertEquals(Me(lin.documents, 0, 0), me())

        // The guest starts something of their own and leaves it open.
        openPractice()
        startLevel(attentionTitle, level1)
        val guestLevel = Level("demo_attention", FrameId.CLASSIC)
        guestLevel.play(steps = 3)
        val guestAt = guestLevel.here()
        systemBack()
        waitForTag("space_materials")

        // Guest -> Lin: Lin's Attention is untouched by the guest's open session.
        switchProfile()
        chooseLin()
        openPractice()
        waitFor("Lin's Attention to read 'Needs practice'") { levelStatus(attentionTitle, level1) == str(R.string.home_needs_review) }
        openMaterial(Level("demo_resnet", FrameId.CASE).pkg.title)
        scrollPracticeTo(resumeText)
        openMe()
        assertEquals("Lin's figures are intact", lin, me())
        assertTrue(textShown("Lin"))

        // Back to the guest: their open session is still theirs.
        switchProfile(); chooseGuest(); openPractice()
        waitFor("the guest's open level") { levelStatus(attentionTitle, level1).startsWith("In progress") }
        assertEquals(guestAt, guestLevel.here())

        // Process restart: the chosen profile (guest) is remembered, no welcome screen.
        restartProcess()
        waitForTag("tab_SPACES")
        assertFalse("welcome shown again after restart", exists(androidx.compose.ui.test.hasTestTag("welcome")))
        openPractice()
        waitFor("the guest's open level after restart") { levelStatus(attentionTitle, level1).startsWith("In progress") }
        openMe()
        assertTrue(textShown(str(R.string.home_guest)))

        // ...and leaving to the welcome screen is remembered too: a restart there shows the welcome screen, nothing chosen for you.
        switchProfile()
        restartProcess()
        waitForTag("welcome")
        chooseLin()
        openMe()
        assertEquals(lin, me())
    }

    @Test fun lin_is_seeded_once_and_keeps_changes_made_afterwards() {
        launch()
        chooseLin()
        openPractice()
        val resnet = Level("demo_resnet", FrameId.CASE)
        val seeded = resnet.session()!!
        // Lin finishes the open case ...
        openMaterial(Level("demo_resnet", FrameId.CASE).pkg.title)
        scrollPracticeTo(str(R.string.level_status_in_progress, seeded.position + 1, seeded.queue.size))
        tapText(str(R.string.home_resume))
        waitFor("play screen") { exists(androidx.compose.ui.test.hasContentDescription(str(R.string.action_back))) }
        resnet.play()
        assertEquals(Phase.Recap, resnet.session()!!.phase)
        val finished = resnet.mastery()
        // ... leaves and comes back: coming back must not replay the seed over the progress made.
        tapText(str(R.string.recap_library))
        waitForTag("space_materials")
        switchProfile()
        chooseLin()
        assertEquals(finished, resnet.mastery())
        assertEquals(Phase.Recap, resnet.session()!!.phase)
        assertNotEquals(seeded, resnet.session())
    }
}
