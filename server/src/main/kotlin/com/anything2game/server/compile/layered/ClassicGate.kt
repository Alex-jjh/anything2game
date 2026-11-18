package com.anything2game.server.compile.layered

import com.anything2game.contract.ContractJson
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Level
import com.anything2game.contract.PackageValidator
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.compile.LevelDraft
import com.anything2game.server.compile.LevelNode
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.compile.ValidatedLevel
import com.anything2game.server.compile.ValidateNode
import com.anything2game.server.llm.LlmClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The support gate on the classic pipeline (arm A+G): after the level is validated, every unit goes through the gate (in parallel). If
 * any unit has a `wrong` or `ambiguous` part, the level's repair path is used ONCE with the gate's reasons as the issue list; the units
 * that were rejected are replaced by their new versions, re-gated once, and dropped if still rejected. Units that passed are never touched.
 */
class ClassicGate(private val compile: LlmClient, private val gate: SupportGate, private val parallelism: Int = 6) {

    suspend fun run(draft: LevelDraft, valid: ValidatedLevel, shell: LearningPackage, stats: GenerationStats): ValidatedLevel {
        val chunks = shell.sources.associateBy { it.chunkId }
        val level = valid.level
        val permits = Semaphore(parallelism)

        suspend fun gateAll(units: List<LearningUnit>, label: String): List<GateResult> = coroutineScope {
            units.map { u -> async { permits.withPermit { gate.check(u, chunks, emptyList(), stats, "support:${u.unitId}$label") } } }.awaitAll()
        }

        val first = gateAll(level.units, "")
        val rejected = level.units.indices.filter { !first[it].ok }
        if (rejected.isEmpty()) return valid
        stats.gateUnitsRejected.addAndGet(rejected.size)
        stats.gateRegenerations.addAndGet(rejected.size)

        val issues = rejected.flatMap { i -> first[i].problems.map { "unit ${level.units[i].unitId}: $it" } }
        val raw = LlmSteps.repair(compile, draft.prompt, ContractJson.json.encodeToString(Level.serializer(), level), issues, LevelNode.MAX_TOKENS, "level:${level.levelId}:gate")
        val rewritten = LevelNode.parse(raw, draft.planned).first
        val candidates = rejected.associateWith { i ->
            rewritten?.units?.firstOrNull { it.unitId == level.units[i].unitId }?.takeIf { structurallyValid(it, chunks) }
        }
        val usable = candidates.filterValues { it != null }.mapValues { it.value!! }
        val second = gateAll(usable.values.toList(), ":regate")
        val accepted = usable.keys.toList().withIndex().filter { second[it.index].ok }.associate { it.value to usable.getValue(it.value) }

        val dropped = rejected.filter { it !in accepted }
        stats.gateUnitsDropped.addAndGet(dropped.size)
        val units = level.units.mapIndexedNotNull { i, u -> if (i in rejected) accepted[i] else u }
        if (units.size < ValidateNode.MIN_UNITS) {
            throw GenerationException.failed("Level ${level.levelId} has only ${units.size} unit(s) left after the support gate; at least ${ValidateNode.MIN_UNITS} are needed.")
        }
        return valid.copy(
            level = level.copy(units = units), repaired = true,
            droppedUnits = valid.droppedUnits + dropped.map { level.units[it].unitId },
        )
    }

    private fun structurallyValid(unit: LearningUnit, chunks: Map<String, com.anything2game.contract.SourceChunk>): Boolean {
        var ok = true
        PackageValidator.checkUnit("unit", unit, chunks) { _, _ -> ok = false }
        return ok
    }
}
