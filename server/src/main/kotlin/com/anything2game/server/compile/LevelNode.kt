package com.anything2game.server.compile

import com.anything2game.contract.CompileOptions
import com.anything2game.contract.ContractJson
import com.anything2game.contract.Level
import com.anything2game.contract.SourceChunk
import com.anything2game.server.llm.LlmClient
import com.anything2game.server.llm.PromptFormat
import com.anything2game.server.llm.PromptTemplates
import com.anything2game.server.llm.RenderedPrompt
import kotlinx.serialization.json.jsonObject

data class LevelInput(val planned: PlannedLevel, val chunks: List<SourceChunk>, val options: CompileOptions)

/**
 * What the model returned for one level. [level] is null when the text did not parse; [parseIssues] says why.
 * [prompt] is kept so the one repair call can reuse the original instructions.
 */
data class LevelDraft(
    val planned: PlannedLevel,
    val raw: String,
    val level: Level?,
    val parseIssues: List<String>,
    val prompt: RenderedPrompt,
)

/** One planned level in, a contract [Level] draft out (not yet validated). */
class LevelNode(private val llm: LlmClient) : PipelineNode<LevelInput, LevelDraft> {
    override val id = "level"

    override suspend fun run(input: LevelInput): LevelDraft {
        val wanted = input.planned.units.flatMap { it.chunkIds }.toSet()
        val chunks = input.chunks.filter { it.chunkId in wanted }.ifEmpty { input.chunks }
        val prompt = PromptTemplates.render(
            "level.v1",
            mapOf(
                "language" to input.options.language,
                "minutes" to input.options.minutesPerLevel.toString(),
                "plan_level" to PromptFormat.defuse(ContractJson.json.encodeToString(PlannedLevel.serializer(), input.planned)).let {
                    "<plan_level>\n$it\n</plan_level>"
                },
                "document" to PromptFormat.documentBlock(chunks),
            ),
        )
        val raw = LlmSteps.call(llm, prompt, MAX_TOKENS, "level:${input.planned.levelId}")
        val (level, issues) = parse(raw, input.planned)
        return LevelDraft(input.planned, raw, level, issues, prompt)
    }

    companion object {
        // Raised from 8000 after the 2026-11-18 eval: every real level (3-4 units, variants, per-option feedback) was cut off.
        const val MAX_TOKENS = 24000

        /** The plan is authoritative for ids, knowledge type and mechanic; the model supplies the content. */
        fun parse(raw: String, planned: PlannedLevel): Pair<Level?, List<String>> {
            val decoded = try {
                ContractJson.json.decodeFromString(Level.serializer(), raw)
            } catch (e: Exception) {
                return null to listOf("the level is not valid JSON of the expected shape: ${e.message?.take(300)}")
            }
            val units = planned.units.mapIndexedNotNull { i, pu ->
                val unit = decoded.units.firstOrNull { it.unitId == pu.unitId } ?: decoded.units.getOrNull(i) ?: return@mapIndexedNotNull null
                unit.copy(
                    unitId = pu.unitId, knowledgeType = pu.knowledgeType, mechanic = pu.mechanic,
                    mechanicReason = unit.mechanicReason.ifBlank { pu.mechanicReason },
                )
            }
            val level = decoded.copy(
                levelId = planned.levelId,
                title = decoded.title.ifBlank { planned.title },
                goal = decoded.goal.ifBlank { planned.goal },
                units = units,
            )
            return level to emptyList()
        }
    }
}
