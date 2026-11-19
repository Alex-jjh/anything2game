package com.anything2game.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.anything2game.app.R
import com.anything2game.contract.FrameId
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SlotKind
import org.junit.Assert.*
import org.junit.Test

/** Journeys 2-4: the same course, played as Case, Rookie and Escape by a guest. */
class FrameJourneysTest : JourneyTest() {
    private val attentionTitle = "Attention Is All You Need (Transformer)"
    private val level1 = "Why the Transformer Replaces Recurrence"

    @Test fun guest_plays_attention_as_a_case_to_case_closed_and_the_stats_move() {
        launch()
        chooseGuest()
        openMe()
        val practiced0 = stat(R.string.home_stat_practiced)
        val documents0 = stat(R.string.home_stat_documents)
        assertEquals("a guest starts fresh", 0, practiced0)

        openPractice()
        playAs(attentionTitle, FrameId.CASE)
        // The chip explains what Case is before the student commits.
        waitForText(str(R.string.frame_case_desc))
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.CASE)
        assertEquals(FrameId.CASE, level.session()!!.frameId)

        level.playUntil("the cross-examination") { it.currentSlot?.kind == SlotKind.BOSS }
        waitForText(str(R.string.boss_case))
        waitForText(str(R.string.crossexam_testimony))
        level.play()

        assertEquals(Phase.Recap, level.session()!!.phase)
        waitForText(str(R.string.stamp_case_closed))
        waitForText(str(R.string.boss_cleared))
        waitForText(str(R.string.recap_all_solid))
        tapText(str(R.string.recap_library))
        waitForTag("home_PRACTICE")

        openMe()
        val units = level.pkg.level("L1")!!.units.size
        waitFor("Me tab to count the practised clues") { stat(R.string.home_stat_practiced) == units }
        assertEquals("documents on the shelf do not change", documents0, stat(R.string.home_stat_documents))
        assertEquals("nothing is due right after a clean run", 0, stat(R.string.home_stat_review))
    }

    @Test fun rookie_teach_back_wrong_answer_neither_brings_the_clue_back_nor_touches_mastery() {
        launch()
        chooseGuest()
        openPractice()
        playAs(attentionTitle, FrameId.ROOKIE)
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.ROOKIE)
        assertEquals(FrameId.ROOKIE, level.session()!!.frameId)

        val teachBacks = level.session()!!.queue.filter { it.kind == SlotKind.TEACH_BACK }
        assertTrue("Rookie plans at least one teach-back on this level", teachBacks.isNotEmpty())
        val first = teachBacks.first()

        level.playUntil("the first teach-back") { it.currentSlot?.kind == SlotKind.TEACH_BACK }
        val rookie = level.engine.rookieName()!!
        waitForText(str(R.string.badge_teach_back, rookie))
        // The rookie speaks a misconception about the clue the student has just answered.
        val before = level.session()!!
        val masteryBefore = level.mastery()
        assertTrue(masteryBefore.containsKey(first.unitId))
        val unitSlotsBefore = before.queue.count { it.unitId == first.unitId && it.kind == SlotKind.UNIT }

        level.step { _, _ -> false }
        waitForText(str(R.string.stamp_wrong))
        val after = level.session()!!
        assertTrue(after.phase is Phase.Feedback && !(after.phase as Phase.Feedback).grade.correct)
        assertFalse("a failed teach-back must not announce a retry", textShown(str(R.string.retry_note)))
        assertEquals("mastery is untouched by a teach-back", masteryBefore, level.mastery())
        assertEquals("no extra slot was queued", before.queue, after.queue)

        level.play()
        val done = level.session()!!
        assertEquals(Phase.Recap, done.phase)
        assertEquals("the clue never came back", unitSlotsBefore, done.queue.count { it.unitId == first.unitId && it.kind == SlotKind.UNIT })
        assertTrue(done.queue.none { it.retry && it.unitId == first.unitId })
        assertEquals("mastery of that clue is still what the clue itself earned", masteryBefore[first.unitId], level.mastery()[first.unitId])
        waitForText(str(R.string.recap_title_rookie))
        waitForText(str(R.string.recap_all_solid))
    }

    @Test fun escape_room_lock_wrong_answers_cost_credibility_until_the_case_goes_cold_and_replay_works() {
        launch()
        chooseGuest()
        openPractice()
        playAs(attentionTitle, FrameId.ESCAPE)
        startLevel(attentionTitle, level1)
        val level = Level("demo_attention", FrameId.ESCAPE)
        assertEquals(FrameId.ESCAPE, level.session()!!.frameId)
        assertEquals(3, level.session()!!.credibility)

        level.playUntil("a room lock") { it.currentSlot?.kind == SlotKind.ROOM_CHECK }
        waitForText(str(R.string.badge_room_lock))
        waitFor("full credibility pips") { exists(hasContentDescription(str(R.string.credibility_left, 3))) }

        level.step { _, _ -> false }              // a wrong lock: one of its claims judged the wrong way
        waitFor("a not-correct verdict") { textShown(str(R.string.stamp_wrong)) || textShown(str(R.string.stamp_partly)) }
        assertEquals(2, level.session()!!.credibility)
        waitFor("credibility pips to drop") { exists(hasContentDescription(str(R.string.credibility_left, 2))) }

        level.play { _, _ -> false }              // keep failing until it ends
        assertEquals(Phase.Failed, level.session()!!.phase)
        assertEquals(0, level.session()!!.credibility)
        waitForText(str(R.string.failed_title_escape))
        waitForText(str(R.string.failed_body))

        tapText(str(R.string.recap_replay))
        waitFor("a fresh level on disk") { level.session()!!.let { it.phase == Phase.Presenting && it.position == 0 } }
        val fresh = level.session()!!
        assertEquals(3, fresh.credibility)
        assertTrue(fresh.results.isEmpty())
        // ...and it can really be played, first scene first.
        waitFor("credibility restored on screen") { exists(hasContentDescription(str(R.string.credibility_left, 3))) || textShown(str(R.string.scene_tap)) }
        level.step()
        level.step()
        assertTrue(level.session()!!.position > 0)
    }
}
