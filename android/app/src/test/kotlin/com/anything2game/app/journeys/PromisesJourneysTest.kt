package com.anything2game.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.anything2game.app.R
import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.FrameId
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/** What the screens promise a student, checked against what the app then does. */
class PromisesJourneysTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"
    private val level1 = "Why the Transformer Replaces Recurrence"

    @Test fun a_wrong_cross_examination_costs_credibility_and_the_recap_says_so() {
        launch(); chooseGuest(); openPractice()
        playAs(attentionTitle, FrameId.CASE)
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CASE)
        level.playUntil("the cross-examination") { it.currentSlot?.kind == SlotKind.BOSS }
        val before = level.session()!!.credibility!!
        level.step { _, item -> item !is CrossExamItem }
        assertEquals(before - 1, level.session()!!.credibility)
        waitFor("a not-correct verdict") { textShown(str(R.string.stamp_wrong)) }
        level.play()
        assertEquals(Phase.Recap, level.session()!!.phase)
        waitForText(str(R.string.boss_missed))
    }

    /** The recap no longer promises "tomorrow" for a missed boss; it promises a replay, and the replay does offer the boss again. */
    @Test fun a_missed_boss_can_be_taken_on_again_by_replaying() {
        launch(); chooseGuest(); openPractice()
        playAs(attentionTitle, FrameId.CASE)
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CASE)
        level.play { _, item -> item !is CrossExamItem }
        waitForText(str(R.string.boss_missed))
        tapText(str(R.string.recap_replay))
        waitFor("fresh run") { level.session()!!.let { it.phase == Phase.Presenting && it.results.isEmpty() } }
        assertTrue(level.session()!!.queue.any { it.kind == SlotKind.BOSS })
    }

    @Test fun replay_after_a_cold_case_shows_different_versions_of_the_missed_clues() {
        launch(); chooseGuest(); openPractice()
        playAs(attentionTitle, FrameId.ESCAPE)
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.ESCAPE)
        level.play { _, _ -> false }
        assertEquals(Phase.Failed, level.session()!!.phase)
        val missed = level.session()!!.results.filter { it.slot.kind == SlotKind.UNIT }.map { it.slot.unitId }.distinct()
        assertTrue(missed.isNotEmpty())
        tapText(str(R.string.recap_replay))
        waitFor("fresh run") { level.session()!!.let { it.phase == Phase.Presenting && it.results.isEmpty() } }
        val fresh = level.session()!!
        assertTrue("a missed clue is planned as its main item again", fresh.queue.filter { it.unitId in missed && it.kind == SlotKind.UNIT }.any { it.variant >= 0 })
    }

    @Test fun double_tapping_continue_as_lin_gives_the_same_history_as_one_tap() {
        launch()
        waitForTag("choose_sample")
        val button = rule.onNodeWithTag("choose_sample")
        button.performClick(); button.performClick(); button.performClick()
        waitForTag("tab_SPACES")
        Thread.sleep(1500); rule.waitForIdle()
        val twice = history()

        // The reference: the same history made with one tap, in a fresh install.
        scenario!!.close()
        app.filesDir.deleteRecursively()
        app.getSharedPreferences("profiles", 0).edit().clear().commit()
        listOf("home@sample", "avatar@sample", "frames@sample", "tutor_connection@sample").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
        val field = com.anything2game.app.A2gApplication::class.java.getDeclaredField("dependencies\$delegate")
        field.isAccessible = true
        field.set(app, lazy { com.anything2game.app.AndroidAppContainer(app) })
        launch()
        chooseLin()
        assertEquals("a double tap replayed Lin's history twice", history(), twice)
    }

    /** Lin's history without clock times (the seed is stamped relative to "now", which moves between two installs). */
    private fun history(): Map<String, List<Any?>?> = listOf("demo_attention", "demo_mapreduce", "demo_resnet", "pkg_demo_retrieval").flatMap { id ->
        val l = Level(id, FrameId.CASE)
        l.mastery().map { (unit, r) -> "$id/$unit" to listOf(r.box, r.attempts, r.misses) } +
            listOf("$id/session" to l.session()?.let { listOf(it.levelId, it.position, it.results.size, it.phase::class.simpleName) })
    }.toMap()
}
