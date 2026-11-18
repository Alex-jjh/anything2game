package com.anything2game.server.compile.layered

import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SourceChunk
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Numbers and text similarity shared by the deterministic checks of claims and units. Pure functions, no model. */
object Numbers {
    private val token = Regex("""\d+(?:[.,]\d+)*""")

    /** Full-width digits, decimal point and percent sign become their ASCII forms; other Unicode decimal digits too. */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            val d = if (c.code > 127) Character.digit(c, 10) else -1
            append(
                when {
                    d >= 0 -> '0' + d
                    c == '．' -> '.'
                    c == '％' -> '%'
                    else -> c
                },
            )
        }
    }

    /** Every integer / decimal in [text] (a `%` sign is ignored, thousands separators are removed): "41.8", "1,057 s", "50 %" -> 41.8, 1057, 50. */
    fun tokens(text: String): List<String> = token.findAll(fold(text)).map { canon(it.value) }.toList()

    private fun canon(t: String): String = t.replace(",", "")

    fun set(text: String): Set<String> = tokens(text).toSet()

    /** [text] with its numbers cut out (for comparing what a sentence is *about*). */
    fun withoutNumbers(text: String): String = token.replace(fold(text), " ")
}

object ClaimChecks {
    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )

    enum class Drop { QUOTE, NUMBER, SCOPE }

    /** The deterministic checks of one claim; null = it passes. */
    fun deterministic(claim: Claim, chunks: Map<String, SourceChunk>): Drop? {
        val chunk = chunks[claim.chunkId]
        if (chunk == null || claim.quote.isBlank() || PackageValidator.normalise(claim.quote) !in PackageValidator.normalise(chunk.text)) return Drop.QUOTE
        if (claim.text.isBlank()) return Drop.QUOTE
        val inQuote = Numbers.set(claim.quote)
        if (Numbers.tokens(claim.text).any { it !in inQuote }) return Drop.NUMBER
        if (claim.type.needsScope && claim.scope.isBlank()) return Drop.SCOPE
        return null
    }

    /** Words (any script) plus character bigrams of CJK runs, over the text with its numbers removed, lower-cased. */
    internal fun similarityTokens(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val word = StringBuilder()
        val run = StringBuilder()
        fun flushWord() { if (word.isNotEmpty()) out += word.toString().lowercase(); word.clear() }
        fun flushRun() {
            if (run.length == 1) out += run.toString()
            for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
            run.clear()
        }
        for (c in Numbers.withoutNumbers(text)) {
            when {
                isCjk(c) -> { flushWord(); run.append(c) }
                c.isLetterOrDigit() -> { flushRun(); word.append(c) }
                else -> { flushWord(); flushRun() }
            }
        }
        flushWord(); flushRun()
        return out
    }

    internal fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    const val CONFLICT_SIMILARITY = 0.6

    /**
     * Pairs of `number` claims that say nearly the same thing (token Jaccard >= 0.6 once the digits are removed; character bigrams for
     * Chinese) but with different numbers: the document disagrees with itself (41.8 vs 41.0 BLEU), or the extractor mixed two sources.
     * Neither claim may be turned into a question.
     */
    fun conflicts(claims: List<Claim>): List<Pair<Claim, Claim>> {
        val numberClaims = claims.filter { it.type == ClaimType.NUMBER }
        val sims = numberClaims.associate { it.claimId to similarityTokens(it.text) }
        val nums = numberClaims.associate { it.claimId to Numbers.set(it.text) }
        val out = mutableListOf<Pair<Claim, Claim>>()
        for (i in numberClaims.indices) for (j in i + 1 until numberClaims.size) {
            val a = numberClaims[i]; val b = numberClaims[j]
            val na = nums.getValue(a.claimId); val nb = nums.getValue(b.claimId)
            if (na.isEmpty() || nb.isEmpty() || na == nb) continue
            if (jaccard(sims.getValue(a.claimId), sims.getValue(b.claimId)) >= CONFLICT_SIMILARITY) out += a to b
        }
        return out
    }
}

