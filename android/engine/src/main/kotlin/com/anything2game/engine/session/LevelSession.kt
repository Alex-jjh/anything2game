package com.anything2game.engine.session

import com.anything2game.contract.Item
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.Level
import com.anything2game.contract.LearningUnit
import com.anything2game.engine.grading.Grading
import com.anything2game.engine.mastery.MasteryPolicy
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.mastery.Outcome
import com.anything2game.engine.ports.Clock

/** Side effects the caller must persist. The engine itself performs no I/O. */
sealed interface Effect {
    data class MasteryChanged(val record: MasteryRecord) : Effect
}

data class Transition(val state: SessionState, val effects: List<Effect> = emptyList())

data class RecapSummary(
    val points: List<String>,
    val solidUnitIds: List<String>,
    val shakyUnitIds: List<String>,
    /** Share of first attempts answered correctly without hints. */
    val firstTryRate: Double,
)

/**
 * The level state machine (docs/architecture/ARCHITECTURE.md §5.2). Pure: same state + action → same result.
 * The LLM never decides flow; this class does.
 */
class LevelSession(
    private val pkg: LearningPackage,
    private val grading: Grading,
    private val mastery: MasteryPolicy,
    private val clock: Clock = Clock.SYSTEM,
    /** A missed unit comes back this many slots later. */
    private val retryGap: Int = 2,
) {
    fun start(levelId: String): SessionState {
        val level = requireNotNull(pkg.level(levelId)) { "Unknown level $levelId" }
        return SessionState(
            packageId = pkg.packageId,
            contentVersion = pkg.contentVersion,
            levelId = levelId,
            queue = level.units.map { Slot(it.unitId) },
        )
    }

    /** A saved state is usable only if it belongs to this exact package version. Otherwise start again. */
    fun canResume(state: SessionState): Boolean {
        if (state.packageId != pkg.packageId || state.contentVersion != pkg.contentVersion) return false
        val level = pkg.level(state.levelId) ?: return false
        return state.queue.all { slot -> level.unit(slot.unitId)?.let { slot.variant < it.variants.size } == true } &&
            (state.isFinished || state.position in state.queue.indices)
    }

    fun level(state: SessionState): Level = requireNotNull(pkg.level(state.levelId))

    fun unit(state: SessionState): LearningUnit? = state.currentSlot?.let { level(state).unit(it.unitId) }

    /** The item to show for the current slot. */
    fun item(state: SessionState): Item? {
        val slot = state.currentSlot ?: return null
        val unit = level(state).unit(slot.unitId) ?: return null
        return if (slot.variant == Slot.MAIN) unit.item else unit.variants[slot.variant]
    }

    fun reduce(state: SessionState, action: Action, masteryRecords: Map<String, MasteryRecord> = emptyMap()): Transition {
        // While paused (e.g. walking) only Resume and closing overlays are accepted.
        if (state.isPaused && action !is Action.Resume && action !is Action.CloseOverlay) return Transition(state)

        return when (action) {
            is Action.Submit -> submit(state, action, masteryRecords)
            Action.RevealHint -> {
                val hints = unit(state)?.hints.orEmpty()
                if (state.phase == Phase.Presenting && state.hintsShown < hints.size) Transition(state.copy(hintsShown = state.hintsShown + 1))
                else Transition(state)
            }
            Action.Next -> next(state)
            is Action.OpenEvidence -> Transition(state.copy(overlay = Overlay.Evidence(action.chunkIds)))
            Action.OpenAsk -> if (state.isFinished) Transition(state) else Transition(state.copy(overlay = Overlay.Asking))
            Action.CloseOverlay -> Transition(state.copy(overlay = null))
            is Action.Pause -> Transition(state.copy(pausedFor = action.reason))
            Action.Resume -> Transition(state.copy(pausedFor = null))
        }
    }

    private fun submit(state: SessionState, action: Action.Submit, records: Map<String, MasteryRecord>): Transition {
        // Exactly one answer per slot: a double tap or a replay after rotation is ignored.
        if (state.phase != Phase.Presenting || state.overlay != null) return Transition(state)
        val slot = state.currentSlot ?: return Transition(state)
        val item = item(state) ?: return Transition(state)
        val grade = grading.grade(item, action.answer)

        val outcome = when {
            !grade.correct -> Outcome.WRONG
            state.hintsShown == 0 && !slot.retry -> Outcome.CORRECT_FIRST_TRY
            else -> Outcome.CORRECT_WITH_HELP
        }
        val previous = records[slot.unitId] ?: MasteryRecord(slot.unitId)
        val updated = mastery.update(previous, outcome, clock.now())

        val queue = if (grade.correct) state.queue else withRetry(state, slot)
        return Transition(
            state.copy(
                queue = queue,
                phase = Phase.Feedback(action.answer, grade),
                results = state.results + SlotResult(slot, grade, state.hintsShown),
            ),
            listOf(Effect.MasteryChanged(updated)),
        )
    }

    /** Queue one retry per unit per session: the next unused variant, or the same item if there is none. */
    private fun withRetry(state: SessionState, slot: Slot): List<Slot> {
        if (state.queue.any { it.unitId == slot.unitId && it.retry }) return state.queue
        val unit = level(state).unit(slot.unitId) ?: return state.queue
        val usedVariants = state.queue.filter { it.unitId == slot.unitId }.map { it.variant }.toSet()
        val nextVariant = unit.variants.indices.firstOrNull { it !in usedVariants } ?: Slot.MAIN
        val at = (state.position + 1 + retryGap).coerceAtMost(state.queue.size)
        return state.queue.toMutableList().apply { add(at, Slot(slot.unitId, nextVariant, retry = true)) }
    }

    private fun next(state: SessionState): Transition {
        if (state.phase !is Phase.Feedback) return Transition(state)
        val nextPosition = state.position + 1
        return if (nextPosition >= state.queue.size) {
            Transition(state.copy(phase = Phase.Recap, overlay = null, hintsShown = 0))
        } else {
            Transition(state.copy(position = nextPosition, phase = Phase.Presenting, overlay = null, hintsShown = 0))
        }
    }

    fun recap(state: SessionState): RecapSummary {
        val level = level(state)
        val firstAttempts = state.results.filter { !it.slot.retry }.associateBy { it.slot.unitId }
        val solid = level.units.map { it.unitId }.filter { id -> firstAttempts[id]?.let { it.grade.correct && it.hintsUsed == 0 } == true }
        val shaky = level.units.map { it.unitId } - solid.toSet()
        val rate = if (level.units.isEmpty()) 0.0 else solid.size.toDouble() / level.units.size
        return RecapSummary(level.recap.points, solid, shaky, rate)
    }

    private fun Level.unit(unitId: String): LearningUnit? = units.firstOrNull { it.unitId == unitId }
}
