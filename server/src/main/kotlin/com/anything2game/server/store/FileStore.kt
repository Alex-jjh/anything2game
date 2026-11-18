package com.anything2game.server.store

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * JSON files under a data directory:
 * `installs/{id}.json`, `documents/{id}.json`, `packages/{id}.json`, `frames/{packageId}.{frameId}.json`, `jobs/{id}.json`,
 * `counters.json`. Writes go to a temp file in the same directory and are renamed over the target, so a crash never leaves
 * half a file.
 *
 * For local development and tests: **single instance only**. The in-process lock below is what keeps it correct, so two
 * server processes (or two stores) over one directory are not supported; use [PostgresStore] for that.
 */
class FileStore(private val root: Path) : Store {
    private val json = ContractJson.json
    private val lock = ReentrantLock()
    private val installsDir = dir("installs")
    private val documentsDir = dir("documents")
    private val packagesDir = dir("packages")
    private val framesDir = dir("frames")
    private val jobsDir = dir("jobs")

    private val countersFile = root.resolve("counters.json")

    /** tokenHash -> installId, rebuilt from disk at startup. */
    private val tokenIndex = ConcurrentHashMap<String, String>()

    /** (scope, bucket) -> counter, loaded from [countersFile] at startup. Guarded by [lock]. */
    private val counters = HashMap<Pair<String, String>, Counter>()

    /** Ids of jobs that are not terminal: the only ones the worker poll has to look at. Guarded by [lock]. */
    private val activeJobs = LinkedHashSet<String>()

    @kotlinx.serialization.Serializable
    private data class Counter(val scope: String, val bucket: String, val n: Int, val updatedAt: Long)

    init {
        read(countersFile, kotlinx.serialization.builtins.ListSerializer(Counter.serializer()))?.forEach { counters[it.scope to it.bucket] = it }
        listPaths(jobsDir).mapNotNull { read(it, JobRecord.serializer()) }.filter { !it.state.isTerminal }.forEach { activeJobs += it.jobId }
        Files.list(installsDir).use { files ->
            files.filter { it.name.endsWith(".json") }.forEach { f ->
                runCatching { read(f, InstallRecord.serializer()) }.getOrNull()?.let { tokenIndex[it.tokenHash] = it.installId }
            }
        }
    }

    private fun dir(name: String): Path = Files.createDirectories(root.resolve(name))

    // ---- installs -------------------------------------------------------------------------------------------

    override fun createInstall(record: InstallRecord) = lock.withLock {
        write(file(installsDir, record.installId), InstallRecord.serializer(), record)
        tokenIndex[record.tokenHash] = record.installId
    }

    override fun findInstallByTokenHash(hash: String): InstallRecord? {
        val id = tokenIndex[hash] ?: return null
        return read(file(installsDir, id), InstallRecord.serializer())
    }

    override fun tryConsume(scope: String, bucket: String, limit: Int): Boolean = lock.withLock {
        val key = scope to bucket
        val used = counters[key]?.n ?: 0
        if (limit <= 0 || used >= limit) return false
        counters[key] = Counter(scope, bucket, used + 1, System.currentTimeMillis())
        saveCounters()
        true
    }

    override fun purgeCounters(beforeEpochMillis: Long) = lock.withLock {
        if (counters.values.removeIf { it.updatedAt < beforeEpochMillis }) saveCounters()
        Unit
    }

    private fun saveCounters() = write(countersFile, kotlinx.serialization.builtins.ListSerializer(Counter.serializer()), counters.values.toList())

    // ---- documents ------------------------------------------------------------------------------------------

    override fun saveDocument(doc: StoredDocument) = lock.withLock { write(file(documentsDir, doc.documentId), StoredDocument.serializer(), doc) }

    override fun getDocument(documentId: String): StoredDocument? = read(file(documentsDir, documentId), StoredDocument.serializer())

    override fun deleteDocument(documentId: String) = lock.withLock {
        for (job in listJobsByDocument(documentId)) {
            Files.deleteIfExists(file(jobsDir, job.jobId))
            activeJobs.remove(job.jobId)
        }
        for (path in listPaths(packagesDir)) {
            val stored = read(path, StoredPackage.serializer()) ?: continue
            if (stored.pkg.documentId == documentId) deletePackageFiles(stored.pkg.packageId)
        }
        Files.deleteIfExists(file(documentsDir, documentId))
        Unit
    }

    // ---- jobs -----------------------------------------------------------------------------------------------

    override fun saveJob(job: JobRecord) = lock.withLock { writeJob(job) }

    override fun createJob(job: JobRecord): JobRecord = lock.withLock {
        listJobsByOwner(job.ownerId).firstOrNull { it.clientRequestId == job.clientRequestId }?.let { return@withLock it }
        writeJob(job)
        job
    }

