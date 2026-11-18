package com.anything2game.server.compile.layered

import com.anything2game.contract.DeduceItem
import com.anything2game.contract.Item
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.OrderItem
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SwipeItem
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class Verdict { CORRECT, WRONG, AMBIGUOUS }

data class PartVerdict(val partId: String, val verdict: Verdict, val reason: String)

data class GateResult(val verdicts: List<PartVerdict>) {
    val ok: Boolean get() = verdicts.all { it.verdict == Verdict.CORRECT }

    /** One line per rejected part: what to fix, in the words of the checker (goes into the regeneration / repair prompt). */
    val problems: List<String>
        get() = verdicts.filter { it.verdict != Verdict.CORRECT }.map { "support check, part '${it.partId}' is ${it.verdict.name.lowercase()}: ${it.reason}" }
}

/**
 * The support gate (flash tier): "is the marked answer of this part right according to these passages only?". It judges the finished item
 * part by part (swipe: each card's supported flag; sort: each card's bin; deduce: the correct option is right and every other option is
 * wrong; order: the order), including the variants. Works the same for both pipelines; in layered mode the unit's claims are shown too.
 * Anything but `correct` (wrong, ambiguous, missing, unparseable) rejects the unit: precision over recall.
 */
class SupportGate(private val flash: LlmClient) {

    suspend fun check(unit: LearningUnit, chunks: Map<String, SourceChunk>, claims: List<Claim>, stats: GenerationStats, label: String): GateResult {
        val items = listOf("item" to unit.item) + unit.variants.mapIndexed { i, v -> "variant${i + 1}" to v }
        val described = items.mapNotNull { (key, item) -> describe(key, item) }
        if (described.isEmpty()) return GateResult(emptyList())

        val chunkIds = (items.flatMap { it.second.allSourceRefs.map { r -> r.chunkId } } + claims.map { it.chunkId }).toSet()
        val cited = chunks.values.filter { it.chunkId in chunkIds }
        val claimBlock = if (claims.isEmpty()) "(none)" else PromptFormat.tag(
            "unit_claims",
            claims.joinToString("\n") { buildJsonObject { put("claimId", it.claimId); put("text", it.text); put("scope", it.scope); put("quote", it.quote) }.toString() },
        )
        val prompt = PromptTemplates.render(
            "support.v1",
            mapOf(
                "items" to PromptFormat.tag("gate_items", buildJsonObject { put("items", JsonArray(described.map { it.json })) }.toString()),
                "claims" to claimBlock,
                "document" to PromptFormat.documentBlock(cited),
            ),
        )
        val wanted = described.flatMap { it.partIds }
        val raw = LlmSteps.call(flash, prompt, MAX_TOKENS, label)
        var parsed = parse(raw, wanted)
        if (parsed == null) {
            val repaired = LlmSteps.repair(flash, prompt, raw, listOf("not a JSON object of the shape {\"parts\":[{\"partId\",\"verdict\",\"reason\"}]}"), MAX_TOKENS, label)
            parsed = parse(repaired, wanted)
        }
        val verdicts = parsed ?: wanted.map { PartVerdict(it, Verdict.AMBIGUOUS, "the checker returned no usable answer") }
        stats.gateUnitsChecked.incrementAndGet()
        verdicts.forEach {
            when (it.verdict) {
                Verdict.CORRECT -> stats.gatePartsCorrect.incrementAndGet()
                Verdict.WRONG -> stats.gatePartsWrong.incrementAndGet()
                Verdict.AMBIGUOUS -> stats.gatePartsAmbiguous.incrementAndGet()
            }
        }
        return GateResult(verdicts)
    }

    private class Described(val json: JsonObject, val partIds: List<String>)

    private fun describe(key: String, item: Item): Described? {
        fun part(id: String, text: String, marked: String) = buildJsonObject { put("partId", "$key.$id"); put("text", text); put("marked", marked) }
        val context: JsonObject
        val parts: List<JsonObject>
        when (item) {
            is SwipeItem -> {
                context = buildJsonObject { put("task", "Decide for each statement whether the passages support it.") }
                parts = item.cards.map { part(it.id, it.statement, if (it.supported) "SUPPORTED by the passages" else "NOT supported by the passages") }
            }
            is SortItem -> {
                val bins = item.bins.associate { it.id to it.label }
                context = buildJsonObject {
                    put("task", item.prompt)
                    put("bins", JsonArray(item.bins.map { buildJsonObject { put("id", it.id); put("label", it.label) } }))
                }
                parts = item.cards.map { part(it.id, it.text, "belongs in the bin \"${bins[it.binId] ?: it.binId}\"") }
            }
            is DeduceItem -> {
                context = buildJsonObject { put("scenario", item.scenario); put("question", item.prompt) }
                parts = item.options.map { part(it.id, it.text, if (it.correct) "the CORRECT answer" else "a WRONG answer") }
            }
            is OrderItem -> {
                context = buildJsonObject { put("task", item.prompt); put("note", "The steps are listed in the order the item marks as correct.") }
                parts = item.steps.mapIndexed { i, s -> part(s.id, s.text, "step ${i + 1} of ${item.steps.size} in the correct order") }
            }
            else -> return null // cross-examination is a frame boss, not gated here
        }
        val json = buildJsonObject {
            put("itemKey", key); put("mechanic", item.mechanic.name.lowercase()); put("context", context)
            put("parts", JsonArray(parts))
        }
        return Described(json, parts.map { it["partId"]!!.jsonPrimitive.content })
    }

    /** Null when the answer has no `parts` array. Parts the model skipped, or gave an unknown verdict, are `ambiguous`. */
    internal fun parse(raw: String, wanted: List<String>): List<PartVerdict>? {
        val array = try {
            LayeredJson.json.parseToJsonElement(raw).jsonObject["parts"]?.jsonArray ?: return null
        } catch (e: Exception) {
            return null
        }
        val byId = array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["partId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val verdict = when (o["verdict"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
                "correct" -> Verdict.CORRECT
                "wrong" -> Verdict.WRONG
                else -> Verdict.AMBIGUOUS
            }
            id to PartVerdict(id, verdict, o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400))
        }.toMap()
        return wanted.map { byId[it] ?: PartVerdict(it, Verdict.AMBIGUOUS, "the checker gave no verdict for this part") }
    }

    companion object { const val MAX_TOKENS = 3_000 }
}
