package com.anything2game.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.DEFAULT_FRAME
import com.anything2game.contract.FrameId
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(val loading: Boolean = true, val entries: List<LibraryEntry> = emptyList())

data class LibraryEntry(
    val summary: PackageSummary,
    val levels: List<LevelEntry>,
    val frames: List<FrameId> = listOf(FrameId.CLASSIC),
    val selectedFrame: FrameId = FrameId.CLASSIC,
)

data class LevelEntry(val levelId: String, val number: Int, val title: String, val status: LevelStatus)

sealed interface LevelStatus {
    data object New : LevelStatus
    data class InProgress(val position: Int, val total: Int) : LevelStatus
    data object Done : LevelStatus
}

class LibraryViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init { refresh() }

    fun selectFrame(packageId: String, frameId: FrameId) {
        deps.framePreferences.set(packageId, frameId)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val entries = deps.packages.list().mapNotNull { summary ->
            val pkg = deps.packages.load(summary.packageId).getOrNull() ?: return@mapNotNull null
            val frames = deps.frames.available(summary.packageId)
            val selected = deps.framePreferences.get(summary.packageId)?.takeIf { it in frames }
                ?: DEFAULT_FRAME.takeIf { it in frames } ?: FrameId.CLASSIC
            // A level in progress under another frame does not count as in progress here.
            val session = deps.sessions.load(summary.packageId)?.takeIf { it.frameId == selected }
            val mastery = deps.mastery.all(summary.packageId)
            val levels = pkg.levels.mapIndexed { i, level ->
                val status = when {
                    session != null && session.levelId == level.levelId && !session.isFinished ->
                        LevelStatus.InProgress(session.position + 1, session.queue.size)
                    level.units.all { it.unitId in mastery } -> LevelStatus.Done
                    else -> LevelStatus.New
                }
                LevelEntry(level.levelId, i + 1, level.title, status)
            }
            LibraryEntry(summary, levels, frames, selected)
        }
        _state.value = LibraryUiState(loading = false, entries = entries)
    }
}
