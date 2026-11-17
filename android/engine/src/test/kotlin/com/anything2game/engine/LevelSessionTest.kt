package com.anything2game.engine

import com.anything2game.contract.DeduceItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.context.ContextDecision.PauseReason
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Effect
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.Overlay
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionCodec
import com.anything2game.engine.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelSessionTest {
    private val pkg = Fixtures.pkg
    private val session = LevelSession(pkg, GraderRegistry.default(), LeitnerPolicy(), Clock { 1_000L })
    private val units = pkg.levels[0].units
    private val swipe = units[0].item as SwipeItem
    private val sort = units[1].item as SortItem
    private val deduce = units[2].item as DeduceItem

    private val swipeRight = SwipeAnswer(swipe.cards.associate { it.id to it.supported })
    private val sortRight = SortAnswer(sort.cards.associate { it.id to it.binId })
    private val deduceRight = DeduceAnswer(deduce.options.single { it.correct }.id)

    private fun SessionState.act(a: Action) = session.reduce(this, a).state

    @Test fun `perfect run reaches recap with everything solid`() {
        var s = session.start("L1")
        listOf(swipeRight, sortRight, deduceRight).forEach { s = s.act(Action.Submit(it)).act(Action.Next) }
        assertEquals(Phase.Recap, s.phase)
        val recap = session.recap(s)
        assertEquals(listOf("L1U1", "L1U2", "L1U3"), recap.solidUnitIds)
        assertEquals(1.0, recap.firstTryRate, 0.0)
    }

    @Test fun `second submit on the same slot is ignored`() {
        val s1 = session.start("L1").act(Action.Submit(swipeRight))
        val s2 = s1.act(Action.Submit(SwipeAnswer(emptyMap())))
        assertEquals(s1, s2)
        assertEquals(1, s2.results.size)
    }

    @Test fun `a miss queues one retry two slots later and marks the unit shaky`() {
        var s = session.start("L1").act(Action.Submit(SwipeAnswer(emptyMap())))
        assertEquals(4, s.queue.size)
        assertEquals("L1U1", s.queue[3].unitId)
        assertTrue(s.queue[3].retry)
        // Missing again does not queue a second retry.
        s = s.act(Action.Next).act(Action.Submit(sortRight)).act(Action.Next).act(Action.Submit(deduceRight)).act(Action.Next)
        s = s.act(Action.Submit(SwipeAnswer(emptyMap())))
        assertEquals(4, s.queue.size)
        s = s.act(Action.Next)
        assertEquals(Phase.Recap, s.phase)
        assertEquals(listOf("L1U1"), session.recap(s).shakyUnitIds)
    }

    @Test fun `miss resets mastery box, first-try success raises it`() {
        val wrong = session.reduce(session.start("L1"), Action.Submit(SwipeAnswer(emptyMap())))
        assertEquals(0, (wrong.effects.single() as Effect.MasteryChanged).record.box)
        val right = session.reduce(session.start("L1"), Action.Submit(swipeRight))
        assertEquals(1, (right.effects.single() as Effect.MasteryChanged).record.box)
    }

    @Test fun `hints are limited to what the unit has and count as help`() {
        var s = session.start("L1")
        repeat(5) { s = s.act(Action.RevealHint) }
        assertEquals(units[0].hints.size, s.hintsShown)
        val t = session.reduce(s, Action.Submit(swipeRight))
        assertEquals(0, (t.effects.single() as Effect.MasteryChanged).record.box)
    }

    @Test fun `evidence overlay returns to the same feedback`() {
        val fb = session.start("L1").act(Action.Submit(swipeRight))
        val ev = fb.act(Action.OpenEvidence(listOf("c1")))
        assertEquals(Overlay.Evidence(listOf("c1")), ev.overlay)
        assertEquals(fb, ev.act(Action.CloseOverlay))
    }

    @Test fun `paused session ignores input until resumed`() {
        val paused = session.start("L1").act(Action.Pause(PauseReason.WALKING))
        assertEquals(paused, paused.act(Action.Submit(swipeRight)))
        val resumed = paused.act(Action.Resume)
        assertNull(resumed.pausedFor)
        assertTrue(resumed.act(Action.Submit(swipeRight)).phase is Phase.Feedback)
    }

    @Test fun `state survives serialisation (process death)`() {
        val s = session.start("L1").act(Action.Submit(SwipeAnswer(emptyMap()))).act(Action.OpenEvidence(listOf("c1")))
        val restored = SessionCodec.decode(SessionCodec.encode(s))
        assertEquals(s, restored)
        assertTrue(session.canResume(restored!!))
    }

    @Test fun `state from another content version is not resumed`() {
        val s = session.start("L1").copy(contentVersion = "old")
        assertFalse(session.canResume(s))
        assertNull(SessionCodec.decode("{broken"))
    }
}
