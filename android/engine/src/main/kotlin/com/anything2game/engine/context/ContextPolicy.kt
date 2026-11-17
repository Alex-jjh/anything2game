package com.anything2game.engine.context

/** Signals the phone reports. Platform adapters in :app produce these; the policy never touches Android APIs. */
data class ContextSignals(
    val activity: Activity = Activity.UNKNOWN,
    val network: Network = Network.UNMETERED,
    val batteryLow: Boolean = false,
) {
    enum class Activity { STILL, WALKING, IN_VEHICLE, UNKNOWN }
    enum class Network { UNMETERED, METERED, OFFLINE }
}

data class ContextDecision(
    /** Pause the level (e.g. walking). The UI shows [pauseReason]. */
    val pause: Boolean,
    val pauseReason: PauseReason? = null,
    /** Thumb-only layout: bigger targets, actions at the bottom. */
    val oneHanded: Boolean,
    val askAvailable: Boolean,
    val prefetchAllowed: Boolean,
    val reduceMotion: Boolean,
) {
    enum class PauseReason { WALKING }
}

fun interface ContextPolicy {
    fun decide(signals: ContextSignals): ContextDecision
}

object DefaultContextPolicy : ContextPolicy {
    override fun decide(signals: ContextSignals): ContextDecision {
        val walking = signals.activity == ContextSignals.Activity.WALKING
        return ContextDecision(
            pause = walking,
            pauseReason = if (walking) ContextDecision.PauseReason.WALKING else null,
            oneHanded = signals.activity == ContextSignals.Activity.IN_VEHICLE,
            askAvailable = signals.network != ContextSignals.Network.OFFLINE,
            prefetchAllowed = signals.network == ContextSignals.Network.UNMETERED && !signals.batteryLow,
            reduceMotion = signals.batteryLow,
        )
    }
}
