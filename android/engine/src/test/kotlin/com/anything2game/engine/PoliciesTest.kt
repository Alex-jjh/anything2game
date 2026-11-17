package com.anything2game.engine

import com.anything2game.engine.context.ContextSignals
import com.anything2game.engine.context.DefaultContextPolicy
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.mastery.Outcome
import com.anything2game.engine.mastery.ReviewScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test fun `walking pauses, vehicle is one-handed, offline disables ask`() {
        val walking = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.WALKING))
        assertTrue(walking.pause)
        val bus = DefaultContextPolicy.decide(ContextSignals(activity = ContextSignals.Activity.IN_VEHICLE, network = ContextSignals.Network.OFFLINE))
        assertFalse(bus.pause); assertTrue(bus.oneHanded); assertFalse(bus.askAvailable); assertFalse(bus.prefetchAllowed)
    }

    @Test fun `leitner boxes and due times`() {
        val p = LeitnerPolicy()
        var r = MasteryRecord("u")
        r = p.update(r, Outcome.CORRECT_FIRST_TRY, 0); assertEquals(1, r.box); assertEquals(LeitnerPolicy.DAY, r.dueAt)
        r = p.update(r, Outcome.CORRECT_FIRST_TRY, 0); assertEquals(2, r.box); assertTrue(r.isSolid)
        r = p.update(r, Outcome.WRONG, 0); assertEquals(0, r.box); assertEquals(1, r.misses)
    }

    @Test fun `boss review picks due, non-solid units, most-missed first`() {
        val records = listOf(
            MasteryRecord("a", box = 0, misses = 1, dueAt = 5),
            MasteryRecord("b", box = 0, misses = 3, dueAt = 5),
            MasteryRecord("c", box = 2, dueAt = 0),
            MasteryRecord("d", box = 1, dueAt = 99),
        )
        assertEquals(listOf("b", "a"), ReviewScheduler.due(records, now = 10).map { it.unitId })
    }
}
