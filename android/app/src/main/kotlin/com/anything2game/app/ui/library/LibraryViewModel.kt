package com.anything2game.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.FramePreferences
import com.anything2game.app.data.learning.LearningProgress
import com.anything2game.app.data.tutor.TutorRepository
import com.anything2game.app.data.tutor.TutorScripts
import com.anything2game.engine.ports.Clock
import com.anything2game.engine.ports.MasteryStore
import com.anything2game.engine.ports.PackageRepository
import com.anything2game.engine.ports.SessionStore
import com.anything2game.engine.ports.FrameRepository
import com.anything2game.contract.LearningPackage
import com.anything2game.engine.mastery.MasteryRecord
import com.anything2game.engine.progress.ActivityProgress
import com.anything2game.engine.progress.ActivityStatus
import com.anything2game.engine.session.SessionState
import com.anything2game.contract.space.ActivityRef
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
    val tutorials: List<com.anything2game.contract.tutor.TutorSession> = emptyList(),
    val activityProgress: com.anything2game.engine.progress.ActivityProgress? = null,
    val legacyUnits: Int = 0,
)

data class LibraryEntry(
    val summary: PackageSummary,
    val frames: List<FrameId> = listOf(FrameId.CLASSIC),
    val selectedFrame: FrameId = FrameId.CLASSIC,
    val practicedUnits: Int = 0,
    val dueUnits: Int = 0,
    val resumableLevelId: String? = null,
    val resumeFrame: FrameId = FrameId.CLASSIC,
    val documentId: String = "",
    val mock: Boolean = false,
    /** The course ships a pre-generated tutor script, so the Learn tab can start an explanation offline. */
    val hasTutor: Boolean = false,
    /** Where the student is in the saved explanation of this course (null: nothing saved yet). */
    val tutorProgress: com.anything2game.app.data.tutor.TutorProgress? = null,
) {
    /** An open session resumes in its own frame; anything new starts in the frame the player chose (Classic by default). */
    fun frameFor(levelId: String): FrameId = if (levelId == resumableLevelId) resumeFrame else selectedFrame
}

/** One row of a course's practice list. [status] is what the route would show for the same level. */
data class PracticeLevel(val levelId: String, val number: Int, val title: String, val status: ActivityStatus)

class LibraryViewModel(
    private val packages: PackageRepository, private val frames: FrameRepository, private val sessions: SessionStore, private val mastery: MasteryStore,
    private val clock: Clock, private val framePreferences: FramePreferences, private val tutorScripts: TutorScripts, private val tutors: TutorRepository,
    private val progress: LearningProgress,
) : ViewModel() {
    companion object {
        fun of(deps: AppDependencies) = LibraryViewModel(deps.packages, deps.frames, deps.sessions, deps.mastery, deps.clock,
            deps.framePreferences, deps.tutorScripts, deps.tutors, deps.learningProgress)
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init { refresh() }

    fun selectFrame(packageId: String, frameId: FrameId) {
        framePreferences.set(packageId, frameId)
        refresh()
    }

    suspend fun preparePractice(sessionId: String) {
        val session = tutors.list().single { it.sessionId == sessionId }
        tutors.preparePractice(session)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        var failed = false
        val entries = try {
            packages.list().mapNotNull { summary ->
                try {
                    val pkg = packages.load(summary.packageId).getOrThrow()
                    val offered = com.anything2game.app.ui.play.offeredFrames(pkg, frames.available(pkg.packageId))
                    libraryEntry(summary, pkg, sessions.load(summary.packageId, pkg.contentVersion), mastery.all(summary.packageId, pkg.contentVersion), clock.now())
                        .copy(frames = offered, selectedFrame = framePreferences.get(pkg.packageId)?.takeIf { it in offered } ?: FrameId.CLASSIC)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true; null }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; emptyList() }
        val scripted = try { tutorScripts.scriptedPackageIds().toSet() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptySet() }
        val tutorials = try { tutors.list() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; emptyList() }
        val projection = try { progress.projection() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true; null }
        val history = entries.sumOf { entry ->
            try { mastery.legacyUnversioned(entry.summary.packageId).values.count { it.attempts > 0 } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; 0 }
        }
        _state.value = LibraryUiState(activityProgress = projection, legacyUnits = history, loading = false, entries = entries.map { e ->
            val has = e.summary.packageId in scripted
            e.copy(hasTutor = has, tutorProgress = if (has) try { tutors.progressFor(e.summary.packageId) } catch (c: CancellationException) { throw c } catch (_: Exception) { null } else null)
        }, hasError = failed, tutorials = tutorials)
    }
}

/** The Me tab's counts: only units belonging to this package, never failed attempts as completed mastery. */
internal fun libraryEntry(
    summary: PackageSummary, pkg: LearningPackage, saved: SessionState?,
    mastery: Map<String, MasteryRecord>, now: Long,
): LibraryEntry {
    val session = openSession(pkg, saved)
    val ids = pkg.levels.flatMap { it.units }.map { it.unitId }.toSet()
    val records = mastery.filterKeys { it in ids }.values.filter { it.attempts > 0 }
    return LibraryEntry(summary, practicedUnits = records.size,
        dueUnits = records.count { !it.isSolid && it.dueAt <= now },
        resumableLevelId = session?.levelId, resumeFrame = session?.frameId ?: FrameId.CLASSIC, documentId = pkg.documentId, mock = pkg.generation.model.contains("fake"))
}

/** The saved session of this course, when it is unfinished, of this content version and of a level that still exists. */
private fun openSession(pkg: LearningPackage, saved: SessionState?) =
    saved?.takeIf { it.contentVersion == pkg.contentVersion && !it.isFinished && pkg.level(it.levelId) != null }

/**
 * The practice list of one course: each level with the status the route shows for the same activity, from the same ledger
 * projection ([progress]); a level with a saved session in progress says so with its place in it.
 */
internal fun practiceLevels(pkg: LearningPackage, saved: SessionState?, progress: ActivityProgress, profileId: String, now: Long): List<PracticeLevel> {
    val session = openSession(pkg, saved)
    return pkg.levels.mapIndexed { i, level ->
        val key = ActivityRef.Practice(pkg.packageId, pkg.contentVersion, level.levelId).key(profileId)
        val open = session?.takeIf { it.levelId == level.levelId }?.let { it.position + 1 to it.queue.size }
        PracticeLevel(level.levelId, i + 1, level.title, progress.status(key, now, open))
    }
}
