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
 * `installs/{id}.json`, `documents/{id}.json`, `packages/{id}.json`, `frames/{packageId}.{frameId}.json`, `jobs/{id}.json`.
 * Writes go to a temp file in the same directory and are renamed over the target, so a crash never leaves half a file.
 */
class FileStore(private val root: Path) : Store {
    private val json = ContractJson.json
    private val lock = ReentrantLock()
    private val installsDir = dir("installs")
    private val documentsDir = dir("documents")
    private val packagesDir = dir("packages")
    private val framesDir = dir("frames")
    private val jobsDir = dir("jobs")

    /** tokenHash -> installId, rebuilt from disk at startup. */
    private val tokenIndex = ConcurrentHashMap<String, String>()

    init {
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

    override fun tryConsumeQuota(installId: String, kind: String, day: String, limit: Int): Boolean = lock.withLock {
        val path = file(installsDir, installId)
        val rec = read(path, InstallRecord.serializer()) ?: return false
        val key = "$kind:$day"
        val used = rec.usage[key] ?: 0
        if (used >= limit) return false
        // keep only today's counters: the map stays tiny
        val pruned = rec.usage.filterKeys { it.endsWith(":$day") } + (key to used + 1)
        write(path, InstallRecord.serializer(), rec.copy(usage = pruned))
        true
    }

    // ---- documents ------------------------------------------------------------------------------------------

    override fun saveDocument(doc: StoredDocument) = lock.withLock { write(file(documentsDir, doc.documentId), StoredDocument.serializer(), doc) }

    override fun getDocument(documentId: String): StoredDocument? = read(file(documentsDir, documentId), StoredDocument.serializer())

    override fun deleteDocument(documentId: String) = lock.withLock {
        for (job in listJobs().filter { it.documentId == documentId }) {
            Files.deleteIfExists(file(jobsDir, job.jobId))
        }
        for (path in listPaths(packagesDir)) {
            val stored = read(path, StoredPackage.serializer()) ?: continue
            if (stored.pkg.documentId == documentId) deletePackageFiles(stored.pkg.packageId)
        }
        Files.deleteIfExists(file(documentsDir, documentId))
        Unit
    }

    // ---- jobs -----------------------------------------------------------------------------------------------

    override fun saveJob(job: JobRecord) = lock.withLock { write(file(jobsDir, job.jobId), JobRecord.serializer(), job) }

    override fun getJob(jobId: String): JobRecord? = read(file(jobsDir, jobId), JobRecord.serializer())

    override fun updateJob(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord? = lock.withLock {
        val path = file(jobsDir, jobId)
        val current = read(path, JobRecord.serializer()) ?: return null
        val next = transform(current)
        if (next != current) write(path, JobRecord.serializer(), next)
        next
    }

    override fun listJobs(): List<JobRecord> = listPaths(jobsDir).mapNotNull { read(it, JobRecord.serializer()) }

    // ---- packages and frames --------------------------------------------------------------------------------

    override fun savePackage(stored: StoredPackage) = lock.withLock { write(file(packagesDir, stored.pkg.packageId), StoredPackage.serializer(), stored) }

    override fun getPackage(packageId: String): StoredPackage? = read(file(packagesDir, packageId), StoredPackage.serializer())

    override fun deletePackage(packageId: String) = lock.withLock { deletePackageFiles(packageId) }

    private fun deletePackageFiles(packageId: String) {
        Files.deleteIfExists(file(packagesDir, packageId))
        FrameId.entries.forEach { Files.deleteIfExists(frameFile(packageId, it)) }
    }

    override fun saveFrame(frame: FrameOverlay) = lock.withLock {
        write(frameFile(frame.packageId, frame.frameId), FrameOverlay.serializer(), frame)
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
