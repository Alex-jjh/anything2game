package com.anything2game.server.compile.layered

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Level
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.Recap
import com.anything2game.contract.SourceChunk
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.store.StoredDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/** What [LayeredPipeline.prepare] hands to [LayeredPipeline.generate]: the surviving claims and the plan over them. */
data class PreparedLayered(val claims: List<Claim>, val plan: LayeredPlan)

data class LayeredLevelResult(val published: Boolean, val error: GenerationException?)

/**
 * The experimental layered pipeline (`A2G_PIPELINE=layered`): claims (L1, checked) -> plan over claims (L2) -> one call per unit (L3,
 * checked, optionally support-gated), all units of all levels in parallel (bounded by [parallelism]), a level published as soon as its own
 * units are done (level 1's units are started first). The output is the ordinary contract [Level]; claims never leave the job.
 */
class LayeredPipeline(
    compile: LlmClient,
    flash: LlmClient,
    private val gate: SupportGate?,
    private val parallelism: Int = 6,
) {
    private val log = LoggerFactory.getLogger(LayeredPipeline::class.java)
    private val llm = compile
    private val claimNode = ClaimNode(compile, parallelism)
    private val claimChecker = ClaimChecker(flash, parallelism)
    private val planNode = LayeredPlanNode(compile)
    private val unitNode = UnitNode(compile)

    /** L1 + L2: extract, check, plan. Runs while the job is in state `planning`. */
    suspend fun prepare(doc: StoredDocument, options: CompileOptions, stats: GenerationStats): PreparedLayered {
        val t0 = System.currentTimeMillis()
        val extracted = claimNode.run(doc, stats)
        val checked = claimChecker.run(extracted, doc.chunks, stats)
        stats.claimsMillis.set(System.currentTimeMillis() - t0)
        log.info(
            "job {} claims: extracted={} kept={} (dropped quote={} number={} scope={} entailment={}, conflicts={} pairs)",
            stats.jobId, extracted.size, checked.kept.size, stats.claimsDroppedQuote.get(), stats.claimsDroppedNumber.get(),
            stats.claimsDroppedScope.get(), stats.claimsDroppedEntailment.get(), stats.conflictPairs.get(),
        )
        val t1 = System.currentTimeMillis()
        val plan = planNode.run(LayeredPlanInput(checked.kept, options), stats)
        stats.planMillis.set(System.currentTimeMillis() - t1)
        return PreparedLayered(checked.kept, plan)
    }

    /**
     * L3: every unit of every level in parallel. A level is published (through [publish]) the moment its units are done, so level 1,
     * whose units are queued first, appears first. A unit that cannot be made is dropped; a level with fewer than
     * [MIN_UNITS] surviving units fails, like in the classic pipeline.
     */
    suspend fun generate(
        prepared: PreparedLayered,
        doc: StoredDocument,
        options: CompileOptions,
        shell: LearningPackage,
        stats: GenerationStats,
        publish: suspend (Level) -> Boolean,
    ): List<LayeredLevelResult> = coroutineScope {
        val permits = Semaphore(parallelism)
        val claimsById = prepared.claims.associateBy { it.claimId }
        val chunkMap = doc.chunks.associateBy { it.chunkId }
        // UNDISPATCHED: level 1's unit coroutines reach the (FIFO) permit queue before level 2's exist.
        prepared.plan.levels.map { planned ->
            async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val results = planned.units.map { u ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            permits.withPermit { buildUnit(u, claimsById, doc.chunks, chunkMap, options, stats) }
                        }
                    }.awaitAll()
                    val units = results.mapNotNull { it.unit }
                    if (units.size < MIN_UNITS) {
                        val err = results.firstNotNullOfOrNull { it.error }
                        throw err ?: GenerationException.failed("Level ${planned.levelId} has only ${units.size} valid unit(s); at least $MIN_UNITS are needed.")
                    }
                    val level = Level(
                        levelId = planned.levelId, title = planned.title, goal = planned.goal, units = units,
                        recap = Recap(recap(planned, claimsById)),
                    )
                    val issues = PackageValidator.validate(shell.copy(levels = listOf(level)))
                    if (issues.isNotEmpty()) throw GenerationException.failed("Level ${planned.levelId} is invalid: ${issues.first()}")
                    val published = publish(level)
                    stats.levelDone(planned.levelId)
                    LayeredLevelResult(published, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GenerationException) {
                    stats.levelDone(planned.levelId)
                    log.warn("job {} level {} failed: {}", stats.jobId, planned.levelId, e.code)
                    LayeredLevelResult(false, e)
                }
            }
        }.awaitAll()
    }

    private data class UnitResult(val unit: LearningUnit?, val error: GenerationException?)

    private suspend fun buildUnit(
        u: LayeredUnit,
        claimsById: Map<String, Claim>,
        chunks: List<SourceChunk>,
        chunkMap: Map<String, SourceChunk>,
        options: CompileOptions,
        stats: GenerationStats,
    ): UnitResult {
        val claims = u.claimIds.mapNotNull { claimsById[it] }
        try {
            var parsed = produce(u, claims, chunks, chunkMap, options, emptyList(), "unit:${u.unitId}", stats)
            if (parsed == null) { stats.unitsDropped.incrementAndGet(); return UnitResult(null, null) }
            stats.unitsGenerated.incrementAndGet()
            if (gate != null) {
                val first = gate.check(parsed.unit, chunkMap, claims, stats, "support:${u.unitId}")
                if (!first.ok) {
                    stats.gateUnitsRejected.incrementAndGet()
                    stats.gateRegenerations.incrementAndGet()
                    val again = produce(u, claims, chunks, chunkMap, options, first.problems, "unit:${u.unitId}:gate", stats)
                    val second = again?.let { gate.check(it.unit, chunkMap, claims, stats, "support:${u.unitId}:regate") }
                    if (again == null || second == null || !second.ok) {
                        stats.gateUnitsDropped.incrementAndGet(); stats.unitsDropped.incrementAndGet()
                        log.info("job {} unit {} dropped by the support gate", stats.jobId, u.unitId)
                        return UnitResult(null, null)
                    }
                    parsed = again
                }
            }
            stats.unitsKept.incrementAndGet()
            return UnitResult(parsed.unit, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationException) {
            stats.unitsDropped.incrementAndGet()
            log.warn("job {} unit {} failed: {}", stats.jobId, u.unitId, e.code)
            return UnitResult(null, e)
        }
    }

    /** One writer call, the deterministic unit checks, and at most ONE repair call with the issue list. Null = the unit is dropped. */
    private suspend fun produce(
        u: LayeredUnit,
        claims: List<Claim>,
        chunks: List<SourceChunk>,
        chunkMap: Map<String, SourceChunk>,
        options: CompileOptions,
        problems: List<String>,
        label: String,
        stats: GenerationStats,
    ): ParsedUnit? {
        val draft = unitNode.run(UnitInput(u, claims, chunks, options, problems, label))
        val issues = draft.parseIssues + (draft.parsed?.let { UnitChecks.check(it, claims, chunkMap) }.orEmpty())
        if (issues.isEmpty()) return draft.parsed
        stats.unitsRepaired.incrementAndGet()
        val raw = LlmSteps.repair(llm, draft.prompt, draft.raw, issues, UnitNode.MAX_TOKENS, label)
        val (second, parseIssues) = UnitNode.parse(raw, u)
        if (second == null || parseIssues.isNotEmpty()) return null
        return if (UnitChecks.check(second, claims, chunkMap).isEmpty()) second else null
    }

    /** The takeaways of a level are its most important claims, restated as the claim layer wrote them (no extra model call, no new facts). */
    private fun recap(level: LayeredLevel, claimsById: Map<String, Claim>): List<String> =
        level.units.flatMap { it.claimIds }.distinct().mapNotNull { claimsById[it] }
            .sortedByDescending { it.importance }.map { it.text }.distinct().take(3)

    companion object { const val MIN_UNITS = 2 }
}
