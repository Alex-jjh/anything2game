package com.anything2game.server.compile

import com.anything2game.contract.LearningPackage
import com.anything2game.contract.Level
import com.anything2game.contract.PackageValidator
import com.anything2game.server.store.Store
import com.anything2game.server.store.StoredPackage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

data class PublishInput(val jobId: String, val installId: String, val level: Level, val shell: LearningPackage)

/**
 * Adds a validated level to the stored package, so `GET /v1/packages/{id}` shows level 1 as soon as it exists.
 * Returns false (and changes nothing) when the job is already in a terminal state: a result that arrives after `cancelled`
 * is discarded here, under the same lock that `cancel` takes. `contentVersion` is the number of published levels.
 */
class PublishNode(private val store: Store, private val stateLock: Mutex, private val clock: () -> Long = System::currentTimeMillis) :
    PipelineNode<PublishInput, Boolean> {
    override val id = "publish"
    private val log = LoggerFactory.getLogger(PublishNode::class.java)

    override suspend fun run(input: PublishInput): Boolean = stateLock.withLock {
        val job = store.getJob(input.jobId) ?: return false
        if (job.state.isTerminal) {
            log.info("job {} discarded level {}: job is {}", input.jobId, input.level.levelId, job.state)
            return false
        }
        val existing = store.getPackage(input.shell.packageId)?.pkg?.levels.orEmpty()
        val levels = (existing.filter { it.levelId != input.level.levelId } + input.level).sortedBy { levelNumber(it.levelId) }
        val pkg = input.shell.copy(levels = levels, contentVersion = levels.size.toString())
        val issues = PackageValidator.validate(pkg)
        if (issues.isNotEmpty()) {
            log.warn("job {} level {} failed final validation: {}", input.jobId, input.level.levelId, issues.first())
            return false
        }
        store.savePackage(StoredPackage(input.installId, input.jobId, complete = false, pkg = pkg))
        store.updateJob(input.jobId) { if (it.state.isTerminal) it else it.copy(levelsReady = levels.size, updatedAt = clock()) }
        true
    }

    private fun levelNumber(levelId: String): Int = levelId.dropWhile { !it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
}
