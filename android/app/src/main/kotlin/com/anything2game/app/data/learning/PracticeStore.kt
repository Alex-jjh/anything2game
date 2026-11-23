package com.anything2game.app.data.learning

import com.anything2game.app.data.*
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.ports.*
import com.anything2game.engine.session.SessionState
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File
import java.time.ZoneId

@Serializable private data class PracticeWrite(val state: SessionState, val changed: List<MasteryRecord>, val millis: Long, val zone: String)

/** Recoverable transition across the existing unit store, checkpoint and common progress record.
 * The durable intent contains absolute post-answer records. Recovery never grades the answer again. */
class PracticeStore(file: File, private val sessions: SessionStore, private val units: MasteryStore,
    private val progress: LearningProgress, private val clock: Clock) : SessionStore, MasteryStore {
    private val lock = File(file.path + ".lock").mutex()
    private val journal = AtomicSnapshot(file, PracticeWrite.serializer().nullable) { null }
    private suspend fun finish(write: PracticeWrite) {
        val state = write.state
        for (record in write.changed) units.put(state.packageId, state.contentVersion, record)
        sessions.save(state)
        progress.practice(state, write.millis, write.zone)
        journal.update { null }
    }
    private suspend fun <T> afterRecovery(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            journal.read()?.let { finish(it) }
            action()
        }
    }
    suspend fun recover() = afterRecovery { Unit }
    /** Accept an initialization calculated outside the lock only if its checkpoint is still current. */
    suspend fun initialize(previous: SessionState?, candidate: SessionState): Map<String, MasteryRecord>? = afterRecovery {
        val current = sessions.load(candidate.packageId, candidate.contentVersion)
        if (current != previous) return@afterRecovery null
        val records = units.all(candidate.packageId, candidate.contentVersion)
        if (candidate != current) sessions.save(candidate)
        records
    }
    suspend fun commit(previous: SessionState, next: SessionState, changed: List<MasteryRecord>) = afterRecovery {
        val saved = sessions.load(previous.packageId, previous.contentVersion)
        if (saved == next) return@afterRecovery
        require(saved == previous) { "Stale practice checkpoint" }
        if (changed.isEmpty() && !next.isFinished) sessions.save(next) else {
            val write = PracticeWrite(next, changed, clock.now(), ZoneId.systemDefault().id)
            journal.update { write }
            finish(write)
        }
    }
    override suspend fun save(state: SessionState) = afterRecovery { sessions.save(state) }
    override suspend fun load(packageId: String) = afterRecovery { sessions.load(packageId) }
    override suspend fun load(packageId: String, version: String) = afterRecovery { sessions.load(packageId, version) }
    override suspend fun clear(packageId: String) = afterRecovery { sessions.clear(packageId) }
    override suspend fun all(packageId: String) = afterRecovery { units.all(packageId) }
    override suspend fun all(packageId: String, version: String) = afterRecovery { units.all(packageId, version) }
    override suspend fun put(packageId: String, record: MasteryRecord) = afterRecovery { units.put(packageId, record) }
    override suspend fun put(packageId: String, version: String, record: MasteryRecord) = afterRecovery { units.put(packageId, version, record) }
    override suspend fun legacyUnversioned(packageId: String) = afterRecovery { units.legacyUnversioned(packageId) }
}
