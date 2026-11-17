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
    /** Counters keyed "<kind>:<yyyy-MM-dd>", e.g. "compile:2026-11-17". Old days are pruned on write. */
    val usage: Map<String, Int> = emptyMap(),
)

@Serializable
data class StoredDocument(
    val documentId: String,
    val installId: String,
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
    val installId: String,
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
    val installId: String,
    val jobId: String,
    val complete: Boolean,
    val pkg: LearningPackage,
)

/**
 * Persistence behind the server. The MVP implementation is [FileStore]; a database or object store can replace it
 * without touching callers. All methods are blocking and thread-safe.
 */
interface Store {
    // installs and quota
    fun createInstall(record: InstallRecord)
    fun findInstallByTokenHash(hash: String): InstallRecord?
    /** Atomically adds one to the counter [kind] for [day]; false (and no change) if it is already at [limit]. */
    fun tryConsumeQuota(installId: String, kind: String, day: String, limit: Int): Boolean

    // documents
    fun saveDocument(doc: StoredDocument)
    fun getDocument(documentId: String): StoredDocument?
    /** Removes the document, its chunks, packages, frames and jobs. Idempotent. */
    fun deleteDocument(documentId: String)

    // jobs
    fun saveJob(job: JobRecord)
    fun getJob(jobId: String): JobRecord?
    /** Atomic read-modify-write. Returns the stored record after the update, or null if the job does not exist. */
    fun updateJob(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord?
    fun listJobs(): List<JobRecord>

    // packages and frames
    fun savePackage(stored: StoredPackage)
    fun getPackage(packageId: String): StoredPackage?
    fun deletePackage(packageId: String)
    fun saveFrame(frame: FrameOverlay)
    fun getFrame(packageId: String, frameId: FrameId): FrameOverlay?
}
