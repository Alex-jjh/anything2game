package com.anything2game.server.compile.layered

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Server-internal types of the layered-generation experiment (docs/architecture/layered-generation.md). Claims are NOT part of the
 * contract yet: they live only inside a compile job and in the log, never in the stored package.
 */
@Serializable
enum class ClaimType {
    @SerialName("definition") DEFINITION,
    @SerialName("number") NUMBER,
    @SerialName("comparison") COMPARISON,
    @SerialName("causal") CAUSAL,
    @SerialName("sequence") SEQUENCE,
    @SerialName("limitation") LIMITATION,
    @SerialName("example") EXAMPLE,
    @SerialName("logistics") LOGISTICS,
    @SerialName("other") OTHER,
    ;

    /** These types must carry a `scope` (the qualifiers without which the claim is easily over-read). */
    val needsScope: Boolean get() = this == NUMBER || this == COMPARISON || this == CAUSAL
}

/** An edge to another claim of the same set. [kind] is one of supports | contrasts | part_of | before | after. */
@Serializable
data class ClaimRelation(val toClaimId: String, val kind: String)

/** One atomic thing THE DOCUMENT SAYS (not world truth). [claimId] is assigned by the server after parsing. */
@Serializable
data class Claim(
    val claimId: String,
    val chunkId: String,
    /** Verbatim substring of the chunk, one or two sentences. */
    val quote: String,
    /** Faithful restatement, in the language of the material. */
    val text: String,
    val type: ClaimType = ClaimType.OTHER,
    val scope: String = "",
    val numbers: List<String> = emptyList(),
    val importance: Int = 2,
    val relations: List<ClaimRelation> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
)

/** What the model writes for one claim (before ids are assigned); every field is optional so a sloppy claim is dropped, not a crash. */
@Serializable
data class RawClaim(
    val chunkId: String = "",
    val quote: String = "",
    val text: String = "",
    val type: ClaimType = ClaimType.OTHER,
    val scope: String = "",
    val numbers: List<String> = emptyList(),
    val importance: Int = 2,
    val relations: List<RawRelation> = emptyList(),
)

@Serializable
data class RawRelation(val to: Int = -1, val kind: String = "supports")

@Serializable
data class ClaimBatchAnswer(val claims: List<kotlinx.serialization.json.JsonElement> = emptyList())

/** The six typed distractor mutations (design section 3, layer L3). */
enum class MutationKind(val wire: String, val misconception: String) {
    OVERGENERALISE_SCOPE("overgeneralise_scope", "stretching the claim beyond the conditions it was stated for"),
    SWAP_NUMBER("swap_number", "mixing up the numbers"),
    REVERSE_CAUSE("reverse_cause", "turning cause and effect around"),
    WRONG_ORDER("wrong_order", "mixing up the order of the steps"),
    SWAP_ENTITY("swap_entity", "attributing the claim to the wrong thing"),
    DROP_LIMITATION("drop_limitation", "leaving out the limitation that comes with the claim"),
    ;

    companion object {
        fun parse(wire: String): MutationKind? = entries.firstOrNull { it.wire == wire.trim() }
    }
}

/** A distractor / unsupported card / wrong option declared by the unit writer as a mutation of one claim. */
data class Mutation(val partId: String, val claimId: String, val kind: String)

/** Lenient JSON for model output of the layered steps (own settings: the contract's discriminator would clash with `type`). */
object LayeredJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
}
