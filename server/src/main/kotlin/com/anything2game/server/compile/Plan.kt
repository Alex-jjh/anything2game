package com.anything2game.server.compile

import com.anything2game.contract.ErrorCodes
import com.anything2game.contract.KnowledgeType
import com.anything2game.contract.Mechanic
import kotlinx.serialization.Serializable

/** The planner's output: what each level teaches and which mechanic each unit uses. Server-internal, not part of the contract. */
@Serializable
data class Plan(val levels: List<PlannedLevel>)

@Serializable
data class PlannedLevel(val levelId: String = "", val title: String, val goal: String = "", val units: List<PlannedUnit>)

@Serializable
data class PlannedUnit(
    val unitId: String = "",
    val knowledgeType: KnowledgeType,
    val mechanic: Mechanic,
    val chunkIds: List<String> = emptyList(),
    val mechanicReason: String = "",
)

/** A step could not produce valid output. [code] is a contract error code. */
class GenerationException(val code: String, message: String, val retryable: Boolean = true) : RuntimeException(message) {
    companion object {
        fun failed(message: String) = GenerationException(ErrorCodes.GENERATION_FAILED, message)
    }
}

/** One step of the pipeline. Small and separately testable; the runner composes them. */
interface PipelineNode<I, O> {
    val id: String
    suspend fun run(input: I): O
}
