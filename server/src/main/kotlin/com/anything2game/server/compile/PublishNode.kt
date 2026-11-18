package com.anything2game.server.compile

import com.anything2game.contract.LearningPackage
import com.anything2game.contract.Level
import com.anything2game.contract.PackageValidator
import com.anything2game.server.store.JobRepository
import com.anything2game.server.store.PackageRepository
import com.anything2game.server.store.StoredPackage
import org.slf4j.LoggerFactory

data class PublishInput(val jobId: String, val ownerId: String, val level: Level, val shell: LearningPackage)

/**
 * Adds a validated level to the stored package, so `GET /v1/packages/{id}` shows level 1 as soon as it exists.
 * Returns false (and changes nothing) when the job is already terminal, gone, or leased to another worker: a result that arrives
 * after `cancelled` is discarded. The guarantee comes from the store: the whole read-merge-write runs inside
 * [JobRepository.updateIfNotTerminal], which locks the job row, so `cancel` either happens before (publish is refused) or after
 * (the level is stored and the package is then marked complete). No in-process lock is involved, so it holds across instances.
 * Publishing only APPENDS levels, so `contentVersion` stays fixed: a player half-way through level 1 keeps their saved progress.
 */
class PublishNode(
    private val jobs: JobRepository,
    private val packages: PackageRepository,
    private val workerId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : PipelineNode<PublishInput, Boolean> {
    override val id = "publish"
    private val log = LoggerFactory.getLogger(PublishNode::class.java)

    override suspend fun run(input: PublishInput): Boolean {
        var published = false
        val after = jobs.updateIfNotTerminal(input.jobId) { job ->
            if (job.claimedBy != workerId) return@updateIfNotTerminal job // lease lost: another worker owns the job now
            val existing = packages.getPackage(input.shell.packageId)?.pkg?.levels.orEmpty()
            val levels = (existing.filter { it.levelId != input.level.levelId } + input.level).sortedBy { levelNumber(it.levelId) }
            val pkg = input.shell.copy(levels = levels)
            val issues = PackageValidator.validate(pkg)
            if (issues.isNotEmpty()) {
                log.warn("job {} level {} failed final validation: {}", input.jobId, input.level.levelId, issues.first())
                return@updateIfNotTerminal job
            }
            packages.savePackage(StoredPackage(input.ownerId, input.jobId, complete = false, pkg = pkg))
            published = true
            job.copy(levelsReady = levels.size, updatedAt = clock())
        }
        if (!published && after != null && after.state.isTerminal) {
            log.info("job {} discarded level {}: job is {}", input.jobId, input.level.levelId, after.state)
        }
        return published
    }

    private fun levelNumber(levelId: String): Int = levelId.dropWhile { !it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
}