data class CheckedClaims(val kept: List<Claim>)

/**
 * L1 checks: deterministic (quote in chunk, numbers in quote, scope), then flash-tier entailment of the survivors in batches of
 * [BATCH], then the deterministic conflict scan. Precision over recall: only `supported` survives. Every drop is counted in [GenerationStats].
 */
class ClaimChecker(private val flash: LlmClient, private val parallelism: Int = 6) {
    private val log = LoggerFactory.getLogger(ClaimChecker::class.java)

    suspend fun run(claims: List<Claim>, chunks: List<SourceChunk>, stats: GenerationStats): CheckedClaims {
        val byId = chunks.associateBy { it.chunkId }
        val passed = mutableListOf<Claim>()
        for (c in claims) {
            when (ClaimChecks.deterministic(c, byId)) {
                ClaimChecks.Drop.QUOTE -> stats.claimsDroppedQuote.incrementAndGet()
                ClaimChecks.Drop.NUMBER -> stats.claimsDroppedNumber.incrementAndGet()
                ClaimChecks.Drop.SCOPE -> stats.claimsDroppedScope.incrementAndGet()
                null -> passed += c
            }
        }
        val supported = entail(passed, stats)
        stats.claimsDroppedEntailment.addAndGet(passed.size - supported.size)

        val pairs = ClaimChecks.conflicts(supported)
        val conflicting = pairs.flatMap { listOf(it.first.claimId, it.second.claimId) }.toSet()
        stats.conflictPairs.addAndGet(pairs.size)
        stats.conflictClaims.addAndGet(conflicting.size)
        pairs.forEach { stats.conflictPairIds += "${it.first.claimId}~${it.second.claimId}" }
        if (pairs.isNotEmpty()) log.info("job {} claim conflicts (ids only): {}", stats.jobId, pairs.joinToString { "${it.first.claimId}~${it.second.claimId}" })
        val kept = supported.filter { it.claimId !in conflicting }
        stats.claimsKept.addAndGet(kept.size)
        return CheckedClaims(kept)
    }

    private suspend fun entail(claims: List<Claim>, stats: GenerationStats): List<Claim> {
        if (claims.isEmpty()) return emptyList()
        val permits = Semaphore(parallelism)
        val verdicts: List<Set<String>> = coroutineScope {
            claims.chunked(BATCH).mapIndexed { i, batch ->
                async { permits.withPermit { supportedIds(batch, "claimcheck:b${i + 1}") } }
            }.awaitAll()
        }
        val ok = verdicts.flatten().toSet()
        return claims.filter { it.claimId in ok }
    }

    /** The ids the flash model calls `supported`. Unparseable answers get one repair call, then count as nothing supported. */
    private suspend fun supportedIds(batch: List<Claim>, label: String): Set<String> {
        val payload = batch.joinToString("\n") { c ->
            buildJsonObject { put("id", c.claimId); put("text", c.text); put("scope", c.scope); put("quote", c.quote) }.toString()
        }
        val prompt = PromptTemplates.render("claim-check.v1", mapOf("claims" to PromptFormat.tag("claims_to_check", payload)))
        val raw = LlmSteps.call(flash, prompt, MAX_TOKENS, label)
        parse(raw)?.let { return it }
        val repaired = LlmSteps.repair(flash, prompt, raw, listOf("not a JSON object of the shape {\"results\":[{\"id\",\"verdict\",\"reason\"}]}"), MAX_TOKENS, label)
        return parse(repaired) ?: emptySet()
    }

    internal fun parse(raw: String): Set<String>? = try {
        val results = LayeredJson.json.parseToJsonElement(raw).jsonObject["results"]?.jsonArray ?: return null
        results.mapNotNull { r ->
            val o = r.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull
            if (id != null && o["verdict"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() == "supported") id else null
        }.toSet()
    } catch (e: Exception) {
        null
    }

    companion object {
        const val BATCH = 15
        const val MAX_TOKENS = 4000
    }
}
