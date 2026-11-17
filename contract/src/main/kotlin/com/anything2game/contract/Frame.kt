package com.anything2game.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val FRAME_SCHEMA_V1 = "a2g.frame/v1"

/**
 * A frame is the meta-game around the same content (docs/product/game-concepts.md §7).
 * Frames may order, wrap and add to units; they never change an item, its answer or its citation.
 */
@Serializable
enum class FrameId {
    /** Clean path: units in order, no story. Needs no overlay. */
    @SerialName("classic") CLASSIC,
    /** Detective: briefing, clues pinned to a board, cross-examine the Summary. */
    @SerialName("case") CASE,
    /** Escape room: units grouped into rooms; a final lock opens the exit. */
    @SerialName("escape") ESCAPE,
    /** Teach the rookie: a junior asks before each unit; correct the rookie's notes at the end. */
    @SerialName("rookie") ROOKIE,
    /** Companion: a partner introduces each unit in a few cited lines (light visual-novel feel). */
    @SerialName("companion") COMPANION,
}

/** Frame-specific content for one package version. A separate artefact: generating a frame never changes the package. */
@Serializable
data class FrameOverlay(
    val schema: String,
    val frameId: FrameId,
    val packageId: String,
    val contentVersion: String,
    val generation: Generation,
    val cast: List<CastMember> = emptyList(),
    val levels: List<LevelOverlay> = emptyList(),
    /** Concept graph the player fills in (Case frame's evidence board). */
    val board: Board? = null,
) {
    fun level(levelId: String): LevelOverlay? = levels.firstOrNull { it.levelId == levelId }
    fun castMember(id: String): CastMember? = cast.firstOrNull { it.id == id }
}

@Serializable
data class CastMember(val id: String, val name: String, val role: CastRole)

@Serializable
enum class CastRole {
    @SerialName("partner") PARTNER,
    @SerialName("suspect") SUSPECT,
    @SerialName("rookie") ROOKIE,
    @SerialName("companion") COMPANION,
    @SerialName("narrator") NARRATOR,
}

@Serializable
data class LevelOverlay(
    val levelId: String,
    /** Scene before the first unit. */
    val intro: List<Line> = emptyList(),
    /** unitId → scene shown right before that unit. */
    val leadIns: Map<String, List<Line>> = emptyMap(),
    /** Escape frame: units grouped into rooms, in play order. */
    val rooms: List<Room> = emptyList(),
    /** Frame-specific final challenge, validated like any unit. */
    val boss: LearningUnit? = null,
    /** Scene after the last challenge. */
    val outro: List<Line> = emptyList(),
)

/** One line of dialogue or narration. Lines that teach must cite. */
@Serializable
data class Line(val speaker: String, val text: String, val sourceRefs: List<SourceRef> = emptyList())

@Serializable
data class Room(val id: String, val title: String, val description: String, val unitIds: List<String>)

@Serializable
data class Board(val nodes: List<BoardNode>, val edges: List<BoardEdge> = emptyList())

@Serializable
data class BoardNode(val id: String, val label: String, val unitId: String)

@Serializable
data class BoardEdge(val from: String, val to: String, val label: String)

/**
 * What each frame requires from its overlay. Shared by the server (frame nodes validate before publishing)
 * and the client (refuse incomplete overlays). One place to read "what does frame X need?".
 */
data class FrameSpec(
    val frameId: FrameId,
    val needsOverlay: Boolean,
    val requiredRoles: Set<CastRole> = emptySet(),
    val needsIntro: Boolean = false,
    /** Every unit must have a lead-in scene. */
    val needsLeadIns: Boolean = false,
    /** Every unit must be in exactly one room. */
    val needsRooms: Boolean = false,
    val bossMechanics: Set<Mechanic> = emptySet(),
    /** Role of the speaker of a cross-exam boss. */
    val bossSpeakerRole: CastRole? = null,
    val needsBoard: Boolean = false,
    val needsOutro: Boolean = false,
    /** Mistakes allowed per level before the level fails; null = no stakes. */
    val credibility: Int? = null,
) {
    val needsBoss: Boolean get() = bossMechanics.isNotEmpty()

    companion object {
        val ALL: Map<FrameId, FrameSpec> = listOf(
            FrameSpec(FrameId.CLASSIC, needsOverlay = false),
            FrameSpec(
                FrameId.CASE, needsOverlay = true,
                requiredRoles = setOf(CastRole.PARTNER, CastRole.SUSPECT),
                needsIntro = true, bossMechanics = setOf(Mechanic.CROSS_EXAM), bossSpeakerRole = CastRole.SUSPECT,
                needsBoard = true, needsOutro = true, credibility = 3,
            ),
            FrameSpec(
                FrameId.ESCAPE, needsOverlay = true,
                requiredRoles = setOf(CastRole.NARRATOR),
                needsIntro = true, needsRooms = true, bossMechanics = setOf(Mechanic.ORDER, Mechanic.DEDUCE),
                needsOutro = true, credibility = 3,
            ),
            FrameSpec(
                FrameId.ROOKIE, needsOverlay = true,
                requiredRoles = setOf(CastRole.ROOKIE),
                needsIntro = true, needsLeadIns = true, bossMechanics = setOf(Mechanic.CROSS_EXAM), bossSpeakerRole = CastRole.ROOKIE,
                needsOutro = true,
            ),
            FrameSpec(
                FrameId.COMPANION, needsOverlay = true,
                requiredRoles = setOf(CastRole.COMPANION),
                needsIntro = true, needsLeadIns = true, needsOutro = true,
            ),
        ).associateBy { it.frameId }

        fun of(id: FrameId): FrameSpec = ALL.getValue(id)
    }
}
