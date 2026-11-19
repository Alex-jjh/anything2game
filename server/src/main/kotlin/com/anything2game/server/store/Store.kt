package com.anything2game.server.store

import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.CompileJobStatus
import com.anything2game.contract.CompileOptions
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.SourceChunk
import kotlinx.serialization.Serializable

/** An anonymous install. Only a hash of the token is stored; the token itself is shown once, at creation. */
@Serializable
data class InstallRecord(
    val installId: String,
    val tokenHash: String,
    val createdAt: Long,
)

/** [ownerId] is the authenticated principal: `install:<id>` for an install token, `user:<sub>` for a signed-in user. */
@Serializable
data class StoredDocument(
    val documentId: String,
    val ownerId: String,
    val title: String,
    val kind: DocumentKind,
    val documentVersion: String,
    val pageCount: Int,
    val createdAt: Long,
    val chunks: List<SourceChunk>,
)

@Serializable
data class JobRecord(
    val jobId: String,
    val ownerId: String,
    val documentId: String,
    val documentVersion: String,
    val options: CompileOptions,
    val clientRequestId: String,
    val packageId: String,
    val state: CompileJobState = CompileJobState.QUEUED,
    val levelsReady: Int = 0,
    val levelsPlanned: Int = 0,
    val error: ApiError? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** The worker (one per server instance) that holds the lease; null while nobody does. */
    val claimedBy: String? = null,
    /** Epoch millis until which [claimedBy] owns the job. A non-terminal job with no lease or an expired lease can be claimed. */
    val leaseUntil: Long? = null,
    /** How many times a worker has claimed this job (a job that keeps killing its worker is failed instead of retried forever). */
    val attempts: Int = 0,
) {
    /** The package id is only announced once there is something to fetch. */
    fun toStatus() = CompileJobStatus(
        jobId = jobId, documentId = documentId, state = state,
        levelsReady = levelsReady, levelsPlanned = levelsPlanned,
        packageId = packageId.takeIf { levelsReady > 0 }, error = error,
    )
}

/** A package with its owner and whether the job is finished (frames are only generated for finished packages). */
@Serializable
data class StoredPackage(
    val ownerId: String,
    val jobId: String,
    val complete: Boolean,
    val pkg: LearningPackage,
)

/** Installs and the counters behind every quota and global cost guard. All methods are blocking and thread-safe. */
interface InstallRepository {
    fun createInstall(record: InstallRecord)
    fun findInstallByTokenHash(hash: String): InstallRecord?

    /**
     * Atomically adds one to the counter ([scope], [bucket]) unless it already is at [limit]; false (and no change) then.
     * The counter lives in the store, so the limit holds across every server instance that shares it.
     */
    fun tryConsume(scope: String, bucket: String, limit: Int): Boolean

    /** Per-owner daily counter, e.g. kind `compile`, day `2026-11-18`. */
    fun tryConsumeQuota(ownerId: String, kind: String, day: String, limit: Int): Boolean = tryConsume(ownerId, "$kind:$day", limit)

    /** Counter shared by all owners, e.g. kind `installs`, window `2026-11-18T14` (one hour). */
    fun tryConsumeGlobal(kind: String, window: String, limit: Int): Boolean = tryConsume(GLOBAL_SCOPE, "$kind:$window", limit)

    /** Drops counters not touched since [beforeEpochMillis] (old days and hours). */
    fun purgeCounters(beforeEpochMillis: Long)

    companion object {
        const val GLOBAL_SCOPE = "*"
    }
}

interface DocumentRepository {
    fun saveDocument(doc: StoredDocument)
    fun getDocument(documentId: String): StoredDocument?
    /** Removes the document, its chunks, packages, frames and jobs. Idempotent. */
    fun deleteDocument(documentId: String)
}

interface JobRepository {
    /** Insert or replace. */
    fun saveJob(job: JobRecord)

    /** Inserts the job unless the owner already has one with the same `clientRequestId`; returns the stored job either way. */
    fun createJob(job: JobRecord): JobRecord

    fun getJob(jobId: String): JobRecord?
    fun listJobsByOwner(ownerId: String): List<JobRecord>
    fun listJobsByDocument(documentId: String): List<JobRecord>

    /** Atomic read-modify-write. Returns the stored record after the update, or null if the job does not exist. */
    fun updateJob(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord?

    /**
     * Atomic read-modify-write that is skipped once the job is terminal (ready, partial, failed, cancelled): a terminal state wins.
     * Returns the stored record afterwards (unchanged if it was terminal), or null if the job does not exist.
     *
     * [transform] runs while the job is locked (Postgres: `SELECT ... FOR UPDATE`; FileStore: its lock), so it must be quick, and
     * it may read and write packages through the same store: those writes join the same transaction. That is how "publish a
     * level" and "cancel" are ordered by the database and not by an in-process lock.
     */
    fun updateIfNotTerminal(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord?

    /**
     * Gives the oldest claimable job to [workerId] for [leaseMillis] and returns it with the claim recorded (`claimedBy`, `leaseUntil`,
     * `attempts + 1`) and its state untouched: a state other than `queued` means a previous worker died or lost its lease. Claimable = not terminal and no lease or an expired
     * lease. Several callers, even on different server instances, never receive the same job (`FOR UPDATE SKIP LOCKED`).
     */
    fun claimNext(workerId: String, leaseMillis: Long, now: Long): JobRecord?

    /** Extends the lease. False if the job is gone, terminal or leased to someone else: the caller must stop working on it. */
    fun renewLease(jobId: String, workerId: String, leaseMillis: Long, now: Long): Boolean

    /** Gives the job back immediately (graceful shutdown) so another worker need not wait for the lease to expire. */
    fun releaseLease(jobId: String, workerId: String)
}

/** Packages and the frames that belong to them. */
interface PackageRepository {
    fun savePackage(stored: StoredPackage)
    fun getPackage(packageId: String): StoredPackage?
    /** Removes the package and its frames. */
    fun deletePackage(packageId: String)
    fun saveFrame(frame: FrameOverlay)
    fun getFrame(packageId: String, frameId: FrameId): FrameOverlay?
}

/**
 * Everything the server persists, as one object to wire up. Callers should depend on the narrow repository they use,
 * not on this interface. Implementations: [FileStore] (local development and tests) and [PostgresStore] (cloud).
 */
interface Store : com.anything2game.server.tutor.TutorStore, InstallRepository, DocumentRepository, JobRepository, PackageRepository, AutoCloseable {
    /** Throws if the backend cannot be reached (readiness probe). */
    fun ping()

    override fun close() {}
}
