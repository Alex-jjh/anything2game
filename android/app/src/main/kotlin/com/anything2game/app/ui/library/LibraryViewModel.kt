package com.anything2game.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.session.SessionState
import kotlinx.coroutines.CancellationException
import com.anything2game.contract.FrameId
import com.anything2game.engine.ports.PackageSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val entries: List<LibraryEntry> = emptyList(),
    val hasError: Boolean = false,
)

data class LibraryEntry(
    val summary: PackageSummary,
    val levels: List<LevelEntry>,
    val frames: List<FrameId> = listOf(FrameId.CLASSIC),
    val selectedFrame: FrameId = FrameId.CLASSIC,
    val prepared: Boolean = false,
    val practicedUnits: Int = 0,
    val dueUnits: Int = 0,
    val resumableLevelId: String? = null,
    val resumeFrame: FrameId = FrameId.CLASSIC,
) {
    fun frameFor(levelId: String): FrameId = if (levelId == resumableLevelId) resumeFrame else FrameId.CLASSIC
}

data class LevelEntry(val levelId: String, val number: Int, val title: String, val status: LevelStatus)

sealed interface LevelStatus {
    data object New : LevelStatus
    data class InProgress(val position: Int, val total: Int) : LevelStatus
    data object NeedsReview : LevelStatus
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
        var failed = false
        val entries = try {
            deps.packages.list().mapNotNull { summary ->
                try {
                    val pkg = deps.packages.load(summary.packageId).getOrThrow()
                    libraryEntry(summary, pkg, deps.sessions.load(summary.packageId), deps.mastery.all(summary.packageId), deps.clock.now())
                        .copy(prepared = deps.homePreferences.isPrepared(pkg.packageId, pkg.contentVersion))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true; null }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; emptyList() }
        _state.value = LibraryUiState(loading = false, entries = entries, hasError = failed)
    }
}

/** UI progress counts only units belonging to this package, never failed attempts as completed mastery. */
internal fun libraryEntry(
    summary: PackageSummary, pkg: LearningPackage, saved: SessionState?,
    mastery: Map<String, MasteryRecord>, now: Long,
): LibraryEntry {
    val session = saved?.takeIf { it.contentVersion == pkg.contentVersion && !it.isFinished && pkg.level(it.levelId) != null }
    val ids = pkg.levels.flatMap { it.units }.map { it.unitId }.toSet()
    val records = mastery.filterKeys { it in ids }.values.filter { it.attempts > 0 }
    val levels = pkg.levels.mapIndexed { i, level ->
        val status = when {
            session?.levelId == level.levelId -> LevelStatus.InProgress(session.position + 1, session.queue.size)
            level.units.isNotEmpty() && level.units.all { mastery[it.unitId]?.isSolid == true } -> LevelStatus.Done
            level.units.any { (mastery[it.unitId]?.attempts ?: 0) > 0 } -> LevelStatus.NeedsReview
            else -> LevelStatus.New
        }
        LevelEntry(level.levelId, i + 1, level.title, status)
    }
    return LibraryEntry(summary, levels, practicedUnits = records.size,
        dueUnits = records.count { !it.isSolid && it.dueAt <= now },
        resumableLevelId = session?.levelId, resumeFrame = session?.frameId ?: FrameId.CLASSIC)
}
