package com.anything2game.engine.session

import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.Item
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Level
import com.anything2game.contract.LevelOverlay
import com.anything2game.contract.Line
import com.anything2game.contract.Room
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.frames.GameFrame
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
    /** Share of package units answered correctly on the first try without hints. */
    val firstTryRate: Double,
    /** Null when the frame has no boss. */
    val bossCleared: Boolean?,
    val credibilityLeft: Int?,
)

/**
 * The level state machine (docs/architecture/ARCHITECTURE.md §5.2). Pure: same state + action → same result.
 * The [frame] decides the order of slots (scenes, units, boss); graders decide right/wrong; the LLM decides neither.
 */
class LevelSession(
    private val pkg: LearningPackage,
    private val grading: Grading,
    private val mastery: MasteryPolicy,
    private val clock: Clock = Clock.SYSTEM,
    /** A missed unit comes back this many slots later. */
    private val retryGap: Int = 2,
    private val frame: GameFrame = Frames.CLASSIC,
    private val overlay: FrameOverlay? = null,
) {
    init {
        require(overlay == null || overlay.frameId == frame.id) { "Overlay ${overlay?.frameId} does not belong to frame ${frame.id}" }
    }

    fun start(levelId: String): SessionState {
        val level = requireNotNull(pkg.level(levelId)) { "Unknown level $levelId" }
        return SessionState(
            packageId = pkg.packageId,
            contentVersion = pkg.contentVersion,
            levelId = levelId,
            frameId = frame.id,
            queue = frame.plan(level, levelOverlay(levelId)),
            credibility = if (overlay != null || !frame.spec.needsOverlay) frame.spec.credibility else null,
        )
    }

    /** A saved state is usable only for this exact package version and frame, and if every slot still resolves. */
    fun canResume(state: SessionState): Boolean {
        if (state.packageId != pkg.packageId || state.contentVersion != pkg.contentVersion || state.frameId != frame.id) return false
        pkg.level(state.levelId) ?: return false
        return state.queue.all { resolves(state.levelId, it) } && (state.isFinished || state.position in state.queue.indices)
    }

    fun level(state: SessionState): Level = requireNotNull(pkg.level(state.levelId))
    fun levelOverlay(levelId: String): LevelOverlay? = overlay?.level(levelId)

    /** The unit behind the current slot (package unit or frame boss); null for scenes. */
    fun unit(state: SessionState): LearningUnit? = state.currentSlot?.let { unitFor(state.levelId, it) }

    /** The item to show for the current slot; null for scenes. */
    fun item(state: SessionState): Item? {
        val slot = state.currentSlot ?: return null
        val unit = unitFor(state.levelId, slot) ?: return null
        return if (slot.variant == Slot.MAIN) unit.item else unit.variants[slot.variant]
    }

    /** Lines of the current scene; null when the slot is not a scene. */
    fun sceneLines(state: SessionState): List<Line>? {
        val slot = state.currentSlot?.takeIf { it.kind == SlotKind.SCENE } ?: return null
        val lo = levelOverlay(state.levelId) ?: return null
        val key = slot.scene ?: return null
        return when {
            key == Slot.INTRO -> lo.intro
            key == Slot.OUTRO -> lo.outro
            key.startsWith("lead:") -> lo.leadIns[key.removePrefix("lead:")]
            key.startsWith("room:") -> room(state)?.let { listOf(Line(overlay?.cast?.firstOrNull()?.id.orEmpty(), it.description)) }
            else -> null
        }
    }

    /** The room the current scene opens (Escape frame). */
    fun room(state: SessionState): Room? {
        val key = state.currentSlot?.scene?.takeIf { it.startsWith("room:") } ?: return null
        return levelOverlay(state.levelId)?.rooms?.firstOrNull { it.id == key.removePrefix("room:") }
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
        val slot = state.currentSlot?.takeIf { it.answerable } ?: return Transition(state)
        val item = item(state) ?: return Transition(state)
        val grade = grading.grade(item, action.answer)

        val effects = if (slot.kind == SlotKind.UNIT) {
            val outcome = when {
                !grade.correct -> Outcome.WRONG
                state.hintsShown == 0 && !slot.retry -> Outcome.CORRECT_FIRST_TRY
                else -> Outcome.CORRECT_WITH_HELP
            }
            val previous = records[slot.unitId] ?: MasteryRecord(slot.unitId)
            listOf(Effect.MasteryChanged(mastery.update(previous, outcome, clock.now())))
        } else emptyList()

        val queue = if (grade.correct || slot.kind != SlotKind.UNIT) state.queue else withRetry(state, slot)
        val credibility = state.credibility?.let { if (grade.correct) it else (it - 1).coerceAtLeast(0) }
        return Transition(
            state.copy(
                queue = queue,
                phase = Phase.Feedback(action.answer, grade),
                results = state.results + SlotResult(slot, grade, state.hintsShown),
                credibility = credibility,
            ),
            effects,
        )
    }

    /**
     * Queue one retry per unit per session: the next unused variant, or the same item if there is none.
     * The retry lands before the boss and the outro, so the level's ending stays the ending.
     */
    private fun withRetry(state: SessionState, slot: Slot): List<Slot> {
        if (state.queue.any { it.unitId == slot.unitId && it.retry }) return state.queue
        val unit = level(state).units.firstOrNull { it.unitId == slot.unitId } ?: return state.queue
        val usedVariants = state.queue.filter { it.unitId == slot.unitId && it.kind == SlotKind.UNIT }.map { it.variant }.toSet()
        val nextVariant = unit.variants.indices.firstOrNull { it !in usedVariants } ?: Slot.MAIN
        val ending = state.queue.withIndex()
            .firstOrNull { (i, s) -> i > state.position && (s.kind == SlotKind.BOSS || s.scene == Slot.OUTRO) }?.index
            ?: state.queue.size
        val at = (state.position + 1 + retryGap).coerceAtMost(ending)
        return state.queue.toMutableList().apply { add(at, Slot(slot.unitId, nextVariant, retry = true)) }
    }

    private fun next(state: SessionState): Transition {
        val slot = state.currentSlot
        val canAdvance = state.phase is Phase.Feedback || (state.phase == Phase.Presenting && slot?.kind == SlotKind.SCENE)
        if (!canAdvance) return Transition(state)
        if (state.credibility == 0) return Transition(state.copy(phase = Phase.Failed, overlay = null, hintsShown = 0))
        val nextPosition = state.position + 1
        return if (nextPosition >= state.queue.size) {
            Transition(state.copy(phase = Phase.Recap, overlay = null, hintsShown = 0))
        } else {
            Transition(state.copy(position = nextPosition, phase = Phase.Presenting, overlay = null, hintsShown = 0))
        }
    }

    fun recap(state: SessionState): RecapSummary {
        val level = level(state)
        val firstAttempts = state.results.filter { !it.slot.retry && it.slot.kind == SlotKind.UNIT }.associateBy { it.slot.unitId }
        val solid = level.units.map { it.unitId }.filter { id -> firstAttempts[id]?.let { it.grade.correct && it.hintsUsed == 0 } == true }
        val shaky = level.units.map { it.unitId } - solid.toSet()
        val rate = if (level.units.isEmpty()) 0.0 else solid.size.toDouble() / level.units.size
        val hasBoss = state.queue.any { it.kind == SlotKind.BOSS }
        val bossCleared = if (hasBoss) state.results.any { it.slot.kind == SlotKind.BOSS && it.grade.correct } else null
        return RecapSummary(level.recap.points, solid, shaky, rate, bossCleared, state.credibility)
    }

    /** Units the player has solved in this session (for the evidence board). */
    fun solvedUnitIds(state: SessionState): Set<String> =
        state.results.filter { it.grade.correct && it.slot.kind == SlotKind.UNIT }.map { it.slot.unitId }.toSet()

    private fun unitFor(levelId: String, slot: Slot): LearningUnit? = when (slot.kind) {
        SlotKind.UNIT -> pkg.level(levelId)?.units?.firstOrNull { it.unitId == slot.unitId }
        SlotKind.BOSS -> levelOverlay(levelId)?.boss?.takeIf { it.unitId == slot.unitId }
        SlotKind.SCENE -> null
    }

    private fun resolves(levelId: String, slot: Slot): Boolean = when (slot.kind) {
        SlotKind.SCENE -> true
        else -> unitFor(levelId, slot)?.let { slot.variant < it.variants.size } == true
    }
}
