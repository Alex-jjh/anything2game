package com.anything2game.app.ui.space

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.api.ApiException
import com.anything2game.app.data.learning.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.ApiError
import com.anything2game.contract.game.GenerationInput
import com.anything2game.contract.space.*
import com.anything2game.engine.progress.NodeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

internal data class SpaceCard(val space: Space, val path: LearningPath?, val status: Map<String, NodeStatus>) {
    val next get() = path?.nodes?.firstOrNull { status[it.nodeId] == NodeStatus.AVAILABLE }
        ?: path?.nodes?.firstOrNull { status[it.nodeId] == NodeStatus.REVIEW }
    val completed get() = status.values.count { it == NodeStatus.COMPLETE || it == NodeStatus.REVIEW }
}

internal class SpaceViewModel(deps: AppDependencies, private val language: String, private val detail: String?) : ViewModel() {
    val spaces = requireNotNull(deps.spaces)
    val catalog = requireNotNull(deps.materials)
    val generation = requireNotNull(deps.generation)
    private val queue = requireNotNull(deps.pdfImports)
    private val progress = requireNotNull(deps.learningProgress)
    private val clock = deps.clock
    var cards by mutableStateOf(emptyList<SpaceCard>()); private set
    var materials by mutableStateOf(emptyList<Material>()); private set
    var imports by mutableStateOf(emptyList<PdfImportItem>()); private set
    var remote by mutableStateOf<Space?>(null); private set
    var rejected by mutableStateOf(emptyMap<String, ApiError>()); private set
    var weekDays by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var stale by mutableStateOf(false); private set

    private suspend fun load() {
        val all = spaces.list()
        val projection = progress.projection()
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        weekDays = projection.days.count { it >= monday && it <= today.toString() }
        materials = catalog.materials()
        cards = all.filter { detail == null || it.spaceId == detail }.map { space ->
            val path = spaces.route(space.spaceId, language)
            SpaceCard(space, path, path?.let { catalog.nodeStatuses(it, progress, clock.now()) }.orEmpty())
        }
        if (detail != null) {
            remote = spaces.serverState(detail)
            rejected = spaces.rejectedEdits(detail)
            imports = queue.list().filter { it.spaceId == detail }
        }
    }
    fun perform(work: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { error = null; stale = false; work(); load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                stale = (e as? ApiException)?.code == "SPACE_STALE"
                if (detail != null) rejected = spaces.rejectedEdits(detail)
            }
            finally { busy = false }
        }
    }
    fun importFiles(id: String, files: List<ImportRequest.PdfFile>) = perform {
        val rows = queue.enqueue(id, files)
        imports = queue.list().filter { it.spaceId == id }
        for (row in rows.filter { it.error == null }) {
            queue.process(row.id)
            imports = queue.list().filter { it.spaceId == id }
        }
    }
    fun retryImport(row: PdfImportItem, confirmedUnknown: Boolean) = perform {
        if (confirmedUnknown) queue.retryUnknownUpload(row.id) else queue.process(row.id)
    }
    suspend fun pathRequest(id: String): GenerationInput.SpacePath {
        try { return spaces.pathRequest(id, language).also { remote = spaces.serverState(id) } }
        catch (e: ApiException) { stale = e.code == "SPACE_STALE"; rejected = spaces.rejectedEdits(id); throw e }
    }
    fun memberTitle(member: SpaceMember): String = materials.firstOrNull { it.member == member || it.localPack == member }?.title ?: member.version().contentId
}
