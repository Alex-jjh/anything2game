package com.anything2game.app.data.learning

import com.anything2game.app.data.AtomicSnapshot
import com.anything2game.app.data.stableId
import com.anything2game.contract.game.*
import com.anything2game.contract.progress.*
import com.anything2game.contract.space.ActivityRef
import com.anything2game.contract.tutor.TutorSession
import com.anything2game.engine.game.*
import com.anything2game.engine.mastery.LeitnerPolicy
import com.anything2game.engine.mastery.MasteryPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.progress.ProgressProjection
import com.anything2game.engine.session.Phase
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId

/** A game's saved attempt (null when none) and the most stars ever earned on it. */
data class GameState(val checkpoint: GameCheckpoint?, val stars: Int)

/** The most stars ever earned on one game. A new attempt never lowers it. */
@Serializable
private data class GameStars(val gameId: String, val gameVersion: String, val stars: Int)

@Serializable
private data class LearningLedger(val checkpoints: List<GameCheckpoint> = emptyList(), val events: List<CompletionRecord> = emptyList(),
    val stars: List<GameStars> = emptyList())

/**
 * One committed snapshot is the source of truth for checkpoints, completion, mastery, game stars and historical days.
 * [policy] is the mastery rule the projection applies. [legacyStars] reads the stars older versions kept in preferences;
 * they are moved into the snapshot the first time they are read, and the preferences are left as they were.
 */
