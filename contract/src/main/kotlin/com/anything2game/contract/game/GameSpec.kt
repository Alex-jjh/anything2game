package com.anything2game.contract.game

import com.anything2game.contract.LearningPackage
import com.anything2game.contract.PackageValidator
import com.anything2game.contract.SourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

const val GAME_SCHEMA = "a2g.game/v1"

@Serializable
data class ContentVersion(val contentId: String, val contentVersion: String) {
    fun validate() {
        requireContentId(contentId)
        requireContentId(contentVersion)
    }
}

fun requireContentId(id: String) {
    require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) { "Invalid content identifier" }
}

@Serializable
sealed interface EvidenceRef {
    val evidenceId: String

    @Serializable @SerialName("pdf")
    data class Pdf(override val evidenceId: String, val documentId: String, val documentVersion: String,
        val chunkId: String, val quote: String, val pageIndex: Int, val pageLabel: String? = null) : EvidenceRef

    @Serializable @SerialName("reviewed-card")
    data class Reviewed(override val evidenceId: String, val cardId: String, val cardVersion: String,
        val sourceTitle: String, val sourceUrl: String, val section: String, val fact: String,
        val reviewNote: String) : EvidenceRef

    @Serializable @SerialName("external")
    data class External(override val evidenceId: String, val title: String, val url: String, val digestSha256: String,
        val sectionOrExcerpt: String) : EvidenceRef

    fun validate() {
        requireContentId(evidenceId)
        when (this) {
            is Pdf -> {
                ContentVersion(documentId, documentVersion).validate()
                requireContentId(chunkId)
                require(pageIndex >= 0 && quote.isNotBlank() && quote.length <= 3000)
            }
            is Reviewed -> {
                ContentVersion(cardId, cardVersion).validate()
                require(listOf(sourceTitle, section, fact, reviewNote).all { it.isNotBlank() })
                requireWebUrl(sourceUrl)
            }
            is External -> {
                require(title.isNotBlank() && sectionOrExcerpt.isNotBlank() && digestSha256.matches(Regex("[a-f0-9]{64}")))
                requireWebUrl(url)
            }
        }
    }
}

private fun requireWebUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Expected HTTPS source URL" }
}

/** Resolve the reference against the specific immutable package before it leaves its v1 scope. */
fun LearningPackage.scopeEvidence(ref: SourceRef, evidenceId: String): EvidenceRef.Pdf {
    val chunk = requireNotNull(chunk(ref.chunkId))
    require(ref.quote.isNotBlank() && PackageValidator.normalise(ref.quote) in PackageValidator.normalise(chunk.text))
    return EvidenceRef.Pdf(evidenceId, documentId, documentVersion, chunk.chunkId, ref.quote, chunk.pageIndex, chunk.pageLabel)
}

@Serializable
sealed interface StageConfig { val mechanicId: String }

@Serializable @SerialName("partition")
data class PartitionConfig(val candidates: Int, val queryBudget: Int) : StageConfig {
    override val mechanicId get() = MechanicRegistry.PARTITION
}

@Serializable @SerialName("allocation")
data class AllocationConfig(val tokens: Int, val firstYield: Int, val secondYield: Int, val firstGoal: Int,
    val secondGoal: Int) : StageConfig {
    override val mechanicId get() = MechanicRegistry.ALLOCATION
}

@Serializable
data class GameStage(val stageId: String, val goal: String, val config: StageConfig, val transfer: Boolean,
    val evidenceIds: List<String>, val rulesVersion: Int = MechanicRegistry.VERSION) {
    val mechanicId get() = config.mechanicId
}

@Serializable
data class GameSpec(val gameId: String, val gameVersion: String, val title: String, val language: String,
    val conceptRefs: List<String>, val evidenceRefs: List<EvidenceRef>, val teachingSetting: String,
    val stages: List<GameStage>, val requiredCapabilities: Set<String>, val schema: String = GAME_SCHEMA)

/** These descriptors are also the source for generation prompts and capability negotiation. */
object MechanicRegistry {
    const val PARTITION = "information-partition"
    const val ALLOCATION = "resource-allocation"
    const val VERSION = 1
    const val MAX_ACTIONS = 512
    val candidates = 2..32
    val queries = 1..8
    val tokens = 2..12
    val yields = 1..12
    val goals = 1..(tokens.last * yields.last)
    val stageCount = 2..8
    fun capability(id: String) = "$id/v$VERSION"
    val capabilities = setOf(capability(PARTITION), capability(ALLOCATION))

    fun describe(): String = """
        partition: $PARTITION v$VERSION, candidates=$candidates, queryBudget=$queries; split after an ordered candidate.
        allocation: $ALLOCATION v$VERSION, tokens=$tokens, firstYield/secondYield=$yields, firstGoal/secondGoal=$goals; place, undo, submit.
        Stages=$stageCount. End with a changed-condition transfer. Required capabilities: ${capabilities.joinToString()}.
    """.trimIndent()

    fun validate(spec: GameSpec) {
        ContentVersion(spec.gameId, spec.gameVersion).validate()
        require(spec.schema == GAME_SCHEMA && spec.title.isNotBlank() && spec.language.isNotBlank())
        require(spec.teachingSetting.isNotBlank() && spec.conceptRefs.isNotEmpty() && spec.conceptRefs.none(String::isBlank))
        require(spec.stages.size in stageCount && spec.stages.distinctBy { it.stageId }.size == spec.stages.size)
        require(spec.evidenceRefs.isNotEmpty() && spec.evidenceRefs.distinctBy { it.evidenceId }.size == spec.evidenceRefs.size)
        spec.evidenceRefs.forEach { it.validate() }
        val evidenceIds = spec.evidenceRefs.map { it.evidenceId }.toSet()
        require(spec.requiredCapabilities == spec.stages.map { capability(it.mechanicId) }.toSet()) { "Unsupported or missing capability" }
        require(!spec.stages.first().transfer && spec.stages.last().transfer) { "Missing practice/transfer stage" }
        spec.stages.forEachIndexed { i, stage ->
            requireContentId(stage.stageId)
            require(stage.rulesVersion == VERSION) { "Unsupported rules version" }
            require(stage.goal.isNotBlank() && stage.evidenceIds.isNotEmpty() && evidenceIds.containsAll(stage.evidenceIds))
            if (stage.transfer) {
                val practice = spec.stages.take(i).filter { !it.transfer && it.mechanicId == stage.mechanicId }
                require(practice.isNotEmpty() && practice.none { it.config == stage.config }) { "Transfer must change conditions" }
            }
            when (val c = stage.config) {
                is PartitionConfig -> require(c.candidates in candidates && c.queryBudget in queries)
                is AllocationConfig -> require(c.tokens in tokens && c.firstYield in yields && c.secondYield in yields &&
                    c.firstGoal in goals && c.secondGoal in goals)
            }
        }
    }
}

@Serializable enum class Zone { FIRST, SECOND }

@Serializable
sealed interface GameAction {
    @Serializable @SerialName("split") data class Split(val after: Int) : GameAction
    @Serializable @SerialName("place") data class Place(val zone: Zone) : GameAction
    @Serializable @SerialName("undo") data object Undo : GameAction
    @Serializable @SerialName("reset") data object Reset : GameAction
    @Serializable @SerialName("submit") data object Submit : GameAction
    @Serializable @SerialName("advance") data object Advance : GameAction
    @Serializable @SerialName("help") data object Help : GameAction
}

@Serializable
data class GameCheckpoint(val gameId: String, val gameVersion: String, val rulesVersions: List<Int>,
    val seed: Long, val actions: List<GameAction>)
