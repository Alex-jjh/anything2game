package com.anything2game.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Schema id of the current package format. Breaking changes get a new id. */
const val PACKAGE_SCHEMA_V1 = "a2g.package/v1"

/** A compiled, immutable learning package for one document version. */
@Serializable
data class LearningPackage(
    val schema: String,
    val packageId: String,
    val contentVersion: String,
    val documentId: String,
    val documentVersion: String,
    val title: String,
    val language: String,
    val generation: Generation,
    val sources: List<SourceChunk>,
    val levels: List<Level>,
) {
    fun chunk(chunkId: String): SourceChunk? = sources.firstOrNull { it.chunkId == chunkId }
    fun level(levelId: String): Level? = levels.firstOrNull { it.levelId == levelId }
}

@Serializable
data class Generation(
    val mode: GenerationMode,
    val model: String,
    val promptVersion: String,
    val review: ReviewStatus,
)

@Serializable
enum class GenerationMode {
    /** Prepared ahead of time (sample or cached). Must be labelled as such in the UI. */
    @SerialName("pre-generated") PRE_GENERATED,
    /** Compiled from the user's own material by the server. */
    @SerialName("live") LIVE,
}

@Serializable
enum class ReviewStatus {
    @SerialName("human-approved") HUMAN_APPROVED,
    @SerialName("auto-validated") AUTO_VALIDATED,
    @SerialName("flagged") FLAGGED,
}

/** A passage of the source document. Citations point here. */
@Serializable
data class SourceChunk(
    val chunkId: String,
    /** Physical page in the source, 0-based. */
    val pageIndex: Int,
    /** Printed page number, when known. */
    val pageLabel: String? = null,
    val text: String,
) {
    /** The page as a reader names it: the printed number when known, else the physical page counted from 1. */
    fun displayPage(): String = pageLabel ?: (pageIndex + 1).toString()
}

/** A citation: the quote must appear verbatim (whitespace-normalised) in the chunk. */
@Serializable
data class SourceRef(val chunkId: String, val quote: String)

@Serializable
data class Level(
    val levelId: String,
    val title: String,
    /** What the player can do after the level, phrased for the player. */
    val goal: String,
    val units: List<LearningUnit>,
    val recap: Recap,
)

@Serializable
data class Recap(val points: List<String>)

/** One knowledge point, played through one mechanic. */
@Serializable
data class LearningUnit(
    val unitId: String,
    val knowledgeType: KnowledgeType,
    val mechanic: Mechanic,
    /** Why the compiler chose this mechanic. For evals, not shown to players. */
    val mechanicReason: String = "",
    val item: Item,
    val hints: List<String> = emptyList(),
    /** Alternative items for the same knowledge point, used after a miss. Same mechanic as [item]. */
    val variants: List<Item> = emptyList(),
)

@Serializable
enum class KnowledgeType {
    @SerialName("claim") CLAIM,
    @SerialName("category") CATEGORY,
    @SerialName("process") PROCESS,
    @SerialName("reasoning") REASONING,
    @SerialName("pair") PAIR,
}

/** Closed list of mechanics. Adding one = DTO here + renderer in :app + prompt section in server. */
@Serializable
enum class Mechanic {
    @SerialName("swipe") SWIPE,
    @SerialName("sort") SORT,
    @SerialName("deduce") DEDUCE,
    @SerialName("order") ORDER,
    /** Find the one statement the material does not support, and present the passage that shows it. */
    @SerialName("crossexam") CROSS_EXAM,
}

/** Mechanic-specific content. JSON discriminator: `type`. */
@Serializable
sealed interface Item {
    val mechanic: Mechanic
    /** Every citation in the item, for validation and the evidence view. */
    val allSourceRefs: List<SourceRef>
}

/** Swipe: decide for each statement whether the material supports it. */
@Serializable
@SerialName("swipe")
data class SwipeItem(val cards: List<SwipeCard>) : Item {
    override val mechanic get() = Mechanic.SWIPE
    override val allSourceRefs get() = cards.flatMap { it.sourceRefs }
}

@Serializable
data class SwipeCard(
    val id: String,
    val statement: String,
    val supported: Boolean,
    val why: String,
    val sourceRefs: List<SourceRef>,
)

/** Sort: file each card into one bin. */
@Serializable
@SerialName("sort")
data class SortItem(
    val prompt: String,
    val bins: List<SortBin>,
    val cards: List<SortCard>,
    val why: String,
    val sourceRefs: List<SourceRef>,
) : Item {
    override val mechanic get() = Mechanic.SORT
    override val allSourceRefs get() = sourceRefs
}

@Serializable
data class SortBin(val id: String, val label: String)

@Serializable
data class SortCard(val id: String, val text: String, val binId: String)

/** Deduce: given a scenario, pick the conclusion the material supports. Exactly one option is correct. */
@Serializable
@SerialName("deduce")
data class DeduceItem(
    val scenario: String,
    val prompt: String,
    val options: List<DeduceOption>,
    val sourceRefs: List<SourceRef>,
) : Item {
    override val mechanic get() = Mechanic.DEDUCE
    override val allSourceRefs get() = sourceRefs
}

@Serializable
data class DeduceOption(val id: String, val text: String, val correct: Boolean, val feedback: String)

/** Order: rebuild a sequence. [steps] are listed in the correct order; the client shuffles. */
@Serializable
@SerialName("order")
data class OrderItem(
    val prompt: String,
    val steps: List<OrderStep>,
    val why: String,
    val sourceRefs: List<SourceRef>,
) : Item {
    override val mechanic get() = Mechanic.ORDER
    override val allSourceRefs get() = sourceRefs
}

@Serializable
data class OrderStep(val id: String, val text: String)

/**
 * Cross-examination: a character makes statements; exactly one goes beyond the evidence.
 * The player picks it and presents a passage (chunk) that shows why.
 */
@Serializable
@SerialName("crossexam")
data class CrossExamItem(
    /** Cast id of the character speaking (from the frame overlay), e.g. "summary" or "rookie". */
    val speaker: String,
    val prompt: String,
    val statements: List<Statement>,
) : Item {
    override val mechanic get() = Mechanic.CROSS_EXAM
    override val allSourceRefs get() = statements.flatMap { it.sourceRefs }
}

@Serializable
data class Statement(
    val id: String,
    val text: String,
    /** True for the one statement the material does not support. */
    val flawed: Boolean,
    /** Why it holds, or why it doesn't. Shown after the answer. */
    val why: String,
    /** For the flawed statement: the passages that disprove it. Presenting any of these chunks counts. */
    val sourceRefs: List<SourceRef>,
)
