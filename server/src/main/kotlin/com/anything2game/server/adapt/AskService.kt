package com.anything2game.server.adapt

import com.anything2game.contract.AskResponse
import com.anything2game.contract.ContractJson
import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SourceChunk
import com.anything2game.contract.SourceRef
import com.anything2game.server.compile.GenerationException
import com.anything2game.server.compile.LlmSteps
import com.anything2game.server.llm.JsonText
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/**
 * "Ask" (flash tier): retrieves the top 4 chunks of this package's document by keyword overlap, lets the flash model answer
 * from them only, and validates what comes back. When the material does not answer, the response is `grounded=false` and carries
 * the nearest chunk, so the client can still show where to look.
 */
class AskService(private val llm: LlmClient, private val timeoutMillis: Long) {
    private val random = SecureRandom()

    @Serializable
    private data class ModelAnswer(val grounded: Boolean = false, val answer: String = "", val sourceRefs: List<SourceRef> = emptyList())

    /** @throws GenerationException with MODEL_TIMEOUT when the flash model does not answer in time. */
    suspend fun ask(pkg: LearningPackage, unitId: String, question: String): AskResponse {
        val requestId = "req_" + ByteArray(9).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val hits = Retrieval.top(question, pkg.sources, 4)
        val unitRefs = unitContext(pkg, unitId)
        val nearest = hits.firstOrNull()?.chunk ?: unitRefs.firstNotNullOfOrNull { pkg.chunk(it.chunkId) } ?: pkg.sources.first()
        val notAnswered = notGrounded(pkg, nearest, requestId)
        // Nothing in the material shares a word with the question: no need to spend a model call.
        if (hits.isEmpty()) return notAnswered

        val prompt = PromptTemplates.render(
            "ask.v1",
            mapOf(
                "language" to pkg.language,
                "unit" to PromptFormat.tag("unit", unitSummary(pkg, unitId)),
                "document" to PromptFormat.documentBlock(hits.map { it.chunk }),
                "question" to PromptFormat.tag("question", question),
            ),
        )
        val raw = try {
            withTimeout(timeoutMillis) { LlmSteps.call(llm, prompt, 800, "ask") }
        } catch (e: TimeoutCancellationException) {
            throw GenerationException(ErrorCodes.MODEL_TIMEOUT, "The answer took too long. Try again.", retryable = true)
        }
        val answer = try {
            ContractJson.json.decodeFromString(ModelAnswer.serializer(), JsonText.extractObject(raw))
        } catch (e: Exception) {
            throw GenerationException.failed("The model's answer could not be read.")
        }
        // Only citations that really exist (chunk of this document, exact quote) survive.
        val chunks = hits.map { it.chunk.chunkId to it.chunk }.toMap()
        val valid = answer.sourceRefs.filter { ref ->
            val bad = mutableListOf<String>()
            PackageValidator.checkRefs("ask", listOf(ref), chunks) { _, m -> bad += m }
            bad.isEmpty()
        }
        if (!answer.grounded || valid.isEmpty() || answer.answer.isBlank()) return notAnswered
        return AskResponse(answer = answer.answer.trim().take(1500), sourceRefs = valid, grounded = true, requestId = requestId)
    }

    private fun notGrounded(pkg: LearningPackage, nearest: SourceChunk, requestId: String): AskResponse {
        val quote = Sentences.split(nearest.text).firstOrNull()?.take(240) ?: nearest.text.take(240)
        val message = if (pkg.language == "zh") "材料里没有回答这个问题。最接近的内容在这一段。" else "The material does not answer this. The closest passage is shown below."
        return AskResponse(answer = message, sourceRefs = listOf(SourceRef(nearest.chunkId, quote)), grounded = false, requestId = requestId)
    }

    private fun unitContext(pkg: LearningPackage, unitId: String): List<SourceRef> =
        pkg.levels.flatMap { it.units }.firstOrNull { it.unitId == unitId }?.item?.allSourceRefs.orEmpty()

    private fun unitSummary(pkg: LearningPackage, unitId: String): String {
        val unit = pkg.levels.flatMap { it.units }.firstOrNull { it.unitId == unitId } ?: return "(none)"
        return unit.item.allSourceRefs.joinToString("\n") { "- ${it.quote}" }.ifBlank { "(none)" }
    }
}
