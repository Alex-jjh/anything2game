package com.anything2game.app.ui.play.mechanics

import androidx.compose.runtime.Composable
import com.anything2game.contract.Item
import com.anything2game.contract.Mechanic
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
    fun Question(key: String, item: Item, oneHanded: Boolean, onSubmit: (Answer) -> Unit)

    /** Show what was right and wrong after grading. */
    @Composable
    fun Review(item: Item, answer: Answer, grade: Grade)
}

object MechanicRenderers {
    private val byMechanic: Map<Mechanic, MechanicRenderer> =
        listOf(SwipeRenderer, SortRenderer, DeduceRenderer).associateBy { it.mechanic }

    fun of(mechanic: Mechanic): MechanicRenderer? = byMechanic[mechanic]
}