class LearningProgress(file: File, val profileId: String, private val clock: Clock = Clock.SYSTEM,
    private val zone: () -> ZoneId = ZoneId::systemDefault, private val tutorHistory: suspend () -> List<TutorSession> = { emptyList() },
    private val beforeProjection: suspend () -> Unit = {}, private val policy: MasteryPolicy = LeitnerPolicy(),
    private val legacyStars: (ContentVersion) -> Int = { 0 }) {
    private val store = AtomicSnapshot(file, LearningLedger.serializer(), ::LearningLedger)
    @Volatile private var checkedLegacyReads = false
    suspend fun projection(): com.anything2game.engine.progress.ActivityProgress {
        beforeProjection()
        val ledger = store.read()
        val identified = if (checkedLegacyReads) ledger else identifyLegacyReads(ledger)
        val progress = ProgressProjection.from(profileId, identified.events, policy)
        // An unidentified historical read still contributes its recorded study day, but cannot satisfy a lesson prerequisite.
        return progress.copy(completed = progress.completed.filterNot { it.activityKind == ActivityKind.TUTOR && it.tutorSessionId == null }.toSet())
    }

    private fun readId(session: TutorSession, messageId: String) =
        stableId("read", session.sessionId, messageId, session.documentId, session.documentVersion)

    /** Old receipts already hashed the exact session and message. Qualify only the receipts we can prove from the saved log. */
    private suspend fun identifyLegacyReads(ledger: LearningLedger): LearningLedger {
        val legacy = ledger.events.filter { it.outcome == ActivityOutcome.READ && it.activity.tutorSessionId == null }.map { it.completionId }.toSet()
        val named = mutableMapOf<String, String>()
        if (legacy.isNotEmpty()) for (session in tutorHistory()) {
            for (message in session.messages.filter { it.role == "tutor" }) {
                val id = readId(session, message.id)
                if (id in legacy) named[id] = session.sessionId
            }
        }
        val result = if (named.isEmpty()) ledger else store.update { current ->
            current.copy(events = current.events.map { e -> named[e.completionId]?.let { e.copy(activity = e.activity.copy(tutorSessionId = it)) } ?: e })
        }
        checkedLegacyReads = true
        return result
    }
    suspend fun checkpoint(version: ContentVersion) = store.read().checkpoints.singleOrNull {
        it.gameId == version.contentId && it.gameVersion == version.contentVersion
    }

    private fun LearningLedger.starsOf(game: ContentVersion) =
        stars.firstOrNull { it.gameId == game.contentId && it.gameVersion == game.contentVersion }?.stars ?: 0
    private fun LearningLedger.withStars(game: ContentVersion, earned: Int): LearningLedger =
        if (earned <= starsOf(game)) this
        else copy(stars = stars.filterNot { it.gameId == game.contentId && it.gameVersion == game.contentVersion } + GameStars(game.contentId, game.contentVersion, earned))

    /** The best star count ever earned on [game] (0 = none yet). */
    suspend fun bestStars(game: ContentVersion): Int = gameStates(listOf(game)).getValue(game).stars

    /** Keeps the better of the stored and the new count. */
    suspend fun recordStars(game: ContentVersion, stars: Int) {
        val earned = stars.coerceIn(0, MAX_STARS)
        if (earned == 0) return
        store.update { it.withStars(game, earned) }
    }

    /** What a game's tile needs: its saved attempt and its stars. One read of the ledger for the whole shelf, moving older preference stars in. */
    suspend fun gameStates(games: Collection<ContentVersion>): Map<ContentVersion, GameState> {
        var ledger = store.read()
        val legacy = games.associateWith { legacyStars(it).coerceIn(0, MAX_STARS) }.filter { (game, old) -> old > ledger.starsOf(game) }
        if (legacy.isNotEmpty()) ledger = store.update { current -> legacy.entries.fold(current) { acc, (game, old) -> acc.withStars(game, old) } }
        return games.associateWith { game -> GameState(ledger.checkpoints.singleOrNull { it.gameId == game.contentId && it.gameVersion == game.contentVersion }, ledger.starsOf(game)) }
    }

    private fun GameCheckpoint.matches(spec: GameSpec) = gameId == spec.gameId && gameVersion == spec.gameVersion
    private fun event(id: String, ref: ActivityRef, outcome: ActivityOutcome, time: Long = clock.now(), zoneId: String = zone().id) =
        CompletionRecord.at(id, ref.key(profileId), outcome, time, zoneId)
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
        val ref = if (run.completed) run.spec.completionActivity() else ActivityRef.Game(run.spec.gameId, run.spec.gameVersion, stage.stageId, stage.rulesVersion)
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

    suspend fun tutorRead(session: TutorSession, time: Long = clock.now(), zoneId: String = zone().id) {
        val message = session.messages.lastOrNull { it.role == "tutor" } ?: return
        val ref = ActivityRef.Tutor(session.documentId, session.documentVersion,
            com.anything2game.contract.tutor.TutorRules.orderedQuestions(session)[session.conceptPosition].conceptId, sessionId = session.sessionId)
        val id = readId(session, message.id)
        store.update { it.add(event(id, ref, ActivityOutcome.READ, time, zoneId)) }
    }

    /** Credits an activity completed at a known time, for histories reconstructed outside the activity itself (the sample student). */
    suspend fun record(ref: ActivityRef, outcome: ActivityOutcome, time: Long, zoneId: String = zone().id) {
        val session = (ref as? ActivityRef.Tutor)?.let { requireNotNull(it.sessionId) } ?: ""
        val key = ref.key(profileId)
        store.update { it.add(event(stableId("record", key.activityKind.name, key.contentId, key.contentVersion, key.activityId, session, outcome.name), ref, outcome, time, zoneId)) }
    }

    suspend fun recordRead(ref: ActivityRef.Tutor, time: Long) = record(ref, ActivityOutcome.READ, time)

    suspend fun practice(state: SessionState, time: Long = clock.now(), zoneId: String = zone().id) {
        if (!state.isFinished) return
        val results = state.results.filter { it.slot.kind == SlotKind.UNIT }
        val outcome = when {
            state.phase == Phase.Failed || results.none { it.grade.correct } -> ActivityOutcome.WRONG
            results.any { !it.grade.correct || it.hintsUsed > 0 || it.slot.retry } -> ActivityOutcome.HELPED
            else -> ActivityOutcome.INDEPENDENT
        }
        val ref = ActivityRef.Practice(state.packageId, state.contentVersion, state.levelId, state.frameId)
        val id = stableId("practice", state.attemptId, ref.contentId, ref.contentVersion, ref.activityId)
        store.update { it.add(event(id, ref, outcome, time, zoneId)) }
    }

    private companion object { const val MAX_STARS = 3 }
}
