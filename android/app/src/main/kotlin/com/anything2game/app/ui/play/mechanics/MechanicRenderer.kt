package com.anything2game.app.ui.play.mechanics

import androidx.compose.runtime.Composable
import com.anything2game.contract.CastMember
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
import com.anything2game.contract.SourceChunk
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade

/**
 * Draws one mechanic. Each renderer owns only its own gestures and layout; grading stays in :engine.
 * Adding a mechanic = one new renderer registered in [MechanicRenderers] (open for extension, closed for modification).
 */
interface MechanicRenderer {
    val mechanic: Mechanic

    /** Collect an answer. [key] changes per slot, so per-slot UI state resets. */
    @Composable
    fun Question(key: String, item: Item, ctx: RenderContext, onSubmit: (Answer) -> Unit)

    /** Show what was right and wrong after grading. */
    @Composable
    fun Review(item: Item, answer: Answer, grade: Grade, ctx: RenderContext)
}

/** What a renderer may need besides its item. Grows by adding fields, never by changing signatures. */
data class RenderContext(
    val oneHanded: Boolean = false,
    /** The package's passages, e.g. for presenting evidence. */
    val chunks: List<SourceChunk> = emptyList(),
    /** The frame's cast, e.g. to name the speaker of a cross-exam. */
    val cast: List<CastMember> = emptyList(),
    /** Passages the player may present as evidence (cross-exam): what this level's clues cite. */
    val evidenceChunks: List<SourceChunk> = chunks,
)

object MechanicRenderers {
    private val byMechanic: Map<Mechanic, MechanicRenderer> =
        listOf(SwipeRenderer, SortRenderer, DeduceRenderer, OrderRenderer, CrossExamRenderer).associateBy { it.mechanic }

    fun of(mechanic: Mechanic): MechanicRenderer? = byMechanic[mechanic]
}
