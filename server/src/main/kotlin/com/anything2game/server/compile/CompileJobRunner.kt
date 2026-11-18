package com.anything2game.server.compile

import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.FrameId
import com.anything2game.contract.Generation
import com.anything2game.contract.GenerationMode
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PACKAGE_SCHEMA_V1
import com.anything2game.contract.ReviewStatus
import com.anything2game.server.store.DocumentRepository
import com.anything2game.server.store.JobRecord
import com.anything2game.server.store.JobRepository
import com.anything2game.server.store.PackageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * The worker that runs compile jobs: queued -> planning -> generating -> ready | partial | failed | cancelled (ARCHITECTURE 5.1).
 *
 * **The jobs table is the queue.** [start] runs a loop that claims jobs from the [JobRepository] with a lease (and is woken at once by
 * [wake] when this instance creates a job); while it works on a job it renews the lease every third of the lease time. Any number of
 * instances can run against one database: a job is handed to exactly one of them, and if its worker dies the lease expires and another
 * worker claims it (a job found half-done is rebuilt from scratch: its half-published package is discarded first).
 *
 * **Terminal states win, guaranteed by the store.** Every state change is [JobRepository.updateIfNotTerminal], and the changes of this
 * worker only apply while it still holds the lease (`claimedBy`), so a model answer that arrives after `cancel`, or after another worker
 * took the job over, is discarded (and [PublishNode] refuses to store it). There is no in-process lock.
 */
