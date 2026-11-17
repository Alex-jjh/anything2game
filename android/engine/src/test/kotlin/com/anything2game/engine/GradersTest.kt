package com.anything2game.engine

import com.anything2game.contract.DeduceItem
import com.anything2game.contract.OrderItem
import com.anything2game.contract.OrderStep
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.GraderRegistry
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GradersTest {
    private val g = GraderRegistry.default()
    private val units = Fixtures.pkg.levels[0].units

    @Test fun swipe() {
        val item = units[0].item as SwipeItem
        val right = SwipeAnswer(item.cards.associate { it.id to it.supported })
        assertTrue(g.grade(item, right).correct)
        val oneWrong = SwipeAnswer(right.verdicts + ("s2" to true))
        val grade = g.grade(item, oneWrong)
        assertFalse(grade.correct)
        assertEquals(setOf("s2"), grade.wrongPartIds)
        assertEquals(2.0 / 3, grade.score, 1e-9)
    }

    @Test fun `sort with a missing placement is wrong for that card`() {
        val item = units[1].item as SortItem
        val all = item.cards.associate { it.id to it.binId }
        assertTrue(g.grade(item, SortAnswer(all)).correct)
        assertEquals(setOf("k1"), g.grade(item, SortAnswer(all - "k1")).wrongPartIds)
    }

    @Test fun deduce() {
        val item = units[2].item as DeduceItem
        assertTrue(g.grade(item, DeduceAnswer("o2")).correct)
        assertEquals(setOf("o1"), g.grade(item, DeduceAnswer("o1")).wrongPartIds)
    }

    @Test fun order() {
        val item = OrderItem("p", listOf(OrderStep("a", "A"), OrderStep("b", "B"), OrderStep("c", "C")), "w", emptyList())
        assertTrue(g.grade(item, OrderAnswer(listOf("a", "b", "c"))).correct)
        assertEquals(setOf("a", "b"), g.grade(item, OrderAnswer(listOf("b", "a", "c"))).wrongPartIds)
    }

    @Test fun `answer of the wrong mechanic is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { g.grade(units[2].item, SwipeAnswer(emptyMap())) }
    }
}
