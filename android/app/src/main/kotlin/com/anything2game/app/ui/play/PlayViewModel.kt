package com.anything2game.app.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.contract.Item
import com.anything2game.contract.LearningPackage
import com.anything2game.contract.LearningUnit
import com.anything2game.engine.context.ContextDecision
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.Action
import com.anything2game.engine.session.Effect
import com.anything2game.engine.session.LevelSession
import com.anything2game.engine.session.RecapSummary
import com.anything2game.engine.session.SessionState
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
        val session: SessionState,
        val unit: LearningUnit?,
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
) : ViewModel() {
    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var engine: LevelSession? = null
    private val mastery = mutableMapOf<String, MasteryRecord>()
    private var context: ContextDecision = deps.contextPolicy.decide(deps.contextSource.signals.value)

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            deps.contextSource.signals.collect { signals ->
                context = deps.contextPolicy.decide(signals)
                val reason = context.pauseReason
                if (context.pause && reason != null) dispatch(Action.Pause(reason)) else refreshContext()
            }
        }
    }

    private suspend fun load() = mutex.withLock {
        val pkg = deps.packages.load(packageId).getOrElse {
            _state.value = PlayUiState.Failed(it.message ?: it::class.simpleName.orEmpty())
            return@withLock
        }
        val e = LevelSession(pkg, deps.grading, deps.masteryPolicy, deps.clock).also { engine = it }
        mastery.clear(); mastery.putAll(deps.mastery.all(packageId))
        val saved = deps.sessions.load(packageId)?.takeIf { it.levelId == levelId && !it.isFinished && e.canResume(it) }
        var session = saved ?: e.start(levelId)
        val reason = context.pauseReason
        if (context.pause && reason != null) session = e.reduce(session, Action.Pause(reason)).state
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
            session = s,
            unit = e.unit(s),
            item = e.item(s),
            recap = if (s.isFinished) e.recap(s) else null,
            context = context,
            retryQueued = slot != null && s.queue.drop(s.position + 1).any { it.unitId == slot.unitId && it.retry },
        )
    }
}
