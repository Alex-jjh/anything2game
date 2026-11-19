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
    data class Read(val packageId: String) : Route
    data class Play(val packageId: String, val levelId: String, val frameId: FrameId) : Route
    data class Import(val request: ImportRequest) : Route
}

/** Small navigation state, independent from durable learning progress. Also restores pre-tabs play routes. */
class Navigator(private val saved: SavedStateHandle) : ViewModel() {
    private val _tab = MutableStateFlow(HomeTab.from(saved["home.tab"]))
    val tab: StateFlow<HomeTab> = _tab.asStateFlow()
    private val _route = MutableStateFlow(restore())
    val route: StateFlow<Route> = _route.asStateFlow()

    fun initializeTab(lastTab: String) {
        if (!saved.contains("home.tab")) {
            _tab.value = HomeTab.from(lastTab)
            saved["home.tab"] = _tab.value.name
        }
    }
    private fun restore(): Route {
        val pkg = saved.get<String>("route.pkg")
        val level = saved.get<String>("route.level")
        if (saved.get<String>("route.type") == "read" && pkg != null) return Route.Read(pkg)
        if (pkg != null && level != null) {
            val frame = saved.get<String>("route.frame")?.let { raw -> FrameId.entries.firstOrNull { it.name == raw } } ?: FrameId.CLASSIC
            return Route.Play(pkg, level, frame)
        }
        val uri = saved.get<String>("route.pdf")
        val title = saved.get<String>("route.title")
        if (uri != null && title != null) return Route.Import(ImportRequest.PdfFile(title, uri.toUri(), saved["route.size"]))
        return Route.Home
    }
    private fun clearDetail() {
        listOf("route.pkg", "route.level", "route.frame", "route.type", "route.pdf", "route.title", "route.size").forEach { saved.remove<Any>(it) }
    }
    fun selectTab(tab: HomeTab) { _tab.value = tab; saved["home.tab"] = tab.name; toHome() }
    fun openRead(packageId: String) {
        clearDetail(); saved["route.type"] = "read"; saved["route.pkg"] = packageId
        _route.value = Route.Read(packageId)
    }
    fun openPlay(packageId: String, levelId: String, frameId: FrameId) {
        clearDetail(); saved["route.pkg"] = packageId; saved["route.level"] = levelId; saved["route.frame"] = frameId.name
        _route.value = Route.Play(packageId, levelId, frameId)
    }
    fun openImport(request: ImportRequest) {
        clearDetail()
        // Only metadata enters the saved-state Bundle; shared document text can be unbounded.
        if (request is ImportRequest.PdfFile) {
            saved["route.pdf"] = request.uri.toString(); saved["route.title"] = request.title; saved["route.size"] = request.sizeBytes
        }
        _route.value = Route.Import(request)
    }
    fun toHome() { clearDetail(); _route.value = Route.Home }
}
