package com.anything2game.server.compile

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.Mechanic
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.store.StoredDocument
import kotlinx.serialization.json.jsonObject

data class PlanInput(val document: StoredDocument, val options: CompileOptions)

/** Chunks in, a validated [Plan] out: 1-4 levels, 3-4 units each, at least 2 mechanics per level, real chunk ids. */
class PlanNode(private val llm: LlmClient) : PipelineNode<PlanInput, Plan> {
    override val id = "plan"
    private val allowed = setOf(Mechanic.SWIPE, Mechanic.SORT, Mechanic.DEDUCE, Mechanic.ORDER)

    override suspend fun run(input: PlanInput): Plan {
        val chunkIds = input.document.chunks.map { it.chunkId }.toSet()
        val prompt = PromptTemplates.render(
            "plan.v1",
            mapOf(
                "language" to input.options.language,
                "minutes" to input.options.minutesPerLevel.toString(),
                "document" to PromptFormat.documentBlock(input.document.chunks),
            ),
        )
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, "plan")
        var (plan, issues) = parse(raw, chunkIds)
        if (issues.isNotEmpty()) {
            val repaired = LlmSteps.repair(llm, prompt, raw, issues, MAX_TOKENS, "plan")
            val second = parse(repaired, chunkIds)
            plan = second.first
            issues = second.second
        }
        if (plan == null || issues.isNotEmpty()) throw GenerationException.failed("The plan was invalid: " + issues.take(3).joinToString("; "))
        return plan
    }

    /** Parses and normalises (ids, caps) and returns the problems that remain. */
    internal fun parse(raw: String, chunkIds: Set<String>): Pair<Plan?, List<String>> {
        val parsed = try {
            ContractJson.json.decodeFromString(Plan.serializer(), raw)
        } catch (e: Exception) {
            return null to listOf("the plan is not valid JSON of the expected shape: ${e.message?.take(200)}")
        }
        val issues = mutableListOf<String>()
        val levels = parsed.levels.take(4).mapIndexed { li, level ->
            val units = level.units.take(4).mapIndexed { ui, u ->
                u.copy(unitId = "L${li + 1}U${ui + 1}", chunkIds = u.chunkIds.filter { it in chunkIds }.distinct())
            }
            level.copy(levelId = "L${li + 1}", title = level.title.trim(), goal = level.goal.trim(), units = units)
        }
        if (levels.isEmpty()) issues += "levels: the plan has no levels (1 to 4 required)"
        levels.forEach { l ->
            val p = l.levelId
            if (l.title.isBlank()) issues += "$p.title: missing title"
            if (l.units.size < 3) issues += "$p.units: ${l.units.size} units, 3 to 4 required"
            if (l.units.map { it.mechanic }.toSet().size < 2) issues += "$p.units: at least 2 different mechanics are required"
            l.units.forEach { u ->
                if (u.mechanic !in allowed) issues += "${u.unitId}.mechanic: '${u.mechanic}' is not allowed (swipe, sort, deduce, order)"
                if (u.chunkIds.isEmpty()) issues += "${u.unitId}.chunkIds: no existing chunk id (use ids from the document)"
            }
        }
        return Plan(levels) to issues
    }

    companion object { const val MAX_TOKENS = 4000 }
}
