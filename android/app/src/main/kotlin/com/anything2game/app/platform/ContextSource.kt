package com.anything2game.app.platform

import com.anything2game.engine.context.ContextSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Produces the phone's current context. Adapters: simulated (debug/demo), sensors (real device). */
interface ContextSource {
    val signals: StateFlow<ContextSignals>
}

/** What the demo controls need from a context source a person can drive by hand. Only a simulated source has it. */
interface ContextSimulatorControls {
    val signals: StateFlow<ContextSignals>
    fun setActivity(activity: ContextSignals.Activity)
    fun setNetwork(network: ContextSignals.Network)
}

/** Driven by the in-app simulator, so context features can be shown on an emulator or in screenshots. */
class SimulatedContextSource(initial: ContextSignals = ContextSignals(activity = ContextSignals.Activity.STILL)) : ContextSource, ContextSimulatorControls {
    private val state = MutableStateFlow(initial)
    override val signals: StateFlow<ContextSignals> = state.asStateFlow()

    override fun setActivity(activity: ContextSignals.Activity) { state.update { it.copy(activity = activity) } }
    override fun setNetwork(network: ContextSignals.Network) { state.update { it.copy(network = network) } }
}
