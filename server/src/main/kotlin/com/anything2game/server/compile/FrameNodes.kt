package com.anything2game.server.compile

import com.anything2game.contract.ContractJson
import com.anything2game.contract.FRAME_SCHEMA_V1
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.FrameValidator
import com.anything2game.contract.Generation
import com.anything2game.contract.GenerationMode
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.ReviewStatus
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.llm.RenderedPrompt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Builds the story layer (a [FrameOverlay]) around a finished package. A frame never changes an item, an answer or a citation.
 * Returns null for a frame that needs no overlay ([FrameId.CLASSIC]).
 */
interface FrameNode {
    val frameId: FrameId
    suspend fun generate(pkg: LearningPackage): FrameOverlay?
}

/**
 * Shared flow of the LLM-backed frames: render the frame's own prompt, parse, validate with [FrameValidator] against the
 * frame's [com.anything2game.contract.FrameSpec], ONE repair call on issues, then fail with GENERATION_FAILED.
 * A concrete frame is one subclass that names its prompt file.
 */
abstract class LlmFrameNode(
    final override val frameId: FrameId,
    private val promptName: String,
    private val llm: LlmClient,
) : FrameNode {

    final override suspend fun generate(pkg: LearningPackage): FrameOverlay {
        val prompt = render(pkg)
        val label = "frame:${frameId.name.lowercase()}"
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, label)
        var (overlay, issues) = parseAndValidate(raw, pkg)
        if (issues.isNotEmpty()) {
            val repaired = LlmSteps.repair(llm, prompt, raw, issues, MAX_TOKENS, label)
            val second = parseAndValidate(repaired, pkg)
            overlay = second.first
            issues = second.second
        }
        if (overlay == null || issues.isNotEmpty()) {
            throw GenerationException.failed("The ${frameId.name.lowercase()} frame was invalid: " + issues.take(3).joinToString("; "))
        }
        return overlay
    }

    private fun render(pkg: LearningPackage): RenderedPrompt {
        // The prompt's <package> block carries the structure (levels, units, items); the chunk texts are in <document>.
        val structure = ContractJson.encodePackage(pkg.copy(sources = emptyList()))
        return PromptTemplates.render(
            promptName,
            mapOf(
                "language" to pkg.language,
                "package" to PromptFormat.tag("package", structure),
                "document" to PromptFormat.documentBlock(pkg.sources),
            ),
        )
    }

    private fun parseAndValidate(raw: String, pkg: LearningPackage): Pair<FrameOverlay?, List<String>> {
        val overlay = try {
            val obj = ContractJson.json.parseToJsonElement(raw).jsonObject
            // The server owns identity and provenance; the model supplies cast, levels and board only.
            val withMeta = JsonObject(
                obj - setOf("schema", "frameId", "packageId", "contentVersion", "generation") + mapOf(
                    "schema" to JsonPrimitive(FRAME_SCHEMA_V1),
                    "frameId" to JsonPrimitive(frameId.name.lowercase()),
                    "packageId" to JsonPrimitive(pkg.packageId),
                    "contentVersion" to JsonPrimitive(pkg.contentVersion),
                    "generation" to ContractJson.json.encodeToJsonElement(Generation.serializer(), generation(promptName)),
                ),
            )
            ContractJson.json.decodeFromJsonElement(FrameOverlay.serializer(), withMeta)
        } catch (e: Exception) {
            return null to listOf("the frame is not valid JSON of the expected shape: ${e.message?.take(300)}")
        }
        return overlay to FrameValidator.validate(overlay, pkg).map { it.toString() }
    }

    private fun generation(promptName: String) = Generation(GenerationMode.LIVE, llm.modelId, promptName, ReviewStatus.AUTO_VALIDATED)

    companion object { const val MAX_TOKENS = 16000 } // raised from 8000 (2026-11-18 eval)
}

class CaseFrameNode(llm: LlmClient) : LlmFrameNode(FrameId.CASE, "frame-case.v1", llm)
class EscapeFrameNode(llm: LlmClient) : LlmFrameNode(FrameId.ESCAPE, "frame-escape.v1", llm)
class RookieFrameNode(llm: LlmClient) : LlmFrameNode(FrameId.ROOKIE, "frame-rookie.v1", llm)
class CompanionFrameNode(llm: LlmClient) : LlmFrameNode(FrameId.COMPANION, "frame-companion.v1", llm)

/** The clean path: units in order, no story. It needs no overlay, so there is nothing to generate or store. */
class ClassicFrameNode : FrameNode {
    override val frameId = FrameId.CLASSIC
    override suspend fun generate(pkg: LearningPackage): FrameOverlay? = null
}

/** FrameId -> node. Adding a frame = one node class, one prompt file and one line here. */
class FrameNodeRegistry(nodes: List<FrameNode>) {
    private val byId = nodes.associateBy { it.frameId }
    operator fun get(id: FrameId): FrameNode = byId[id] ?: error("no frame node for $id")
    val ids: Set<FrameId> get() = byId.keys

    companion object {
        fun default(llm: LlmClient) = FrameNodeRegistry(
            listOf(ClassicFrameNode(), CaseFrameNode(llm), EscapeFrameNode(llm), RookieFrameNode(llm), CompanionFrameNode(llm)),
        )
    }
}
