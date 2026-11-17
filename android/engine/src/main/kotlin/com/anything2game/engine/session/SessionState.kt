package com.anything2game.engine.session

import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One play of one item: the unit's main item ([variant] = -1) or one of its variants. */
@Serializable
data class Slot(val unitId: String, val variant: Int = MAIN, val retry: Boolean = false) {
    companion object { const val MAIN = -1 }
}

@Serializable
data class SlotResult(val slot: Slot, val grade: Grade, val hintsUsed: Int)

@Serializable
sealed interface Phase {
    @Serializable @SerialName("presenting") data object Presenting : Phase
    @Serializable @SerialName("feedback") data class Feedback(val answer: Answer, val grade: Grade) : Phase
    @Serializable @SerialName("recap") data object Recap : Phase
}

/** Something on top of the phase. Closing it returns to exactly the same phase. */
@Serializable
sealed interface Overlay {
    @Serializable @SerialName("evidence") data class Evidence(val chunkIds: List<String>) : Overlay
    @Serializable @SerialName("asking") data object Asking : Overlay
}

/** Complete, serialisable state of one level in progress. Persisted after every transition. */
@Serializable
data class SessionState(
    val packageId: String,
    val contentVersion: String,
    val levelId: String,
    val queue: List<Slot>,
    val position: Int = 0,
    val phase: Phase = Phase.Presenting,
    val hintsShown: Int = 0,
    val results: List<SlotResult> = emptyList(),
    val overlay: Overlay? = null,
    val pausedFor: ContextDecision.PauseReason? = null,
) {
    val currentSlot: Slot? get() = queue.getOrNull(position)
    val isPaused: Boolean get() = pausedFor != null
    val isFinished: Boolean get() = phase == Phase.Recap
}

/** Everything the player can do. */
sealed interface Action {
    data class Submit(val answer: Answer) : Action
    data object RevealHint : Action
    data object Next : Action
    data class OpenEvidence(val chunkIds: List<String>) : Action
    data object OpenAsk : Action
    data object CloseOverlay : Action
    data class Pause(val reason: ContextDecision.PauseReason) : Action
    data object Resume : Action
}
