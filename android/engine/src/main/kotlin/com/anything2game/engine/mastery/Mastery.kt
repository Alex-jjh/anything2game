package com.anything2game.engine.mastery

import kotlinx.serialization.Serializable

/** Long-lived memory of one knowledge unit, across sessions. */
@Serializable
data class MasteryRecord(
    val unitId: String,
    /** Leitner box 0..[LeitnerPolicy.MAX_BOX]. ≥2 counts as "solid". */
    val box: Int = 0,
    val attempts: Int = 0,
    val misses: Int = 0,
    val lastSeenAt: Long = 0,
    val dueAt: Long = 0,
) {
    val isSolid: Boolean get() = box >= 2
}

/** How an attempt went, from the mastery point of view. */
enum class Outcome { CORRECT_FIRST_TRY, CORRECT_WITH_HELP, WRONG }

interface MasteryPolicy {
    fun update(record: MasteryRecord, outcome: Outcome, now: Long): MasteryRecord
}

/**
 * Leitner boxes. Intervals are experiment knobs, not claims: the literature gives no universal schedule
 * (Cepeda et al. 2006).
 */
class LeitnerPolicy(
    private val intervalsMillis: List<Long> = listOf(10 * MINUTE, 1 * DAY, 3 * DAY, 7 * DAY),
) : MasteryPolicy {
    override fun update(record: MasteryRecord, outcome: Outcome, now: Long): MasteryRecord {
        val box = when (outcome) {
            Outcome.CORRECT_FIRST_TRY -> (record.box + 1).coerceAtMost(MAX_BOX)
            Outcome.CORRECT_WITH_HELP -> record.box
            Outcome.WRONG -> 0
        }
        return record.copy(
            box = box,
            attempts = record.attempts + 1,
            misses = record.misses + if (outcome == Outcome.WRONG) 1 else 0,
            lastSeenAt = now,
            dueAt = now + intervalsMillis[box.coerceIn(0, intervalsMillis.lastIndex)],
        )
    }

    companion object {
        const val MAX_BOX = 3
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}

/** Picks units for the "boss" review: not solid yet and due. */
object ReviewScheduler {
    fun due(records: Collection<MasteryRecord>, now: Long, limit: Int = 8): List<MasteryRecord> =
        records.filter { it.box <= 1 && it.dueAt <= now }
            .sortedWith(compareBy<MasteryRecord> { it.box }.thenByDescending { it.misses }.thenBy { it.dueAt })
            .take(limit)
}
