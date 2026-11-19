package com.anything2game.engine.demo

import com.anything2game.contract.CrossExamItem
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.Item
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SwipeItem
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.CrossExamAnswer
import com.anything2game.engine.grading.DeduceAnswer
import com.anything2game.engine.grading.OrderAnswer
import com.anything2game.engine.grading.SortAnswer
import com.anything2game.engine.grading.SwipeAnswer
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Effect
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.SessionState

/**
 * Plays a level with scripted answers through the real engine (same reducer, graders and mastery policy as a player).
 * Used to give the sample profile a believable history, and by tests — never hand-written progress records.
 */
object ScriptedPlayer {
    /** The [n]-th answered challenge (0-based) → answer right? */
    fun interface Script { fun correct(n: Int, item: Item): Boolean }

    data class Played(val state: SessionState, val mastery: Map<String, MasteryRecord>)

    /**
     * Plays from [from] until the level ends, or until [stopAfterAnswers] challenges have been answered
     * (then the session is left open, as if the player put the phone away mid-level).
     */
    fun play(
        session: LevelSession,
        from: SessionState,
        script: Script,
        mastery: Map<String, MasteryRecord> = emptyMap(),
        stopAfterAnswers: Int? = null,
    ): Played {
        var s = from
        val records = mastery.toMutableMap()
        var answered = 0
        var guard = 0
        while (!s.isFinished && guard++ < 500) {
            if (stopAfterAnswers != null && answered >= stopAfterAnswers) break
            val item = session.item(s)
            val t = if (item == null) session.reduce(s, Action.Next, records) else {
                val answer = if (script.correct(answered++, item)) correctAnswer(item) else wrongAnswer(item)
                val fed = session.reduce(s, Action.Submit(answer), records)
                fed.effects.forEach { if (it is Effect.MasteryChanged) records[it.record.unitId] = it.record }
                session.reduce(fed.state, Action.Next, records)
            }
            t.effects.forEach { if (it is Effect.MasteryChanged) records[it.record.unitId] = it.record }
            s = t.state
        }
        return Played(s, records)
    }

    fun correctAnswer(item: Item): Answer = when (item) {
        is SwipeItem -> SwipeAnswer(item.cards.associate { it.id to it.supported })
        is SortItem -> SortAnswer(item.cards.associate { it.id to it.binId })
        is OrderItem -> OrderAnswer(item.steps.map { it.id })
        is DeduceItem -> DeduceAnswer(item.options.first { it.correct }.id)
        is CrossExamItem -> item.statements.first { it.flawed }.let { CrossExamAnswer(it.id, it.sourceRefs.first().chunkId) }
        else -> error("no scripted answer for ${item::class.simpleName}")
    }

    /** A plausible mistake: one part wrong, the rest right. */
    fun wrongAnswer(item: Item): Answer = when (item) {
        is SwipeItem -> SwipeAnswer(item.cards.associate { it.id to (it.supported xor (it == item.cards.last())) })
        is SortItem -> {
            val first = item.cards.first()
            val other = item.bins.firstOrNull { it.id != first.binId }?.id ?: first.binId
            SortAnswer(item.cards.associate { it.id to if (it == first) other else it.binId })
        }
        is OrderItem -> OrderAnswer(item.steps.map { it.id }.let { if (it.size > 1) listOf(it[1], it[0]) + it.drop(2) else it })
        is DeduceItem -> DeduceAnswer((item.options.firstOrNull { !it.correct } ?: item.options.first()).id)
        is CrossExamItem -> (item.statements.firstOrNull { !it.flawed } ?: item.statements.first()).let { st ->
            CrossExamAnswer(st.id, st.sourceRefs.firstOrNull()?.chunkId ?: "")
        }
        else -> error("no scripted answer for ${item::class.simpleName}")
    }
}
