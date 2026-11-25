package com.anything2game.app.ui.play

import com.anything2game.app.ui.common.problemCode
import com.anything2game.app.ui.common.problemText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.learning.PracticeStore
import com.anything2game.app.platform.ContextSource
import com.anything2game.engine.context.ContextPolicy
import com.anything2game.engine.grading.Grading
import com.anything2game.engine.mastery.MasteryPolicy
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.engine.ports.PackageRepository
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
    private val packages: PackageRepository,
    private val frames: FrameRepository,
    /** Every save of a level goes through this one journalled store: answer, mastery and activity record commit together or not at all. */
    private val practice: PracticeStore,
    private val grading: Grading,
    private val masteryPolicy: MasteryPolicy,
    private val clock: Clock,
    private val contextSource: ContextSource,
    private val contextPolicy: ContextPolicy,
    private val packageId: String,
    private val levelId: String,
    private val frameId: FrameId,
    private val contentVersion: String? = null,
    private val resumeSavedFrame: Boolean = contentVersion != null,
) : ViewModel() {
    companion object {
        /** The level's ports, picked out of the container. */
        fun of(deps: AppDependencies, packageId: String, levelId: String, frameId: FrameId, contentVersion: String? = null,
            resumeSavedFrame: Boolean = contentVersion != null) =
            PlayViewModel(deps.packages, deps.frames, deps.practiceCommits, deps.grading, deps.masteryPolicy, deps.clock,
                deps.contextSource, deps.contextPolicy, packageId, levelId, frameId, contentVersion, resumeSavedFrame)
    }

    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var engine: LevelSession? = null
    private val mastery = mutableMapOf<String, MasteryRecord>()
    private var overlay: FrameOverlay? = null
    private var activeFrame: FrameId = FrameId.CLASSIC
    private var context: ContextDecision = contextPolicy.decide(contextSource.signals.value)

    private var contextJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { load() }
        listenToContext()
    }

    /** Follows the phone's context (e.g. walking pauses the level) while this level is on screen. */
    fun listenToContext() {
        if (contextJob?.isActive == true) return
        contextJob = viewModelScope.launch {
            contextSource.signals.collect { signals ->
                context = contextPolicy.decide(signals)
                val reason = context.pauseReason
                if (context.pause && reason != null) dispatch(Action.Pause(reason)) else refreshContext()
            }
        }
    }
    fun stopListeningToContext() { contextJob?.cancel(); contextJob = null }

    private suspend fun load() = mutex.withLock {
        while (true) {
            val prior = practice.load(packageId)
            val version = contentVersion ?: prior?.takeIf { !it.isFinished && it.levelId == levelId && it.frameId == frameId }?.contentVersion
            val pinned = version?.let { packages.load(packageId, it) }
            val loaded = if (contentVersion != null || pinned?.isSuccess == true) requireNotNull(pinned) else packages.load(packageId)
            val pkg = loaded.getOrElse {
                _state.value = PlayUiState.Failed(problemCode(it))
                return@withLock
            }
            // A frame whose overlay is missing falls back to Classic, so the level always plays.
            val priorVersion = practice.load(packageId, pkg.contentVersion)
            val checkpoint = priorVersion?.takeIf { it.levelId == levelId && !it.isFinished }
            val requestedFrame = if (resumeSavedFrame) checkpoint?.frameId ?: frameId else frameId
            val overlay = frames.load(packageId, pkg.contentVersion, requestedFrame)
            val frame = Frames.of(requestedFrame).takeIf { it.isPlayable(overlay) } ?: Frames.CLASSIC
            val e = LevelSession(pkg, grading, masteryPolicy, clock, frame = frame, overlay = overlay?.takeIf { frame.id == requestedFrame })
            val saved = checkpoint?.takeIf(e::canResume)
            var session = saved ?: e.start(levelId)
            if (session.attemptId.isEmpty()) session = session.copy(attemptId = java.util.UUID.randomUUID().toString())
            val reason = context.pauseReason
            if (context.pause && reason != null) session = e.reduce(session, Action.Pause(reason)).state
            else if (session.isPaused) session = e.reduce(session, Action.Resume).state // a pause saved by an earlier run is stale once the phone is still
            val records = practice.initialize(priorVersion, session) ?: continue
            engine = e
            this.overlay = overlay?.takeIf { frame.id == requestedFrame }
            activeFrame = frame.id
            mastery.clear()
            mastery.putAll(records)
            publish(pkg, e, session)
            break
        }
    }

    fun dispatch(action: Action) {
        viewModelScope.launch {
            mutex.withLock {
                val ready = _state.value as? PlayUiState.Ready ?: return@withLock
                val e = engine ?: return@withLock
                val t = e.reduce(ready.session, action, mastery)
                if (t.state == ready.session && t.effects.isEmpty()) return@withLock
                val changed = t.effects.filterIsInstance<Effect.MasteryChanged>().map { it.record }
                try {
                    practice.commit(ready.session, t.state, changed)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    _state.value = PlayUiState.Failed(problemCode(e))
                    return@withLock
                }
                for (record in changed) mastery[record.unitId] = record
                publish(ready.pkg, e, t.state)
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            mutex.withLock {
                val ready = _state.value as? PlayUiState.Ready ?: return@withLock
                val e = engine ?: return@withLock
                // Replay asks what was missed in another form where the course has one (the cold-case screen promises it).
                val missed = ready.session.results.filter { it.slot.kind == com.anything2game.engine.session.SlotKind.UNIT && !it.grade.correct }.map { it.slot.unitId }.toSet()
                val s = e.start(levelId, missed).copy(attemptId = java.util.UUID.randomUUID().toString())
                practice.save(s)
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
