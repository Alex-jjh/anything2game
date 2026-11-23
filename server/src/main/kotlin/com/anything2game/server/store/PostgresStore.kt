package com.anything2game.server.store

import com.anything2game.server.tutor.TutorRecord
import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.DocumentKind
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.SourceChunk
import com.anything2game.server.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * PostgreSQL through plain JDBC (HikariCP pool, Flyway migrations under `db/migration`). No cloud SDK: RDS, Cloud SQL and
 * ApsaraDB are all just a JDBC URL.
 *
 * Several server instances can share one database:
 *  - job state changes are `SELECT ... FOR UPDATE` transactions ([updateIfNotTerminal]), so cancel and publish cannot interleave;
 *  - jobs are claimed with `FOR UPDATE SKIP LOCKED` and a lease ([claimNext]);
 *  - quotas and global cost guards are atomic upserts ([tryConsume]).
 *
 * Inside the callback of [updateJob] / [updateIfNotTerminal] the package methods of this class use the same connection (a
 * thread-local), so "store the level and bump the job" is one transaction.
 */
class PostgresStore private constructor(private val ds: DataSource, private val closeable: AutoCloseable?) : Store {
    private val json = ContractJson.json
    private val tx = ThreadLocal<Connection?>()

    // ---- connection helpers ---------------------------------------------------------------------------------

    private fun <T> withConnection(block: (Connection) -> T): T {
        tx.get()?.let { return block(it) }
        return ds.connection.use(block)
    }

    /** Runs [block] in one transaction (or joins the running one). */
    private fun <T> transaction(block: (Connection) -> T): T {
        tx.get()?.let { return block(it) }
        ds.connection.use { c ->
            c.autoCommit = false
            tx.set(c)
            try {
                val result = block(c)
                c.commit()
                return result
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            } finally {
                tx.remove()
                c.autoCommit = true
            }
        }
    }

