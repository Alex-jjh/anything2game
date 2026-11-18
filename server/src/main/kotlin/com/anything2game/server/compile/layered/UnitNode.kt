package com.anything2game.server.compile.layered

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Mechanic
import com.anything2game.contract.SourceChunk
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.llm.RenderedPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [problems] are the reasons of an earlier attempt (the support gate's `wrong`/`ambiguous` verdicts); empty on the first attempt.
 * [label] names the call for logs (`unit:L1U2`, `unit:L1U2:gate` for the regeneration).
 */
data class UnitInput(
    val planned: LayeredUnit,
    val claims: List<Claim>,
    val chunks: List<SourceChunk>,
    val options: CompileOptions,
    val problems: List<String> = emptyList(),
    val label: String = "unit:${planned.unitId}",
)

/** A contract unit plus the mutations its writer declared (read from the raw JSON; they are not part of the contract). */
data class ParsedUnit(val unit: LearningUnit, val mutations: List<Mutation>)

data class UnitDraft(val parsed: ParsedUnit?, val raw: String, val parseIssues: List<String>, val prompt: RenderedPrompt, val label: String)

/**
 * L3: ONE call per unit. The unit's claims (text, scope, quote) and their chunks' full text go in; a contract [LearningUnit] comes out.
 * Correct answers come from the claims; every wrong part is a declared mutation of one claim (`"mutations":[{partId,claimId,kind}]`).
 */
class UnitNode(private val llm: LlmClient) {

    suspend fun run(input: UnitInput): UnitDraft {
        val claimIds = input.claims.map { it.chunkId }.toSet()
        val chunks = input.chunks.filter { it.chunkId in claimIds }
        val plan = buildJsonObject {
            put("unitId", input.planned.unitId)
            put("knowledgeType", ContractJson.json.encodeToJsonElement(KnowledgeType.serializer(), input.planned.knowledgeType))
            put("mechanic", ContractJson.json.encodeToJsonElement(Mechanic.serializer(), input.planned.mechanic))
            put("mechanicReason", input.planned.mechanicReason)
        }.toString()
        val claims = input.claims.joinToString("\n") { c ->
            buildJsonObject {
                put("claimId", c.claimId); put("chunkId", c.chunkId); put("type", c.type.name.lowercase())
                put("text", c.text); put("scope", c.scope); put("quote", c.quote)
            }.toString()
        }
        val prompt = PromptTemplates.render(
            "unit.v1",
            mapOf(
                "language" to input.options.language,
                "unit_plan" to PromptFormat.tag("unit_plan", plan),
                "unit_claims" to PromptFormat.tag("unit_claims", claims),
                "problems" to if (input.problems.isEmpty()) "(none: this is the first attempt)"
                else PromptFormat.tag("previous_problems", input.problems.take(30).joinToString("\n") { "- $it" }),
                "document" to PromptFormat.documentBlock(chunks),
            ),
        )
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, input.label)
        val (parsed, issues) = parse(raw, input.planned)
        return UnitDraft(parsed, raw, issues, prompt, input.label)
    }

    companion object {
        const val MAX_TOKENS = 12_000

        /** The plan is authoritative for the unit id, knowledge type and mechanic; the model supplies item, hints, variants and mutations. */
        fun parse(raw: String, planned: LayeredUnit): Pair<ParsedUnit?, List<String>> {
            val root = try {
                LayeredJson.json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                return null to listOf("the unit is not a valid JSON object: ${e.message?.take(300)}")
            }
            val obj = (root["unit"] as? JsonObject) ?: root
            val shaped = JsonObject(
                obj + mapOf(
                    "unitId" to JsonPrimitive(planned.unitId),
                    "knowledgeType" to ContractJson.json.encodeToJsonElement(KnowledgeType.serializer(), planned.knowledgeType),
                    "mechanic" to ContractJson.json.encodeToJsonElement(Mechanic.serializer(), planned.mechanic),
                    "mechanicReason" to JsonPrimitive((obj["mechanicReason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: planned.mechanicReason),
                ),
            )
            val unit = try {
                ContractJson.json.decodeFromJsonElement(LearningUnit.serializer(), shaped)
            } catch (e: Exception) {
                return null to listOf("the unit is not valid JSON of the expected shape: ${e.message?.take(300)}")
            }
            val mutations = (obj["mutations"] as? JsonArray).orEmpty().mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (m[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                Mutation(s("partId"), s("claimId"), s("kind"))
            }
            return ParsedUnit(unit, mutations) to emptyList()
        }
    }
}
