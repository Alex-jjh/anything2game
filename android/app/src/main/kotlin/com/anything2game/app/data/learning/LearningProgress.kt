package com.anything2game.app.data.learning

import com.anything2game.app.data.AtomicSnapshot
import com.anything2game.app.data.stableId
import com.anything2game.contract.game.*
import com.anything2game.contract.progress.*
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.tutor.TutorSession
import com.anything2game.engine.game.*
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.progress.ProgressProjection
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId

@Serializable
private data class LearningLedger(val checkpoints: List<GameCheckpoint> = emptyList(), val events: List<CompletionRecord> = emptyList())

/** One committed snapshot is the source of truth for checkpoints, completion, mastery and historical days. */
class LearningProgress(file: File, val profileId: String, private val clock: Clock = Clock.SYSTEM,
    private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val store = AtomicSnapshot(file, LearningLedger.serializer(), ::LearningLedger)
    suspend fun projection() = ProgressProjection.from(profileId, store.read().events)
    suspend fun checkpoint(version: ContentVersion) = store.read().checkpoints.singleOrNull {
        it.gameId == version.contentId && it.gameVersion == version.contentVersion
    }
    private fun GameCheckpoint.matches(spec: GameSpec) = gameId == spec.gameId && gameVersion == spec.gameVersion
    private fun event(id: String, ref: ActivityRef, outcome: ActivityOutcome, time: Long = clock.now()) =
        CompletionRecord.at(id, ref.key(profileId), outcome, time, zone().id)
    private fun LearningLedger.add(record: CompletionRecord) =
        if (events.any { it.completionId == record.completionId }) this else copy(events = events + record)

    /** Explicit new attempt. Passing the same seed resumes that attempt; a different seed replaces its checkpoint. */
    suspend fun start(spec: GameSpec, seed: Long): GameRun {
        val initial = GameRuntime.start(spec, seed)
        val ledger = store.update { old ->
            val saved = old.checkpoints.find { it.matches(spec) }
            if (saved?.seed == seed) old else {
                val record = gameEvent(initial, "start")
                old.add(record).copy(checkpoints = old.checkpoints.filterNot { it.matches(spec) } + initial.checkpoint)
            }
        }
        return GameRuntime.replay(spec, ledger.checkpoints.single { it.matches(spec) })
    }

    private fun gameEvent(run: GameRun, marker: String): CompletionRecord {
        val stage = run.stage
        val ref = ActivityRef.Game(run.spec.gameId, run.spec.gameVersion, stage.stageId, stage.rulesVersion)
        val id = stableId("game", ref.contentId, ref.contentVersion, ref.activityId, ref.rulesVersion.toString(), run.checkpoint.seed.toString(), marker)
        return event(id, ref, run.outcome)
    }

    /** Compare-and-apply rejects stale UI input. Repeating the immediately preceding callback is idempotent. */
    suspend fun apply(spec: GameSpec, expected: GameCheckpoint, action: GameAction): GameRun {
        val previous = GameRuntime.replay(spec, expected)
        val next = GameRuntime.apply(previous, action)
        store.update { old ->
            val saved = old.checkpoints.singleOrNull { it.matches(spec) }
            if (saved == next.checkpoint) old else {
                require(saved == expected) { "Stale game checkpoint" }
                var updated = old.copy(checkpoints = old.checkpoints.filterNot { it.matches(spec) } + next.checkpoint)
                if (next.completed || next.stageIndex != previous.stageIndex)
                    updated = updated.add(gameEvent(next, if (next.completed) "complete" else "played"))
                updated
            }
        }
        return next
    }

    suspend fun tutorRead(session: TutorSession) {
        val message = session.messages.lastOrNull { it.role == "tutor" } ?: return
        val ref = ActivityRef.Tutor(session.documentId, session.documentVersion, session.questions[session.conceptPosition].conceptId)
        val id = stableId("read", session.sessionId, message.id, ref.contentId, ref.contentVersion)
        store.update { it.add(event(id, ref, ActivityOutcome.READ)) }
    }

    suspend fun practice(state: SessionState, time: Long = clock.now()) {
        if (!state.isFinished) return
        val results = state.results.filter { it.slot.kind == SlotKind.UNIT }
        val outcome = when {
            state.phase == Phase.Failed || results.none { it.grade.correct } -> ActivityOutcome.WRONG
            results.any { !it.grade.correct || it.hintsUsed > 0 || it.slot.retry } -> ActivityOutcome.HELPED
            else -> ActivityOutcome.INDEPENDENT
        }
        val ref = ActivityRef.Practice(state.packageId, state.contentVersion, state.levelId, state.frameId)
        val id = stableId("practice", state.attemptId, ref.contentId, ref.contentVersion, ref.activityId)
        store.update { it.add(event(id, ref, outcome, time)) }
    }
}