    private fun <T> Connection.query(sql: String, bind: PreparedStatement.() -> Unit = {}, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            ps.bind()
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun Connection.update(sql: String, bind: PreparedStatement.() -> Unit = {}): Int =
        prepareStatement(sql).use { ps -> ps.bind(); ps.executeUpdate() }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = jsonbSafe(json.encodeToString(serializer, value))
    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    override fun <T> withOwnerLock(ownerId: String, block: () -> T): T = transaction { c ->
        c.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 712))", { setString(1, ownerId) }) { Unit }
        block()
    }
    override fun saveSpace(space: StoredSpace) {
        withConnection { c -> c.update("""INSERT INTO spaces VALUES (?, ?, ?::jsonb)
            ON CONFLICT(space_id) DO UPDATE SET body = EXCLUDED.body""") {
            setString(1, space.space.spaceId); setString(2, space.ownerId); setString(3, encode(StoredSpace.serializer(), space))
        } }
    }
    override fun getSpace(id: String) = withConnection { c ->
        c.query("SELECT body FROM spaces WHERE space_id = ?", { setString(1, id) }) { decode(StoredSpace.serializer(), it.getString(1)) }.firstOrNull()
    }
    override fun listSpaces(ownerId: String) = withConnection { c ->
        c.query("SELECT body FROM spaces WHERE owner_id = ?", { setString(1, ownerId) }) { decode(StoredSpace.serializer(), it.getString(1)) }
    }
    override fun writeArtifact(jobId: String, bytes: ByteArray) {
        withConnection { c -> c.update("INSERT INTO activity_artifacts(job_id, archive) VALUES (?, ?)") {
            setString(1, jobId); setBytes(2, bytes)
        } }
    }
    override fun readArtifact(jobId: String) = withConnection { c ->
        c.query("SELECT archive FROM activity_artifacts WHERE job_id = ?", { setString(1, jobId) }) { it.getBytes(1) }.firstOrNull()
    }

    override fun getTutor(id: String): TutorRecord? = withConnection { c ->
        c.query("SELECT body FROM tutors WHERE session_id = ?", { setString(1, id) }) {
            decode(TutorRecord.serializer(), it.getString(1))
        }.firstOrNull()
    }

    override fun createTutor(record: TutorRecord): TutorRecord? = transaction { c ->
        // Lock the document while inserting, so concurrent deletion cannot resurrect a tutor.
        if (c.query("SELECT document_id FROM documents WHERE document_id = ? FOR KEY SHARE", {
            setString(1, record.session.documentId)
        }) { it.getString(1) }.isEmpty()) return@transaction null
        c.update("INSERT INTO tutors (session_id, document_id, revision, body) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING") {
            setString(1, record.session.sessionId); setString(2, record.session.documentId)
            setInt(3, record.session.revision); setString(4, encode(TutorRecord.serializer(), record))
        }
        getTutor(record.session.sessionId)
    }

    override fun saveTutorIfRevision(record: TutorRecord, expectedRevision: Int): Boolean = withConnection { c ->
        c.update("UPDATE tutors SET revision = ?, body = ?::jsonb WHERE session_id = ? AND revision = ?") {
            setInt(1, record.session.revision); setString(2, encode(TutorRecord.serializer(), record))
            setString(3, record.session.sessionId); setInt(4, expectedRevision)
        } == 1
    }

    // ---- installs and counters ------------------------------------------------------------------------------

    override fun createInstall(record: InstallRecord) {
        withConnection { c ->
            c.update("INSERT INTO installs (install_id, token_hash, created_at) VALUES (?, ?, ?)") {
                setString(1, record.installId); setString(2, record.tokenHash); setLong(3, record.createdAt)
            }
        }
    }

    override fun findInstallByTokenHash(hash: String): InstallRecord? = withConnection { c ->
        c.query("SELECT install_id, token_hash, created_at FROM installs WHERE token_hash = ?", { setString(1, hash) }) {
            InstallRecord(it.getString(1), it.getString(2), it.getLong(3))
        }.firstOrNull()
    }

    override fun tryConsume(scope: String, bucket: String, limit: Int): Boolean {
        if (limit <= 0) return false
        return withConnection { c ->
            // One statement: insert the first use, or add one while still below the limit. No row back = at the limit.
            c.query(
                """INSERT INTO quota_counters (scope, bucket, n, updated_at) VALUES (?, ?, 1, ?)
                   ON CONFLICT (scope, bucket) DO UPDATE SET n = quota_counters.n + 1, updated_at = EXCLUDED.updated_at
                   WHERE quota_counters.n < ?
                   RETURNING n""",
                { setString(1, scope); setString(2, bucket); setLong(3, System.currentTimeMillis()); setInt(4, limit) },
            ) { it.getInt(1) }.isNotEmpty()
        }
    }

    override fun purgeCounters(beforeEpochMillis: Long) {
        withConnection { c -> c.update("DELETE FROM quota_counters WHERE updated_at < ?") { setLong(1, beforeEpochMillis) } }
    }

    // ---- documents ------------------------------------------------------------------------------------------

    override fun saveDocument(doc: StoredDocument) {
        withConnection { c ->
            c.update(
                """INSERT INTO documents (document_id, owner_id, title, kind, document_version, page_count, created_at, chunks)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                   ON CONFLICT (document_id) DO UPDATE SET owner_id = EXCLUDED.owner_id, title = EXCLUDED.title, kind = EXCLUDED.kind,
                     document_version = EXCLUDED.document_version, page_count = EXCLUDED.page_count, chunks = EXCLUDED.chunks""",
            ) {
                setString(1, doc.documentId); setString(2, doc.ownerId); setString(3, doc.title); setString(4, doc.kind.name.lowercase())
                setString(5, doc.documentVersion); setInt(6, doc.pageCount); setLong(7, doc.createdAt)
                setString(8, encode(ListSerializer(SourceChunk.serializer()), doc.chunks))
            }
        }
    }

    override fun getDocument(documentId: String): StoredDocument? = withConnection { c ->
        c.query(
            "SELECT document_id, owner_id, title, kind, document_version, page_count, created_at, chunks FROM documents WHERE document_id = ?",
            { setString(1, documentId) },
        ) {
            StoredDocument(
                it.getString(1), it.getString(2), it.getString(3), DocumentKind.valueOf(it.getString(4).uppercase()), it.getString(5),
                it.getInt(6), it.getLong(7), decode(ListSerializer(SourceChunk.serializer()), it.getString(8)),
            )
        }.firstOrNull()
    }

    /** Jobs, packages and (through the package) frames go with the document: `ON DELETE CASCADE`. */
    override fun deleteDocument(documentId: String) {
        val owner = getDocument(documentId)?.ownerId ?: return
        withOwnerLock(owner) {
            cancelPathsUsing(owner, documentId)
            withConnection { c -> c.update("DELETE FROM documents WHERE document_id = ?") { setString(1, documentId) } }
        }
    }

    // ---- jobs -----------------------------------------------------------------------------------------------

    private val jobColumns = "job_id, owner_id, document_id, document_version, options, client_request_id, package_id, state, " +
        "levels_ready, levels_planned, error, created_at, updated_at, claimed_by, lease_until, attempts, operation, generation"

    private fun readJob(rs: ResultSet) = JobRecord(
        jobId = rs.getString(1), ownerId = rs.getString(2), documentId = rs.getString(3), documentVersion = rs.getString(4),
        options = rs.getString(5)?.let { decode(CompileOptions.serializer(), it) }, clientRequestId = rs.getString(6), packageId = rs.getString(7),
        state = CompileJobState.valueOf(rs.getString(8).uppercase()), levelsReady = rs.getInt(9), levelsPlanned = rs.getInt(10),
        error = rs.getString(11)?.let { decode(ApiError.serializer(), it) }, createdAt = rs.getLong(12), updatedAt = rs.getLong(13),
        claimedBy = rs.getString(14), leaseUntil = rs.getLong(15).takeUnless { rs.wasNull() }, attempts = rs.getInt(16),
        generation = rs.getString(18)?.let { decode(GenerationWork.serializer(), it) },
    )

    private fun PreparedStatement.bindJob(job: JobRecord) {
        setString(1, job.jobId); setString(2, job.ownerId); setString(3, job.documentId); setString(4, job.documentVersion)
        setString(5, job.options?.let { encode(CompileOptions.serializer(), it) }); setString(6, job.clientRequestId); setString(7, job.packageId)
        setString(8, job.state.name.lowercase()); setInt(9, job.levelsReady); setInt(10, job.levelsPlanned)
        setString(11, job.error?.let { encode(ApiError.serializer(), it) })
        setLong(12, job.createdAt); setLong(13, job.updatedAt); setString(14, job.claimedBy)
        if (job.leaseUntil == null) setNull(15, java.sql.Types.BIGINT) else setLong(15, job.leaseUntil)
        setInt(16, job.attempts); setString(17, job.operation); setString(18, job.generation?.let { encode(GenerationWork.serializer(), it) })
    }

    private val insertJob = "INSERT INTO jobs ($jobColumns) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb)"

    override fun saveJob(job: JobRecord) {
        withConnection { c ->
            c.update(
                "$insertJob ON CONFLICT (job_id) DO UPDATE SET state = EXCLUDED.state, levels_ready = EXCLUDED.levels_ready, " +
                    "levels_planned = EXCLUDED.levels_planned, error = EXCLUDED.error, updated_at = EXCLUDED.updated_at, " +
                    "claimed_by = EXCLUDED.claimed_by, lease_until = EXCLUDED.lease_until, attempts = EXCLUDED.attempts, generation = EXCLUDED.generation",
            ) { bindJob(job) }
        }
    }

    override fun createJob(job: JobRecord): JobRecord = withConnection { c ->
        val inserted = c.update("$insertJob ON CONFLICT (owner_id, operation, client_request_id) DO NOTHING") { bindJob(job) }
        if (inserted == 1) job
        else c.query("SELECT $jobColumns FROM jobs WHERE owner_id = ? AND client_request_id = ? AND operation = ?", {
            setString(1, job.ownerId); setString(2, job.clientRequestId); setString(3, job.operation)
        }, ::readJob).first()
    }

    override fun getJob(jobId: String): JobRecord? = withConnection { c ->
        c.query("SELECT $jobColumns FROM jobs WHERE job_id = ?", { setString(1, jobId) }, ::readJob).firstOrNull()
    }

    override fun listJobsByOwner(ownerId: String): List<JobRecord> = withConnection { c ->
        c.query("SELECT $jobColumns FROM jobs WHERE owner_id = ? ORDER BY created_at", { setString(1, ownerId) }, ::readJob)
    }

    override fun listJobsByDocument(documentId: String): List<JobRecord> = withConnection { c ->
        c.query("SELECT $jobColumns FROM jobs WHERE document_id = ? ORDER BY created_at", { setString(1, documentId) }, ::readJob)
    }

    override fun updateJob(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord? = lockedUpdate(jobId, skipTerminal = false, transform)

    override fun updateIfNotTerminal(jobId: String, transform: (JobRecord) -> JobRecord): JobRecord? = lockedUpdate(jobId, skipTerminal = true, transform)

    private fun lockedUpdate(jobId: String, skipTerminal: Boolean, transform: (JobRecord) -> JobRecord): JobRecord? = transaction { c ->
        val current = c.query("SELECT $jobColumns FROM jobs WHERE job_id = ? FOR UPDATE", { setString(1, jobId) }, ::readJob).firstOrNull()
            ?: return@transaction null
        if (skipTerminal && current.state.isTerminal) return@transaction current
        val next = transform(current)
        if (next != current) {
            c.update(
                """UPDATE jobs SET state = ?, levels_ready = ?, levels_planned = ?, error = ?::jsonb, updated_at = ?,
                     claimed_by = ?, lease_until = ?, attempts = ?, generation = ?::jsonb WHERE job_id = ?""",
            ) {
                setString(1, next.state.name.lowercase()); setInt(2, next.levelsReady); setInt(3, next.levelsPlanned)
                setString(4, next.error?.let { encode(ApiError.serializer(), it) }); setLong(5, next.updatedAt); setString(6, next.claimedBy)
                if (next.leaseUntil == null) setNull(7, java.sql.Types.BIGINT) else setLong(7, next.leaseUntil)
                setInt(8, next.attempts); setString(9, next.generation?.let { encode(GenerationWork.serializer(), it) }); setString(10, jobId)
            }
        }
        next
    }

    override fun claimNext(workerId: String, leaseMillis: Long, now: Long, runningHere: Set<String>): JobRecord? = withConnection { c ->
        c.query(
            """UPDATE jobs SET claimed_by = ?, lease_until = ?, attempts = attempts + 1
               WHERE job_id = (
                 SELECT job_id FROM jobs
                 WHERE state IN ('queued', 'planning', 'generating') AND (lease_until IS NULL OR lease_until < ?) AND job_id <> ALL (?)
                 ORDER BY created_at, job_id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED)
               RETURNING $jobColumns""",
            { setString(1, workerId); setLong(2, now + leaseMillis); setLong(3, now); setArray(4, c.createArrayOf("text", runningHere.toTypedArray())) },
            ::readJob,
        ).firstOrNull()
    }

    override fun renewLease(jobId: String, workerId: String, leaseMillis: Long, now: Long): Boolean = withConnection { c ->
        c.update(
            """UPDATE jobs SET lease_until = ?
               WHERE job_id = ? AND claimed_by = ? AND lease_until >= ? AND state IN ('queued', 'planning', 'generating')""",
        ) { setLong(1, now + leaseMillis); setString(2, jobId); setString(3, workerId); setLong(4, now) } == 1
    }

    override fun releaseLease(jobId: String, workerId: String) {
        withConnection { c ->
            c.update("UPDATE jobs SET claimed_by = NULL, lease_until = NULL WHERE job_id = ? AND claimed_by = ? AND state IN ('queued', 'planning', 'generating')") {
                setString(1, jobId); setString(2, workerId)
            }
        }
    }

    // ---- packages and frames --------------------------------------------------------------------------------

    override fun savePackage(stored: StoredPackage) {
        withConnection { c ->
            c.update(
                """INSERT INTO packages (package_id, owner_id, job_id, document_id, complete, pkg) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                   ON CONFLICT (package_id) DO UPDATE SET owner_id = EXCLUDED.owner_id, job_id = EXCLUDED.job_id,
                     document_id = EXCLUDED.document_id, complete = EXCLUDED.complete, pkg = EXCLUDED.pkg""",
            ) {
                setString(1, stored.pkg.packageId); setString(2, stored.ownerId); setString(3, stored.jobId); setString(4, stored.pkg.documentId)
                setBoolean(5, stored.complete); setString(6, encode(LearningPackage.serializer(), stored.pkg))
            }
        }
    }

    override fun getPackage(packageId: String): StoredPackage? = withConnection { c ->
        c.query("SELECT owner_id, job_id, complete, pkg FROM packages WHERE package_id = ?", { setString(1, packageId) }) {
            StoredPackage(it.getString(1), it.getString(2), it.getBoolean(3), decode(LearningPackage.serializer(), it.getString(4)))
        }.firstOrNull()
    }

    override fun deletePackage(packageId: String) {
        withConnection { c -> c.update("DELETE FROM packages WHERE package_id = ?") { setString(1, packageId) } }
    }

    override fun saveFrame(frame: FrameOverlay) {
        try {
            withConnection { c ->
                c.update(
                    """INSERT INTO frames (package_id, frame_id, overlay) VALUES (?, ?, ?::jsonb)
                       ON CONFLICT (package_id, frame_id) DO UPDATE SET overlay = EXCLUDED.overlay""",
                ) { setString(1, frame.packageId); setString(2, frame.frameId.name.lowercase()); setString(3, encode(FrameOverlay.serializer(), frame)) }
            }
        } catch (e: SQLException) {
            // The package (and its document) was deleted while the model was working: do not resurrect anything.
            if (e.sqlState != FOREIGN_KEY_VIOLATION) throw e
        }
    }

    override fun getFrame(packageId: String, frameId: FrameId): FrameOverlay? = withConnection { c ->
        c.query("SELECT overlay FROM frames WHERE package_id = ? AND frame_id = ?", { setString(1, packageId); setString(2, frameId.name.lowercase()) }) {
            decode(FrameOverlay.serializer(), it.getString(1))
        }.firstOrNull()
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------

    override fun ping() {
        withConnection { c -> c.query("SELECT 1", map = { it.getInt(1) }) }
    }

    override fun close() {
        closeable?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresStore::class.java)
        private const val FOREIGN_KEY_VIOLATION = "23503"

        /** `\u0000` that is an escape (preceded by an even number of backslashes), not text that merely looks like one. */
        private val JSONB_NUL = Regex("""(?<!\\)((?:\\\\)*)\\u0000""")

        /** Opens the pool and applies the migrations (Flyway takes an advisory lock, so instances starting together are safe). */
        fun connect(config: DbConfig): PostgresStore {
            val pool = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password.reveal()
                    maximumPoolSize = config.poolSize
                    minimumIdle = minOf(2, config.poolSize)
                    connectionTimeout = 10_000
                    poolName = "a2g-db"
                },
            )
            try {
                Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
            } catch (e: Exception) {
                pool.close()
                throw e
            }
            log.info("postgres store ready (pool size {})", config.poolSize)
            return PostgresStore(pool, pool)
        }

        /** jsonb cannot hold U+0000; a model can emit it. Replace the JSON escape of it by the replacement character. */
        internal fun jsonbSafe(jsonText: String): String =
            if (!jsonText.contains("\\u0000")) jsonText else JSONB_NUL.replace(jsonText) { it.groupValues[1] + "\\ufffd" }
    }
}
