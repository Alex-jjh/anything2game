package com.anything2game.engine.session

import com.anything2game.contract.FrameId
import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.grading.Answer
import com.anything2game.engine.grading.Grade
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SlotKind {
    /** A package unit (main item or variant). */
    UNIT,
    /** A frame scene: dialogue/narration, no answer. */
    SCENE,
    /** The frame's final challenge from the overlay. */
    BOSS,
}

/** One step of a level: a unit's main item ([variant] = -1) or a variant, a scene, or the frame boss. */
@Serializable
data class Slot(
    val unitId: String,
    val variant: Int = MAIN,
    val retry: Boolean = false,
    val kind: SlotKind = SlotKind.UNIT,
    /** For [SlotKind.SCENE]: [INTRO], [OUTRO], `lead:<unitId>` or `room:<roomId>`. */
    val scene: String? = null,
) {
    val answerable: Boolean get() = kind != SlotKind.SCENE

    companion object {
        const val MAIN = -1
        const val INTRO = "intro"
        const val OUTRO = "outro"
        fun leadIn(unitId: String) = "lead:$unitId"
        fun room(roomId: String) = "room:$roomId"
    }
}

@Serializable
data class SlotResult(val slot: Slot, val grade: Grade, val hintsUsed: Int)

@Serializable
sealed interface Phase {
    @Serializable @SerialName("presenting") data object Presenting : Phase
    @Serializable @SerialName("feedback") data class Feedback(val answer: Answer, val grade: Grade) : Phase
    @Serializable @SerialName("recap") data object Recap : Phase
    /** Out of credibility: the case went cold. Replay starts the level again (with variants where available). */
    @Serializable @SerialName("failed") data object Failed : Phase
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
    val frameId: FrameId = FrameId.CLASSIC,
    val queue: List<Slot>,
    val position: Int = 0,
    val phase: Phase = Phase.Presenting,
    val hintsShown: Int = 0,
    val results: List<SlotResult> = emptyList(),
    val overlay: Overlay? = null,
    val pausedFor: ContextDecision.PauseReason? = null,
    /** Mistakes left before the level fails; null when the frame has no stakes. */
    val credibility: Int? = null,
) {
    val currentSlot: Slot? get() = queue.getOrNull(position)
    val isPaused: Boolean get() = pausedFor != null
    val isFinished: Boolean get() = phase == Phase.Recap || phase == Phase.Failed
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