class CompileJobRunner(
    private val jobs: JobRepository,
    private val documents: DocumentRepository,
    private val packages: PackageRepository,
    private val planNode: PlanNode,
    private val levelNode: LevelNode,
    private val validateNode: ValidateNode,
    private val publishNode: PublishNode,
    private val frames: FrameService,
    private val defaultFrames: List<FrameId>,
    private val compileModelId: String,
    private val scope: CoroutineScope,
    val workerId: String,
    private val leaseMillis: Long = 30_000,
    private val pollMillis: Long = 1_000,
    private val maxParallelJobs: Int = 2,
    maxParallelLevels: Int = 3,
    private val maxAttempts: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(CompileJobRunner::class.java)
    private val levelPermits = Semaphore(maxParallelLevels)
    private val running = ConcurrentHashMap<String, Job>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    /** Starts the claim loop (once). Jobs left over from a stopped instance are picked up like any other queued job. */
    fun start() {
        if (started.compareAndSet(false, true)) scope.launch { loop() }
    }

    /** Asks the loop to look for work now instead of at the next poll. Call after creating a job. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /** Terminal state wins: if the job already finished, its state is returned unchanged. */
    fun cancel(jobId: String): JobRecord? {
        var cancelled = false
        val result = jobs.updateIfNotTerminal(jobId) { job ->
            cancelled = true
            markPackageComplete(job)
            job.copy(state = CompileJobState.CANCELLED, updatedAt = clock())
        }
        if (cancelled) running.remove(jobId)?.cancel()
        return result
    }

    /** Gives the running jobs back so another instance can take them at once, then stops. */
    fun close() {
        for (jobId in running.keys.toList()) runCatching { jobs.releaseLease(jobId, workerId) }
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------------------------

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            try {
                while (running.size < maxParallelJobs) {
                    val claimed = jobs.claimNext(workerId, leaseMillis, clock()) ?: break
                    launchJob(claimed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("claiming jobs failed: {}", e.javaClass.simpleName) // e.g. the database is restarting; try again at the next poll
            }
            withTimeoutOrNull(pollMillis) { wakeups.receive() }
        }
    }

    private fun launchJob(claimed: JobRecord) {
        val jobId = claimed.jobId
        val handle = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val framesWanted = coroutineScope {
                    val work = this.coroutineContext[Job]!!
                    val heartbeat = launch { renewLeaseWhileRunning(jobId, work) }
                    try { run(claimed) } finally { heartbeat.cancel() }
                }
                if (framesWanted) frames.generateDefaults(claimed.packageId, defaultFrames)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("job {} crashed outside the pipeline: {}", jobId, e.javaClass.name)
            } finally {
                running.remove(jobId)
                wake() // a slot is free
            }
        }
        running[jobId] = handle
        handle.start()
    }

    /** Extends the lease; if the job was cancelled, deleted or taken over elsewhere, stops this worker's work on it. */
    private suspend fun renewLeaseWhileRunning(jobId: String, work: Job) {
        while (true) {
            delay(leaseMillis / 3)
            val kept = try {
                jobs.renewLease(jobId, workerId, leaseMillis, clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("job {} lease renewal failed: {}", jobId, e.javaClass.simpleName)
                continue // the lease is still valid for two more attempts
            }
            if (!kept) {
                log.info("job {} is no longer ours (cancelled, deleted or taken over): stopping", jobId)
                work.cancel()
                return
            }
        }
    }

    /** Returns true when the job ended ready or partial under this worker (default frames are generated next). */
    private suspend fun run(claimed: JobRecord): Boolean {
        val jobId = claimed.jobId
        if (claimed.attempts > maxAttempts) {
            log.warn("job {} gave up after {} attempts", jobId, claimed.attempts - 1)
            finish(jobId, CompileJobState.FAILED, ApiError(ErrorCodes.GENERATION_FAILED, "Generation failed repeatedly.", retryable = true))
            return false
        }
        val doc = documents.getDocument(claimed.documentId)
        if (doc == null) {
            finish(jobId, CompileJobState.FAILED, ApiError(ErrorCodes.NOT_FOUND, "The document no longer exists.", retryable = false))
            return false
        }
        val job = claimed
        try {
            if (job.state != CompileJobState.QUEUED) {
                // a previous worker died half-way: start again from a clean package
                packages.deletePackage(job.packageId)
                log.info("job {} re-queued after a worker stopped (was {})", jobId, job.state)
                if (!transition(jobId, CompileJobState.QUEUED) { it.copy(levelsReady = 0, levelsPlanned = 0) }) return false
            }
            if (!transition(jobId, CompileJobState.PLANNING) { it }) return false
            val plan = planNode.run(PlanInput(doc, job.options))
            if (!transition(jobId, CompileJobState.GENERATING) { it.copy(levelsPlanned = plan.levels.size) }) return false

            val shell = LearningPackage(
                schema = PACKAGE_SCHEMA_V1, packageId = job.packageId, contentVersion = "1",
                documentId = doc.documentId, documentVersion = doc.documentVersion, title = doc.title, language = job.options.language,
                generation = Generation(GenerationMode.LIVE, compileModelId, "plan.v1+level.v1", ReviewStatus.AUTO_VALIDATED),
                sources = doc.chunks, levels = emptyList(),
            )
            // Level 1 is started first (UNDISPATCHED = it reaches the permit queue before level 2 exists); the rest follow in parallel.
            val outcomes: List<LevelOutcome> = coroutineScope {
                plan.levels.map { planned ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        levelPermits.withPermit {
                            try {
                                val draft = levelNode.run(LevelInput(planned, doc.chunks, job.options))
                                val valid = validateNode.run(ValidateInput(draft, shell))
                                if (valid.droppedUnits.isNotEmpty() || valid.repaired) {
                                    log.info("job {} level {}: repaired={} droppedUnits={}", jobId, planned.levelId, valid.repaired, valid.droppedUnits.size)
                                }
                                val published = publishNode.run(PublishInput(jobId, job.ownerId, valid.level, shell))
                                LevelOutcome(published, null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: GenerationException) {
                                log.warn("job {} level {} failed: {}", jobId, planned.levelId, e.code)
                                LevelOutcome(false, e)
                            }
                        }
                    }
                }.awaitAll()
            }

            val published = outcomes.count { it.published }
            val first = outcomes.firstNotNullOfOrNull { it.error }
            val final = when {
                published == plan.levels.size -> CompileJobState.READY
                published > 0 -> CompileJobState.PARTIAL
                else -> CompileJobState.FAILED
            }
            val error = if (final == CompileJobState.FAILED) {
                ApiError(first?.code ?: ErrorCodes.GENERATION_FAILED, first?.message ?: "No level could be generated.", first?.retryable ?: true)
            } else null
            return finish(jobId, final, error) && final != CompileJobState.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationException) {
            log.warn("job {} failed: {}", jobId, e.code)
            finish(jobId, CompileJobState.FAILED, ApiError(e.code, e.message ?: "Generation failed.", e.retryable))
        } catch (e: Exception) {
            log.error("job {} crashed: {}", jobId, e.javaClass.name)
            finish(jobId, CompileJobState.FAILED, ApiError(ErrorCodes.GENERATION_FAILED, "Generation failed unexpectedly.", retryable = true))
        }
        return false
    }

    private data class LevelOutcome(val published: Boolean, val error: GenerationException?)

    /**
     * Applies [change] unless the job is terminal or this worker no longer holds the lease; true if it was applied.
     * (The lease check stops a worker that was thought dead from writing over the worker that took its job.)
     */
    private fun mutate(jobId: String, change: (JobRecord) -> JobRecord): Boolean {
        var applied = false
        jobs.updateIfNotTerminal(jobId) { job ->
            if (job.claimedBy != workerId) job else { applied = true; change(job) }
        }
        return applied
    }

    /** Moves the job forward unless it is already terminal. Returns false if it was (the caller stops). */
    private fun transition(jobId: String, to: CompileJobState, change: (JobRecord) -> JobRecord): Boolean =
        mutate(jobId) { change(it).copy(state = to, updatedAt = clock()) }

    /** Sets a terminal state unless one is already set; true if this call did it. */
    private fun finish(jobId: String, state: CompileJobState, error: ApiError?): Boolean = mutate(jobId) { job ->
        if (state != CompileJobState.FAILED) markPackageComplete(job)
        job.copy(state = state, error = error, updatedAt = clock())
    }

    /** Runs inside the job's locked update, so the package is complete exactly when the job is terminal. */
    private fun markPackageComplete(job: JobRecord) {
        val stored = packages.getPackage(job.packageId) ?: return
        if (!stored.complete) packages.savePackage(stored.copy(complete = true))
    }
}
