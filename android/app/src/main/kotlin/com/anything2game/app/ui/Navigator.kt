package com.anything2game.app.ui

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.anything2game.app.importing.ImportRequest
import com.anything2game.app.ui.home.HomeTab
import com.anything2game.contract.FrameId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Route {
    data object Home : Route
    data class Space(val id: String, val materials: Boolean = false, val share: ImportRequest.PdfFile? = null, val batchId: String? = null) : Route
    data class Source(val packageId: String, val version: String) : Route
    data class Game(val id: String, val version: String) : Route
    data class Read(val packageId: String, val conceptId: String? = null, val sourcePackage: String? = null, val contentVersion: String? = null) : Route
    /** [run] is a fresh number per tap on a level, so a finished or abandoned run's screen state is never reused for the next one. */
    data class Play(val packageId: String, val levelId: String, val frameId: FrameId, val run: Int = 0, val version: String? = null, val resumeFrame: Boolean = version != null) : Route
    data class Import(val request: ImportRequest) : Route
}

/** Small navigation state, independent from durable learning progress. Also restores pre-tabs play routes. */
class Navigator(private val saved: SavedStateHandle) : ViewModel() {
    private val _tab = MutableStateFlow(HomeTab.from(saved["home.tab"]))
    val tab: StateFlow<HomeTab> = _tab.asStateFlow()
    private var plays = 0
    private val _route = MutableStateFlow(restore())
    val route: StateFlow<Route> = _route.asStateFlow()

    fun resetProfile() {
        toHome()
        saved.remove<String>("home.tab")
        _tab.value = HomeTab.SPACES
    }
    fun initializeTab(lastTab: String) {
        if (!saved.contains("home.tab")) {
            _tab.value = HomeTab.from(lastTab)
            saved["home.tab"] = _tab.value.name
        }
    }
    private fun restore(): Route {
        val pkg = saved.get<String>("route.pkg")
        val level = saved.get<String>("route.level")
        val type = saved.get<String>("route.type")
        if (type == "space") return parent()?.let { space ->
            val pdf = saved.get<String>("route.pdf") ?: return space
            space.copy(share = ImportRequest.PdfFile(saved["route.title"] ?: return space, pdf.toUri(), saved["route.size"]), batchId = saved["route.batch"])
        } ?: Route.Home
        if (type == "source" && pkg != null) return Route.Source(pkg, saved["route.version"] ?: return Route.Home)
        if (type == "game" && pkg != null) return Route.Game(pkg, saved["route.version"] ?: return Route.Home)
        if (type == "read" && pkg != null) return Route.Read(pkg, saved["route.concept"], saved["route.source"], saved["route.version"])
        if (pkg != null && level != null) {
            val frame = saved.get<String>("route.frame")?.let { raw -> FrameId.entries.firstOrNull { it.name == raw } } ?: FrameId.CLASSIC
            return Route.Play(pkg, level, frame, version = saved["route.version"], resumeFrame = saved["route.resume"] ?: false)
        }
        val uri = saved.get<String>("route.pdf")
        val title = saved.get<String>("route.title")
        if (uri != null && title != null) return Route.Import(ImportRequest.PdfFile(title, uri.toUri(), saved["route.size"]))
        return Route.Home
    }
    private fun clearDetail() {
        listOf("route.pkg", "route.level", "route.frame", "route.type", "route.pdf", "route.title", "route.size", "route.version", "route.resume", "route.concept", "route.source", "route.batch").forEach { saved.remove<Any>(it) }
    }
    fun selectTab(tab: HomeTab) { _tab.value = tab; saved["home.tab"] = tab.name; toHome() }
    /** The Space a detail screen was opened from (a game started from a route step), so it can offer the way back by name. */
    val parentSpaceId: String? get() = saved.get<String>("parent.space")
    private fun parent(): Route.Space? = saved.get<String>("parent.space")?.let { Route.Space(it, saved["parent.materials"] ?: false) }
    fun openSpace(id: String, materials: Boolean = false) {
        clearDetail()
        saved["parent.space"] = id
        saved["parent.materials"] = materials
        saved["route.type"] = "space"
        _route.value = Route.Space(id, materials)
    }
    fun openSpaceForPdf(id: String, request: ImportRequest.PdfFile) {
        openSpace(id, true)
        val batch = java.util.UUID.randomUUID().toString()
        saved["route.pdf"] = request.uri.toString(); saved["route.title"] = request.title
        saved["route.size"] = request.sizeBytes; saved["route.batch"] = batch
        _route.value = Route.Space(id, true, request, batch)
    }
    fun clearPdfConfirmation(batchId: String) {
        val space = _route.value as? Route.Space ?: return
        if (space.batchId == batchId) openSpace(space.id, true)
    }
    fun openSource(packageId: String, version: String) {
        clearDetail()
        saved["route.type"] = "source"; saved["route.pkg"] = packageId; saved["route.version"] = version
        _route.value = Route.Source(packageId, version)
    }
    fun back() {
        val parent = parent()
        if (parent != null && route.value !is Route.Space) openSpace(parent.id, parent.materials) else toHome()
    }
    fun openGame(id: String, version: String) {
        clearDetail()
        saved["route.type"] = "game"
        saved["route.pkg"] = id
        saved["route.version"] = version
        _route.value = Route.Game(id, version)
    }
    fun openRead(packageId: String, conceptId: String? = null, sourcePackage: String? = null, version: String? = null) {
        clearDetail(); saved["route.type"] = "read"; saved["route.pkg"] = packageId
        saved["route.concept"] = conceptId; saved["route.source"] = sourcePackage; saved["route.version"] = version
        _route.value = Route.Read(packageId, conceptId, sourcePackage, version)
    }
    fun openPlay(packageId: String, levelId: String, frameId: FrameId, version: String? = null, resumeFrame: Boolean = version != null) {
        clearDetail(); saved["route.pkg"] = packageId; saved["route.level"] = levelId; saved["route.frame"] = frameId.name
        saved["route.version"] = version; saved["route.resume"] = resumeFrame
        _route.value = Route.Play(packageId, levelId, frameId, ++plays, version, resumeFrame)
    }
    fun openImport(request: ImportRequest) {
        clearDetail()
        // Only metadata enters the saved-state Bundle; shared document text can be unbounded.
        if (request is ImportRequest.PdfFile) {
            saved["route.pdf"] = request.uri.toString(); saved["route.title"] = request.title; saved["route.size"] = request.sizeBytes
        }
        _route.value = Route.Import(request)
    }
    fun toHome() { saved.remove<String>("parent.space"); saved.remove<Boolean>("parent.materials"); clearDetail(); _route.value = Route.Home }
}
