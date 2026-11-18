package com.anything2game.server.llm

import com.anything2game.contract.ContractJson
import com.anything2game.contract.DeduceItem
import com.anything2game.contract.DeduceOption
import com.anything2game.contract.Item
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.LearningUnit
import com.anything2game.contract.Mechanic
import com.anything2game.contract.OrderItem
import com.anything2game.contract.OrderStep
import com.anything2game.contract.SortBin
import com.anything2game.contract.SortCard
import com.anything2game.contract.SortItem
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import com.anything2game.contract.SwipeCard
import com.anything2game.contract.SwipeItem
import com.anything2game.server.adapt.Sentences
import com.anything2game.server.compile.layered.ClaimType
import com.anything2game.server.compile.layered.LayeredJson
import com.anything2game.server.compile.layered.LayeredLevel
import com.anything2game.server.compile.layered.LayeredPlan
import com.anything2game.server.compile.layered.LayeredUnit
import com.anything2game.server.compile.layered.Numbers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The offline model's answers for the layered-generation steps (claims, claim-check, plan-claims, unit, support). Like the classic fake,
 * everything is derived from what is in the prompt, so the answers always pass the checks: quotes are exact sentences of the chunks
 * (Chinese text is split on 。！？ too), claims restate their quote, mutated parts carry a declared mutation, and the checkers approve.
 * Tests that need a failing step rewrite these answers (see `RecordingLlm`).
 */
internal object FakeLayered {
    private val json = ContractJson.json

    private fun lang(prompt: String): String = PromptFormat.tagContent(prompt, "language")?.trim() ?: "en"
    private fun t(lang: String, en: String, zh: String) = if (lang == "zh") zh else en
    private fun bad(message: String): Nothing = throw LlmException(LlmException.Kind.BAD_RESPONSE, "fake model: $message")

