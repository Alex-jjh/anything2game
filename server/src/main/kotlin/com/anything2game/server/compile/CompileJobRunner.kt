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
import com.anything2game.server.store.JobRecord
import com.anything2game.server.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-process worker that runs compile jobs: queued -> planning -> generating -> ready | partial | failed | cancelled
 * (ARCHITECTURE 5.1). The job table is the queue: [resumePending] re-queues non-terminal jobs after a restart.
 *
 * Terminal states win. Every state write goes through [stateLock] and is skipped once the job is terminal, so a model answer
 * that arrives after `cancel` is discarded (and [PublishNode] refuses to store it).
 */
class CompileJobRunner(
    private val store: Store,
    private val planNode: PlanNode,
    private val levelNode: LevelNode,
    private val validateNode: ValidateNode,
    private val publishNode: PublishNode,
    private val frames: FrameService,
    private val defaultFrames: List<FrameId>,
    private val compileModelId: String,
    private val stateLock: Mutex,
    private val scope: CoroutineScope,
    maxParallelJobs: Int = 2,
    maxParallelLevels: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(CompileJobRunner::class.java)
    private val jobPermits = Semaphore(maxParallelJobs)
    private val levelPermits = Semaphore(maxParallelLevels)
    private val running = ConcurrentHashMap<String, Job>()

    fun submit(jobId: String) {
        running[jobId] = scope.launch {
            try {
                jobPermits.withPermit { run(jobId) }
            } finally {
                running.remove(jobId)
            }
        }
    }

    /** Re-queues every job that was not finished when the process stopped. Their half-published packages are discarded first. */
    fun resumePending() {
        for (job in store.listJobs().filter { !it.state.isTerminal }) {
            store.deletePackage(job.packageId)
            store.updateJob(job.jobId) { it.copy(state = CompileJobState.QUEUED, levelsReady = 0, levelsPlanned = 0, updatedAt = clock()) }
            log.info("job {} re-queued after restart", job.jobId)
            submit(job.jobId)
        }
    }

    /** Terminal state wins: if the job already finished, its state is returned unchanged. */
    suspend fun cancel(jobId: String): JobRecord? {
        val result = stateLock.withLock {
            store.updateJob(jobId) { if (it.state.isTerminal) it else it.copy(state = CompileJobState.CANCELLED, updatedAt = clock()) }
                ?.also { if (it.state == CompileJobState.CANCELLED) markPackageComplete(it) }
        }
        if (result?.state == CompileJobState.CANCELLED) running.remove(jobId)?.cancel()
        return result
    }

    fun close() = scope.cancel()

    // ---------------------------------------------------------------------------------------------------------

    private suspend fun run(jobId: String) {
        val job = store.getJob(jobId) ?: return
        if (job.state.isTerminal) return
        val doc = store.getDocument(job.documentId)
        if (doc == null) {
            finish(jobId, CompileJobState.FAILED, ApiError(ErrorCodes.NOT_FOUND, "The document no longer exists.", retryable = false))
            return
        }
        try {
            if (!transition(jobId, CompileJobState.PLANNING) { it }) return
            val plan = planNode.run(PlanInput(doc, job.options))
            if (!transition(jobId, CompileJobState.GENERATING) { it.copy(levelsPlanned = plan.levels.size) }) return

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
                                val published = publishNode.run(PublishInput(jobId, job.installId, valid.level, shell))
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
            val state = finish(jobId, final, error)
            if (state == final && final != CompileJobState.FAILED) frames.generateDefaults(job.packageId, defaultFrames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationException) {
            log.warn("job {} failed: {}", jobId, e.code)
            finish(jobId, CompileJobState.FAILED, ApiError(e.code, e.message ?: "Generation failed.", e.retryable))
        } catch (e: Exception) {
            log.error("job {} crashed: {}", jobId, e.javaClass.name)
            finish(jobId, CompileJobState.FAILED, ApiError(ErrorCodes.GENERATION_FAILED, "Generation failed unexpectedly.", retryable = true))
        }
    }

    private data class LevelOutcome(val published: Boolean, val error: GenerationException?)

    /** Moves the job forward unless it is already terminal. Returns false if it was (the caller stops). */
    private suspend fun transition(jobId: String, to: CompileJobState, change: (JobRecord) -> JobRecord): Boolean = stateLock.withLock {
        val updated = store.updateJob(jobId) { if (it.state.isTerminal) it else change(it).copy(state = to, updatedAt = clock()) }
        updated?.state == to
    }

    /** Sets a terminal state unless one is already set; returns the state the job ends up in. */
    private suspend fun finish(jobId: String, state: CompileJobState, error: ApiError?): CompileJobState? = stateLock.withLock {
        val updated = store.updateJob(jobId) { if (it.state.isTerminal) it else it.copy(state = state, error = error, updatedAt = clock()) }
        if (updated != null && updated.state == state && state != CompileJobState.FAILED) markPackageComplete(updated)
        updated?.state
    }

    private fun markPackageComplete(job: JobRecord) {
        val stored = store.getPackage(job.packageId) ?: return
        if (!stored.complete) store.savePackage(stored.copy(complete = true))
    }
}
