package com.anything2game.app.ui.space

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anything2game.app.AppDependencies
import com.anything2game.app.data.api.ApiException
import com.anything2game.app.data.learning.*
import com.anything2game.app.importing.ImportRequest
import com.anything2game.contract.ApiError
import com.anything2game.contract.game.ContentVersion
import com.anything2game.contract.game.EvidenceRef
import com.anything2game.contract.game.GenerationInput
import com.anything2game.contract.space.*
import com.anything2game.engine.progress.NodeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** [contentLanguage] is the language of the Space's materials ("en", "zh"), or null when they do not say. */
internal data class SpaceCard(val space: Space, val path: LearningPath?, val status: Map<String, NodeStatus>, val contentLanguage: String? = null) {
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
    /** The Space's files for the Materials list, in the Space's order. */
    var files by mutableStateOf(emptyList<MaterialFile>()); private set
    /** The file open in the viewer, and the files whose practice list is unfolded; kept here so they survive a trip to a game. */
    var viewing by mutableStateOf<SpaceMember?>(null)
    var practising by mutableStateOf(emptySet<SpaceMember>()); private set
    var remote by mutableStateOf<Space?>(null); private set
    var rejected by mutableStateOf(emptyMap<String, ApiError>()); private set
    var weekDays by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unavailable by mutableStateOf(false); private set
    var stale by mutableStateOf(false); private set

    private suspend fun load() {
        val inventory = catalog.snapshot()
        val all = spaces.list(inventory.materials)
        if (detail != null && all.none { it.spaceId == detail }) {
            cards = emptyList()
            unavailable = true
            return
        }
        val projection = progress.projection()
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        weekDays = projection.days.count { it >= monday && it <= today.toString() }
        materials = inventory.materials
        val now = clock.now()
        cards = all.filter { detail == null || it.spaceId == detail }.map { space ->
            val path = spaces.route(space, language, inventory.materials)
            val own = inventory.materials.filter { it.member in space.members || it.localPack?.let(space.members::contains) == true }
            SpaceCard(space, path, path?.let { inventory.statuses(it, progress.profileId, projection, now) }.orEmpty(),
                own.map { it.language.substringBefore('-').lowercase() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        }.let { list -> if (detail != null) list else
            // First impression: Spaces in the reader's own language first; stable, so the saved order holds within each group.
            list.sortedBy { it.contentLanguage != null && it.contentLanguage != language.substringBefore('-').lowercase() } }
        if (detail != null) {
            remote = spaces.serverState(detail)
            rejected = spaces.rejectedEdits(detail)
            imports = queue.list().filter { it.spaceId == detail }
            files = cards.firstOrNull()?.space?.members.orEmpty().map { member ->
                val file = catalog.file(member, inventory.materials, memberTitle(member))
                val key = inventory.materials.firstOrNull { it.member == member && it.localPack == null }?.tutorKey
                file.copy(addedAt = imports.firstOrNull { key != null && it.sessionId == key }?.addedAt ?: 0)
            }
        }
    }
    fun togglePractice(member: SpaceMember) { practising = if (member in practising) practising - member else practising + member }
    fun perform(work: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { error = null; unavailable = false; stale = false; work(); load() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                stale = (e as? ApiException)?.code == "SPACE_STALE"
                if (detail != null) readRejections(detail)
            }
            finally { busy = false }
        }
    }
    private suspend fun readRejections(id: String) {
        try { rejected = spaces.rejectedEdits(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep the original failure and the last readable snapshot. */ }
    }
    fun importFiles(id: String, files: List<ImportRequest.PdfFile>, batchId: String, onJournaled: () -> Unit) = perform {
        val rows = queue.enqueue(id, files, batchId, language, onJournaled)
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
        catch (e: ApiException) { stale = e.code == "SPACE_STALE"; readRejections(id); throw e }
    }
    /** The material a step comes from: its lesson, its course, or the document its citation names. */
    fun materialOf(node: PathNode): Material? {
        val activity = node.activity
        return materials.firstOrNull { m -> when (activity) {
            is ActivityRef.Tutor -> activity.sessionId != null && m.tutorKey == activity.sessionId
            is ActivityRef.Practice -> m.localPack?.version() == ContentVersion(activity.contentId, activity.contentVersion)
            is ActivityRef.Game -> m.nodes.any { it.nodeId == node.nodeId }
        } } ?: node.evidence.filterIsInstance<EvidenceRef.Pdf>().firstOrNull()?.let { ref -> materials.firstOrNull { it.source == ContentVersion(ref.documentId, ref.documentVersion) } }
    }
    fun materialInfo(node: PathNode): MaterialInfo? = materialOf(node)?.let { MaterialInfo(it.title, it.fixture) }
    /** The step's own material when it holds the cited document: two lessons can share document labels. */
    fun sourceTitle(node: PathNode, ref: EvidenceRef.Pdf): String? {
        val source = ContentVersion(ref.documentId, ref.documentVersion)
        return materialOf(node)?.takeIf { it.source == source }?.title ?: materials.firstOrNull { it.source == source }?.title
    }
    fun memberTitle(member: SpaceMember): String = materials.firstOrNull { it.member == member || it.localPack == member }?.title ?: member.version().contentId
}