    override fun getJob(jobId: String): JobRecord? = read(file(jobsDir, jobId), JobRecord.serializer())

    override fun listJobsByOwner(ownerId: String): List<JobRecord> = listJobs().filter { it.ownerId == ownerId }

    override fun listJobsByDocument(documentId: String): List<JobRecord> = listJobs().filter { it.documentId == documentId }

    override fun updateJob(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord? = lock.withLock {
        val current = read(file(jobsDir, jobId), JobRecord.serializer()) ?: return null
        val next = transform(current)
        if (next != current) writeJob(next)
        next
    }

    override fun updateIfNotTerminal(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord? =
        updateJob(jobId) { if (it.state.isTerminal) it else transform(it) }

    override fun claimNext(workerId: String, leaseMillis: Long, now: Long): JobRecord? = lock.withLock {
        val candidate = activeJobs.mapNotNull { getJob(it) }
            .filter { !it.state.isTerminal && (it.leaseUntil == null || it.leaseUntil < now) }
            .minWithOrNull(compareBy({ it.createdAt }, { it.jobId })) ?: return null
        val claimed = candidate.copy(claimedBy = workerId, leaseUntil = now + leaseMillis, attempts = candidate.attempts + 1)
        writeJob(claimed)
        claimed
    }

    override fun renewLease(jobId: String, workerId: String, leaseMillis: Long, now: Long): Boolean = lock.withLock {
        val job = getJob(jobId) ?: return false
        if (job.state.isTerminal || job.claimedBy != workerId) return false
        writeJob(job.copy(leaseUntil = now + leaseMillis))
        true
    }

    override fun releaseLease(jobId: String, workerId: String) {
        lock.withLock {
            val job = getJob(jobId) ?: return
            if (!job.state.isTerminal && job.claimedBy == workerId) writeJob(job.copy(claimedBy = null, leaseUntil = null))
        }
    }

    /** Every job on disk. For tests and tooling, not part of [JobRepository]. */
    fun listJobs(): List<JobRecord> = listPaths(jobsDir).mapNotNull { read(it, JobRecord.serializer()) }

    private fun writeJob(job: JobRecord) {
        write(file(jobsDir, job.jobId), JobRecord.serializer(), job)
        if (job.state.isTerminal) activeJobs.remove(job.jobId) else activeJobs.add(job.jobId)
    }

    override fun ping() {
        check(Files.isWritable(root)) { "data directory is not writable" }
    }

    // ---- packages and frames --------------------------------------------------------------------------------

    override fun savePackage(stored: StoredPackage) = lock.withLock { write(file(packagesDir, stored.pkg.packageId), StoredPackage.serializer(), stored) }

    override fun getPackage(packageId: String): StoredPackage? = read(file(packagesDir, packageId), StoredPackage.serializer())

    override fun deletePackage(packageId: String) = lock.withLock { deletePackageFiles(packageId) }

    private fun deletePackageFiles(packageId: String) {
        Files.deleteIfExists(file(packagesDir, packageId))
        FrameId.entries.forEach { Files.deleteIfExists(frameFile(packageId, it)) }
    }

    /** A frame of a package that no longer exists is dropped (the package was deleted while the model was working). */
    override fun saveFrame(frame: FrameOverlay) {
        lock.withLock {
            if (file(packagesDir, frame.packageId).exists()) write(frameFile(frame.packageId, frame.frameId), FrameOverlay.serializer(), frame)
        }
    }

    override fun getFrame(packageId: String, frameId: FrameId): FrameOverlay? = read(frameFile(packageId, frameId), FrameOverlay.serializer())

    // ---- file helpers ---------------------------------------------------------------------------------------

    private fun frameFile(packageId: String, frameId: FrameId): Path {
        requireSafeId(packageId)
        return framesDir.resolve("$packageId.${frameId.name.lowercase()}.json")
    }

    /** IDs reach file names, so they must never contain path separators or dots. */
    private fun file(dir: Path, id: String): Path {
        requireSafeId(id)
        return dir.resolve("$id.json")
    }

    private fun requireSafeId(id: String) = require(SAFE_ID.matches(id)) { "unsafe id" }

    private fun listPaths(dir: Path): List<Path> = Files.list(dir).use { s -> s.filter { it.name.endsWith(".json") }.toList() }

    private fun <T> read(path: Path, serializer: KSerializer<T>): T? {
        if (!path.exists()) return null
        return try {
            json.decodeFromString(serializer, Files.readString(path, StandardCharsets.UTF_8))
        } catch (e: java.nio.file.NoSuchFileException) {
            null
        }
    }

    private fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        val tmp = Files.createTempFile(path.parent, ".tmp-", ".part")
        try {
            Files.writeString(tmp, json.encodeToString(serializer, value), StandardCharsets.UTF_8)
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    companion object {
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    }
}
