package com.anything2game.server.compile.layered

import com.anything2game.server.compile.GenerationException

import com.anything2game.contract.SourceChunk
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.store.StoredDocument
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * L1: the document's chunks in batches (about [MAX_CHUNKS] chunks or [MAX_CHARS] characters, never splitting a chunk), one model call per
 * batch, all batches in parallel (bounded). Output: claims with ids `k1..kn` in document order. Nothing is checked here; see [ClaimChecker].
 */
class ClaimNode(private val llm: LlmClient, private val parallelism: Int = 6) {

    suspend fun run(doc: StoredDocument, stats: GenerationStats): List<Claim> {
        val batches = batches(doc.chunks)
        stats.claimBatches.set(batches.size)
        val permits = Semaphore(parallelism)
        val parsed: List<List<RawClaim>> = coroutineScope {
            batches.mapIndexed { i, batch ->
                async { permits.withPermit { extract(batch, "claims:b${i + 1}", stats) } }
            }.awaitAll()
        }
        var n = 0
        val out = mutableListOf<Claim>()
        for (raw in parsed) {
            val ids = raw.indices.map { "k${n + it + 1}" }
            raw.forEachIndexed { i, r ->
                out += Claim(
                    claimId = ids[i], chunkId = r.chunkId.trim(), quote = r.quote.trim(), text = r.text.trim(), type = r.type, scope = r.scope.trim(),
                    numbers = r.numbers.map { it.trim() }.filter { it.isNotEmpty() }, importance = r.importance.coerceIn(1, 3),
                    relations = r.relations.mapNotNull { rel ->
                        ids.getOrNull(rel.to)?.takeIf { it != ids[i] }?.let { ClaimRelation(it, rel.kind) }
                    },
                )
            }
            n += raw.size
        }
        stats.claimsExtracted.set(out.size)
        return out
    }

    /**
     * A batch whose answer fails (e.g. cut off at max tokens) is split in half and retried, so one dense batch never
     * fails the whole job (2026-11-18 eval: 6 truncated batches failed 3 of 6 documents). A single chunk that still
     * fails yields nothing and is counted in batchesFailed.
     */
    private suspend fun extract(batch: List<SourceChunk>, label: String, stats: GenerationStats): List<RawClaim> = try {
        extractOnce(batch, label, stats)
    } catch (e: GenerationException) {
        if (batch.size > 1) {
            val half = batch.size / 2
            extract(batch.subList(0, half), "$label.a", stats) + extract(batch.subList(half, batch.size), "$label.b", stats)
        } else {
            stats.claimBatchesFailed.incrementAndGet()
            emptyList()
        }
    }

    private suspend fun extractOnce(batch: List<SourceChunk>, label: String, stats: GenerationStats): List<RawClaim> {
        val prompt = PromptTemplates.render("claims.v1", mapOf("document" to PromptFormat.documentBlock(batch)))
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, label)
        parse(raw)?.let { return it }
        val repaired = LlmSteps.repair(llm, prompt, raw, listOf("not a JSON object of the shape {\"claims\":[...]}"), MAX_TOKENS, label)
        return parse(repaired) ?: run {
            stats.claimBatchesFailed.incrementAndGet() // this batch yields nothing; the job only fails if too few claims remain overall
            emptyList()
        }
    }

    /** Null when the answer is not `{"claims":[...]}`. A single malformed claim is skipped, not fatal. */
    internal fun parse(raw: String): List<RawClaim>? {
        val array = try {
            LayeredJson.json.parseToJsonElement(raw).jsonObject["claims"] as? JsonArray ?: return null
        } catch (e: Exception) {
            return null
        }
        return array.mapNotNull { el ->
            try { LayeredJson.json.decodeFromJsonElement(RawClaim.serializer(), el) } catch (e: Exception) { null }
        }
    }

    companion object {
        const val MAX_CHUNKS = 6
        const val MAX_CHARS = 7_000
        const val MAX_TOKENS = 12_000

        fun batches(chunks: List<SourceChunk>): List<List<SourceChunk>> {
            val out = mutableListOf<MutableList<SourceChunk>>()
            var chars = 0
            for (c in chunks) {
                val current = out.lastOrNull()
                if (current == null || current.size >= MAX_CHUNKS || chars + c.text.length > MAX_CHARS) {
                    out += mutableListOf(c)
                    chars = c.text.length
                } else {
                    current += c
                    chars += c.text.length
                }
            }
            return out
        }
    }
}
