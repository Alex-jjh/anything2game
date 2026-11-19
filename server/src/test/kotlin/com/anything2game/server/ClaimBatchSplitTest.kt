package com.anything2game.server

import com.anything2game.contract.DocumentKind
import com.anything2game.contract.SourceChunk
import com.anything2game.server.compile.layered.ClaimNode
import com.anything2game.server.compile.layered.GenerationStats
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.LlmException
import com.anything2game.server.llm.LlmRequest
import com.anything2game.server.store.StoredDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression for the 2026-11-18 eval: one truncated claim batch used to fail the whole job. */
class ClaimBatchSplitTest {
    private val chunks = (1..4).map { SourceChunk("c$it", it - 1, "$it", "Sentence number $it says something specific about topic $it.") }
    private val doc = StoredDocument("d", "install:x", "t", DocumentKind.TEXT, "sha256:x", 4, 0, chunks)

    /** Answers like a real model for one-chunk batches; "cuts off" (max tokens) whenever a batch holds several chunks or chunk c3. */
    private val picky = object : LlmClient {
        override val modelId = "picky"
        override suspend fun completeJson(request: LlmRequest): String {
            val ids = Regex("""c\d+""").findAll(request.user).map { it.value }.toSet().filter { id -> chunks.any { it.chunkId == id } }
            if (ids.size > 1 || "c3" in ids) throw LlmException(LlmException.Kind.BAD_RESPONSE, "cut off", retryable = true)
            val c = chunks.first { it.chunkId == ids.single() }
            return """{"claims":[{"chunkId":"${c.chunkId}","quote":"${c.text}","text":"${c.text}","type":"other","scope":"","numbers":[],"importance":2}]}"""
        }
    }

    @Test fun `a truncated batch is split and retried, only the chunk that still fails is lost`() = runBlocking {
        val stats = GenerationStats("j", "layered", gate = false)
        val claims = ClaimNode(picky).run(doc, stats)
        assertEquals(listOf("c1", "c2", "c4"), claims.map { it.chunkId }.sorted())
        assertEquals(1, stats.claimBatchesFailed.get())
    }
}

/** Regression for the 2026-11-18 eval: one empty entailment answer used to fail the whole job. */
class ClaimCheckFailureTest {
    private val chunk = SourceChunk("c1", 0, "1", "Alpha is defined as beta.")
    private fun claim(id: String) = com.anything2game.server.compile.layered.Claim(
        claimId = id, chunkId = "c1", quote = "Alpha is defined as beta.", text = "Alpha is defined as beta.",
        type = com.anything2game.server.compile.layered.ClaimType.DEFINITION, scope = "", numbers = emptyList(), importance = 2, relations = emptyList(),
    )

    @Test fun `a failing check batch drops its claims but does not throw`() = runBlocking {
        var calls = 0
        val broken = object : LlmClient {
            override val modelId = "broken"
            override suspend fun completeJson(request: LlmRequest): String { calls++; throw LlmException(LlmException.Kind.BAD_RESPONSE, "Empty answer.") }
        }
        val stats = GenerationStats("j", "layered", gate = false)
        val checked = com.anything2game.server.compile.layered.ClaimChecker(broken).run(listOf(claim("k1"), claim("k2")), listOf(chunk), stats)
        assertEquals(0, checked.kept.size)
        assertEquals(2, calls) // first try + one retry
        assertEquals(2, stats.claimsDroppedEntailment.get())
    }
}

/** Experiment knobs A2G_LEVEL_PROMPT and A2G_FRAME_INPUT (2026-11-19). */
class ExperimentKnobsTest {
    @Test fun `knobs default to shipped behaviour and refuse unknown values`() {
        val d = com.anything2game.server.config.ServerConfig.fromEnv(emptyMap())
        assertEquals("level.v1", d.levelPrompt); assertEquals("document", d.frameInput)
        val c = com.anything2game.server.config.ServerConfig.fromEnv(mapOf("A2G_LEVEL_PROMPT" to "level.v2", "A2G_FRAME_INPUT" to "cited"))
        assertEquals("level.v2", c.levelPrompt); assertEquals("cited", c.frameInput)
        val bad = runCatching { com.anything2game.server.config.ServerConfig.fromEnv(mapOf("A2G_FRAME_INPUT" to "all")) }
        assertEquals(true, bad.exceptionOrNull()?.message?.contains("A2G_FRAME_INPUT"))
    }

    @Test fun `cited frame input sends only the chunks the units cite`() = runBlocking {
        val pkg = com.anything2game.contract.ContractJson.decodePackage(java.io.File(System.getProperty("contract.examples") ?: "contract/examples", "package.v1.example.json").readText())
        val seen = mutableListOf<String>()
        val spy = object : LlmClient {
            override val modelId = "spy"
            override suspend fun completeJson(request: LlmRequest): String { seen += request.user; throw LlmException(LlmException.Kind.BAD_RESPONSE, "stop") }
        }
        // add an uncited chunk; it must not reach the frame writer
        val withExtra = pkg.copy(sources = pkg.sources + SourceChunk("c99", 3, "4", "UNCITED-MARKER text that no unit cites."))
        runCatching { com.anything2game.server.compile.CaseFrameNode(spy, citedOnly = true).generate(withExtra) }
        assertEquals(false, seen.first().contains("UNCITED-MARKER"))
        seen.clear()
        runCatching { com.anything2game.server.compile.CaseFrameNode(spy).generate(withExtra) }
        assertEquals(true, seen.first().contains("UNCITED-MARKER"))
    }
}