    private fun lines(prompt: String, tag: String): List<JsonObject> =
        (PromptFormat.tagContent(prompt, tag) ?: bad("no <$tag> in the prompt")).lines().filter { it.isNotBlank() }
            .map { LayeredJson.json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    // ---- claims: one claim per sentence --------------------------------------------------------------------------

    private val sequenceMarker = Regex("(?i)\\b(first|then|next|finally|afterwards)\\b|首先|然后|接着|最后")
    private val logisticsMarker = Regex("(?i)\\b(deadline|due date|grading|office hours)\\b|截止|评分|考勤")

    fun claims(prompt: String): String {
        val chunks = PromptFormat.parseChunks(prompt)
        if (chunks.isEmpty()) bad("no chunks in the prompt")
        val zh = chunks.any { c -> c.text.any { it in '一'..'鿿' } }
        val claims = chunks.flatMap { chunk ->
            val sentences = Sentences.split(chunk.text).map { it.take(240) }
            sentences.mapIndexed { i, s ->
                val numbers = Numbers.tokens(s)
                val type = when {
                    logisticsMarker.containsMatchIn(s) -> ClaimType.LOGISTICS
                    sequenceMarker.containsMatchIn(s) -> ClaimType.SEQUENCE
                    numbers.isNotEmpty() -> ClaimType.NUMBER
                    else -> ClaimType.OTHER
                }
                buildJsonObject {
                    put("chunkId", chunk.chunkId); put("quote", s); put("text", s)
                    put("type", type.name.lowercase())
                    put("scope", if (type.needsScope || numbers.isNotEmpty()) t(if (zh) "zh" else "en", "as stated in this passage", "在这段材料中") else "")
                    put("numbers", buildJsonArray { numbers.forEach { add(JsonPrimitive(it)) } })
                    put("importance", if (i == 0) 3 else 2)
                    if (i > 0) put("relations", buildJsonArray { add(buildJsonObject { put("to", -1); put("kind", "supports") }) })
                }
            }
        }
        // relations point at the previous claim of the same chunk: rewrite "to" as a real index in this batch
        var offset = 0
        val fixed = chunks.flatMap { chunk ->
            val n = Sentences.split(chunk.text).size
            val slice = claims.subList(offset, offset + n)
            val base = offset
            offset += n
            slice.mapIndexed { i, c ->
                if (i == 0) c else JsonObject(c + mapOf("relations" to buildJsonArray { add(buildJsonObject { put("to", base + i - 1); put("kind", "supports") }) }))
            }
        }
        return buildJsonObject { put("claims", JsonArray(fixed)) }.toString()
    }

    fun claimCheck(prompt: String): String {
        val ids = lines(prompt, "claims_to_check").map { it.str("id") }
        return buildJsonObject {
            put("results", buildJsonArray { ids.forEach { add(buildJsonObject { put("id", it); put("verdict", "supported"); put("reason", "The quote says this.") }) } })
        }.toString()
    }

    // ---- plan over claims -----------------------------------------------------------------------------------------

    fun planClaims(prompt: String): String {
        val lang = lang(prompt)
        val claims = lines(prompt, "claim_set")
        if (claims.isEmpty()) bad("no claims in the prompt")
        val levelCount = ((claims.size + 5) / 6).coerceIn(1, 4)
        val groups = claims.chunked((claims.size + levelCount - 1) / levelCount)
        val levels = groups.mapIndexed { li, group ->
            val ids = group.map { it.str("id") }
            val sequence = group.filter { it.str("type") == "sequence" }.map { it.str("id") }
            val mechanics = buildList {
                add(Mechanic.SWIPE); add(Mechanic.SORT); add(Mechanic.DEDUCE)
                if (sequence.isNotEmpty() && group.size >= 3) add(Mechanic.ORDER)
            }
            val units = mechanics.mapIndexed { ui, mechanic ->
                val claimIds = when (mechanic) {
                    Mechanic.ORDER -> (sequence + ids.filter { it !in sequence }).take(4).sortedBy { ids.indexOf(it) }
                    else -> listOf(ids[ui % ids.size], ids[(ui + 1) % ids.size]).distinct()
                }
                LayeredUnit(
                    unitId = "L${li + 1}U${ui + 1}",
                    knowledgeType = when (mechanic) {
                        Mechanic.SWIPE -> KnowledgeType.CLAIM
                        Mechanic.SORT -> KnowledgeType.CATEGORY
                        Mechanic.DEDUCE -> KnowledgeType.REASONING
                        else -> KnowledgeType.PROCESS
                    },
                    mechanic = mechanic, claimIds = claimIds,
                    mechanicReason = t(lang, "Fits this knowledge point.", "适合这个知识点。"),
                )
            }
            val head = group.first().str("text").take(if (lang == "zh") 14 else 48).trim()
            LayeredLevel(title = "${li + 1}. $head", goal = t(lang, "Tell what the material says from what it does not.", "分清材料说了什么、没说什么。"), units = units)
        }
        return json.encodeToString(LayeredPlan.serializer(), LayeredPlan(levels))
    }

    // ---- unit ------------------------------------------------------------------------------------------------------

    private data class C(val claimId: String, val chunkId: String, val text: String, val quote: String)

    fun unit(prompt: String): String {
        val lang = lang(prompt)
        val plan = LayeredJson.json.parseToJsonElement(PromptFormat.tagContent(prompt, "unit_plan") ?: bad("no <unit_plan>")).jsonObject
        val claims = lines(prompt, "unit_claims").map { C(it.str("claimId"), it.str("chunkId"), it.str("text"), it.str("quote")) }
        if (claims.isEmpty()) bad("no claims in the prompt")
        val mechanic = json.decodeFromJsonElement(Mechanic.serializer(), JsonPrimitive(plan.str("mechanic")))
        val mutations = mutableListOf<JsonObject>()
        fun mutate(partId: String, c: C, kind: String) { mutations += buildJsonObject { put("partId", partId); put("claimId", c.claimId); put("kind", kind) } }
        val item = item(mechanic, claims, lang, rot = 0, v = "", ::mutate)
        val variant = item(mechanic, claims, lang, rot = 1, v = "v", ::mutate)
        val unit = LearningUnit(
            unitId = plan.str("unitId"), knowledgeType = json.decodeFromJsonElement(KnowledgeType.serializer(), JsonPrimitive(plan.str("knowledgeType"))),
            mechanic = mechanic, mechanicReason = plan.str("mechanicReason"), item = item,
            hints = listOf(t(lang, "Re-read the cited passage.", "回到引用的那段原文。"), t(lang, "Look for the exact words in the passage.", "找找原文里的确切用词。")),
            variants = listOf(variant),
        )
        val base = json.encodeToJsonElement(LearningUnit.serializer(), unit).jsonObject
        return JsonObject(base + mapOf("mutations" to JsonArray(mutations))).toString()
    }

    private fun item(mechanic: Mechanic, claims: List<C>, lang: String, rot: Int, v: String, mutate: (String, C, String) -> Unit): Item {
        val n = claims.size
        fun c(i: Int) = claims[(i + rot) % n]
        fun ref(c: C) = SourceRef(c.chunkId, c.quote)
        val allRefs = claims.distinctBy { it.quote }.map { ref(it) }
        val stretched = t(lang, "Always, without exception: ", "无一例外地：")
        return when (mechanic) {
            Mechanic.SWIPE -> {
                mutate("${v}s2", c(1), "overgeneralise_scope")
                SwipeItem(
                    listOf(
                        SwipeCard("${v}s1", c(0).text, true, t(lang, "The passage says this directly.", "材料原文直接说了这一点。"), listOf(ref(c(0)))),
                        SwipeCard("${v}s2", stretched + c(1).text, false,
                            t(lang, "This stretches the claim beyond the conditions it was stated for; the passage says less.", "这把说法扩大到了原文所述条件之外；原文说得更窄。"), listOf(ref(c(1)))),
                        SwipeCard("${v}s3", c(2).text, true, t(lang, "The passage states this.", "材料明确写了这一点。"), listOf(ref(c(2)))),
                    ),
                )
            }
            Mechanic.SORT -> {
                mutate("${v}k3", c(2), "overgeneralise_scope")
                SortItem(
                    prompt = t(lang, "File each statement.", "把每条陈述归类。"),
                    bins = listOf(SortBin("says", t(lang, "The material says", "材料里有")), SortBin("not", t(lang, "The material does not say", "材料里没有"))),
                    cards = listOf(SortCard("${v}k1", c(0).text, "says"), SortCard("${v}k2", c(1).text, "says"), SortCard("${v}k3", stretched + c(2).text, "not")),
                    why = t(lang, "Only what the passage states belongs in the first bin.", "只有原文写到的内容才属于第一个分类。"),
                    sourceRefs = listOf(ref(c(0))),
                )
            }
            Mechanic.DEDUCE -> {
                mutate("${v}o2", c(1), "overgeneralise_scope"); mutate("${v}o3", c(2), "drop_limitation")
                DeduceItem(
                    scenario = c(0).text,
                    prompt = t(lang, "Which conclusion does the material support?", "根据材料，哪个结论成立？"),
                    options = listOf(
                        DeduceOption("${v}o1", c(0).text, true, t(lang, "This is what the passage states.", "这正是原文所述。")),
                        DeduceOption("${v}o2", stretched + c(1).text, false, t(lang, "This stretches the claim beyond its scope; the passage states less.", "这把说法扩大到了原文范围之外。")),
                        DeduceOption("${v}o3", t(lang, "Without any condition: ", "不附带任何条件：") + c(2).text, false,
                            t(lang, "This leaves out the limitation that comes with the claim.", "这去掉了说法原本附带的限制。")),
                    ),
                    sourceRefs = listOf(ref(c(0))),
                )
            }
            Mechanic.ORDER -> {
                val steps = claims.distinctBy { it.text }
                OrderItem(
                    prompt = t(lang, "Put these in the order the material gives them.", "按材料中的顺序排列。"),
                    steps = steps.mapIndexed { i, s -> OrderStep("${v}p${i + 1}", s.text) },
                    why = t(lang, "That is the order of the passage.", "这就是原文的顺序。"),
                    sourceRefs = allRefs,
                )
            }
            Mechanic.CROSS_EXAM -> error("not a unit mechanic")
        }
    }

    // ---- support gate ------------------------------------------------------------------------------------------------

    fun support(prompt: String): String {
        val body = PromptFormat.tagContent(prompt, "gate_items") ?: bad("no <gate_items>")
        val ids = LayeredJson.json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
            .flatMap { it.jsonObject["parts"]!!.jsonArray.map { p -> p.jsonObject.str("partId") } }
        return buildJsonObject {
            put("parts", buildJsonArray { ids.forEach { add(buildJsonObject { put("partId", it); put("verdict", "correct"); put("reason", "The passages say so.") }) } })
        }.toString()
    }
}
