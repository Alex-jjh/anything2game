package com.anything2game.contract.game

import com.anything2game.contract.ApiError
import com.anything2game.contract.CompileJobState
import com.anything2game.contract.space.SpaceMember
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface GenerationInput {
    @Serializable @SerialName("concept-game")
    data class ConceptGame(val topic: String, val language: String = "en") : GenerationInput
    @Serializable @SerialName("space-path")
    data class SpacePath(val spaceId: String, val expectedRevision: Long, val language: String = "en") : GenerationInput
}

@Serializable data class GenerationRequest(val input: GenerationInput, val clientRequestId: String)

@Serializable enum class GenerationStage { KNOWLEDGE, EXPERIENCE, VALIDATION, PACKAGE }
@Serializable enum class ContentKind { GAME, PATH }

@Serializable data class GeneratedContent(val content: ContentVersion, val kind: ContentKind)
@Serializable data class GenerationStatus(val jobId: String, val state: CompileJobState, val stage: GenerationStage? = null,
    val result: GeneratedContent? = null, val error: ApiError? = null, val excludedMembers: List<SpaceMember> = emptyList())
