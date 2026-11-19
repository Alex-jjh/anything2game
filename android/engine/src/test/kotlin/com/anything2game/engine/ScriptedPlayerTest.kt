package com.anything2game.engine

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.engine.demo.ScriptedPlayer
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScriptedPlayerTest {
    private val grading = GraderRegistry.default()
    private val pkg = Fixtures.pkg
    private val dir = File(System.getProperty("contract.examples") ?: "contract/examples")

    @Test fun `scripted right and wrong answers grade as intended for every mechanic in the example`() {
        pkg.levels.flatMap { it.units }.forEach { u ->
            assertTrue(u.unitId, grading.grade(u.item, ScriptedPlayer.correctAnswer(u.item)).correct)
            assertFalse(u.unitId, grading.grade(u.item, ScriptedPlayer.wrongAnswer(u.item)).correct)
        }
    }

    @Test fun `a full run with one miss ends in the recap with that unit shaky`() {
        val e = LevelSession(pkg, grading, LeitnerPolicy(), Clock { 1_000_000L })
        val played = ScriptedPlayer.play(e, e.start(pkg.levels[0].levelId), { n, _ -> n != 0 })
        assertEquals(Phase.Recap, played.state.phase)
        assertEquals(pkg.levels[0].units.size, played.mastery.size)
        assertTrue(played.mastery.values.any { it.misses > 0 })
    }

    @Test fun `stopping early leaves an open session that can be resumed`() {
        val e = LevelSession(pkg, grading, LeitnerPolicy())
        val played = ScriptedPlayer.play(e, e.start(pkg.levels[0].levelId), { _, _ -> true }, stopAfterAnswers = 2)
        assertFalse(played.state.isFinished)
        assertTrue(e.canResume(played.state))
    }

    @Test fun `the case boss is played through the same reducer`() {
        val overlay = ContractJson.decodeFrame(File(dir, "frame.v1.retrieval.case.json").readText())
        val e = LevelSession(pkg, grading, LeitnerPolicy(), frame = Frames.of(FrameId.CASE), overlay = overlay)
        val played = ScriptedPlayer.play(e, e.start(pkg.levels[0].levelId), { _, _ -> true })
        assertEquals(Phase.Recap, played.state.phase)
        assertEquals(true, e.recap(played.state).bossCleared)
    }
}
