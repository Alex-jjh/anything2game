package com.anything2game.engine.grading

import com.anything2game.contract.DeduceItem
import com.anything2game.contract.Item
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import kotlin.reflect.KClass

/** Grades one mechanic. New mechanic = new grader registered in [GraderRegistry]; nothing else changes. */
interface MechanicGrader<I : Item, A : Answer> {
    val itemType: KClass<I>
    val answerType: KClass<A>
    fun grade(item: I, answer: A): Grade
}

/** What the session engine depends on: grade any item. */
fun interface Grading {
    fun grade(item: Item, answer: Answer): Grade
}

class GraderRegistry(graders: List<MechanicGrader<*, *>>) : Grading {
    private val byItem = graders.associateBy { it.itemType }

    override fun grade(item: Item, answer: Answer): Grade {
        @Suppress("UNCHECKED_CAST")
        val grader = byItem[item::class] as? MechanicGrader<Item, Answer>
            ?: error("No grader for ${item::class.simpleName}")
        require(grader.answerType.isInstance(answer)) {
            "${answer::class.simpleName} cannot answer ${item::class.simpleName}"
        }
        return grader.grade(item, answer)
    }

    companion object {
        fun default() = GraderRegistry(listOf(SwipeGrader, SortGrader, DeduceGrader, OrderGrader))
    }
}

private fun grade(total: Int, wrong: Set<String>) =
    Grade(correct = wrong.isEmpty(), wrongPartIds = wrong, score = if (total == 0) 0.0 else (total - wrong.size).toDouble() / total, parts = total)

object SwipeGrader : MechanicGrader<SwipeItem, SwipeAnswer> {
    override val itemType = SwipeItem::class
    override val answerType = SwipeAnswer::class
    override fun grade(item: SwipeItem, answer: SwipeAnswer): Grade {
        val wrong = item.cards.filter { answer.verdicts[it.id] != it.supported }.map { it.id }.toSet()
        return grade(item.cards.size, wrong)
    }
}

object SortGrader : MechanicGrader<SortItem, SortAnswer> {
    override val itemType = SortItem::class
    override val answerType = SortAnswer::class
    override fun grade(item: SortItem, answer: SortAnswer): Grade {
        val wrong = item.cards.filter { answer.placements[it.id] != it.binId }.map { it.id }.toSet()
        return grade(item.cards.size, wrong)
    }
}

object DeduceGrader : MechanicGrader<DeduceItem, DeduceAnswer> {
    override val itemType = DeduceItem::class
    override val answerType = DeduceAnswer::class
    override fun grade(item: DeduceItem, answer: DeduceAnswer): Grade {
        val chosen = item.options.firstOrNull { it.id == answer.optionId }
        val ok = chosen?.correct == true
        return Grade(ok, if (ok) emptySet() else setOf(answer.optionId), if (ok) 1.0 else 0.0)
    }
}

object OrderGrader : MechanicGrader<OrderItem, OrderAnswer> {
    override val itemType = OrderItem::class
    override val answerType = OrderAnswer::class
    override fun grade(item: OrderItem, answer: OrderAnswer): Grade {
        val wrong = item.steps.filterIndexed { i, s -> answer.stepIds.getOrNull(i) != s.id }.map { it.id }.toSet()
        return grade(item.steps.size, wrong)
    }
}
