package com.anything2game.app.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.contract.FrameId
import com.anything2game.contract.FrameOverlay
import com.anything2game.contract.Item
import com.anything2game.contract.Line
import com.anything2game.contract.Room
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.frames.Frames
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Effect
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.RecapSummary
import com.anything2game.engine.session.SessionState
import com.anything2game.engine.session.SlotKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PlayUiState {
    data object Loading : PlayUiState
    data class Failed(val reason: String) : PlayUiState
    data class Ready(
        val pkg: LearningPackage,
        val frameId: FrameId,
        val overlay: FrameOverlay?,
        val session: SessionState,
        /** Lines of the current scene; null when the current slot is a challenge. */
        val scene: List<Line>?,
        val room: Room?,
        /** Units solved now or in earlier sessions — pins on the evidence board. */
        val solvedUnitIds: Set<String>,
        /** The package unit (or frame boss) behind the slot; null for scenes and derived slots, which have no unit of their own (no hints). */
        val unit: LearningUnit?,
        /** The item to render: the unit's, or one derived from the level's units (teach-back, room lock). */
        val item: Item?,
        val recap: RecapSummary?,
        val context: ContextDecision,
        /** A retry of the current unit is queued later in this level. */
        val retryQueued: Boolean,
    ) : PlayUiState
}

/**
 * Glue between the engine and the screen: loads, restores, reduces, persists. Holds no game rules.
 * Every transition is saved before the UI sees it, so process death resumes at the same step.
 */
class PlayViewModel(
    private val deps: AppDependencies,
    private val packageId: String,
    private val levelId: String,
    private val frameId: FrameId,
) : ViewModel() {
    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var engine: LevelSession? = null
    private val mastery = mutableMapOf<String, MasteryRecord>()
    private var overlay: FrameOverlay? = null
    private var activeFrame: FrameId = FrameId.CLASSIC
    private var context: ContextDecision = deps.contextPolicy.decide(deps.contextSource.signals.value)

    private var contextJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { load() }
        listenToContext()
    }

    /** Follows the phone's context (e.g. walking pauses the level) while this level is on screen. */
    fun listenToContext() {
        if (contextJob?.isActive == true) return
        contextJob = viewModelScope.launch {
            deps.contextSource.signals.collect { signals ->
                context = deps.contextPolicy.decide(signals)
                val reason = context.pauseReason
                if (context.pause && reason != null) dispatch(Action.Pause(reason)) else refreshContext()
            }
        }
    }
    fun stopListeningToContext() { contextJob?.cancel(); contextJob = null }

    private suspend fun load() = mutex.withLock {
        val pkg = deps.packages.load(packageId).getOrElse {
            _state.value = PlayUiState.Failed(it.message ?: it::class.simpleName.orEmpty())
            return@withLock
        }
        // A frame whose overlay is missing falls back to Classic, so the level always plays.
        val overlay = deps.frames.load(packageId, frameId)
        val frame = Frames.of(frameId).takeIf { it.isPlayable(overlay) } ?: Frames.CLASSIC
        val e = LevelSession(pkg, deps.grading, deps.masteryPolicy, deps.clock, frame = frame, overlay = overlay?.takeIf { frame.id == frameId })
            .also { engine = it; this.overlay = overlay?.takeIf { frame.id == frameId }; activeFrame = frame.id }
        mastery.clear(); mastery.putAll(deps.mastery.all(packageId))
        val saved = deps.sessions.load(packageId)?.takeIf { it.levelId == levelId && !it.isFinished && e.canResume(it) }
        var session = saved ?: e.start(levelId)
        val reason = context.pauseReason
        if (context.pause && reason != null) session = e.reduce(session, Action.Pause(reason)).state
        else if (session.isPaused) session = e.reduce(session, Action.Resume).state // a pause saved by an earlier run is stale once the phone is still
        deps.sessions.save(session)
        publish(pkg, e, session)
    }

    fun dispatch(action: Action) {
        viewModelScope.launch {
            mutex.withLock {
                val ready = _state.value as? PlayUiState.Ready ?: return@withLock
                val e = engine ?: return@withLock
                val t = e.reduce(ready.session, action, mastery)
                if (t.state == ready.session && t.effects.isEmpty()) return@withLock
                t.effects.forEach { effect ->
                    when (effect) {
                        is Effect.MasteryChanged -> {
                            mastery[effect.record.unitId] = effect.record
                            deps.mastery.put(packageId, effect.record)
                        }
                    }
                }
                deps.sessions.save(t.state)
                publish(ready.pkg, e, t.state)
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            mutex.withLock {
                val ready = _state.value as? PlayUiState.Ready ?: return@withLock
                val e = engine ?: return@withLock
                val s = e.start(levelId)
                deps.sessions.save(s)
                publish(ready.pkg, e, s)
            }
        }
    }

    private fun refreshContext() {
        val ready = _state.value as? PlayUiState.Ready ?: return
        _state.value = ready.copy(context = context)
    }

    private fun publish(pkg: LearningPackage, e: LevelSession, s: SessionState) {
        val slot = s.currentSlot
        _state.value = PlayUiState.Ready(
            pkg = pkg,
            frameId = activeFrame,
            overlay = overlay,
            session = s,
            scene = e.sceneLines(s),
            room = e.room(s),
            solvedUnitIds = e.solvedUnitIds(s) + mastery.values.filter { it.box >= 1 }.map { it.unitId },
            unit = e.unit(s),
            item = e.item(s),
            recap = if (s.isFinished) e.recap(s) else null,
            context = context,
            retryQueued = slot != null && slot.kind == SlotKind.UNIT && s.queue.drop(s.position + 1).any { it.unitId == slot.unitId && it.retry },
        )
    }
}
