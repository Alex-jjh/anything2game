package com.anything2game.server.compile.layered

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.Mechanic
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The L2 plan: like the classic [com.anything2game.server.compile.Plan], but a unit names claims instead of chunks. Server-internal. */
@Serializable
data class LayeredPlan(val levels: List<LayeredLevel>)

@Serializable
data class LayeredLevel(val levelId: String = "", val title: String, val goal: String = "", val units: List<LayeredUnit>)

@Serializable
data class LayeredUnit(
    val unitId: String = "",
    val knowledgeType: KnowledgeType,
    val mechanic: Mechanic,
    val claimIds: List<String> = emptyList(),
    val mechanicReason: String = "",
)

data class LayeredPlanInput(val claims: List<Claim>, val options: CompileOptions)

/**
 * L2: picks and orders claims into levels and units. The model sees the surviving claims only, never the document. Rules enforced in code
 * (and stated in the prompt): every claim id exists; logistics claims are not planned unless the material is mostly logistics (a syllabus);
 * an `order` unit needs a `sequence` claim, otherwise it becomes a `deduce` unit; 1-4 levels, 3-4 units each, at least 2 mechanics per level.
 */
class LayeredPlanNode(private val llm: LlmClient) {

    suspend fun run(input: LayeredPlanInput, stats: GenerationStats): LayeredPlan {
        val usable = usableClaims(input.claims)
        if (usable.size < MIN_CLAIMS) throw GenerationException.failed("Only ${usable.size} usable claim(s) were found in the material; at least $MIN_CLAIMS are needed.")
        val payload = usable.joinToString("\n") { c ->
            buildJsonObject {
                put("id", c.claimId); put("type", c.type.name.lowercase()); put("importance", c.importance); put("scope", c.scope); put("text", c.text)
            }.toString()
        }
        val prompt = PromptTemplates.render(
            "plan-claims.v1",
            mapOf(
                "language" to input.options.language,
                "minutes" to input.options.minutesPerLevel.toString(),
                "claims" to PromptFormat.tag("claim_set", payload),
            ),
        )
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, "planclaims")
        var switched = java.util.concurrent.atomic.AtomicInteger()
        var (plan, issues) = parse(raw, usable, switched)
        if (issues.isNotEmpty()) {
            val repaired = LlmSteps.repair(llm, prompt, raw, issues, MAX_TOKENS, "planclaims")
            switched = java.util.concurrent.atomic.AtomicInteger()
            val second = parse(repaired, usable, switched)
            plan = second.first
            issues = second.second
        }
        stats.orderSwitched.set(switched.get())
        if (plan == null || issues.isNotEmpty()) throw GenerationException.failed("The plan was invalid: " + issues.take(3).joinToString("; "))
        stats.unitsPlanned.set(plan.levels.sumOf { it.units.size })
        return plan
    }

    /** Logistics (admin, grading, deadlines) is dropped from planning unless it is at least 60 % of the claims. */
    internal fun usableClaims(claims: List<Claim>): List<Claim> {
        if (claims.isEmpty()) return claims
        val logistics = claims.count { it.type == ClaimType.LOGISTICS }
        return if (logistics.toDouble() / claims.size >= LOGISTICS_SHARE) claims else claims.filter { it.type != ClaimType.LOGISTICS }
    }

    /** Parses and normalises the plan (ids, caps, the `order` rule) and returns the problems that remain. */
    internal fun parse(raw: String, usable: List<Claim>, switchedCount: java.util.concurrent.atomic.AtomicInteger? = null): Pair<LayeredPlan?, List<String>> {
        val parsed = try {
            ContractJson.json.decodeFromString(LayeredPlan.serializer(), raw)
        } catch (e: Exception) {
            return null to listOf("the plan is not valid JSON of the expected shape: ${e.message?.take(200)}")
        }
        val byId = usable.associateBy { it.claimId }
        val issues = mutableListOf<String>()
        var switched = 0
        val levels = parsed.levels.take(4).mapIndexed { li, level ->
            val units = level.units.take(4).mapIndexed { ui, u ->
                val ids = u.claimIds.filter { it in byId }.distinct().take(4)
                val unit = u.copy(unitId = "L${li + 1}U${ui + 1}", claimIds = ids)
                if (unit.mechanic == Mechanic.ORDER && ids.none { byId.getValue(it).type == ClaimType.SEQUENCE }) {
                    switched++
                    unit.copy(mechanic = Mechanic.DEDUCE, knowledgeType = KnowledgeType.REASONING)
                } else unit
            }
            level.copy(levelId = "L${li + 1}", title = level.title.trim(), goal = level.goal.trim(), units = units)
        }
        switchedCount?.addAndGet(switched)
        if (levels.isEmpty()) issues += "levels: the plan has no levels (1 to 4 required)"
        levels.forEach { l ->
            val p = l.levelId
            if (l.title.isBlank()) issues += "$p.title: missing title"
            if (l.units.size < 3) issues += "$p.units: ${l.units.size} units, 3 to 4 required"
            if (l.units.map { it.mechanic }.toSet().size < 2) issues += "$p.units: at least 2 different mechanics are required (an order unit needs a sequence claim; otherwise use deduce)"
            l.units.forEach { u ->
                if (u.mechanic !in allowed) issues += "${u.unitId}.mechanic: '${u.mechanic}' is not allowed (swipe, sort, deduce, order)"
                if (u.claimIds.isEmpty()) issues += "${u.unitId}.claimIds: no existing claim id (use ids from the claim set; logistics claims are not available)"
            }
        }
        return LayeredPlan(levels) to issues
    }

    companion object {
        const val MAX_TOKENS = 16_000 // 75+ claims overflowed 8k (2026-11-18 eval)
        const val MIN_CLAIMS = 3
        const val LOGISTICS_SHARE = 0.6
        private val allowed = setOf(Mechanic.SWIPE, Mechanic.SORT, Mechanic.DEDUCE, Mechanic.ORDER)
    }
}
